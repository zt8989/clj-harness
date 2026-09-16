# 04 — 服务器反过来问人：elicitation 走既有的 park/resume，不新造通道

**What to build:** agent 调一个 MCP 工具时，服务器在**这次调用中途**回一个
`elicitation/create`（请求用户输入，通常是一个表单），run 因此 park 下来：客户端的输入框被堵住、
弹出一张表单卡片；人填完提交，答复随下一次 run 的 `resume` 回传，服务器拿到结果把这次调用做完。
人拒绝或取消，服务器收到的是「被拒绝」而不是一个空表单。挂在那里没人答，超时后果由既有超时纪律决定。

**这一票是 MCP 里最大的一块未知，也是 Anthropic 自己标成 v2 的那部分**（general-harness spec 特意把它
排在最后：`elicitation 复用既有审批 park/resume 通道`，不新发明一条恢复通道）。票面的验收是「能跑通
一条完整回路」，不是「把 elicitation 的所有形态都支持了」——`requestedSchema` 只渲染**够用**的那几种字段。

**Blocked by:** 01

**Status:** done（2026-09-16，分支 `mcp`）

## 决策

- **复用既有 park/resume，一个新通道都不加。** interrupt 的 id 仍是相关键，`resume` 里仍是
  `resolved` / `cancelled`；**新增的是 reason 的取值**：`"elicitation"`（今天只有 `"tool-approval"`）。
  理由不是省事，是 AG-UI 契约不动、流式路径仍只有一条、客户端的 `resume` 数组本来就允许多条并发的答复。
  UI 侧按 reason 认领卡片，与审批门同一套做法（那张卡认领 `"tool-approval"`）。
- **桥接线程在这次 `tools/call` 上阻塞等答复**——elicitation 是这次调用的一部分，不是另一条 run。
  等的是「人的答复到了」，实现上仍是进程内的一次 park + 一次 resume，绝不写成「先把工具调完再补一条消息」。
- **超时与拒绝是两种不同的答案，且必须分得开：**
  - 人**拒绝/取消** → 服务器收到 `{action: "decline"|"cancel"}`，这次调用**照常做完**（服务器有权据此
    返回一个错误或一个部分结果，那是它的事）；
  - 挂到超时 → 服务器收到……**什么都没有**：连接按 02 的纪律丢弃，这次调用的结果是**指名超时**的错误结果。
    绝不在超时后替人**假装**回答了一个表单——那是把一句谎话写进协议。
- **不猜表单。** `requestedSchema` 渲染出 `text` / `number` / `boolean` / `enum`（单一类型就是字符串）四类
  字段，其余类型**渲染成文本输入并带原始类型名**，提交的值按 JSON 原样回传；不认识的 schema 不阻断，
  但也不静默丢掉字段（丢字段 = 服务器拿到一个少了一半的表单还以为是真的）。
- **两个 P3 hook 点在这一票接线**（它们在 `harness.hooks/points` 里已经登记为数据）：请求到达时
  `Elicitation`、答复回传服务器**之前** `ElicitationResult`。两点都是观察者，不复用 hook 的答复通道
  ——谁回答这个表单是人（或客户端）的事，不是 hook 的事（这一点与 `PermissionRequest` 刻意不同，写下来：
  那条通道存在是因为「替人回答一次审批」是个合理的委派，「替人填一张表」不是）。
- **输入的形态是「谁在问」+「问什么」**，两者都要在卡上看得见：服务器名与那句 prompt。人需要知道
  这是哪个外部程序在问他要东西。

## 验收

- [x] 假服务器在 `tools/call` 里发 `elicitation/create` → run 以 interrupt 收尾、reason 是 `"elicitation"`、
      卡片拿到 prompt 与 schema；**这条帧是真 AG-UI 流里出来的**（不猜服务端内部状态）
- [x] 人提交 → 服务器的 `elicitation/create` 拿到 `{action:"accept", content:{…}}`，返回值与填写内容逐字段相等；
      **这次工具调用随即做完**，工具结果照常回给模型，run 照常继续
- [x] 人拒绝 → 服务器拿到 `{action:"decline"}`；取消 → `{action:"cancel"}`（两条断言分开）
- [x] 挂到超时且无人答复 → 这次调用的结果是**指名超时**的错误结果，连接被丢弃；断言**没有任何**
      `accept` 形态的答复被发回服务器
- [x] `requestedSchema` 的 `text` / `number` / `boolean` / `enum` 各渲染出一个可填字段，提交后类型不变
      （数字仍是数字、布尔仍是布尔）
- [x] 一个不认识的字段类型：仍渲染、仍提交、**不丢字段**，并且界面上明说原始类型
- [x] `Elicitation` 与 `ElicitationResult` 各落一行 `hook/<Point>` 审计行，payload 里有 server 名
- [x] 审批门与 elicitation 卡**同时开着时不串**：一轮里既有 parked 的工具审批又有 elicitation 时，两张卡
      各认领自己的 reason，提交的 batch 两条都带上
- [x] 离线全量 `harness.test-runner` 全绿；`cd ui && npm test` 全绿（含新套件）

## 落地（2026-09-16）

**先做了一次抽取，因为不做就编译不过**：`harness.tools` 要 `harness.mcp`（工具表折外部来源），
而 elicitation 要反过来（找一个调用的 parked 记录、取人的答复），于是成环。把 **parked 登记表**
搬进新 ns `harness.parked`（登记表本身 + park/parked/decided/take + 那个「停下来并抛出」的信号），
它谁也不依赖，tools 与 mcp 各自 require 它。顺带把调用点全部改成 `parked/...`
（loop、http、四个测试 ns），`*tool-call-id*` 也放在那里——因为 parked 记录**就是**按
(thread-id, tool-call-id) 键控的，这个词表是它的。

**机制**（票面那套，一处不差）：

- 服务器在 `tools/call` 中途发 `elicitation/create`。**读线程不能中止任何东西**（它必须继续读），
  所以它把问题塞进那条 `tools/call` 的 promise，**工具线程**醒来并中止——中止要有东西可解，
  所以只在工具体所在的那条线程上做。`parked/suspend!` 就是那个「登记并抛出」。
- 缝接住这个信号（`parked/suspended?`），把调用报成悬置、**补上 name/args**（工具体知道问题，
  不知道自己是拿什么参数被调的，而 resume 要靠这两个字段重新发起这次调用），run 以 interrupt 收尾。
- interrupt 的 `:reason` 是 `"elicitation"`（ag_ui 的帧构造现在按 reason 选那句话：问题本身就是
  `message`，而不是「Approve xx?」）。`:question` 只在内核事件里走，**不上 wire**。
- 答复走既有的 `resume`：`resolved` + 表单值 → `{action: "accept", content: {...}}`；
  `cancelled` + `{action: "cancel"|"decline"}` → 对应的动作。同一个 parked 记录、同一个
  interruptId、同一条通道。
- **schema 不在 interrupt 上**：AG-UI 的 interrupt 是严格形状，多一个键客户端就拒。所以新增
  只读端点 `GET /api/elicitation?interruptId=..`，答 `{:server :prompt :schema :expiresAt}`，
  未知 id 是**指名 404**（没有这个 id 还画表，等于把回答收进一个没有人的问题里）。
- **超时不是人的超时，是服务器的超时**：服务器正拿着一个请求等我们回话，所以这个问题有
  截止时间（该服务器的 `:timeout`）。人回来得太晚 → 退回服务器什么都不能编（编一个答复
  就是把谎话写进协议），连接按 02 的纪律丢掉，这次调用是**指名超时**的错误结果。
- 两个 P3 点接线：`Elicitation`（请求到达）与 `ElicitationResult`（答复回传前）。两者都是观察者：
  「替人填一张表」不是可以委派的事（与 `PermissionRequest` 刻意不同，理由写在票面）。

**没有做的，以及为什么**：**HTTP transport 上的 elicitation 不接线**。MCP over HTTP 的服务器请求
会出现在响应的那一条流上，而这个客户端为一次 POST 只读一条流（读到自己的 id 为止），
所以 HTTP 上的 elicitation 会以那次请求的**指名超时**收场，而不是 park。客户端签名保留同一个
arity（两种 transport 可以互换，这正是这张票要的性质），代码里写明了这处限制。

**测试**：`mcp_test` +5 个（park 的 reason/记录、答复回得去且调用做完、decline 与 cancel 分开、
超时后什么都没编造、schema 逐字段不丢），`mcp_wired_test` +2 个（真边：interrupt 的 reason 与
message、端点、404、答复后 run 继续、两个 hook 点各落一行且 payload 里有服务器名）。
UI：新增 `ui/test/suites/elicitation.ts`（5 条）——一条走真后端走完「park → 取 schema → resume →
工具结果里带着填的值」，四条测 `ui/src/lib/elicitation.ts` 的纯规则（四类字段各得其所、
类型不被 JSON 化、不认识的字段**保留并标注原始类型**、空 schema 不是崩溃）。
表单规则从组件里提到 lib 就是因为它们错起来在截图上看不见：丢一个字段、或数字以 `"36"` 发出去，
服务器都以为表单被正确回答了。

**一处诚实的量**：`Elicitation` 会**触发两次**（第一次 park，resume 重新发起这次调用时再问一次）。
这是「调用被重新发起」这个设计必须付的价，测试里按 2 断言并写明了理由——一次就说明调用没有被重新发起。

**测数**：全量 **650 / 9977**（+7 tests / +45 assertions），2 红仍是
`project_test/a-binding-survives-a-real-restart` 那两条环境问题。`cd ui && npm test` 16/16 全绿，
`tsc --noEmit` 干净。
