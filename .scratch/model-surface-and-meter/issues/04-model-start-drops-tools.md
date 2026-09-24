# 04 — `model/start` 不再背工具表（只留一个名字签名 + 两个小量）

**What to build:** `harness.kernel.event/model-start` 现在把**发起请求时那张完整工具表**写进每一行
`model/start`（`(seq specs) (assoc :tools specs)`）。同一轮里这张表一字不差地重复几十上百遍。
thread `bbcd4ae4-…` 实测：672 条 `model/start` 的 payload 合计 **50.2 MB**，占整份 129.7 MB 的四成。

这张表是 runtime 配置（`harness.kernel.tools` 解析出来、按会话工具开关变），不是记录该背的对话事实。
「这张表变没变」也**不用整张表来回答**——老板口径：**按名字集合哈希，描述改了不管**。记录改背：

- `:tools-names-hash` —— 工具**名字**集合（排序后）的 SHA-256。给压力表的锚点判「envelope 变没变」，
  也是 `.scratch/instruction-updates` 决定「要不要重建 system prompt」那半个签名。**改一个工具的描述
  不改变它；加/删一个工具才改变它。**
- `:hooks-names-hash` —— SystemPrompt 点上参与组装的 hook **身份集合**（名字 + 来源 + 顺序）的 SHA-256，
  同一个签名的另一半。
- `:tools-bytes` —— `harness.edge.context/size-of` 的结果（UTF-8 JSON 字符数），给上下文圈的 tools 篮子。
- `:tools-count` —— 工具条数，给压力表 `estimate-tools` 的逐条 framing 开销。

**不留整张表、也不留内容 hash**：既然「描述改了不管」，内容 hash 与名字 hash 会给出两个可能打架的答案，
只留名字那一份。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] `event/model-start` 改收工具表 + 签名，但**只写上面四样**（表为空时不写 `:tools-*`，与今天
      「没有表就不写 `:tools`」同义）；删掉 `:tools` 键
- [ ] **签名在组装 system prompt 的那一处算**（hooks 身份集合）**与解析工具表的那一处算**（工具名集合），
      只算一次、传进来——`event` 层不自己去解析（今天 `:tools` 也是如此：「两个解析会是两张碰巧一致的表」）
- [ ] **读侧接受新旧两种拼写**：老记录只有 `:tools`，新记录只有 `:tools-*`；各读侧给一个
      「取这张表的签名 / 字节 / 条数」的小函数，只有一处判据
- [ ] 读侧逐一改：
      - `harness.edge.context/shares`：tools 篮子改用 `:tools-bytes`（缺失时回落到 `size-of (:tools …)`）
      - `harness.edge.pressure/records->pressure`：`estimate-tools` 用 `:tools-count`；`anchored?` 用
        `:tools-names-hash` / `:hooks-names-hash`（缺失时回落旧拼写）——比名字签名，不比内容
      - `harness.edge.trajectory/one-call`：`:tools` 改成 `:toolsNamesHash` / `:toolsCount`（或等价的键），
        `trajectory-view.tsx` 的按表分身分组改用名字 hash
- [ ] `harness/edge/http` 里 `run-compaction!` 自己那条 `model/start`（`specs []`）不受影响（本来就没工具表）
- [ ] 体积基准：一次 98-tool 调用的 `model/start` payload 从 ~75 KB 降到 **< 300 B**；
      在 thread `bbcd4ae4-…` 的构造上，`model/start` 总计从 50.2 MB 降到 < 1 MB
- [ ] 回归：新旧两种记录都能读出 window/route/**名字签名**；加/删一个工具让签名变、改一个描述不变；
      上下文圈的 tools 篮子数值与旧口径一致
- [ ] **前端**：按 AGENTS.md 跑 `cd ui && npm test`、`npm run typecheck`、`npm run build`；
      动过 `ui/src/` 合前跑一次 `node scripts/dev.mjs --scripted`
- [ ] 文档：`docs/architecture/edge.md` 的 `model/start` 一行（今写 `:tools`「照发出的那张工具表」）改成新事实；
      判断要不要补一条 ADR（「记录不再背工具表」，与 ADR 0003「不改记录格式」的边界说清）
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-09-24 — 老板拍板：「model/start 不要存 tools，完全没必要」；随后修正为**按名字集合哈希**——
「不是数量变化，name 变化也算（加一个工具、删一个工具）；描述改变不管」。留 `:tools-names-hash` 不是把表
存回来，是把「envelope 变没变」压到一个键——这是压力表锚点成立的**前提**，也是
`.scratch/instruction-updates` 决策 1 那半个签名。

2026-09-24 — 落地（本分支 `.worktrees/model-surface-and-meter`）。按老板「机制在 kernel、实现在外层」
的口径定下两处：

- **`:tools-bytes` 在外层算、传进来**。kernel 提供机制：`harness.kernel.loop` 的 `model-call!` 收
  `:tool-signature`（缺省 `harness.kernel.tools/default-signature`，只给名字 hash + 条数）；edge 的
  `harness.edge.context/tool-signature` 是能力那半（加 `size-of` 的字节数），由 `http` 的两处
  `loop/run-chan` 传进去。`event/model-start` 只落 `:tools-names-hash` / `:tools-count` / `:tools-bytes`，
  没有表就不写这三个键。
- **`:hooks-names-hash` 落在 system 那条 `message` 行的信封上**（老板同意），不落 `model/start`：写
  `model/start` 时还不知道 hook 集合。`dispatch/fire` 在 content 点返回 `:hooks`（matched 声明的 id，
  按书写序），`cap.system-prompt/assemble*` 返回 `{:text .. :hooks-names-hash ..}`，http 两处写系统行时带上。

读侧：`context/shares` 用 `:tools-bytes`（缺时回落 `size-of (:tools …)`）；`pressure/estimate-tools` 改吃
start payload（`:tools-count` + `:tools-bytes`，旧记录回落 `:tools`），`anchored?` 比 `:tools-names-hash` 与
系统行的 `:hooks-names-hash`（旧记录回落 system 文本）；`trajectory/one-call` 改出 `:toolsNamesHash` /
`:toolsCount`，前端按名字 hash 分组、不再画整张表（i18n 加 `tools.notKept`）。

回归：`event_test` 加了 98-tool 的体积基准（表 ~78 KB，`model/start` 行 < 300 B）；`pressure_test` 把
「工具表变了」改成**换名字**、并加「只改描述仍锚定」；`trajectory_test` 新旧两种拼写各一条。

验证：离线全量 `harness.test-runner` = 1215 tests / 13483 assertions / **1 failure**（`claims_test`
的 `a-second-jvm-owns-a-conversation-until-it-goes-away`，预存在、与本票无关）；`ui` 的 typecheck / vitest(128)
/ build 全过。

**未做**：ADR 判断（「记录不再背工具表」与 ADR 0003 的边界）留给票 05；真浏览器走查也留给票 05。

2026-09-24（同日追加）— 主人更正：「tools 应该写进 role=system」，接着又划清边界「正文不需要 tools 块」。
所以落地是：票 04 仍只改「表不再背在 `model/start` 上」，而**整张表（名字 + 描述 + parameters）写到
system 那条 `message` 行的信封上**（键 `:tools`，和 `:source` / `:hash` 并排）——**不进 message 正文**，
`replay/payload` 把它挡在消息之外：记录里回读得到、模型读不到、不白付 prompt token。两处 system 行写入
（agent / subagent 路由）都加了 `:tools (harness.kernel.tools/specs thread-id)`。

联动：`http_test` 的 `the-assembled-system-message-reaches-the-model-and-never-the-client` 加了一条断言
（信封有 `:tools`、名字逐一对得上、payload 里没有它、正文里没有 `<tools>`）；`.scratch/system-prompt-blocks`
与 `docs/architecture/hooks.md` **不加行、决策 6 不改**（`<tools>` 块不进正文）；`docs/adr/0004` 加决定 6，
`docs/architecture/edge.md`（`model/start` 行与 system 行）、`CONTEXT.md` 轨迹条、`trajectory.json` 的
`tools.notKept` 文案一起改。

验证：全量 1220/13498，唯一红仍是预存在的 `claims_test`；`ui` typecheck / vitest(128) / build 全过。

2026-09-24（同日追加 2）— 主人又提两条，都已落地：

1. **轨迹每条数据自包含**：system 条目现在自带那张表——`run-segments` 把那条 system 行整个留在
   `:prompt-row`（信封和 payload 一起），`system-item` 因此能带 `:tools`；前端「工具表」那一栏**直接
   渲染条目自带的表**（名字 + 描述 + schema，18 行），旧记录（信封里没有 `:tools`）才回落到按名字
   hash 分组。点开 system 消息不再需要任何第二处数据。
2. **`message` 行按数组顺序写，第一条一定是 `role=system`**：把 system 行的写入从 `(when provider …)`
   里**挪到 birth 之后、动作条目之前**——`harness.edge.http` 现在先写 system 行，再写动作自己的
   `message` 行（顺序=数组顺序=system/用户/assistant）。`provider/init` 是 `event` 行，仍旧写在最前
   （「先遇到由谁服务」保住了）。`run-segments` 的 run 因此总是从它的 system 行开——它也留着兼容旧
   记录（从客户端那条开）。

验证：全量 1219/13501，唯一红仍是预存在的 `claims_test`；`ui` typecheck / vitest(128) / build 全过；
走查确认 jsonl 首 8 行是 `hook/SystemPrompt` → `message role=system` → `message role=user` → …，
system 行的信封带 `:tools`（18 条），轨迹「工具表」栏渲染出 18 行（ask / bash / eval / glob / grep …，
含描述与 schema）。
