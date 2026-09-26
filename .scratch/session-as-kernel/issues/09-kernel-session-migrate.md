# 09 — 会话搬进 kernel：消费者搬过去（migrate）

**What to build:** 把 edge 会话身上那些**消费者**搬过去：行的解析与「行 → 对话」的折、claims 的接管、AG-UI 的读法，
全部改成向 kernel 的会话**登记 / 注入**；edge 只剩「定位文件、解析、折好、登记」这一层适配。

**Blocked by:** 08 — 机制立起来。

**Status:** ready-for-agent

- [x] edge 的会话不再自己持有状态（状态在 kernel 的会话上）。
- [x] 写者（`harness.edge.record`）只向 kernel 的会话登记，不再 require edge 的会话。
- [x] 行为一字不变；`harness.layers-test` 绿；铁律仍成立。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- 写者 `harness.edge.record` 只向 `harness.kernel.session` 登记（`watch-unflushed!`），不再 require 边那份。
- 状态只在 kernel 的会话上：edge 的会话没有一个 atom；行为一字不变（全量绿）。
