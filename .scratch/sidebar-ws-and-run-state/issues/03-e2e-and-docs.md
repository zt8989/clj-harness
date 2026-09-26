# 03: 端到端走查 + 文档

**What to build:** 两张票合并后的整体验收:`node scripts/dev.mjs --scripted` 构建并起服务,自己开浏览器走一趟——重点验双窗推送(窗口 A 发消息,窗口 B 侧栏的 lastSentAt 与 running 更新)和进程重启后运行状态的正确回落(重启后没有幽灵 spinner)。把「左侧 listing:首次 HTTP、后续 WS 推送」的边界写进 `docs/architecture/client.md`(或就近文档),并检查 `ui/src/lib/projects.ts`、`ui/src/lib/host.ts` 头部注释与新行为一致。

**Blocked by:** 02.

**Status:** ready-for-agent

- [ ] `node scripts/dev.mjs --scripted` 全绿
- [ ] 双窗走查:推送更新、无重复拉取(网络面板确认打开页面后无第二次 `GET /api/projects`)
- [ ] 重启进程:遗留 `running` 清回 `idle`,侧栏无幽灵 spinner
- [ ] 边界说明落在 `docs/` 下的正确位置(不进 README 四节)
