# spec: agentmemory 的 hook 保真度（差的那几格，以及为什么差）

2026-09-20 立。**要的是：把「clj-harness 的 hook 引擎」与「agentmemory 要求的那套 hook」之间还差
什么，摆成能一张一张做的票。** 原话是

> 呃，我这边设计的hook和agentMemory要求的hook还差多少

> 你帮我形成ticket，我明天再弄。

这一页只写**今天证到的事实**与**据此立的票**；票面在 `issues/`，三处原始测量在 `evidence/`。

## 一、事件层：12 个要求，4 个有落点

`plugin/hooks/hooks.json`（agentmemory 的 Claude Code 清单）注册 12 个事件。同一个清单里
`harness.kernel.hooks/points` **一个不缺**——27 个点里全都声明了，所以差的不是词汇，是**触发源**：

| agentmemory 事件 | clj-harness 点 | 今天 |
|---|---|---|
| SessionStart | `:session-start` | 已接（`~/.clj-harness/hooks.edn`）|
| PostToolUse | `:post-tool-use` | 已接 |
| Stop | `:stop` | 已接（与 Claude Code 同义：每轮结束关会话）|
| PreToolUse | `:pre-tool-use` | 点会触发，但脚本在 `AGENTMEMORY_INJECT_CONTEXT=false` 下是空操作；开着也没落点（见三）|
| UserPromptSubmit | `:user-prompt-submit` | **无触发源**（`prompt` 字段已就绪）→ 票 06 |
| PostToolUseFailure | `:post-tool-use-failure` | **无触发源**（`err` 就在 emit 那一行的作用域里）→ 票 04 |
| PreCompact | `:pre-compact` | 无触发源，等压缩子系统 |
| SubagentStart / SubagentStop | `:subagent-start` / `:subagent-stop` | 无触发源，等子代理 |
| Notification | `:notification` | 无触发源，等「有事要告诉人」那个缝 |
| TaskCompleted | `:task-completed` | 无触发源，等任务 |
| SessionEnd | `:session-end` | 无触发源；**不立票**（理由见四）|

九个会触发的点，每一个的 `hook/emit` 都数过一遍，九个都在；顺带查出**一处声明了没传的字段**：

| 点 | 声明 `:payload` | emit 实际传的 |
|---|---|---|
| `:post-tool-use` | `:tool_name :tool_input :result`（`hooks.clj:105`）| `:tool_name :tool_input`（`tools.clj:894`）——**`:result` 没人传** |
| 其余八个 | 与 emit 一致 | |

`result` 与 `err` 都**已经在 `tools.clj:894` 那一行的作用域里**（`[result err]` 是同一个 `let` 里
875 行绑的），所以这是「忘了传」而不是「拿不到」。

## 二、字段层：6 个映射到位，2 个在漏

桥（`scripts/hooks/agentmemory.mjs`）把 6 个键对上了（`hook_event_name` / `session_id` / `cwd` /
`tool_name` / `tool_input` / `tool_response`，外加 `CLAUDE_PROJECT_DIR`），但两个字段落地时是空的：

**A. `tool_input` 到对面是字符串，不是对象。** 契约规定命令侧每个 fact 都经 `str` 渲染
（`dispatch.clj:250` 传的就是 `str`），于是 agentmemory 的服务端 `extractFiles$1`（要求
`typeof input === "object"`）永远返回 `[]`。实测见 `evidence/measurements.md`：探针拿到
`typeof tool_input = string`，库里那条观察是 `"files": []`、`"narrative": "{:path \"src/x.clj\", …}"`。

后果要分两半说，别夸大：**数据没丢**（原文在 `narrative` 里，模型读得出来——那条会话的摘要
`filesModified: ["src/x.clj"]` 就是模型从文本里读出来的）；**丢的是结构化归属**（观察级 `files`
永远空 → 按文件召回、文件图、`/agentmemory/file-context` 这一层接不上；PreToolUse 的 enrich 直接
提前 return）。

顺带一处死代码：`dispatch.clj:51` 的 `jsonable`（"JSON 装得下就装，装不下退回打印形式"）**没有任何
调用点**——它看起来正是为这件事写的，只是没接上。

**B. `result` 从来没被送出去**（见上表）。所以 agentmemory 每条 PostToolUse 观察 = 工具名 + 参数，
**没有工具的输出**——半条观察。票 01 修这一条。

另外四个字段到不了，因为对面的事件不触发：`transcript_path`（SessionEnd 回捞提问用）、`error`、
`prompt`、`agent_id`/`agent_type`/`task_id`/`message`。

## 三、注入层：0 / 2

agentmemory 有两条「把记忆推回模型」的路，两条都**没有落点**：

1. **SessionStart 的 `additionalContext`**（要 `AGENTMEMORY_INJECT_CONTEXT=true`）——往 stdout 写
   纯文本。只有 `:system-prompt` 的 stdout 被当内容，`SessionStart` 的 stdout 只在退出 0 时被当
   JSON 答案读 → 文本被丢掉。
2. **PreToolUse 的 `/enrich`**——同样写纯文本；而 `:pre-tool-use` 是门禁，慢一点就拖住它正在丰富的
   那次调用。

代价可量化（`evidence/measurements.md` 第三节）：同一个服务器、同一个文件，`/agentmemory/enrich`
拿**非空 files** 返回 1375 字符的 `<agentmemory-file-context>`；拿空 files 直接
`sessionId (string) and files (string[]) are required`——而 clj-harness 这条路的 files 就是空的。

**所以现状是：写通了（三个点已接，观察 + 摘要都进库），读回来是 0。**

## 四、立的六张票，和故意不立的

| 票 | 一句话 | 类型 |
|---|---|---|
| 01 | `:post-tool-use` 把已经在手里的 `result` 传给 hook | 内核，1 行 + 边界 |
| 02 | 命令侧的 fact 值改成「JSON 装得下就装」（`tool_input` 变成对象，`files` 才有归属）| 内核 + 文档同步 |
| 03 | 三条声明里钉住 `AGENTMEMORY_URL`（端口变了不静默丢）| 配置，5 分钟 |
| 04 | `:post-tool-use-failure` 接线（`err` 就在手边）| 内核 ~3 行 + 配置一行 |
| 05 | 项目/会话记忆进 system 消息（第一个「读回来」）| 配置 + 桥加一个动词 |
| 06 | `UserPromptSubmit` 接上触发源，并给它一个内容落点 | 内核，设计件 |

**不立票的**：PreCompact / Subagent×2 / Notification / TaskCompleted 五个点各等一个还不存在的
子系统——票面写「等 X」没有可做的事，列在这里就是全部。**SessionEnd 单独说一句**：它存在的理由是
Claude Code 在会话真结束时去读 `transcript_path` 回捞用户提问；clj-harness 的 `:stop` 是每 run
一次（已经接了，与 Claude Code 的 Stop 同义），而它的 `projects/<项目>/*.jsonl` 与 Claude Code 的
transcript 形状不同，不能直接喂给 `session-end.mjs`。**票 06 把提问实时捕获之后，这条路的唯一价值
就只剩「会话真结束时关一次」——而会话在 clj-harness 里没有那个时刻，所以不做。**

**被否掉的两条路**（写下来，免得明天重新想一遍）：

- **在桥里写一个 Clojure 打印形式的解析器**（把 `"{:path \"a\"}"` 读回对象）。它能让文件归属回来，
  但它是一个「跟着 `pr-str` 走的第二方言」，而且只修好一个消费者。**票 02 走内核那条**：改 3 行，
  所有命令侧 hook 一起受益（Claude Code / CodeBuddy 的 `tool_input` 本来就是对象）。
- **给 PreToolUse 加一个内容落点**。它是门禁、每次工具调用都在 run 的关键路径上，为一次 enrich
  挂一个外部 daemon 在那里，代价与收益不成比例。记忆的读回走票 05（每 run 一次）与票 06
  （每条用户消息一次）两处，不碰工具路径。

## 五、证据

- `evidence/probe-tool-input.mjs` —— 探针：把这棵仓里的桥按 `AGENTMEMORY_PLUGIN_DIR` 指到一个假
  plugin 目录，看 agentmemory 的脚本**到底收到什么**。一条命令可重跑。
- `evidence/measurements.md` —— 三处原始输出与产生它们的命令：探针、库里那条观察的 JSON、
  `/agentmemory/enrich` 的空/非空对比。
- 本次调查的产物（已提交）：`scripts/hooks/agentmemory.mjs`、`~/.clj-harness/hooks.edn`、
  `~/.clj-harness/mcp.edn`、`docs/architecture/hooks.md`、`docs/architecture/mcp.md`、
  `hooks.edn.example`、`mcp.edn.example`。
