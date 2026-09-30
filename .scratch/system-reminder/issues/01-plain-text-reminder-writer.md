# 票 01：纯文本提醒写手 + 指令合并 + 目录 + 上下文条目

Blocked by: 无。

## 目标

`<system-reminder>` 的写手立起来；**同一个会话的多个 AGENTS.md 只产生一条注入**；技能目录、会话上下文条目
各包一层。此后「注入物的外形」只有一种。

## 改哪里

1. **新命名空间 `harness.cap.reminder`**（建议路径 `src/harness/cap/reminder.clj`）：
   - `(wrap lines)` → `"<system-reminder>\n" + (str/join "\n" lines) + "\n</system-reminder>"`，
     入参是**行**的序列；写手不解析、不转义、不嵌套，里面只有纯文本（决定 1、2）。
   - 首行标签表与一个 `(kind-of text)`（或同名），把这五条映射写成**一处**：`Instructions from` /
     `Available skills` / `Session context` / `Skill ` / `Background job `（决定 3）。票 02 会 require 它，
     所以这一票就要把表定下来并配单测。
   - 只 require `clojure.string`：`preamble` → `skills`，`jobs` 谁都不 require，写手不能住 `preamble`（决定 1）。
2. **`harness.cap.preamble`**：
   - `instruction-message` 退休，换 `instructions-message`：**所有** `:instructions` 折成一条 user 消息，
     一个 reminder，逐文件一段：`Instructions from <绝对路径>` + 空行 + 正文，段间空行（决定 4）。
   - `skills-message`：`<skills>` → reminder，首行 `Available skills`。
   - `messages`：`[instruction-message ×N, skills-message]` → `[instructions-message, skills-message]`。
   - `report`：合并块的字符数 + 逐文件的 `:path`（决定 4 末条）。
   - docstring 里「每个文件一条」「tag 是块自己带的」一段重写。
3. **`harness.edge.ag_ui/context-entry`**：内容包一层 reminder，首行 `Session context`，里面原来的
   `- 描述: 值` 列表不动。id 仍是 `session-context`。
4. **`harness.edge.ag_ui/opening-entries`**：条数与文本都跟着 `preamble/messages` 走，函数本身不用改。

## 判据

- `preamble-test` / `instruction-update` 无关的 `ag-ui-test` / `http-test`：
  - 两份 AGENTS.md → **一条** user 消息（不是两条）；第一行 `Instructions from <第一条路径>`；
    第二条路径的段也在同一条里；整条以 `<system-reminder>` 开头、`</system-reminder>` 结尾；
    正文里**没有** `<instructions` 子串。
  - 一份 AGENTS.md 与零份 AGENTS.md 的条数。
  - 出生那轮写进会话的开场条目：有目录时 2 条（`session-opening-0` / `-1`），无目录时 1 条。
  - 会话上下文条目：`Session context` 首行、`- 描述: 值` 正文。
- `cap.reminder` 自己的单测：一行的、多行的、空行、正文本身含 `<` `>` 之类字符（不转义）。
- 全量 `clojure -M:test -m harness.test-runner` 与 `ui npm test`（前端这时只可能被 `injections.ts`
  的首行解析影响，票 03 才改；若红了就是暴露了不该有的耦合，记下来）。

## 不做

- 技能正文、作业通知（票 02）。
- 轨迹与会话栏（票 04 / 05）。
- `<project>` / `<env>`（非目标）。
