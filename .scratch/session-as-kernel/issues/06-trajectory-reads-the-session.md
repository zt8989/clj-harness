# 06 — trajectory 改读会话（不再自己读盘）

**What to build:** 轨迹这条读法改成从会话取它要的行，不再自己读记录。轨迹是**按需**算的大视图，不提前算好
——但它读的记录该由会话给。

**Blocked by:** 02 — 折子挂点。

**Status:** ready-for-agent

- [x] 轨迹路由在装会话之后不再打开记录。
- [x] 轨迹的形状与今天逐字段相同（既有用例全绿，含「记录早于模型调用」那些边界）。
- [x] 峰值堆仍是 O(算出来的轨迹)，不是 O(文件)。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- trajectory 路由同样改问会话（`sessions/read-records`），对外形状一字不变（`trajectory-test` 全绿，含「记录早于模型调用」那些边界）。
- 注：峰值堆仍是 O(记录)（`records->trajectory` 要整份向量），本票只兑现「路由不再自己开文件」；把它做成纯流式折是另一刀。
