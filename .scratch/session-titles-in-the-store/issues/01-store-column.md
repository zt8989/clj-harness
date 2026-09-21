# 01 — store 里那一列，与写入它的那一刻

Status: done

## 做什么

- `src/harness/infra/db.clj`：追加迁移步 `sessions-remember-their-title`
  （`:present?` = `(column? % "sessions" "title")`，`:run` = `ALTER TABLE sessions ADD COLUMN title TEXT`）。
  **不回填**，docstring 里写明这是主人的判决以及代价（老会话显示 id，直到它下一次跑）。
- `src/harness/cap/project.clj`：`session-columns` 加 `title`；`as-session` 把它带出去；
  新 `remember-title!`：`UPDATE sessions SET title = ? WHERE id = ? AND title IS NULL`（幂等、
  不改写已有标题），nil/空白不写。
- `src/harness/edge/ag_ui.clj`：从 RunAgentInput 的 messages 里取第一条 `user` 消息的文本
  （字符串 content 或 text part 拼起来），去首尾空白，**按码点**截到 200（不劈开 emoji）。
- `src/harness/edge/http.clj`：`run-agent!` 在 `log!` 那行旁边调一次 `remember-title!`；
  `session-row` / `task-row` 带 `:title`。

## 验收

- 老 store（没有这一列）打开后有了列，且旧行 `title` 是 NULL（没有被回填）。
- 一次 run 之后标题在库里；第二次 run 不改写它。
- 没有 user 消息的 input 不写；长文本在 200 码点处截断且不劈开代理对。
- `GET /api/projects` 的每一行都带 `:title`（可能是 null）。
- `clojure -M:test -m harness.test-runner` 相关命名空间绿。

## 落地

- `infra/db.clj`：`sessions-remember-their-title`（`column?` 探针，只 `ALTER TABLE`，**不回填**），
  追加在链尾；docstring 写下了「库从此存一条对话内容」这条反转、以及它仍要付的代价。
- `cap/project.clj`：`session-columns` 多一列；`remember-title!`
  （`UPDATE .. WHERE id = ? AND title IS NULL`，空白不写、没有这一行也不造一行，返回写没写）。
- `edge/ag_ui.clj`：`first-user-text`（第一条**非空**的 user 消息，trim + 200 **码点**裁切）。
  取消息的实测依据：真 home 某条日志的第一个 `input` 记录里就是一条 `role=user` 的正文，
  服务端拼的 context / 指令块走 `inbound`、不进 input。
- `edge/http.clj`：`run-agent!` 在 `log!` 那行旁边写；`session-row`/`task-row` 带 `:firstUserText`。
- 测试：`db_test/a-store-written-before-titles-has-none`（旧 store 加列且旧行为 NULL）、
  `project_test/a-name-is-given-once-and-never-taken-away`（含「不认识的行不造」与外力读库）、
  `ag_ui_test/the-first-thing-said-is-the-first-user-message-of-the-run`（含 emoji 边界）、
  `http_test` 里列表带名字 + 新的 `a-run-names-the-session-once-and-only-once`（真跑两次 run）。
- **改掉了 `db_test/sessions-hold-no-conversation-content`**：列名单加 `title`、禁名单去掉 `titles?`、
  注释改写成「为什么这一列可以例外」。主人拍板（选项：存 sqlite、改掉那条测试）。
- 绿：`clojure -M:test -m harness.test-runner harness.infra.db-test harness.cap.project-test
  harness.edge.ag-ui-test` 76 tests / 466 assertions；`harness.edge.http-test` 76 / 862。
