# 01 — 队列、`commands` 字段、优先级表

**What to build:** 这条通道的地基：一场会话一个**有序队列**，命令从 `POST /api/agent` 的 `commands` 进队。

- **入站**：`POST /api/agent` 的体多一个 `commands` 数组；edge 把每条**放进这场会话的队列**，
  按到达顺序排。队列放**进程内存、按会话**（与待决审批、作业注册表同族）。
- **不 409**：`handle-run` 的第四个决定（`refuse-second-run!`）多一个例外——
  一个**带 `commands` 而没有 `append`** 的请求，在 run 已经在跑时**不拒、答 200**，
  命令入那场 run 的队列。带 `append` 的照旧 409（那才是第二场 run）。
- **优先级表是一处的数据**：`type → {:priority n :no-run :refuse|:start-run|:wait}` 一张 map
  （与 `harness.cap.todos/statuses` 同一条纪律：消费者排的次序、拒绝时说的合法集合，都从它来）。
  这一轮的行：`interrupt`(0, refuse)、`steer`(1, refuse)、`compact`(2, start-run)、`goal`(3, start-run)、
  `queue`(4, wait)——后两条这一轮不实现（票 05 只把契约写死）。
- **未知 `type`** 按名字拒绝，点出合法集合。

**Blocked by:** None — 可以立即开始

**Status:** ready-for-agent

- [ ] 一个带 `commands` 的请求把命令放进队列：同一条命令取走即出队，只生效一次。
- [ ] **两个连接**同时下命令，队列里的先后 = 到达先后（一条用例）。
- [ ] run 在跑时，带 `commands` 无 `append` 的请求答 **200**（不是 409）；带 `append` 的仍 409。
- [ ] 没有 run 在跑时，`commands` 留在队列里、不会自己开 run（这一票只做队列；开 run 是票 02/04）。
- [ ] 队列按会话分家：A 会话的命令进不了 B 的队（一条用例）。
- [ ] 优先级表**只有一处**，且 `type` 集合与 run 请求里那几处（校验、前端、测试）对得上
      （票 06 的键集合测试钉住）。
- [ ] 未知 `type` 拒绝时列出合法集合（从表里来，不是手写）。
- [ ] 测试：新命名空间（建议 `harness.cap.commands`，queue + 表 + 拒绝）的单元用例 +
      `harness.edge.http-test` 里那条「不 409」的用例；隔离照 `AGENTS.md` 既有纪律。

**本票的界线**：不消费（票 02）、不做 `interrupt` 的执行（票 03）。
