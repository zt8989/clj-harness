# 04: 会话切换项目目录

**What to build:** 对已绑定 thread 重新绑定到另一目录：路径解析立即切到新目录；落审计行带 before/after（对齐 provider/changed 行的风格），两次绑定可从日志重建出目录变更时间线；CwdChanged 事件源以可被 hook 引擎直接消费的形态就位（hook 接线是 P2，本票只产事件事实与审计）。UI 复用 01 的选择入口完成切换。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 已绑定 thread 重新绑定成功，此后相对路径解析到新目录
- [ ] 审计行带 before/after，从日志可读出目录变更时间线
- [ ] UI 用同一入口完成切换并即时显示当前绑定
- [ ] CwdChanged 事件源就位（形态写进 spec），本票不接 hook
