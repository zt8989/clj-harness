# 07 — context 改读会话（不再自己读盘）

**What to build:** 上下文圈这条读法改成从会话取（最近一次报用量的调用、它自己的窗口、三样拆分），
不再自己读记录。

**Blocked by:** 02 — 折子挂点。

**Status:** ready-for-agent

- [x] 上下文路由在装会话之后不再打开记录。
- [x] 三个篮子的数值与今天逐字段相同（含旧记录回退 `:tools` / `timeline-window` 的那两条）。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- 上下文圈随 stats 那条路一起改问会话（同一个 `read-records`，一份记录折三个读者，所以两条读数不会各说各话），不再自己开文件。
- 三个篮子的数值逐字段不变（`context-test` 全绿），含旧记录回退 `:tools` / `timeline-window` 的两条。
