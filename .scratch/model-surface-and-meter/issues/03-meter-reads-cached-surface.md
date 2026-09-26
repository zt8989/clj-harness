# 03 — 压力表读「缓存的模型面 + O(1) 条带」，不重读整份 jsonl

**What to build:** 让 run 开头那次压力测量**不再 `read-records` 整份记录**。

现状违反 ADR 0002 决定 2（「历史初次从记录重建，之后在内存里操作；读文件是一次，不是每轮的」）：

- `compact-if-pressured!`（`harness.edge.http`）：`(replay/read-records f)` 之后 `pressure/records->pressure`；
- `log-pressure`（`harness.edge.pressure`）：`(stats/read-records f)`。

thread `bbcd4ae4-…` 实测：`read-records` 2.1–3.2 s；`records->pressure` 3.1–3.6 s（内部还要把 `entries`
折两遍——`messages-in` 全量一次、`messages-in prefix` 一次）。也就是**每轮 run 开头白白花 ~5.7 s**，
而压力表真正需要的只有 O(1) 条事实。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `sessions` 的 registry 在行落盘时**增量维护**一个小的「表针」状态，至少含：
      最新一条真 run 的 `model/start` payload（给 window/route/工具表标识）、
      最后一条报 usage 的 `model/end` payload（给 `prompt_tokens`）、
      **锚那一次 run 的「指令签名」**（两个 name hash：hooks 名字集合 + tools 名字集合）
- [ ] **锚点比签名，不比 system 文本**：`anchored?` 不再读/算 system 的内容（`prompt.md` 不参与）。
      两半怎么用，取决于送达方式（`.scratch/instruction-updates`）：
      - **tools 名字集合**变了 ⇒ 请求最前的 `:tools` 数组变了 ⇒ 前缀断 ⇒ **两档都作废**（与
        `.scratch/instruction-updates` 决定 1「工具表的冷前缀不省」一致）
      - **hooks 名字集合**变了 ⇒ system 文本变了：`:replace` 换 `message[0]` ⇒ 前缀断 ⇒ 作废；
        `:in-place` 作为尾部 developer 消息送出 ⇒ 共享前缀没断 ⇒ **不作废**，新指令全文算进 delta
      - 只改一个工具**描述**、只改 `prompt.md` ⇒ 签名不变 ⇒ 锚点照用
      能力位读不到时按缺省 `:replace` 办
- [ ] `:in-place` 落地后回归：加/删一条 hook → `:in-place` 端点 `anchored?` 仍成立、`:baseline` 仍
      `"usage"`，delta 含新指令全文；同一改动在 `:replace` 端点 → 锚点作废、退回 `estimated`
- [ ] 新增一个**纯函数**入口（如 `pressure/state->pressure`），吃「缓存的模型面 + 表针 + ratios」，
      不要再吃整个 records；`records->pressure` 保留（测试与离线读仍用它），两者对同一份事实必须同答案
- [ ] `compact-if-pressured!` / `log-pressure` 改走新入口；run 开头路径上不再调用 `read-records` /
      `stats/read-records`
- [ ] `anchor-est`（锚那一次自己的 prompt 估算）要在不折整份记录的前提下算出来。建议：缓存面里每条 entry
      带 `:seq`（record 行偏移），锚行也有自己的行偏移，于是「锚那一刻的消息」= `:seq <= 锚行偏移` 的那一段，
      加上那一次 run 的注入（`injected-rows`）与 system —— 全在缓存里切，无需读盘。
      **这条是要在票里定下来的设计点**；定了写进 `pressure` 的 docstring
- [ ] 基准：在 thread `bbcd4ae4-…`（129.7 MB / 362,359 行）上，run 开头那次压力测量 **< 10 ms**，
      且与对同一记录跑 `records->pressure` 的答案逐字段相等（`:pressureTokens` / `:baseline` /
      `:windowTokens` / `:percent` / `:thresholdTokens` / `:retainTokens`）
- [ ] 压力读盘失败仍然 fail-soft（ADR 纪律：report-only 的表不能杀死 run）——新入口同样成立
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-09-24 — 依赖 02：表针要「只认 run 调用」，这条判据在 02 里定。**缓存的是客户端面（#3），
模型面（#2）每次现算**——本票不缓存模型面，那是 01 定的「#2 必须是纯投影」的反面。

2026-09-24 — 「system 到底要不要缓存」查过：整条 system message **每次 run 现装**
（`harness.cap.system-prompt/assemble`；`harness.edge.http` 注释「WRITTEN PER RUN, NOT ONCE」），
in-place 冻结的只有 `prompt.md` 那段 opening（`harness.kernel.llm/frozen-prompt` / `reset-prompt!`）。
所以是「否则需要」——但需要的不是 4 KB 文本、也不扫全表：按**名字集合 hash** 判（此段随后被下面两条
更正），一个 hash 而已。thread `bbcd4ae4-…` 18 个 run 恰好同 hash，那是「没变」的结果，不是可以赖的承诺。

2026-09-24 — 老板补充纠正：「in-place」指的不是冻 opening，而是 `.scratch/instruction-updates` 那套**送达方式**：
能收对话中途 `developer`/`system` 的端点，变化作为尾部一条消息送出、`message[0]` 不动、前缀缓存保住；
不能的端点才替换 `message[0]`（今天 `ag/provider-array` 的行为）。所以「system 要不要缓存」不是常数，
而是**按端点能力位分档**：`:replace` 要，`:in-place` 不要（新指令算进 delta）。本票据此改成上面对照。

2026-09-24 — **未实现**（本轮预算止于 04、06）。设计的坑记在这里，下一次照做：

1. `records->pressure` 的 `anchor-est` 是**锚那一次**的消息估算（`messages-in prefix`），而 prefix 是
   「锚那次模型调用之前的记录」。注册表现在只有 conversation entries（带 `:seq`）与 live 状态，所以要
   O(1) 得由 registry 增量维护：最新真 run 的 `model/start` payload、最后报 usage 的 `model/end` 的
   `prompt_tokens`、锚那次的 start-line `:seq`、锚那次的 system 行、**锚那次 run 的注入**
   （`injected-rows` 里那些没有 id 的 message，`entries` 丢掉了它们）。
2. 触发器是 `model/end` 带 usage 那一刻（一轮一次）：此刻 registry 可以把「锚」快照下来——entries 的
   前缀（按 `:seq` 切）、当时的 system payload、当时的注入。`state->pressure` 之后就是 O(entries)，
   不读文件。
3. 别忘了三个回退：`:windowTokens` 的 `timeline-window`（老记录没有 `:context-window`）、system 文本
   （老记录没有 `:hooks-names-hash`）、`:tools`（老记录没有 `:tools-*`）。
4. 相等的判据是**逐字段**（`:pressureTokens` / `:baseline` / `:windowTokens` / `:percent` /
   `:thresholdTokens` / `:retainTokens`），所以 `records->pressure` 保留、两条路对同一份事实必须同答案。

2026-09-24 — **已落地**（`.worktrees/model-surface-and-meter`）。做法如下：

- **一条算术，两条路**：`pressure/records->pressure` 现在就是 `meter-of-records`（折出「表针」）+
  `state->pressure`（纯函数，吃 band + 本轮组装的 messages + ratios）。离线读与缓存读跑的是**同一套
  算术**，所以不可能对同一份事实给出两个答案——`pressure_test` 有一条真会话上的用例逐字段比它们。
- **表针（band）**：`pressure/meter-row!` 由 `harness.edge.http/log!`（每行都走的那条路）逐行喂，
  只认四种：真 run 的 `model/start`（`runId` 非空）→ `:latest-start`；报 `prompt_tokens` 的 `model/end`
  → 锚（连同那一刻的会话、system payload、该 run 的注入一起快照）；`system-prompt` 行 → 系统签名；
  run 自己的注入（`skill`/`job`/`injection` 且无 id）→ 注入表。`nil` runId 的压缩调用照旧被忽略（票 02 的规则搬到 band 上）。
- **播种**：一个进程里第一次问某个会话才折一次记录装 band（`seed-band!`）——**这是唯一一次读**；此后
  `log-pressure` / `band-pressure` 都是 O(1)。`band-for` 没装过 band 时播种，`meter-row!` 对没装过 band 的
  会话一律不动，所以播种前的行不会丢（播种那次折全都有）。
- **压缩触发**：`compact-if-pressured!` 先问 band（不读盘）；**只有在 band 已报过阈值之后**才读记录
  ——因为免费的 prune 与 lock 检查都要它，而 prune 自己可能把压力降回阈值以下。没过阈值的 run 一次盘都不碰。
- **fail-soft**：`log-pressure` 把 band 读数的任何异常都降级成「对 MESSAGES 的估算」（`empty-band`），
  与旧行为一致。
- **回退**：`:windowTokens` 仍用 `(or (:context-window latest-start) :timeline-window)`（老记录），system
  签名仍走「有 hash 比 hash、没有比文本」，`:tools-*` 缺失照旧回落 `:tools`。

**没做完整的那半**：band 的播种仍是一次 `stats/read-records`（一个进程、一个会话一次），不是零读；
因为「从记录折 band」需要 trajectory/context/pressure 三层，而 `sessions` 折记录时（`replay/sofar`）
拿不到它们。要彻底零读，得让 `sofar` 顺带把 band 的原料折出来，那是另一刀。run 开头稳态（会话已装过 band）
现在是零读。

验证：全量 `harness.test-runner` 1221/13505，唯一稳定红是预存在的 `claims_test`（另有一次全量里
`http_test` 的 rebuild 计数偶发，单跑该命名空间 0 红，判定为偶发）；`pressure_test` 新增两条
（真会话 band == record 折、band 忽略 nil runId 的调用）。

**基准**（126 MB 合成日志，同一份记录、同一份 messages）：`records->pressure` **528.8 ms**，
`band-pressure`（band 已播种）**0.08 ms**，两者 `=`。播种那次折一份 ≈ 与 `records->pressure` 同量级
（一次，一个进程一个会话）。真机 thread `bbcd4ae4-…` 上 `records->pressure` 实测 3.1–3.6 s，band 的读数
不随记录长大。
