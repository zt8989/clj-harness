# 04 — 服务器反过来问人：elicitation 走既有的 park/resume，不新造通道

**What to build:** agent 调一个 MCP 工具时，服务器在**这次调用中途**回一个
`elicitation/create`（请求用户输入，通常是一个表单），run 因此 park 下来：客户端的输入框被堵住、
弹出一张表单卡片；人填完提交，答复随下一次 run 的 `resume` 回传，服务器拿到结果把这次调用做完。
人拒绝或取消，服务器收到的是「被拒绝」而不是一个空表单。挂在那里没人答，超时后果由既有超时纪律决定。

**这一票是 MCP 里最大的一块未知，也是 Anthropic 自己标成 v2 的那部分**（general-harness spec 特意把它
排在最后：`elicitation 复用既有审批 park/resume 通道`，不新发明一条恢复通道）。票面的验收是「能跑通
一条完整回路」，不是「把 elicitation 的所有形态都支持了」——`requestedSchema` 只渲染**够用**的那几种字段。

**Blocked by:** 01

**Status:** ready-for-agent

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

- [ ] 假服务器在 `tools/call` 里发 `elicitation/create` → run 以 interrupt 收尾、reason 是 `"elicitation"`、
      卡片拿到 prompt 与 schema；**这条帧是真 AG-UI 流里出来的**（不猜服务端内部状态）
- [ ] 人提交 → 服务器的 `elicitation/create` 拿到 `{action:"accept", content:{…}}`，返回值与填写内容逐字段相等；
      **这次工具调用随即做完**，工具结果照常回给模型，run 照常继续
- [ ] 人拒绝 → 服务器拿到 `{action:"decline"}`；取消 → `{action:"cancel"}`（两条断言分开）
- [ ] 挂到超时且无人答复 → 这次调用的结果是**指名超时**的错误结果，连接被丢弃；断言**没有任何**
      `accept` 形态的答复被发回服务器
- [ ] `requestedSchema` 的 `text` / `number` / `boolean` / `enum` 各渲染出一个可填字段，提交后类型不变
      （数字仍是数字、布尔仍是布尔）
- [ ] 一个不认识的字段类型：仍渲染、仍提交、**不丢字段**，并且界面上明说原始类型
- [ ] `Elicitation` 与 `ElicitationResult` 各落一行 `hook/<Point>` 审计行，payload 里有 server 名
- [ ] 审批门与 elicitation 卡**同时开着时不串**：一轮里既有 parked 的工具审批又有 elicitation 时，两张卡
      各认领自己的 reason，提交的 batch 两条都带上
- [ ] 离线全量 `harness.test-runner` 全绿；`cd ui && npm test` 全绿（含新套件）
