# 01: 会话级开关 —— 可用性维度与四个正交入口

**What to build:** 会话 overlay 从「`:added` + `:removed`」改为「`:added` + `:disabled`」：插入/删除管定义的有无，关闭/打开管可用性，四个入口各自只动自己那一轴。`session-unregister!` 收窄为只撤本会话新增的定义（对 base 名 no-op），并在撤回时**连带清掉该名的关闭标记**——否则同名重新插入会继承一个僵尸的关闭状态。`session-disable!` / `session-enable!` 是一对可逆操作：关闭后工具**仍在工具表里**，打开即恢复，定义全程不动。base 注册表永不被改，跨会话隔离，重复关/重复开幂等。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 关闭一个 base 工具后，该会话的工具表**仍然包含它**（只是调用会被拒，见 02）；打开后工具表无变化——它从没离开过
- [ ] 关闭本会话新增的工具后，其定义仍留在 overlay 里（不丢）；打开后仍能被查找
- [ ] 四个入口互不串味：`session-unregister!` 对 base 名是 no-op（不再兼职隐藏）；`session-enable!` 对没关过的名字 no-op；`session-disable!` 对不存在的名字 no-op，不凭空造标记
- [ ] `session-unregister!` 撤回一个已被关闭的会话新增工具时，定义与关闭标记一起清掉；同名重新插入后是"打开"状态
- [ ] 跨会话隔离：A 关闭不影响 B 的工具表
- [ ] 命名与 docstring 诚实：返回本会话定义集的函数不能暗示"可用"——被关的仍在里面
- [ ] 既有断言按 spec 的改写清单更新（"移除 base 即隐藏"与"移除单调"两个块）；离线全量 harness.test-runner 全绿（基线 55/247）
