# 04 — 工具表与会话 overlay 回 harness.tools

**What to build:** 工具注册表回到它唯一的装配与消费处（`harness.tools`）：注册表本身、`register!`、
`effective-tools`、`*thread-id*`，以及会话级的四个入口（register / unregister / disable / enable）与
`session-disabled?`。thread-id 的贯通不变（执行缝给工具体绑定它，eval 靠它认自己的会话）；两条正交轴与
"被关的工具仍在表里、只是调用被拒"的语义一字不动。

搬家理由：工具表在 `1d92d5e` 之前就住在 `harness.tools`；overlay 是同一张表的会话级切片。
memory 之所以把它收走，是为了让 eval 能自省——而 eval 的定位正在收敛（见 spec）。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 注册表与 overlay 的四个入口住在 `harness.tools`；`harness.memory` 不再含它们
- [ ] `effective-tools` 的语义不变：基座 + 本会话新增；nil thread-id 即基座；
      **`effective-tools` 仍然不是"将会跑的工具集"**（被关的仍在里面）
- [ ] `register!` 的可见性收成模块内：它此后只有本 ns 自己用（基座工具在这里登记），
      对外的会话级入口是 register/unregister/disable/enable 四个——别让 `harness.tools/register!`
      变成"谁都能改基座"的门（今天没有任何外部调用方）
- [ ] `*thread-id*` 仍在执行缝被绑定，工具体（eval 在内）仍能认自己的会话
- [ ] 会话级注册/撤回/关闭/打开的跨会话隔离与幂等语义不变（撤回本会话新增时连带清关闭标记这条也在）
- [ ] 测试（session_tools_test 及其它引用处）改指新位置，**断言内容不动**；`provider_test` 里那处
      直接 deref 基座注册表读 `session-configure` 定义的地方改指新位置时**保留"读的是基座"这个含义**
- [ ] `harness.tools` 的 ns docstring 说明工具表住在自己这里、`harness.memory` 不再代管
- [ ] `prompt.md` 里指向 `harness.memory` 的路径引用改为新位置（自省段落的退场是 13 号票的事）
- [ ] 全绿，断言数与基线持平（189 tests / 930 assertions）
