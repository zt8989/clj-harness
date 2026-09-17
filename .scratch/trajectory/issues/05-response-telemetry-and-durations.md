# 05 — 响应侧：用量、结束原因，与每段的耗时

**What to build:** 每次模型调用回来时说的事实（token 用量、结束原因、vendor 回声的 model）不再丢掉；
两条 `model/*` 标记与既有的 `tools/*` 行一起，把每一段的时长算出来，并在条上显示。

从用户视角：能回答「这一轮为什么慢、慢在哪一段、那一次调用烧了多少 token」——
今天这些数在 provider 的响应里，解析器读到了，然后**丢了**。

**Blocked by:** 04（本票的用量挂在它的 `model/end` 上，耗时是它那两条标记的差）

**Status:** ready-for-agent

## 验收

- [ ] `harness.llm/consume-sse` 留住今天被丢掉的三样：`usage`、`finish_reason`、响应里的 `model`。
      它**仍然是对行纯函数**（遥测来自同一批行，不碰网络），返回形状由本票定：
      助手消息 + 一段遥测（`{:usage … :finish-reason … :model …}`）。
- [ ] 断言用仓库里那份**真的录下来的响应**：`test/harness/fixtures/deepseek_sse.txt` 的末块里本来就有
      `prompt_tokens 769 / completion_tokens 324 / total_tokens 1093 / reasoning_tokens 296` 与
      `finish_reason "tool_calls"`。断言它们**读得回来**——今天这份 fixture 里的数字一个都没被读过。
- [ ] `:model/end` 带 `{:usage … :finish-reason … :model …}`（字段**原样**，不重命名 vendor 的键：
      记录照收到的样子）。读侧再折成 UI 用的四个（输入 / 输出 / 合计 / 推理），
      **缺哪个就少哪个**——没报就是没报，填 0 是撒谎。
- [ ] **流中途失败也要发 `:model/end`**（`try` / `finally`）：没有终点的那一段正是最该看见的一次调用，
      卡在半路的模型调用不该在时间轴上变成一根无限长的条。
- [ ] 假 provider 的脚本能写用量（一轮一个可选的 `usage`），否则本票与 06 只能起真机验。
      不写用量的轮：读侧如实少那几个键（与「没报就是没报」同一条）。
- [ ] 读侧给每一段补上时间，**不加新的时间戳字段**（决策 7，一切由行的 `:ts` 差出来）：
      - 助手条：`startedAt` / `endedAt`（它那次调用的 `model/start` → `model/end`），
        `call` 指向 `calls[]` 里的哪一次；
      - `calls[]`：`startedAt` / `endedAt` / `usage` / `finishReason`；
      - 工具条：`queuedAt`（`tools/pre-execute`）/ `startedAt`（`tools/execute`）/ `endedAt`
        （`tools/post-execute`）。
- [ ] **等人的时间不算执行时间**：被 park 的工具，等待落在 `queuedAt` → `startedAt` 之间，
      执行时长是 `startedAt` → `endedAt`。一次等了三分钟的审批不该长得像一次跑了三分钟的工具。
- [ ] **被否决的调用没有 `startedAt`**（它从没执行过，`executed` 是 false）：执行时长是**空**，不是 0；
      UI 上也别画成 0s。
- [ ] **并发的工具会重叠**：读侧如实给出各自的段，不替它们排成首尾相接（画法在 06）。
- [ ] UI：`工具` 行显示执行时长，助手行显示那次调用的耗时与用量（形如 `1.2s · 1093 tok`），
      量级用既有的时长格式化（仓库里已有一个，**提到公共处复用，不复制第二份**）。
      没有用量的调用不显示 token 部分——空着，别写 0。
- [ ] 老日志（没有 `model/*` 行）里工具段照旧能算出时长（`tools/*` 那三条本来就在），
      模型段如实缺失——**不拿两次工具之间的间隔冒充模型耗时**。
- [ ] 测试：折法的手搓记录里加一段「模型调用 + 并发两个工具 + 一个被否决」，
      断言 4 个段的起止与重叠关系；`consume-sse` 对 fixture 的用量断言；假 provider 一轮带用量能折出来。
- [ ] `clojure -M:test -m harness.test-runner` 与 `cd ui && npm test` 全绿
      （基线以落地当次为准，报数带上分支与提交）。

## 复议（2026-09-16）：响应侧（留住用量）已由 `.scratch/composer-status/` 01 落地

**加注，不改写上面的话。** `consume-sse` 留住 `usage` / `finish_reason` / 回声的 `model`、
`stream!` 的返回形状（`{:message … :telemetry …}`，四个 defmethod 一起改）、
以及对 `test/harness/fixtures/deepseek_sse.txt` 那份真录下来的响应的断言，都已经由
`.scratch/composer-status/` 的票 01 落地（769 / 324 / 1093 / 296 与 `finish_reason "tool_calls"` 都读得回来）。
「中途失败也要发 `:model/end`」也落了：那是 `harness.kernel.loop/model-call!` 里的 `try` / `catch`，
载荷为空。

**本条票只剩**：读侧把每一段补上起止（`startedAt` / `endedAt` / 排队与执行的分段）、
`calls[]` 的 `usage` / `finishReason`、以及条上显示耗时与用量。量的分母仍然照上面那条—
「没报就是没报，别写 0」。

**假 provider 的 `:usage`** 也已经在了（每一轮可选），别再加一遍。

## 复议（2026-09-17，记录一半已由别人落地）

**票面第 1、2、3 条所要求的「留住被丢掉的用量」已经落地**，但不是按本票的路径：`composer-status` 那一路做了
`consume-sse` 回来一段遥测、`harness.kernel.loop` 把它放进 `model/end` 的载荷、vendor 的键名原样保留；
`harness.edge.stats` 是那个读侧，它连「nil 不是 0」的纪律都写好了。**这一半不用再做了。**

**仍然开着的（本票剩下的）**：

- 读侧的**段与耗时**归到 `harness.edge.trajectory` 上：助手条的 `startedAt` / `endedAt`、工具条的
  `queuedAt` / `startedAt` / `endedAt`、以及 `:calls` 里的 `startedAt` / `endedAt` / `usage` / `finishReason`。
  （工具段与等待段的原始事实一直在记录里，`tools/*` 与 `approval/decided` 的 `:ts`；没人读而已。）
- **等人的时间不算执行时间**（`queuedAt` → `startedAt` 是等待，`startedAt` → `endedAt` 是执行），
  以及被否决的调用**没有** `startedAt`（不是 0 秒）。
- 行上的显示（助手行的耗时与用量）——与 03/06 的界面一起。

## 复议（2026-09-17，读侧落地，本票结束）

**读侧补齐**：`calls[]` 每条带 `:startedAt` / `:endedAt` / `:usage`（厂商原样）/ `:tokens`（按
`stats/tokens-of` 折出的合计）/ `:finishReason`；工具条带 `:queuedAt` / `:startedAt` / `:endedAt`；
用户条带 `:at`（`input` 行自己的 `:ts`——用户消息是**一个时刻**，不是一段）。

**一处与票面不同，按「一件事一个地方」改的**：助手条**不带**自己的时间，只带 `:call` 指针
（指向这一轮的 `calls[]`）；时间只有那一份。工具条保留自己那三个标记，因为一次工具调用有自己的命
（排队 → 真的跑 → 缝里做完），而且 `:startedAt` **缺**就是「从没跑」，不是「跑了 0 秒」。
`:call` 是结构事实、不是猜：内核**一次调用一条 assistant 消息**地追加历史，所以第 n 条助手消息就是
第 n 次调用，它之后到下一次调用之前的工具结果同属那次调用。记录早于 `model/*` 时 `:call` **整个不出现**
——指一个记录里没有的调用就是编一个。

**顺带**：`stats/total-of` 提成公开的 `stats/tokens-of`，与 `incomplete?` / `user-ids` 同一个理由
（两个读侧共用一条规则，不许各写一份）。

**显示**：助手行 `耗时 · 用量`，工具行 `执行时长 / 状态`；没有用量就不显示那一段，不写 0。

## 复议（2026-09-18 凌晨，牛总指出的：工具那一段根本没画出来）

**症状**：时间轴上 `input` 与 `model` 之间那段空白（其实就是工具在跑）**没有任何标注**，
工具 lane 上只有右端一个约 2 毫秒的点。

**根因是我读错了一行记录的语义**：`tools/execute` 是**离开执行**——工具**跑完**的那一刻，
`harness.kernel.event/tool-executed` 的 docstring 一直写着「One tool call left execution」，
而我看名字想当然当成了「开始执行」。于是：

- 我算的 `ran = endedAt(缝里做完) - startedAt(其实是跑完)` ≈ 2ms ← 这就是那个点；
- 我算的 `waited = startedAt - queuedAt` 其实**含整个真实执行时间**，被当成「等人」，画成浅色叠在里面；
- 结果：真正跑的那一秒既没进「执行」也没单独成段，等于整段没画。

**改法**：读侧把四个时刻按记录的真实含义改名并补齐——
`:arrivedAt`（进缝）→ `:resumedAt`（悬置被人决完，第二次 pre-execute）→ `:executedAt`（**离开**执行 = 跑完）
→ `:closedAt`（缝里做完）。**工具那段 = `arrivedAt` → `executedAt`**；悬置的那段 = `arrivedAt` → `resumedAt`，
是**人的时间**，与工具自己那段分得开。没跑过的调用（被否决）**没有 `executedAt`**，仍然不是「0 秒」。
界面上：条的长度用 `executedAt` 收尾、浅色前段用 `resumedAt` 起头；面板里 `waited` 与 `ran` 分别算。

**量到的**（`sleep 1.0` 的那次 bash、同一次真机会话）：工具条 `left 15.5%`、`width 77.9%`、
`title "bash · 1.0s"`——正好落在两次模型调用（10.4% 与 96.0%）中间那段空白上；
面板事实行 `executed: yes · outcome: pass · ran: 1.0s`（没有 `waited`，因为没有人被问过）。
折法的手搓断言跟着重写：一秒的工具 = 1000ms 的 `ran`；悬置一次 = 4980ms 的 `waited` + 10ms 的 `ran`；
被否决的没有 `executedAt`。
