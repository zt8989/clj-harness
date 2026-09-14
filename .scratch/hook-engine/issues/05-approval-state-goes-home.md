# 05 — 审批状态回执行缝所在的模块

**What to build:** park / decide / verdict 这一套状态回到 `harness.tools`——它是执行缝的状态，
而执行缝就在那里：会话级审批集合（要求某个工具点名）、待决登记（park / 按 interruptId 查 / 按调用 id 查 /
按 thread 列）、人的决定（record / 原子取用且只消费一次）。loop 的 resume 重放路径、http 边的审批端点、
执行缝与测试改指新位置。

语义逐字不变：一份决定只消费一次、未知 interruptId 的 resume 被指名拒绝、重 park 同一个 id 会清掉上一次的
决定、不做超时也不做跨进程持久化。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 审批相关的全部入口住在 `harness.tools`；`harness.memory` 不再含它们
- [ ] 一次决定只消费一次：重放同一个 interrupt 不会执行两次（既有测试继续守）
- [ ] 未知 interruptId 的 resume 仍被指名拒绝；重 park 同一个 id 清掉旧决定
- [ ] 进程内、不持久化这条不变：重启即失，不落盘、不从盘读回
- [ ] 调用方（loop 的 resume 重放、http 边的审批端点、执行缝）与测试改指新位置，**断言内容不动**
- [ ] 全绿，断言数与基线持平（189 tests / 930 assertions）
