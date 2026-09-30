# 02 — 管理边：一个读、一个写、一族推送帧

**What to build:** 让**人**（页面）与**别的客户端**能动这个目标。

- `GET /api/threads/<stem>/goal` → `{threadId, goal}`，`goal` 是 spec 决定 2 那份记录或 `null`。
  **只读、不留痕、不 locate、不 404**——照 `todos-get`：目标是一行的键，不是一个家目录下的日志文件，
  这个家没听说过的 stem 与「从没立过」答同一个 `null`。
- `POST /api/goal`，体是 `{threadId, action, text?}`，`action` ∈ `set|edit|pause|resume|clear`；
  答 `{threadId, goal}`（写后的那份）。规则与拒绝全部来自 `harness.cap.goal`（票 01），
  **这一层不判一条规则**；被拒绝答 `400` 加 `{error}`（管理边既有的形状）。
- **每一次真写都推一个 `goal` 帧**：`harness.edge.http/goal-send!` 把整份载荷
  `{:type "goal", threadId, goal}` 发给**所有在看这场会话**的连接（`mux/channels-for`）。
  与右栏那条 `task-send!` 同形同理由：目标住在库里、没有 `seq` 可编号、没有东西可按游标重放，
  所以整份载荷一个帧一次变。`clear` 之后那份是 `null` 也要推（「它没了」是一次变化）。
- **客户端认这一族帧**：`ui/src/lib/mux.ts` 的 `familyOf` 多一个 `"goal"`、多一张订阅表、
  多一个 `subscribeGoals`。**漏了这一步不是显示问题**：帧会掉进 run 家族、被 `@ag-ui/client`
  的 schema 拒掉、把整个 run 弄挂——`task` 那条注释早写过这个坑，`ui/test/suites/frames.ts`
  的 `the-wire-says-which-names-are-facts` 是钉住它的那条测试（它读 wire，不是读编译器）。
- **模型那一半的写不经这里**（票 04 的工具直接调 `harness.cap.goal`）：cap 不反向 require 边。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `GET` 一个从没有过目标的会话答 `200 {threadId, goal: null}`，不是 404——「没得选」是日常状态
      （`GET /api/git` 答 `{:dir nil}` 同一条理由）。
- [ ] `GET` 只读：再问一次不写审计、不改任何一行。
- [ ] `POST` 五个动作各一条路径，全部落到 `harness.cap.goal` 的对应动词；成功答写后的那份，
      拒绝答 `400 {error}` 且错误句是 `harness.cap.goal` 的原文（这一层不翻译、不改写）。
- [ ] `POST` 一个未知 `action` 答 `400`，点出合法动作集合（`harness.cap.goal` 没有这个动词，
      但「我认得哪些」是这一层知道的，写在它自己的拒绝里）。
- [ ] 路由按既有形状挂：`GET` 进 `stem-verb-route` 那张表（与 `todos` 同处），
      `POST /api/goal` 进管理边那一组（与 `/api/model`、`/api/project` 同处）。
      方法不对答 `405`，不是掉进 run 端点。
- [ ] **一次真写推一帧**：两个连接看着同一个会话，一个写、两个都收到 `{:type "goal", threadId, goal}`；
      **没有人在看时是一个 no-op**（不构造载荷），与 `task-send!` 同一条。
- [ ] `clear` 也推，载荷里的 `goal` 是 `null`。
- [ ] `ui/src/lib/mux.ts`：`familyOf("goal") === "goal"`，`deliver` 把帧交给该 threadId 的 `goal` 订阅者，
      别的 threadId 的帧到不了——由 `ui/test/suites/frames.ts`（或同族的 mux 用例）钉住，
      **不是靠 `default` 兜底**。
- [ ] 测试：`test/harness/edge/http_test.clj`（或同族）覆盖五个动作、两种拒绝、
      以及「没有人在看时不炸」；隔离照 `AGENTS.md` 既有纪律，不新写一套。

**本票的界线**：不做每轮提醒、不做工具、不做界面。前端只到 `mux.ts` 能收帧为止。
