# 06 — `ctx.tools`：工具表是服务

**What to build:** 注册表、会话 overlay、待决审批、三相执行、工具声明的词汇升格成核心服务 `ctx.tools`；
`cap.tools` 那二十个工具照旧装上。`install!` 今天那几条语义**一条不丢**：同名后者覆盖前者、
禁用不是删除、`teardown` 撤自己那一层并还原下面那层。overlay 的第一个用户是 `ctx.scope`。

**Blocked by:** 03 — `ctx.scope`；04 — `ctx.sessions`

**Status:** ready-for-agent

## 验收

- [ ] 二十个工具的**声明逐字节相同**（名字 / 描述 / 参数 schema 一字未动）——本仓现成的硬断言照跑
- [ ] 编辑模式的收窄与停用语照旧：`:hashline` 下 `edit` **不在**表里；`:str-replace` 下
      `replace` / `insert` / `grep` / `undo_last_replace` **不在**表里；被拒的名字仍在表里、拒绝信息照旧说清
- [ ] 审批的三个出口（放行 / 阻断 / 悬置）逐条不变；悬置那条线在票 10 里再收一次
- [ ] 会话 overlay 经 `ctx.scope` 生效：两个会话两张表，各改各的，互不影响
- [ ] 「禁用不是删除」与「同名后者覆盖前者」各有断言；卸载顺序不成为 load-bearing（本仓有测试钉着）
- [ ] 缝在没装东西时是**空的**，答案是 `unknown tool` 而不是 `disabled`；组合根的 `stop` 带回 teardown
- [ ] 「core 不认识任何一个具体工具」这条在新形状里仍成立：`ctx.tools` 里不出现任何工具名
