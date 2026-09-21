# 01 — 库那一列，与只读库的列表（后端）

Status: done

## 做什么

- `infra/home.clj`：stem → 日志文件的查找（`projects-dir` 下任意 workspace 里的 `<stem>.jsonl`），
  只给迁移回填用；**infra 不 require edge**。
- `infra/db.clj`：迁移步 `sessions-remember-their-last-send`——`ALTER TABLE sessions ADD COLUMN
  last_sent_at INTEGER`，并**用日志 mtime 回填**（没有日志的行留 NULL）。
- `cap/project.clj`：`session-columns` 加 `last_sent_at`；`remember-title!` 改名/扩成
  `remember-send!`（一句 `UPDATE`：`last_sent_at = ?`、`title = COALESCE(title, ?)`）。
- `edge/http.clj`：`run-agent!` 调 `remember-send!`；`session-row`/`task-row` 去掉 `:bytes` 与
  `:lastActivity`，改带 `:lastSentAt`（库里那列）；`projects-get` 不再走树、`task-row` 不再要
  `by-stem`；`newest-first` 换成按 `:lastSentAt` 降序、NULL 排最后。

## 验收

- 老 store 迁移后每行都有 `last_sent_at`（有日志的）或 NULL（没日志的），且是按日志 mtime 填的。
- 一次 run 之后 `last_sent_at` 更新；`title` 仍然只写一次。
- `GET /api/projects` 不再读任何日志文件：改一个日志的大小/mtime，列表字段不变。
- 排序：按 `last_sent_at` 降序，NULL 最后；同一个项目内与任务块内都如此。
- 后端套件全绿（含改了字段名的既有用例）。

## 落地

- `infra/home.clj`：`log-file-for-stem`——`projects/` 下任意 workspace 里找 `<sanitize id>.jsonl`，
  同名多份时取最后改动的那个；**只给迁移回填用**（infra 不 require edge，所以不是 `replay/locate`）。
- `infra/db.clj`：迁移步 `sessions-remember-their-last-send`（`ALTER TABLE sessions ADD COLUMN
  last_sent_at INTEGER`），同一步里用日志 mtime 回填 `last_sent_at IS NULL` 的行；没有日志的留 NULL。
- `cap/project.clj`：`session-columns` 加 `last_sent_at`；`remember-title!` → `remember-send!`
  （一句 `UPDATE sessions SET last_sent_at = ?, title = COALESCE(title, ?) WHERE id = ?`，
  在 `db/with-transaction` 里，名字仍然只写一次）。
- `edge/http.clj`：`run-agent!` 在写 `input` 帧的同一处调 `remember-send!`；`session-row` 只剩
  `{:threadId :archived :running :lastSentAt :firstUserText}`（**`:bytes` / `:lastActivity` 没了**）；
  `task-row` 整个删掉（它存在的理由就是按 stem 问树的体积）；`newest-first` 改成按 `:lastSentAt`
  降序、**NULL 排最后**；`projects-get` 不再 walk 那棵树。`GET /api/threads` 一个字没动
  （它仍是诊断用的原始树视图）。
- 用例：`infra/db_test` 加 `chain-up-to` helper 与回填那一例；`cap/project_test` 的
  `a-send-names-the-session-once-and-restamps-the-clock-every-time`；`edge/http_test` 的字段与排序用例。
  整轮：**991 tests / 12175 assertions / 0 failures**（`harness.edge.http-test` 77 tests）。
- 落地时的出入写在 spec 的「落地时与计划的出入」一节（`session-row` 里 `running` 的位置、
  `task-row` 是删而不是改、回填取的是 mtime 而不是别的）。
