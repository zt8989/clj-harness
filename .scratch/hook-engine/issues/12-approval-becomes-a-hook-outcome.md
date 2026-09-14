# 12 — 审批成为 PreToolUse 的一个结局（悬置）+ PermissionRequest 代答

**What to build:** 今天"这个调用要人点头"是工具定义上的标记（`session-configure` 就是），由执行缝直接
park 等人；本票把它变成 **PreToolUse 决策链里的第三种结局**。一次工具调用因此有三个出口：
**放行** / **阻断**（回喂理由）/ **悬置**（park，等人点头）。人——或一个 `PermissionRequest` hook——
来回答悬置的调用。

**AG-UI 的 interrupt 帧形状不变**，批准/否决的客户端路径不动（UI 这一票完全不碰）。
`:requires-approval` 与 `session-require-approval!` **不删**，收窄为"给这个工具装一条悬置型判定"的两种来源；
权限标记与 hook 声明从此是同一条链上的两种输入，而不是两套并行的机制。

**这是本特征唯一要改写既有断言的票**：`approval_test`（483 行）按新决策链更新，改写清单必须在落地说明里
逐条列明——本仓的"只增不减"在这一票不适用。

**Blocked by:** 11

**Status:** ready-for-agent

- [ ] 一次调用经 PreToolUse 得到三种结局之一，三种都有端到端用例（放行执行 / 阻断回喂 / 悬置 park）
- [ ] 悬置走**既有**的 park/resume 通道：不发 `:tool/result`、不写 tool 消息、run 以 interrupt 收尾；
      帧形状与今天逐字节相同（客户端一个字都不用改）
- [ ] 悬置与阻断的先后写死：hook 给的 veto 与人的否决语义一致（都当工具结果回喂，都带理由）
- [ ] `PermissionRequest` 点能**代答**悬置的调用（批准 / 拒绝），代答之后不再打扰人；
      一个声明了代答 hook 的会话里，原本会 park 的调用直接执行并有审计行
- [ ] 未知 interruptId 的 resume 仍被指名拒绝；一份决定只消费一次
- [ ] 不回归：没有声明任何 hook 的会话里，被标记的工具仍然 park，批准/否决两条路都与今天一致
      （既有 approval_test 里未改写的断言继续绿）
- [ ] 需要改写的既有断言在落地说明里**逐条**列出（哪条、原来断言什么、为什么必须改）
- [ ] 全绿
