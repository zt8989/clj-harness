# 02: 侧栏首次 HTTP、后续 WebSocket 更新

**What to build:** 左侧 Project 列表只在**第一次**通过 HTTP 拉取(`GET /api/projects`),之后一切变化都由 `events.host` WebSocket 推送更新——包括切换会话、另一个窗口发消息、别的窗口增删项目、运行状态起止。`sidebar.tsx` 去掉 `currentThreadId` 变化触发的重复拉取(它当年的理由是「重建日志令 mtime 失真」,listing 已不读 mtime,理由消失);打开会话时由推送或会话自身的窗口流补齐状态。手动刷新按钮保留为 socket 连不上时的兜底。端到端可验:开两个窗口,一个发送,另一个侧栏不刷新也更新。

**Blocked by:** 01 (运行状态落 SQLite —— 推送帧里的 `running` 才有可靠来源)。

**Status:** ready-for-agent

- [ ] `sidebar.tsx` 挂载时 `listSidebar()` 一次;`useEffect` 不再依赖 `currentThreadId` 触发 `refresh`
- [ ] 切换会话/运行状态变化都经由 `events.host` 推送反映到 listing(票 01 已把 run 状态入 store,run 起止已 `host/ring!`)
- [ ] 手动刷新按钮保留,注释说明它是 socket 不可用时的兜底而非常规路径
- [ ] 动过 `ui/src/` 合前跑一次 `node scripts/dev.mjs --scripted` 并自开浏览器走一趟(双窗验证推送)
- [ ] `ui` 侧单测(typecheck / vitest)过

## Comments

### 实现中的裁决(2026-09-26)

走查发现 `lib/sidebar-refetch.ts` 的 ask-again 重读(`asked`)在推送模式下仍然必要,并且
有第三个理由成立:慢脚本下,listing 的 `running` 可能比注册表先过期——但 `events.host`
的 ring 里没有「运行中」的帧,于是那行 spinner 会停留到下一次重读。保留该机制,并在
`sidebar.tsx` 的 effect 上方写明它是 `refresh` 仅剩的调用方之一,与推送不冲突。

`openThread` / `archive` / `remove` / `addDirectoryAt` 的 `await refresh()` 全部去掉:
这些路由本来就走 `rung`(`host/ring!`),推送会把新 listing 送回本页;自己再拉一次是
重复劳动。挂载读 + 按钮兜底 + ask-again 重读是 `refresh` 仅剩的三个入口。
