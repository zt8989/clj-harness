# 01 — 冻结 prompt 回 harness.llm

**What to build:** system prompt 的冻结与热改回到 provider 层：`harness.memory/prompt`、`reset-prompt!`
以及那个冻结槽搬进 `harness.llm`，调用方（http 边的 run 装配、replay 的重建与续跑）改指新位置，
`prompt.md` 里点名 `harness.memory` 的那几行同步改路径。行为逐字节不变：第一次调用读 `prompt.md` 并冻结、
之后每个 run 复用同一份文本、`reset-prompt!` 是唯一的重读入口——provider 前缀缓存的前提不动。

这是搬家范式的第一票，刻意挑最小的：先把"一段代码从 memory 回到原模块、调用方与文档一起改指"走通一遍，
后面四张搬家票照着做。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 冻结槽与两个函数住在 `harness.llm`，`harness.memory` 不再含它们
- [ ] 调用方全部改指新位置：http 边的 run 装配、replay 的重建与续跑路径，以及引用它们的测试
- [ ] 冻结语义不变：首调读盘并冻结、之后的 run 复用同一份文本；`reset-prompt!` 是唯一重读入口
- [ ] `prompt.md` 里指向 `harness.memory` 的路径引用改为新位置（**只改路径**；措辞与段落的收敛是 13 号票的事）
- [ ] `harness.llm` 的 ns docstring 说明 system prompt 的载体与冻结纪律住在自己这里
- [ ] 离线全量 `harness.test-runner` 全绿，断言数与基线持平（189 tests / 930 assertions）
