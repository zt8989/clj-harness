# 03 — 只读存量、`goal` 命令的执行、`goal` 推送帧

**What to build:** 让人（页面）与别的客户端能动目标——**写入走 `.scratch/run-commands` 那条命令通道，
不再有 `POST /api/goal`**。

- **只读**：`GET /api/threads/<stem>/goal` → `{threadId, goal, armed?}`：
  `goal` 是记录快照（票 02 那份，或 `null`），`armed?` 是**进程事实**（这条会话现在还允许自动续轮吗，
  spec 决定 3），单独一个字段、不在 `goal` 里。**只读、不留痕、不 locate、不 404**（`todos-get` 同款）。
  它是面板的**存量**那一半，**不是命令**——读不需要一场 run。
- **写**：一条 `{:type "goal", :action …, :objective?, :max_goal_rounds?}` 命令，
  `action` ∈ `create|edit|pause|resume|clear`。**这一票实现它的执行器**：
  在**模型调用前**那个边界（`.scratch/run-commands` 票 02 的边界）把命令交给 `harness.cap.goal` 的动词（票 02 的规则与栅栏，
  一个字不改），`pause`/`resume`/`clear` 走**人的门**（`:by :human`）——这正是「paused 只有人能恢复」的落点。
  队列、优先级、`no-run :start-run` 都是 run-commands 那套的（它的票 01/02/04），这一票不重复实现。
- **每一次真写推一个 `goal` 帧**：`harness.edge.http/goal-send!` 把 `{:type "goal", threadId, goal, armed?}`
  发给所有在看这场会话的连接（`mux/channels-for`）。与右栏那条 `task-send!` 同形同理由：
  目标是库/记录里的东西，没有 `seq` 可编号、没有东西可按游标重放，所以整份载荷一个帧一次变。
  `clear` 之后 `goal` 是 `null` 也要推。
- **客户端认这一族帧**：`ui/src/lib/mux.ts` 的 `familyOf` 多一个 `"goal"`、多一张订阅表、多一个
  `subscribeGoals`，并导出一个 `GOAL_FRAME_TYPE`（照 `TASK_FRAME_TYPE`）。**漏这一步不是显示问题**：
  帧会掉进 run 家族、被 `@ag-ui/client` 的 schema 拒掉、把整个 run 弄挂（`task` 那条注释写过这个坑）。
- **一次命令的差错怎么回给人**：命令的执行在 run 里，被 `harness.cap.goal` 拒绝时
  （`:goal-exists`、`:goal-moved`、`:paused-by-human` …）错误**进这一轮**（一条注入/一条 run 错误），
  由前端画出来——不是一条 HTTP 应答（那条路由没有了）。实现照 run 里既有的「拒绝怎么到客户端」那条。

**Blocked by:** 02、`.scratch/run-commands` 的 01/02

**Status:** ready-for-agent

- [ ] `GET` 一个从没有过目标的会话答 `200 {threadId, goal: null, armed?: false}`，不是 404。
- [ ] `GET` 只读：再问一次不写审计、不改任何一行、不改 `armed?`；**它不要求 run**。
- [ ] `POST /api/goal` **不在路由表里**（这一票把它拿掉，spec 决定 4 已改）。
- [ ] 一条 `goal` 命令（`create`）在**没有 run** 时：起一场只有命令的 run，执行后 jsonl 多一条
      `goal/change`、投影行在、`GET` 与帧都报同一份。
- [ ] 一条 `goal` 命令在**有 run 跑着**时：入那场 run 的队列，在下一个模型调用前执行（不另起 run）。
- [ ] `pause`/`resume`/`clear` 走 `:by :human`：模型的 `resume` 拒一个 `paused` 的目标，
      人的命令恢复得了（一条用例对标票 02 的同款断言，从命令这头走一遍）。
- [ ] 被拒绝时（如 `create` 时已有未完成目标）**错误到得了客户端**（这一轮里看得见），
      且用的是 `harness.cap.goal` 那句原文。
- [ ] **一次真写推一帧**：两个连接看着同一会话，一个下命令、两个都收到；**没人在看时是 no-op**；
      `clear` 也推，`goal` 是 `null`。
- [ ] **模型那一半也推**：一条带 `update_goal` 工具调用的 run 收尾时，看客收到 `goal` 帧
      （边在 `:run/end` 处调 `goal-send!`；cap 不动）。
- [ ] `ui/src/lib/mux.ts`：`familyOf("goal") === "goal"`，`deliver` 只交给该 threadId 的 `goal` 订阅者，
      **不靠 `default` 兜底**；由 `ui/test/suites/frames.ts` 或同族 mux 用例钉住。
- [ ] 测试：`test/harness/edge/http_test.clj`（或同族）覆盖 `GET`、命令执行的四种动作、两种拒绝、
      「没人看时不炸」、以及「run 里的写也在收尾推帧」。

**本票的界线**：不做注入（票 05）、不做工具（票 04）、不做界面。
