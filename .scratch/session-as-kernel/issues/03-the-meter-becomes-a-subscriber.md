# 03 — 压力表成为第一个订阅者

**What to build:** 压力表的「表针」改写成**只吃行**的折子（就是今天写入口顺手喂的那套规则：真 run 的
`model/start`、报用量的 `model/end`、system 行、run 自己的注入），于是：

- 点开会话时表针就折好了，**run 开头那次压力测量不再读记录**——连这场会话在本进程的第一次也不读；
- 压力表从会话读表针；离线的 `records->pressure` 用**同一个折子**折出表针（一份实现，两个调用点）；
- 两条路对同一份事实给出**逐字段相同**的答案。

**Blocked by:** 02 — 折子挂点。

**Status:** ready-for-agent

- [x] 表针是 02 那道缝上的一个折子；「播种」那次读记录消失。
- [x] 一场真会话上，《会话折出来的表针》与《记录折出来的表针》同答案；run 开头压力逐字段相等。
- [x] 大记录上 run 开头那次测量 O(1)（不再是秒级）。
- [x] 一个 `runId` 为空的压缩调用照旧不影响锚点（票 02 的规则搬到折子上）。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- 表针 = `pressure/band-step`，登记在会话的**读流**上（`register-fold!`）；`band-pressure` / `log-pressure` 不再收文件参数 —— 播种那次读**没有了**。
- `pressure-test/the-live-band-and-the-record-fold-answer-the-same-thing`（真会话上，会话折出来的表针与 `records->pressure` 逐字段相等）＋ 新增断言「表针是会话自己的折子」。
- `pressure-test/the-band-ignores-a-call-the-harness-wrote-for-itself`：runId 为空的压缩调用照旧不影响锚点。
