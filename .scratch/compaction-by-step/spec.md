# spec: 压缩不能抹掉「我刚做过的事」

**症状**：会话 `068fd63f-5f44-44c0-81f8-ba2983c54e9a`（2026-09-27）。run `f0872d84` 在 19:23
`git worktree add .worktrees/task-pane-push`（**exit 0**），写下票，并用脚本改了 16 个文件
（`cap/jobs`、`cap/subagents`、`edge/http`、`mux.ts`、`use-task-pane.ts`、i18n、测试…）。20:04
触发压缩，`context/compacted` 的范围是 seq 7–8926。压缩后模型**重新开始**同一份实现，`git status`
看到 worktree 里本来就存在的改动，把它判成**另一个会话的在制品**，停手报告；它甚至把自己那条
成功的 `git worktree add` 记成「失败被重定向掉了」。

记录本身是干净的：那些行都在，keep window 从 seq 8960 起，覆盖了 11898–12289 的全部工作。
出问题的是**压缩写进模型记忆的那句总结**。

## 三条根因

1. **摘要成了过期的进度快照。** 压缩只覆盖 seq 7–8926，摘要末尾写的是那一刻的实话
   「Next step … **Nothing has been created or committed for this ticket yet**」；而真正的产出
   （建 worktree、写票、改文件）发生在 seq 11898+，留在 keep window 里。模型以摘要为「当前进度」，
   于是把自己 81 分钟前的产物判给了别人。
2. **`summary-instruction` 不要求保留产出。** 它今天要求保留「路径 / 命令 / 错误串 / 标识符 /
   数字 / 函数签名 / 已做的决定，包括试过并失败的」，**没有**「已经产出了什么」这一栏。
3. **边界切在任意消息上。** `compaction/plan` 按 token 预算切 head，会切进
   `assistant(tool_calls)` 与它的工具响应之间；摘要请求又不走 `llm/adjacent-answers`，于是厂商以
   400 拒收（同一会话三次压缩里两次如此）。正确的边界是 **step 边界**——一次模型调用加上它调的
   那些工具，天然不会切坏配对。

## 目标

- 摘要与摘要请求都带上「已产出物」：分支、worktree、改过/新建的文件。
- head / tail 只落在 step 边界上（step-events 落地之后）。
- 开场块（指令文件 / 技能清单 / 出生上下文）在任何 plan 下都不被压进摘要。

## 不做

- 不碰界面；不为压缩加新界面。
- 不做 fork（那是 `session-fork` 这个 feature）。
- 不改 `model/*`，也不改 step-events 自己的语义。

## 票

| # | 票 | 依赖 |
|---|---|---|

## 落地（票 01，2026-09-27）

**摘要与摘要请求都带上「已产出物」。**

- `harness.edge.compaction/summary-instruction` 明确要求摘要写出 `Already produced` 一栏：建/切过的
  分支与 worktree、写/改过的文件、未提交的改动。
- `harness.edge.compaction/product-facts`：从被折范围的**工具调用**里投影出产物——`write`/`replace`/
  `insert` 的 `:path`，`bash` 里 `git worktree add` / `git switch -c` / `git checkout -b` 的目标，
  排序去重。`harness.edge.http/run-compaction!` 把这份清单附在摘要请求上，和 prompt 那一栏互为兜底
  （清单是读出来的，不靠 summarizer 的措辞）。
- 判据：`harness.edge.compaction-test` 的 `the-produced-artifacts-are-read-off-the-tool-calls`。

## 落地（票 02，2026-09-27）

**压缩的 head 只在 step 边界上收尾。**

- `harness.edge.compaction/step-start-indexes` 与 `tail-anchor`：tail 从「它落在的那一步的第一个节点」开始（旧记录没有 `step/*` 时退回最近一条 user 消息），head 就收到那里。一步是一次模型调用加上它调的那些工具，所以更早的步整步留在 head 里——`assistant(tool_calls)` 不会被与回答它的工具消息切开（那个 400）。
- `plan` 走这一个锚点；`overflow-plan` 本来就从最新 user 消息切（更窄的一刀），不变。
- 判据：`harness.edge.compaction-test` 的 `the-head-only-ends-where-a-step-ends`。

## 落地（票 03，2026-09-27）

**开场块不再被压进摘要。**

- `harness.edge.compaction/protected-boundary`：head 的最低起点改成「最后一个受保护节点之后」——
  原来的 `(take-while protected-node? nodes)` 假设开场块是对话的**连续前缀**，而边写的顺序是
  **system → 提问 → 开场块**，所以前缀扫描在第一个节点就归零（`k = 0`），每次压缩都把开场块折进
  摘要。受保护的节点无论坐在哪都受保护。
- `plan` 与 `overflow-plan` 都走这一个边界函数。
- 判据：`harness.edge.compaction-test` 的 `the-opening-is-never-in-a-compaction-head`（含开场块的
  记录，压缩的 `:shadowed` 不含开场块那一节点，且 head 从它之后开始）；`compaction-test` 与
  `compaction-run-test` 全绿。

## 落地（追加：压缩前交代「工作在哪」，2026-09-27）

**摘要请求现在自带一段「工作在哪」，以及一个可注入的钩子。**（主人：`pre-compact` / `post-compact` 两个
钩子待用；「你想办法在压缩前增加提示词，留下当前正在操作的 worktree、git 分支等信息，防止前后不认识，
或者在 hook 里注入」。两条都做。）

- `harness.edge.compaction/environment`：**当场读 git** —— `rev-parse --show-toplevel` 的 worktree、
  `rev-parse --abbrev-ref HEAD` 的分支、`status --porcelain` 的未提交（最多 20 行，多了报数）。
  在会话的**项目绑定目录**上跑，10s 超时，**任何失败都是 nil**（没 git、不是仓库、超时）——
  环境块是摘要的顺带，绝不是压缩失败的理由。`environment-block` 把它渲染成几行文本（纯函数，可测）。
- `harness.edge.compaction/summary-content`：摘要请求那一条 user 消息的**唯一**拼装处 —— 指令 +
  `Already produced`（工具调用投影）+ `Where this work is happening`（上面那段）+ `:pre-compact` 钩子
  打印的字，顺序固定，没有内容的那段连标题都不出现。
- `PreCompact` 这个钩子点加了 `:stdout :content`：**声明打印的 stdout 会原文附到摘要请求上**
  （和 `SystemPrompt` 之于 system 消息同一套机制），所以项目可以自己说「worktree 是这一个，先读这里的
  AGENTS.md」。它照旧在压缩**之前**发；`PostCompact` 保持观察者（摘要已经写完，字无处可附）。
- 文档跟上：`docs/architecture/hooks.md` 的表格把这两个点的「有触发源」标上（9 → 11），并写明
  两个 stdout 是内容的点。
- 判据：`compaction-test` 的 `the-environment-block-reads-like-a-sentence`、
  `the-environment-is-read-off-git`（建一个真仓库、改一个文件再看）、
  `a-directory-that-is-not-a-repository-answers-nothing`、
  `the-summary-request-carries-every-part-and-only-the-ones-that-exist`；`http-test` 整轮
  （115 用例 / 1240 断言）全绿 —— 压缩这条路上多了三个 git 子进程，它不能把压缩弄坏。
