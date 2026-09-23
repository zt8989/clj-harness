# 05 — 收口：删掉三条 SSE 路由与旧读取器

**What to build:** 三条 SSE 流都搬完之后，把没人再调用的旧路由与客户端旧读取器删掉，并把文档与 ADR
收口——让别人读今天的架构时，看到的只有 WebSocket 下行这一条路。

**Blocked by:** 01、02、03、04。

**Status:** ready-for-agent

- [ ] `POST /api/agent` 的 SSE 响应体、`GET …/follow`、`GET …/feed` 三条路由在没有调用者之后删除。
- [ ] 客户端的旧 SSE 读取器（含为 SSE 保留的 `fetch` + reader 那条缝）删除。
- [ ] 文档收口：`docs/architecture/edge.md` 的路由表、`client.md` 的模块地图更新到 WebSocket 下行；
      ADR 0003 的修订落地（或新增一条 ADR 指明取代关系）。
- [ ] 后端全量与前端 `npm test` / `npm run typecheck` / `npm run build` 绿。
- [ ] `node scripts/dev.mjs --scripted` 走查绿。
