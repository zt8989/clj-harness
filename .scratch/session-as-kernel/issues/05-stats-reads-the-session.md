# 05 — stats 改读会话（不再自己读盘）

**What to build:** 统计这条读法（一场会话的几个数）改成从**会话已有的东西**取，不再自己读记录。
对外答案一字不变（`GET /api/threads/<stem>/stats` 的形状与数字）。

**Blocked by:** 02 — 折子挂点。

**Status:** ready-for-agent

- [x] 统计路由在装会话之后不再打开记录。
- [x] 数字与今天逐字段相同（既有用例全绿，含「窗口期 / 未完成会话」那些边界）。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- stats 路由改问会话：`sessions/read-records`（404/400 的句子与命名规则仍是同一处），不再自己 `read-records` / `stats/read-records`。
- 数字逐字段不变：`stats-test` / `http-test` 全绿（含窗口期与未完成会话的边界）。
