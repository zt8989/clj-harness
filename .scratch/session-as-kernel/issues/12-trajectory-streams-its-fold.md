# 12 — 轨迹也走流：`records->trajectory` 不再物化整份记录

**What to build:** 票 06 把轨迹路由改成向会话要记录了，但 `harness.edge.trajectory/records->trajectory`
仍收一个**记录向量**（`stats/read-records` 折出来的整份），于是「峰值堆是轨迹、不是文件」那一格没兑现
——长会话上这条路由先把整份记录摊在堆里再算，这就是它卡的原因（票 06 的收口验证里记着这一格未达成）。

这一票把**第一条折**（`trajectory/run-segments`）提成可以流式驱动的 step，路由改从会话的读流折。
`run-segments` 之后的一切（`call-index` / `tool-lifecycles` / `align` / `one-run` / `calls-of`）本来
就只看**段**、不看行，所以流式的边界正好落在段上——不用把整个 `trajectory` 命名空间翻一遍。

**交付：**

- `trajectory/segments-init` / `segments-step` / `segments-answer`：把 `run-segments` 那份循环提成
  reducing fn，形状照 `harness.edge.stats` 的 `stats-init` / `stats-step` / `stats-answer`（那里是
  ticket 06 已经趟过的同一条路）。
- `trajectory/fold-trajectory`：`(harness.edge.replay/fold-records f (segments-init) segments-step)`
  → 段 → `records->trajectory` 的后半段。`log-trajectory` 改走它，不再 `stats/read-records`。
- 路由（`harness.edge.http/trajectory-get`）：今天用 `sessions/read-records`（票 06 的接线），改成
  `sessions/fold-record`（会话那道**流**的门），所以记录的行一列都不会整份留在堆里。

**Blocked by:** 无。票 06 的用户可见行为已经在了；这一票只把「要一份向量」换成「折一条流」。

**Status:** ready-for-agent

- [ ] `log-trajectory` 不再物化整份记录：峰值堆是 O(段 + 算出来的轨迹)，不是 O(记录行)。
- [ ] `records->trajectory` 的输出逐字段不变（既有用例全绿，含「记录早于模型调用」「悬置恢复同一轮」
      那些边界）。
- [ ] 路由在装会话之后不再自己读盘（走 `sessions/fold-record`）。
- [ ] 长会话上有证据：在一条大到 `read-records` 明显吃堆的记录上，折轨迹的峰值堆**不随文件行数增长**
      （照 `harness.edge.replay-test/fold-records-streams-the-same-records-read-records-answers` 的形状写）。
- [ ] 离线全量 `harness.test-runner` 全绿。

## 这一票与票 06 的关系

票 06 的验收有四格，今天三格成立、一格不成立：

| 票 06 那一格 | 今天 |
|---|---|
| 轨迹路由在装会话之后不再打开记录 | 成立（走 `sessions/read-records`） |
| 轨迹的形状与今天逐字段相同 | 成立（既有用例全绿） |
| **峰值堆仍是 O(算出来的轨迹)，不是 O(文件)** | **不成立** —— 就是这一票要兑现的 |
| 离线全量全绿 | 成立（1 红是预存在那条 `claims_test`） |

所以：**这一票做完，票 06 才算真的做完**（票 06 的那一格在它的收口验证里已经被标成未达成，不是悄悄
漏掉的）。

另：本票**不是**票 06 那刀引入的退步。改之前 `trajectory/log-trajectory` 就是
`(records->trajectory (stats/read-records f))`，改之后路由只是把「谁去 read-records」从自己换成会话
——两边都物化整份。卡是这条路一直有的成本，这一票才是把它拿掉。

## 折的时候别又把它拖回整份记录（两处）

- **段里装着每个调用的 `model/start` payload，也就是那张工具表。** 它正是轨迹要画的东西，所以它是
  O(轨迹) 而不是 O(文件)——但「顺手把整条 start 行的原始 map 留着」会让它翻倍。用记录里**已经有的事实**
  （`:tools-names-hash` / `:tools-bytes`，`.scratch/model-surface-and-meter` 票 04 的规矩），别在折里
  再存一份表。
- **`stats/incomplete?` 今天收 records。** 流式之后它要么收段，要么由段自己说（「最后一段没有终帧」），
  别为了它再读一遍文件——那正是这一票要消掉的那种读。

## 复议（2026-09-24）：票 06 落地之后，参照物换一个

`.scratch/events-mux-and-host` 票 06 已经先把一半落了：`harness.edge.trajectory/fold-trajectory`（带
emit，逐轮折逐轮吐）与 **NDJSON 路由**都在了。但路由仍走 `sessions/read-records`，折法仍收一份记录
**向量**——所以上面「峰值堆」那一格仍未成立，剩下的就是这三条：折法收流、三条 prepass 并进同一步、
路由走 `sessions/fold-record`。

**参照物要换掉**：`harness.edge.stats` 今天**没有** `stats-init` / `stats-step` / `stats-answer` 这套
（上面「交付」那三条是立票时的假设，没有落地）。真正的先例是 `harness.edge.pressure` 的 `band-step` +
`replay/fold-consumers`：**一份折法，三种驱动**（内存走一遍 / 会话 build 走一遍 / 写流一行一遍）。
票 13 要把这一步挂上会话的两条缝，所以写的时候按 `band-step` 的形状写，别照一份不存在的 stats 步。
