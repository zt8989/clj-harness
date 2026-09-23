# 03 — run 帧改走 `events.mux`，`POST /api/agent` 只起跑

**What to build:** 发送仍是一次普通 HTTP POST，但它只**起跑并回执**；这一轮的 AG-UI 帧改从
`events.mux` 下行到达，发起的窗口与所有看客画的是同一条流。停（`/cancel`）、abort、以及记录在
终帧处的折叠（`settle!`）语义一个字不改；被换掉的只有客户端那个「从 POST 的响应里读 SSE」的读取器。
拉取（尾页、补页）仍旧 HTTP。

**Blocked by:** 01（mux 管道与订阅）。

**Status:** ready-for-agent

- [ ] `POST /api/agent` 起跑后立即回执，这一轮的流不再挂在这个响应上。
- [ ] AG-UI 帧经 `events.mux` 到达，按 thread 归位；发起窗口与观察窗口看到同一轮输出。
- [ ] 停的既有语义不变：一条按会话寻址的取消到得了循环，人按停不是 host 失败（不丢掉整列会话）。
- [ ] run 结束时记录的折叠与 `settle!` 一字不变，窗口最终与记录一致。
- [ ] 断线重连后这一轮接着到达，不漏帧、不重帧。
- [ ] 客户端用例 + 真浏览器走查：一个窗口驱动、另一个窗口观察同一轮。
