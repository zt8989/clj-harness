# 03: agent 自己开关它 —— eval 集成与 prompt.md

**What to build:** prompt.md 的 Session tools 段补上关闭/打开两个操作，并把"读工具表"的说明修对：现在它让 agent 读 `(keys @harness.memory/registry)`，那是 **base**，漏掉会话 overlay——agent 自己关掉或加上的东西都不在里面。改成读**本会话生效的工具集**。同时写明关闭的语义（仍可见、调用被拒、可以自己打开）与那条局限（关闭不是禁止）。

**Blocked by:** 01: 会话级开关 —— 可用性维度与四个正交入口；02: 执行缝拒绝被关的调用 —— `:disabled` 取代误报的 unknown-tool；eval-self-extension/02（改同一段 prompt.md）

**Status:** ready-for-agent

- [ ] 经 eval 关闭 `bash` 后，下一 run 的工具表**仍含** `bash`，而调用它得到明确的"已关闭"；经 eval 打开后立即恢复可执行
- [ ] agent 经 eval 读到的是本会话生效的工具集（反映 overlay：含会话新增、含被关的），不再是纯 base
- [ ] prompt.md 四个操作齐全；语义写清关闭 ≠ 删除 ≠ 禁止；有"如何查看自己现在的工具集"的说明
- [ ] 离线全量 harness.test-runner 全绿
