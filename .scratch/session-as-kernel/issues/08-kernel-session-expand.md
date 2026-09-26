# 08 — 会话搬进 kernel：机制立起来（expand）

**What to build:** `harness.kernel.session` 立起来：它是**机制**——按 thread 的状态、02 / 04 那两道订阅缝、
run 开关——而且**不 require `harness.cap.*` 或 `harness.edge.*`**。`harness.edge.sessions` 保留原名与行为，
但把状态与缝**委托**给它。这一刀只加不改：所有调用点照旧，行为一字不变。

**Blocked by:** 01、02、03、04、05、06、07 — 机制的形状要由那几道缝定下来才立得稳。

**Status:** ready-for-agent

- [x] `harness.kernel.session` 不 require cap / edge（`harness.layers-test` 绿）。
- [x] edge 的会话委托它；对外行为与今天一字不变（既有用例全绿）。
- [x] 两道缝（折子 / 实时 step）由 kernel 的会话定义，edge 只登记。
- [x] 铁律仍成立：run 进行中内核不读自己的记录。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- `harness.kernel.session` 立起来了：按 thread 的状态、两道订阅缝、窗口算术、run / stop 的钉子，**只 require `harness.kernel.*`**（`layers-test` 绿）。外来的事实走 `install!` 的五道缝（`:build` / `:model-messages` / `:read` / `:fold` / `:claim`）。
- `harness.edge.sessions` 保留原名与行为，但**不含状态**（无 atom），只装缝 + 再导出。铁律写在机制与 ADR 0005 里：会话一生只读一次（出生那次走查）。
