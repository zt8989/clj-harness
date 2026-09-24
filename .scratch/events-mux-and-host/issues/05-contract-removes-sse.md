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

## 进行中（2026-09-23）

**`feed` 与 `follow` 两条已删**（见 spec 的「票 05（一半）」）。run 的 SSE 响应体还在，
因为删它是一次**宽改动的收口**：每一个自带 run 读取器的测试命名空间会同时变红（试过一次，
全量 83 failures / 20 errors）。已迁移：`http_test`（84 处）、`mcp-wired-test`；两者都用
`harness.test-support/mux-run!`。

**还没迁移的 9 个**（各有一个 `post-run` 形状的助手）：

- `cap/ask-test`、`edge/context-test`、`edge/delegation-test`、`edge/delegation-line-test`
- `edge/frames-route-test`、`edge/stats-test`、`edge/trajectory-test`、`edge/ui-test`
- `kernel/hooks-wired-test`

**删门前还要先补一处**：异常结束的 run（崩溃、事件通道无终帧关闭）现在靠 SSE close 替读者收尾；
下行的读者要等一个终帧。要给这两条路补一个合成的 `RUN_ERROR` 广播。
