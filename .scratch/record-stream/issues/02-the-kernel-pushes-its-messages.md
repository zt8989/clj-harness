# 02: 内核 push 它的消息

**What to build:** 一轮的流交给内核，内核把**它自己的消息** push 进去——`harness.kernel.event` 的
`:message/added` 不再是「报给边、边去写」，而是内核直接落进这一轮的队列（号由队列同步答）。
边的角色回到它该在的位置：读**它自己**那批（请求、帧、事实）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 一次真 run 之后，记录里那几条消息行的行号与 `push!` 的答案一致（一条端到端用例）
- [ ] 顺序仍然对：助手那条落在这次调用的 `model/end` 之前；工具那条落在
      `tools/execute` 与 `tools/post-execute` 之间
- [ ] 边不再代写内核的消息（那一段代码删掉，不是留着不用）
- [ ] `:run/done` 的 `:added` 仍然是对账：没报过的补写并点名，同一条消息绝不落两行
