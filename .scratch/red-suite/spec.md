# spec: main 上的单测红 —— 逐条定性后修掉（2026-09-30）

**来源**：主人「修复 main 中的单元测试失败问题」。两张票（后端 `01`、UI `02`）就是那件事的记录，现在都
做完删掉；这份 spec 留下**每一条为什么红、怎么修的、怎么验的**，以及这一轮里没能立住的那条票面说法。

## 后端（票 01）

`clojure -M:test -m harness.test-runner` 在 pull 之后的 main 上跑，红的是下面四组。

### 一、家级 `:editing` 漏给了同命名空间的后续用例（8 条断言）

`specs-expose-every-base-tool`、`a-huge-answer-comes-back-as-a-tail-and-a-way-to-read-the-rest`、
`a-background-command-runs-where-a-foreground-one-would`、`edit-requires-an-exact-unique-match`（四处）、
以及 `a-bound-session-roots-relative-paths-at-its-project` 的 NPE（`(subs row 0 (str/index-of row "│"))`
拿到 nil 的下标）。

**根因在测试侧**：`.scratch/config-merge` 之后 `:editing` 是**家级**的——一个 `config.edn` 的 `:session`，
全进程每个线程读同一份——而 `tools_test` 的 `:each` fixture 给**每一个**用例都写了
`{:editing {:mode :str-replace}}`。于是「默认是 anchor」的那批用例实际跑在 str-replace 上：`grep` 不被
服务、`read` 不打 `锚点│` 前缀（NPE 就是这么来的）、`specs` 列的是另一套工具。

**修**：fixture 的职责改成**两头清场**（`wipe-session!` 前后各一次）；需要另一种编辑器的用例自己写
（`bind-mode!` 一直就是这么做的；`specs-expose-every-base-tool` 的第二半现在自己写、自己收）。
`edit-requires-...` 的「默认模式」那一段先清场——**同一个用例里「没有 thread-id」不等于「默认」**，
只要还有模式写着。

### 二、hooks-test：`SystemPrompt` 不再是唯一带 `:stdout` 的点

`the-system-prompt-point-was-added-as-one-row-of-the-same-table` 的下半断言「没有别的点要 stdout」，
而 `PreCompact` 现在也有 `:stdout :content`——`hooks.clj` 自己写着理由：一个项目的字可以搭在**摘要请求**
上，正如 SystemPrompt 的搭在系统消息上。

**断言陈旧**：改成把这两个名字**点名列出**，于是第三个出现时，必须有人来这里说它什么意思。

### 三、project-test：写进 EDN 的 Windows 路径没转义

`this-home-can-write-its-own-sensitive-list` 把 `C:\Users\...` 原样拼进 config.edn ⇒
`Unsupported escape character: \U`，整个文件读不出来，用例于是红在「文件」上而不是「它写的名单」上。

**修**：那个路径用 `pr-str` 生成 EDN 字符串字面量（夹具在 EDN 里插值，就得按 EDN 的规矩来）。

### 四、mcp-wired-test：一条真竞态，不是 flake

`a-run-sees-a-servers-tools-and-calls-one` 的三条。**它单独跑也红**，所以票面写的「单独跑绿、一起跑红、
两种跑法都见过」没有立住。

**根因**：`mcp/server` 那一行是在 **`run/done`** 时才从 mcp outbox 抽出来的（`edge/http` 的注释写着
为什么——服务器是在**这一次 run 的第一次 LLM 调用**的路上才连的，所以更早的 drain 点上什么都还没有），
而用例只 `wait-for` 到「工具结果」那行就开读。

**修**：等「工具结果 + 连接行」两件都在，再断言。它于是不再看运气：真出事就是那条断言红。

### 五、claims-test：这一轮没红

票面点名的 `a-second-jvm-owns-a-conversation-until-it-goes-away` 在 pull 之后的树上单独跑绿，不动。

## UI（票 02）

### 六、subagents：断言的还是旧文件名

`body.path` 现在发 `config.edn`（`harness.edn` 自己标着 RETIRED）。一行的事，连同上面那条说
`harness.edn` 的注释一起改。

### 七、elicitation：声明写进了已经不存在的文件

`declareFakeServer` 写的是 **`mcp.edn`**——而 `.scratch/config-merge` 把 MCP 声明搬进了 `config.edn`
的 `:mcp`（`mcp.clj` 自己写着 "This used to be mcp.edn"）。那个文件没人读 ⇒ 假服务器起不来 ⇒ 这一次
run 没有问题 ⇒ 断言说「run 没有 park」。**测试脚手架陈旧，不是行为回归**——而且它正是这个文件自己的
注释描述过的那种失败（`ednPath` 那段：「从外面看，这读起来像一条关于问题的断言」）。

**修**：把 `:mcp` 一段**拼进 `config.edn`**（读进来，在最后的 `}` 前插一段），撤的时候把**原文写回**
（不是删文件——`config.edn` 现在是这个家的整份配置，删掉会把种下的 provider 一起带走）。

## 验证

- 后端点名的那几家：`harness.kernel.tools-test`、`harness.kernel.hooks-test`、
  `harness.cap.project-test`、`harness.cap.mcp-wired-test`、`harness.cap.claims-test`
  → **0 failures / 0 errors**。
- UI：`cd ui && npm test` → **202 passed / 202**；`npm run typecheck` 绿。
- 整轮后端：**1413 tests / 14481 assertions / 0 failures / 0 errors**（69 家，合计 915.1s；同一台机器上
  另有别的 agent 在跑测试，墙钟会有出入）。
