# 模型面只有一份，压力表只读它，记录不再背工具表

**起因**：一条真实会话 `bbcd4ae4-…`（`.clj-harness/projects/_Users_zhouteng_Documents_workspace_clj-harness/`）
在上下文用到 **80%（842,109 / 1,048,576）** 时压缩没有生效。翻记录查出来的不是一个 bug，是三个：

1. **压缩那次其实触发了，但摘要调用 422 失败。** 压缩把 `replay/model-nodes` 的 `:messages` 直接交给摘要模型；
   那份「自称 model-facing」的表面投影的是 `entries`（客户端面），**没有脱卡**，于是请求里带上了 25 个
   `{:type "data" :name "injected-context"}` 的块，厂商原样拒收：
   ```
   HTTP 422 ... messages[4]: unknown variant `data`, expected one of `text`, `image_url`, `file`
   ```
   （记录 `bbcd4ae4-…jsonl` 第 347794–347797 行：`compaction/start` → `model/start` → `model/end {}` → `compaction/end` 带 `error`。）
2. **失败那次把压力表的锚点打歪了。** 摘要调用自己写的 `model/start`（`specs` 为空、**没有 tools**）
   成了记录里最新的一次模型调用，`pressure/latest-start` 读到它，`anchored?` 里
   `(= (:tools start-p) tools)` 变成 false → `:baseline` 从 `"usage"` 掉回 `"estimated"`，
   退化成按字符估算（对中文严重低估）。同一条记录实测：
   ```
   压缩前 records->pressure = 825,840 tok / 79% / baseline usage / latest tools=98
   压缩后（纯抄记录）      = 634,404 tok / 61% / baseline estimated / latest tools=0
   记录里落的那行          = 272,159 tok / 26% / baseline estimated
   ```
   于是这一轮 run 开头读到的压力低于 0.7 阈值，不再压缩；run 里涨到 842k（80%）也没有第二次检查。
3. **压力表每轮 run 开头重读整个 jsonl。** ADR 0002 决定 2 写的是「历史初次从记录重建，之后在内存里操作；
   读文件是一次，不是每轮的」——`compact-if-pressured!` 和 `log-pressure` 违反了这条。

加上一个记录重量问题：`model/start` 把**每次调用发出的整张工具表**写进记录，而这张表一轮里 672 行**一字不差地重复**。
这条 thread 实测：文件 129.7 MB / 362,359 行，其中 672 条 `model/start` 的 payload 就占 **50.2 MB**。

## 三份东西（老板的框架）

| # | 是什么 | 代码里的位置 | 现状 |
|---|---|---|---|
| 1 | 实际 jsonl | `replay/read-records` 出来的 rows | 活会话不存，每次读盘 |
| 3 | 用户看的（客户端面） | `replay/entries` 折出的 messages，**卡在里面** | 常驻 `sessions` 的 registry `:entries` |
| 2 | 模型看的（模型面） | `sessions/model-view` 之后的消息，**没有卡** | 每次现算（对的） |

正确性规则：**2 必须是 1 的纯投影，「压缩」和「压力表」都必须消费这一个纯投影，谁都不许自己另写一份。**
现状恰恰是压缩另写了一份（`replay/model-nodes`，未脱卡）。这条 thread 就是它的后果。

## 实测（这台机器，热 JVM，就在当前进程里量；thread `bbcd4ae4-…`）

| 单位操作 | 实测 |
|---|---|
| `read-records`（slurp + parse 整份） | 2080 / 2960 / 2992 / 3172 ms |
| `entries`（rows → 客户端面） | 1535 / 1653 / 1685 / 1698 / 2169 ms |
| `compacted-messages` + `model-view`（客户端面 → 模型面） | **0.45 / 0.46 / 0.49 / 0.5 ms** |
| `records->pressure`（run 开头那次） | **3128 / 3164 / 3247 / 3263 / 3578 ms** |
| `run-segments` | 80 / 81 / 87 / 109 ms |

内存：raw rows ≈ **307 MB 常驻**；客户端面 3.5 MB 序列化（常驻个位到十几 MB）；模型面与它共享，增量很小。

结论：**缓存客户端面（#3）最优**——取一次模型面 0.5 ms，常驻小两个数量级；缓存 raw 两个维度都被支配
（内存最大、取视图还要重折 1.7 s）；什么都不缓存是每次 4.2 s。

## 决策

1. **一个模型面投影，写在一个两边都能到的地方。** 把「脱卡 / 卡-only 还原成 role+text」从
   `harness.edge.sessions/model-view` 提成共用实现（`replay` 或 `ag`；`sessions` 依赖 `replay`，
   反过来不行），`replay/model-nodes` 也走它。**还原，不丢弃**：节点 id 是 record seq，
   `context/compacted` 的 `:shadowed` 就是节点 id 列表；丢弃会让范围错位。
2. **压力表只认真正的 run 调用。** 摘要调用（`runId` 为 null）不是「下一次调用从哪继续」，
   不进 `latest-start` / 锚点。
3. **压力表读「缓存 #3 + O(1) 条带」，不 `read-records` 整份。** 它真正需要的只有：
   缓存的模型面（算 `current` 估算）+ 最后一条真 run 的 `model/start`（window/route/tools 标识）+
   最后一条报 usage 的 `model/end` + **锚那一次 run 的 system 标识**。这些都是 O(1) 条事实；
   做成 registry 增量维护的小状态，配一个纯 `state->pressure` 入口。
   **锚点比的是「指令签名」（hooks / tools 的名字集合 hash），不是 system 文本。** 「变没变」由签名回答
   （`.scratch/instruction-updates` 决策 1）；**「断没断」还要看送达方式**：`:replace`（缺省）换
   `message[0]` ⇒ 前缀断 ⇒ 作废；`:in-place` 把变化作为尾部 `developer` 消息送出 ⇒ 共享前缀没断 ⇒ 不作废，
   新指令全文算进 delta。而**工具名字**变了，请求最前的 `:tools` 数组跟着变 ⇒ **两档都作废**。
   `prompt.md` 变了不算（签名不含它）。
4. **`model/start` 不再背工具表，只留一个「名字签名」加两个小量。**
   - `:tools-names-hash` —— 工具**名字**集合（排序后）的 SHA-256；**改一个描述不改变它**，
     加/删一个工具才改变它。
   - `:hooks-names-hash` —— SystemPrompt 点上参与组装的 hook 身份集合的 SHA-256（同一个签名的另一半）。
   - `:tools-bytes` —— `context/size-of` 的结果，给上下文圈的 tools 篮子（给一个**显示的数**，
     不是给判据）。
   - `:tools-count` —— 工具条数，给压力表 `estimate-tools` 的逐条 framing 开销。
   **不留整张表、也不留内容 hash**：这张表是 runtime 配置，每次调用一字不差，背 672 遍是纯浪费；
   「变没变」由**名字集合**回答（`.scratch/instruction-updates` 决策 1）。读侧接受新旧两种拼写。
5. **raw 读取是流式的一遍，不是 slurp 整份。** `read-lines` 的 `slurp`+`split-lines`、
   `lines->records` 的 `mapv` 在 362k 行上物化 ≈ 307 MB，而消费者只是折。改成滞后一行的流式行源 +
   一次走的 fold；`read-records`（vector）留给真需要整个数组的调用者，建在流上。strict 规则不变。（票 06）

## 非目标

- 不改 AG-UI 协议、不改 `entries`（客户端面整份保留，卡照样画）。
- 不动 `context-usage` 的三分口径，只把 tools 篮子的取数从「表的字节」换成行上的 `:tools-bytes`。
- **读取方式**：raw 读取改成流式（走一遍、不物化整份）是**票 06**；strict 规则（中间坏行硬失败、
  最后半行丢掉）一个字不改。
- 不管异步写丢失（老板：jsonl 即将改同步写，本特性按同步写设计）。

## 验收主线

1. 带 `injected-context` 卡的会话能压缩成功，摘要请求里没有 `data` 块，`:shadowed` 不变。
2. 记录里即使留着一次失败压缩的 `model/start`，压力表仍读 `baseline "usage"`。
3. thread `bbcd4ae4-…` 上 run 开头的压力表 **O(1) / < 10 ms**，且与 `records->pressure` 对同一记录的答案一致；
   run 开始路径上不再出现 `read-records`。
4. 新写的 `model/start` 行没有 `:tools` 键；一次 98-tool 调用的 `model/start` payload 从 ~75 KB 降到 ~200 B；
   上下文圈仍画出 tools 篮子；旧记录仍能读。
5. 离线全量 `harness.test-runner` 全绿；动过 `ui/src/` 就跑一次 `node scripts/dev.mjs --scripted`。
6. 在 thread `bbcd4ae4-…` 上折一遍，**峰值堆是 O(对话) 不是 O(文件)**；`src/` 的折路径不再
   `slurp`/`split-lines`/`mapv` 整份物化。（票 06）

## 票清单（`.scratch/model-surface-and-meter/issues/`）

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 模型面一处：脱卡／还原，压缩消费它 | — | 一个共用投影；压缩请求不再带 `data`；`:shadowed` 不变 + 回归 |
| 02 | 压力表只认 run 调用 | — | 忽略 runId 为 null 的 `model/start`；失败压缩不再打歪锚点 + 回归 |
| 03 | 压力表读缓存面 + O(1) 条带 | 02 | registry 增量维护小状态；纯 `state->pressure`；run 开头不再读整份 + 基准 |
| 04 | `model/start` 不再背工具表 | — | 改存 `:tools-names-hash` / `:hooks-names-hash` / `:tools-bytes` / `:tools-count`；三个读侧一起改 + 体积基准 |
| 05 | 收口：文档与全量 | 01,02,03,04,06 | `docs/architecture/edge.md`、ADR、全量测试、真浏览器走查 |
| 06 | raw 读取改成流式：走一遍、不物化整份 | — | 流式行源 + `fold-records`；折路径不再 `slurp`/`split-lines`/`mapv`；strict 规则不变 + 峰值堆基准 |

## 备注

- **记录格式**：ADR 0003 说「不改记录的格式」。04 只动 `model/start` 行 payload 的一个键，
  且读侧兼容旧拼写，不改行种类、不改 append-only。是否补一条 ADR（记录不再背工具表）在 04 里定。
- **为什么留签名而不是全不存**：压力表的锚点前提是「envelope 没变」，而「工具表（名字集合）、hooks
  （名字集合）、route 变没变」就是它的判据。签名是把这件事压到两个 hash，不是把表存回来；**描述改了
  不算**（老板口径），所以按名字集合哈希，不按整张表哈希。

## 落地记录

2026-09-24 — 票 01–02（上一轮）+ 04、06（本轮）已落地于 `.worktrees/model-surface-and-meter`；
**票 03 也已落地**（同日追加）：`state->pressure`（纯）+ `meter-of-records`，`records->pressure` 建在它们
之上；`harness.edge.http/log!` 逐行喂 `pressure/meter-row!` 维护「表针」，run 开头不再读整份（一个进程、
一个会话播种一次）。见票 03 的 `## Comments`。

- **票 04**：`model/start` 只留 `:tools-names-hash` / `:tools-count` / `:tools-bytes`（表空不写），
  删掉 `:tools`；签名在 kernel 提供机制、edge 提供字节数（`run-chan` 的 `:tool-signature`）；
  `:hooks-names-hash` 落在 system `message` 行的信封上，**整张工具表也写在那条行的信封上**（`:tools`，主人
  2026-09-24：不进正文，模型读不到、不白付 token）。读侧三处 + 前端一起改。落地记在票 04 的 `## Comments` 里。
  ADR 见 `docs/adr/0004`。
- **票 06**：`read-lines` 改成显式 UTF-8 的懒行源（读完即关）、`lines->records` 改懒、新增
  `fold-records`（reader 关在自己里面）、`read-records` 建在流上；`entries` 与 `stats/records->stats`
  改成**单趟折叠**（`fold-entries` / `log-stats` 走 `fold-records`）；`compaction-facts` / `prune-facts`
  去掉 `(vec records)`。
- **票 03**：`state->pressure`（纯，吃 band + messages + ratios）与 `meter-of-records`；`records->pressure`
  建在它们之上（同一套算术，不可能给出两个答案）。band 由 `log!` 逐行喂（真 run 的 `model/start`、报 usage 的
  `model/end`、system 行、run 的注入），`seed-band!` 一个进程一个会话折一次装它；`compact-if-pressured!`
  只在 band 报过阈值之后才读记录（prune 与 lock 要它）。落地记在票 03 的 `## Comments`。

### 实测（票 06 的峰值堆，126 MB 合成日志 / 231,289 行）

| 读法 | `-Xmx512m` 峰值增量 | `-Xmx200m` |
|---|---|---|
| 旧：`slurp` + `split-lines` + `mapv read-row` | 438 MB | **OOM** |
| 新：`read-records`（流式行源，但仍 vector 化） | 307 MB | **OOM** |
| 新：`fold-entries`（折一遍，不物化） | 110 MB（含 GC 前垃圾） | **通过，峰值增量 36 MB** |

（数字由 `-J-Xmx… -M:test` 下的采样线程给出，脚本是一次性的 scratch，未入库。）
