# 06: UI 会话列表与恢复

**What to build:** UI 会话面板：从 /api/threads 列出历史会话（最后活动/规模）；点恢复 → rebuild → 重建历史进入客户端持有（服务端不变成会话状态权威）→ 用户输入下一条消息走常规 AG-UI run 继续对话。截断/损坏日志在 UI 指名报错且面板不崩、可切换其他会话。SessionStart(source=resume) 的 hook 语义留给 P2，本票不做。

**Blocked by:** 05

**Status:** ready-for-agent

- [ ] UI 列出会话并可刷新
- [ ] 点恢复后重建历史完整呈现（含 reasoning 与工具调用形态），继续输入得到连贯回复（模型可见前文）
- [ ] 恢复后常规 AG-UI 流式照常（帧结构过 violations 校验，无结构违规）
- [ ] 截断/损坏日志在 UI 指名报错，面板可恢复（换会话/新建）
- [ ] 重建历史归客户端持有，后续 run 由客户端回传（AG-UI 契约不变）
