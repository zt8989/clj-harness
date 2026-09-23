# 04 — subagent 的 `follow` 并入 `events.mux`

**What to build:** 看一个子 agent 的对话，改用同一条 `events.mux`，把今天的 `GET
/api/threads/<stem>/follow` 这条 SSE 退休。子 agent 的帧与主 agent 的帧是同一种帧、同一个下游，只是
`threadId` 不同；重放与终帧的语义照旧。

**Blocked by:** 03（run 帧先落到 mux 上，子 agent 的帧才有一处可并）。

**Status:** ready-for-agent

- [ ] 子 agent 的帧经 `events.mux` 到达并归位到它的对话。
- [ ] `follow` 那条 SSE 路由不再有调用者。
- [ ] 子 agent 记录的**重放**（已经跑完的一场）与**终帧**语义不变。
- [ ] 真浏览器走查：委派一个子 agent，从另一个窗口/刷新后也能看到它的对话。
