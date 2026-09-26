# 01 — 一条读口子：`GET /api/threads/<stem>/todos`

Status: ready-for-agent
Blocked by: None (can start immediately)

## 做什么

给「这个会话的任务列表」开一条**只读**的口子，让界面上任何东西都能问到它。

`GET /api/threads/<stem>/todos` 答：

```json
{ "threadId": "<stem>",
  "todos": [ { "content": "读一遍 ComposerFrame", "status": "in_progress" },
             { "content": "写横条", "status": "pending" } ] }
```

- 列表**原样**来自 `harness.cap.todos/items-for`——`content` 与 `status` 两个字段、写入顺序，
  **不在这里重新分类、不在这里排序、不在这里改名**。它是同一行的第二个读者，不是第二个实现。
- `status` 仍是 `todos.clj` 的 `statuses` 那三个字符串（`pending` / `in_progress` / `completed`）。
  界面怎么翻译它是界面的事（票 02），这条路答的是**存的东西**。
- **没写过 = `[]`**，200。`items-for` 已经把「没写过」与「写了空表」折成同一个值，这里不要把它们再拆开。
- **不 locate、不 404**：任务列表不是某条日志里的一行，它是按 thread id 的一行。
  一个这个家没听过的 stem 答 `[]`——与 `jobs-get` 同一条理由：「这个会话没有列表」是对「有什么」的正常回答。
- **只读，所以不写审计行**：问第二遍是常规用法（票 03 的每次重问都是一次），一条写一行的路由会把日志灌满
  「有人看了一眼」。

接线的地方是那个**闭合**的动词集合：`harness.edge.http` 的 `thread-verbs`（今天
`#{"rebuild" "compact" "archive" "stats" "trajectory" "sofar" "page" "delegations" "frames" "cancel" "jobs"}`）
加一个 `"todos"`，dispatch 的 `case` 加 `[:get "todos"]`。集合是闭合的这件事**不要动**：一个名字了
没人服务的动词，必须继续落到 AG-UI 那条路上，而不是被这条形状答成 405。

## 验收

- [ ] 一个会话用 `todo_write` 写过三条之后，`GET /api/threads/<stem>/todos` 答出那三条，
      字段名与顺序同库里那行；把同一份读回来与 `harness.cap.todos/items-for` 比一次，**逐字段相等**。
- [ ] 没写过的 stem、以及这个家没听过的 stem，都答 `{"threadId": "<stem>", "todos": []}`，HTTP 200。
- [ ] 同一个路由上的 `POST` / `PUT` / `DELETE` 答 **405**（走 dispatch 里既有的那一句）。
- [ ] `GET /api/threads/<stem>/todos` **不改任何一行**：调用前后那个 stem 的日志与 `todos` 行逐字节不变
      （只读的一条路由不写审计）。
- [ ] 后端测试补进 `test/harness/edge/http_test.clj` 那一套，至少四个：写过之后读到、没写过的答空、
      别的 method 405、以及一次「读两次答一样」。
- [ ] `clojure -M:test -m harness.test-runner harness.edge.http-test harness.cap.todos-test` 绿。
