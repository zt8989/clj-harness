# 03 — 管理边：`GET …/goal`、`POST /api/goal`、`goal` 推送帧

**What to build:** 让人（页面）与别的客户端能动目标。

- `GET /api/threads/<stem>/goal` → `{threadId, goal, armed?}`：
  `goal` 是记录快照（票 02 那份，或 `null`），`armed?` 是**进程事实**（这条会话现在还允许自动续轮吗，
  spec 决定 3），单独一个字段、不在 `goal` 里。**只读、不留痕、不 locate、不 404**（`todos-get` 同款）。
- `POST /api/goal`，体 `{threadId, action, ...}`，`action` ∈ `show|create|edit|pause|resume|clear`
  （`create`/`edit` 带 `objective` 与可选的 `max_goal_rounds`），答 `{threadId, goal, armed?}`。
  规则与拒绝**全部来自 `harness.cap.goal`**（票 02），这一层不判一条规则；拒绝答 `400 {error}`。
  `pause`/`resume`/`clear` 走的是**人的门**（`:by :human`）——这正是「paused 只有人能恢复」的落点。
- **每一次真写推一个 `goal` 帧**：`harness.edge.http/goal-send!` 把 `{:type "goal", threadId, goal, armed?}`
  发给所有在看这场会话的连接（`mux/channels-for`）。与右栏那条 `task-send!` 同形同理由：
  目标是库/记录里的东西，没有 `seq` 可编号、没有东西可按游标重放，所以整份载荷一个帧一次变。
  `clear` 之后 `goal` 是 `null` 也要推。
- **客户端认这一族帧**：`ui/src/lib/mux.ts` 的 `familyOf` 多一个 `"goal"`、多一张订阅表、多一个
  `subscribeGoals`，并导出一个 `GOAL_FRAME_TYPE`（照 `TASK_FRAME_TYPE`）。**漏这一步不是显示问题**：
  帧会掉进 run 家族、被 `@ag-ui/client` 的 schema 拒掉、把整个 run 弄挂（`task` 那条注释写过这个坑）。
- 模型那一半的写不经这里（票 04 直接调 `harness.cap.goal`，并在 `:run/end` 那条边界推一次帧，
  这样目标条不必等到下一轮才看见模型的 `complete`）——cap 不反向 require 边，推的点在边这一侧。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `GET` 一个从没有过目标的会话答 `200 {threadId, goal: null, armed?: false}`，不是 404。
- [ ] `GET` 只读：再问一次不写审计、不改任何一行、不改 `armed?`。
- [ ] `POST` 六个动作各一条路径，全部落到 `harness.cap.goal` 对应动词；成功答 `{goal, armed?}`，
      拒绝答 `400 {error}` 且错误句是 `harness.cap.goal` 的原文（这一层不翻译）。
- [ ] `POST` 未知 `action` 答 `400`，点出合法动作集合（「我认得哪些」是这一层知道的）。
- [ ] `POST` 一次 **create**：jsonl 多一条 `goal/change` 行、投影行在、
      `GET` 与帧都报同一份；**没有在跑任何 run**（这条路由不调模型）。
- [ ] 路由按既有形状挂：`GET` 进 `stem-verb-route` 那张表（与 `todos` 同处），
      `POST /api/goal` 进管理边那一组（与 `/api/model`、`/api/project` 同处）；方法不对答 `405`。
- [ ] **一次真写推一帧**：两个连接看着同一会话，一个写、两个都收到；**没人在看时是 no-op**
      （不构造载荷），与 `task-send!` 同一条。`clear` 也推，`goal` 是 `null`。
- [ ] **模型那一半也推**：一条带 `update_goal` 工具调用的 run 收尾时，看客收到 `goal` 帧；
      这由边在 `:run/end` 处调 `goal-send!` 实现（cap 不动）。
- [ ] `ui/src/lib/mux.ts`：`familyOf("goal") === "goal"`，`deliver` 只交给该 threadId 的 `goal` 订阅者，
      **不靠 `default` 兜底**；由 `ui/test/suites/frames.ts` 或同族 mux 用例钉住。
- [ ] 测试：`test/harness/edge/http_test.clj`（或同族）覆盖六个动作、两种拒绝、「没人看时不炸」、
      以及「run 里的写也在收尾推帧」。

**本票的界线**：不做注入、不做工具、不做界面（前端只到 `mux.ts` 能收帧）。
