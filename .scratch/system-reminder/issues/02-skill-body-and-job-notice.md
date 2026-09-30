# 票 02：技能正文与作业通知换形，分类改读标签行

Blocked by: 01。

## 目标

`skill` / `job` 两条派生注入也走 `<system-reminder>`；**读它们的两处代码**（记录的 `:source` 分类、技能幂等）
从「内容标签前缀」换成「首行标签行」，并且**旧记录仍能读**。

## 为什么这一票不是「改字符串」

`harness.edge.http/returned-source` 按内容前缀决定记录信封上的 `:source`：

```
"<skill name="  -> skill
"<job-ended "   -> job
"<instructions" -> opening
"<skills"       -> opening
:else           -> injection
```

`skill` / `job` **故意不在** entry 集合（`#{client injection opening}`）里：它们每轮重算、不算会话持有的 entry。
`harness.cap.skills/loaded-names` 也靠 `(?s)<skill name="([^"]*)">.*` 做幂等。
换掉标签而不换这两处读法，派生注入会被当成 entry 去重、再发一遍，轨迹也会画错位置。

## 改哪里

1. `harness.cap.skills/skill-message`：reminder，首行 `Skill <name>`，正文随后。
   `loaded-names` 改读首行（`Skill ` 之后到行尾就是名字，**不转义**；名字里带空格也照收）。
2. `harness.cap.jobs/notice`：reminder，行序
   `Background job <id> ended: <status>` / `by: user`（人停的才有）/ `Command: <原样命令>` / 读法句。
   `edge/http.clj` 的分类那条注释跟着改。
3. `harness.edge.http/returned-source`：
   - 用 `cap.reminder` 的 `kind-of` 读首行标签行；
   - **旧标签兜底**：首行是 `<skill name=` / `<job-ended ` / `<instructions` / `<skills` 时按今天分类
     （老记录、老会话读回来还是老字节）；
   - `:else -> injection` 不变。
4. `harness.cap.skills/derived-injections` 的 docstring（`<skill name=…>` 的锚）跟着改。

## 判据

- `skills-test`：幂等用例改写——同一条 `Skill tdd` 只加一次；一个名字在正文里带 `<` `>` 也不影响识别。
- `jobs-test`：通知的字节逐行钉住（含人停的那条多一行 `by: user`、命令原样不截断）。
- `http-test`：`returned-source` 一张表——新 reminder 的四种首行、**旧四种标签**、以及一个普通 user 消息
  落 `injection`；并且 **`skill` / `job` 仍不是 entry**（`replay` / `trajectory` 的 `entry-row?` 用例）。
- 全量后端一轮。

## 不做

- 不改通知的时机、次数、id。
- 不改 `job_output` / `job_kill` 的读法。
