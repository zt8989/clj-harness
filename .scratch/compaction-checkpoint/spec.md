# spec: 压缩的检查点要说得清「这是早先的一段、你是谁、干到哪了」

**症状**：会话 `a0621fce-9bf4-4f33-8671-026e783196f6`（2026-10-01 09:15）被压了一次（折掉
记录行 17..4944，797 个节点、约 272k 估计 token，摘要 13,064 字），醒来后它把**自己 17 分钟前
干的活认成了别人的**：

- 它醒来第一句是「先看 07 剩下的 checkbox 与 03 要动的几处代码」——那两张票它自己已经做完并提交了；
- 随后它说「**那是另一个 agent 在同一个 worktree 里接着干完了 03+07 并提交**」「提交里有我未提交的
  07 改动，加上我没做过的 03」；
- 它对用户报了一次假警：「**同一棵 worktree 上有两个 agent**……建议以后按票分树」「为了不和还在
  干活的那位起冲突，我没删」。

**现场**（记录本身就是证据，`projects/C__Users_zhouteng_…_lisp-harness/a0621fce-….jsonl`）：

| 行 | 是什么 |
|---|---|
| 9350 | 它自己敲的 `git add -A && git commit -q -m "票 07 + 票 03：…"` |
| 9355 | 回显 `a8a7bfb 票 07 + 票 03：runner 能逐条报耗时；shell-test 38.5s -> 26.8s`（author `zhouteng`，08:57:58） |
| 9456 / 9499 | 它自己刚改的两处 pid 轮询（未提交，shell_test / tools_test） |
| 9504 | `compaction/start`，折掉 797 个节点 |
| 9585-9635 | 「另一个 agent」那套说法 |

**它手里其实什么都有**：用仓库自己的 `replay/model-nodes` 把记录折到那一刻，压缩后的模型面是
**753 条消息**——系统提示 + 4 条开场 + 摘要 + **747 条原样保留的尾巴**（id 4954..9499），
`a8a7bfb` 的提交命令与回显、那两处未提交的改动，全在尾巴里（`dev/scratch_self_after_compaction.clj`
可重跑；上一轮真机 A/B `dev/scratch_fold_prompt_ab.clj` 里，同一个面 + 原样的摘要，四个变体
**全部答对**「a8a7bfb 是这个会话做的」）。所以丢的不是数据，是**读法**：摘要被当成了「现在」。

## 根因（两条，都在「防线」上，不是模型笨）

**一、「Where this work is happening」读的是绑定目录，不是活在哪棵树。**
`compaction/start` 的 instruction 原文（记录第 9504 行）写着：

```
Where this work is happening (read from git just now):
worktree: C:/Users/zhouteng/Documents/workspace/lisp-harness
branch:   main
uncommitted (2 path(s)):
  M .gitignore
  ?? .claude/
```

而那一票的活（两处未提交的 test 文件）全在 `.worktrees/shell-03-07`、分支 `shell-03-07`。
`compaction/environment` 是主人 2026-09-27 为 thread `068fd63f` 那次「看不见自己在哪棵树、
把自己未提交的活当成别人的」加的——**它这次答错了，而且答的是「你的未提交改动是别人的那两个
文件」**。一个说错的答案比不说更坏。

**二、`Already produced` 那半段在生产路径上是空的。**
`compaction/summary-content` 会把 `product-facts`（从工具调用里读出来的产出：写过的文件、
开过的 worktree/分支）拼进 instruction，它的 docstring 说这就是那道防「认错自家活」的墙。
而 `product-facts` 读的是 provider 形状（`[:function :arguments]`），`plan` 手里的 surface 是
`sessions/model-nodes` = **kernel 形状**（tool call 在 AG-UI 那一套键下）。实测：同一条记录，
kernel 形状折出来 **0 条**，过一遍 `ag/provider-messages` 是 **31 条**。所以真实的 instruction
里从来没有那一段（a0621 的 `compaction/start` 里就没有），而 `compaction_test.clj` 的
`the-summary-request-carries-every-part-and-only-the-ones-that-exist` 是**手写 `:facts […]`**
喂进去的，于是绿的。

## 照抄的话，抄谁：DSH

本机装着参考实现（全局 npm，`@deepseek-ai/dsh@0.1.5-rc.2`，上游
`github.com/deepseek-ai/deepseek-harness`）。压缩那几个包的源码与 README 已拷到
`%TEMP%\dsh-ref`（`dsh-compaction` / `dsh-compaction-basic` / `-tool-result-pruner` /
`-command-compact` / `dsh-token-meter`）。我们要抄的四处：

1. **检查点的壳**（`dsh-compaction-basic/lib/index.js` 的 `CHECKPOINT_PREAMBLE`）——
   现在只发 `<compacted-summary>…</compacted-summary>`，DSH 在它前面还有一段：

   > This is an automatically generated checkpoint condensing an earlier span of the conversation
   > to free up context. Treat the captured context as established background and build on it
   > without restating it. **Continue the task directly from the messages that follow**, without
   > acknowledging this checkpoint.

   这句「从**后面**的消息接着干」就是我们缺的那根指针。

2. **摘要的骨架**（同一个文件的 `COMPACTION_INSTRUCTION`）——固定章节、空节写 `(none)`、
   末尾两节是 `## Current Work` 与 `## Next Step`；规则里有「不许提这次压缩/不许提压缩这件事」
   与「已经有一个 `<compacted-summary>` 就当它是上一次的检查点，不要照抄、合并仍为真的事实」。
   我们的 instruction 是自由格式 + `Already produced`，没有任何一节回答「此刻干到哪、下一步是什么」。

3. **摘要必须真的更小**（`summarizeCompaction`）：`framedSummaryTokenCount >= shadowedRouteTokenCount`
   就抛；外加摘要调用的 `maxTokens`（DSH 8192）。我们只有 `min-head-tokens`（头部至少要有多少），
   没有「出来的摘要必须比折掉的便宜」。

4. **摘要调用吃缓存前缀**：DSH 回放会话自己的 system + 上一次路由请求的 tools + 被折区间的消息，
   把指令作为最后一条 user 消息。我们的摘要调用 `:tools []`、不带 system，前缀和会话对不上。

**不抄的两条**（也写下来，免得下次又被抄回去）：DSH 不告诉模型「压了多少历史」（反过来，
它要求摘要**别提**压缩这回事），而上一轮的真机 A/B 也证明说与不说没有可测差别；
DSH 也没有 `Where this work is happening` 这一段——环境事实要么从会话自己读，要么不说。

## 票

| # | 是什么 | 类型 |
|---|---|---|
| 01 | 「工作在哪棵树」由 git 现读（worktree 列表 + 各自的脏文件），绑定目录如实叫绑定目录 | 修 bug |
| 02 | `Already produced` 在生产路径上要真的有东西（surface 折叠与投影用同一个形状） | 修 bug |
| 03 | 抄壳：preamble + 标签 | 照抄 |
| 04 | 抄骨架：固定章节、Current Work / Next Step、不许写 provenance | 照抄 |
| 05 | 抄校验：摘要必须更小 + 摘要调用的输出上限 | 照抄 |
| 06 | 查清楚：live 面比记录折叠大出来的那 1/3 是什么 | 调查 |
| 07 | 抄缓存前缀：摘要调用带上会话自己的 system + tools | 照抄（省钱/省时） |

06 的证据：同一条记录同一段折叠，我重建的那一发 provider 报 `prompt_tokens` **171,798**；
真事那一发（`model/end`，记录第 9520 行）是 **260,165**。差出来的约 1/3 我判不了是
live 的推理文本、per-run 注入，还是别的——这一条不查清楚，任何「压缩后大小」的账都算不准。
## 已落地：01、02（坏的那半堵墙）

两条都是「防线本来就该挡住、实际是空的」：

- **01** —— `compaction/environment` 从绑定目录读一棵树，现在读的是**这个仓库的树们**：绑定目录
  如实叫绑定目录，另外把**有未提交改动的 worktree** 连着它们各自的分支与脏文件列出来
  （`other-trees` / `repository` / `repository-block`，每棵一句 `git status`，上限 `trees-limit`）。
  instruction 那句小标题也从「Where this work is happening」改成「The working trees of this
  repository」——它说的是它真读到的那些树。
- **02** —— `product-facts` 自己先过一遍 `ag/provider-messages`（运行路径那一处折法，幂等），
  于是 kernel 形状的面也能读出来；这条在修之前是**红**的：新回归
  `the-already-produced-facts-read-the-surface-the-plan-has` 断言真调用路径落下来的
  instruction 里带着 `Already produced` 与它写过的文件名，修前它拿到的只是裸 instruction
  （`Ran 17 tests / 1 failures`，已复现）。

在这台机器的主检出上跑一次 `repository-block`，形状是（节选）：

```
this session's bound directory: C:/Users/zhouteng/Documents/workspace/lisp-harness
branch:   main
uncommitted (5 path(s)):
  M .gitignore
  ?? .claude/
  ...

other working trees of this repository with uncommitted changes:
C:/Users/zhouteng/Documents/workspace/lisp-harness/.worktrees/global-stats-panel
branch:   global-stats-panel
uncommitted (23 path(s)):
  M src/harness/edge/http.clj
  ...and 3 more
```

换成 a0621 那一发，第二段就会出现 `.worktrees/shell-03-07` 与它那两处未提交的 test 文件。

## 已落地：03、04、05、07（照抄参考实现的那四件）

参考实现指 `@deepseek-ai/dsh@0.1.5-rc.2`（上游 `github.com/deepseek-ai/deepseek-harness`）；
本机拷贝在 `%TEMP%\dsh-ref`。照抄的都是**原句**，不是转述——它们要能被认出来。

- **03 壳**（`replay/checkpoint-preamble`，`replay/compaction-summary`）：检查点前面多了
  DSH 的 `CHECKPOINT_PREAMBLE` 原文，其中「Continue the task directly from the messages that
  follow」正是这次缺的那一句。模型面 = 一段 preamble + 空行 + `<compacted-summary>` + 摘要 + 标签收尾。
- **04 骨架**（`compaction/summary-instruction`）：换成 DSH 的固定八节（末两节 `## Current Work` /
  `## Next Step`）、空节写 `(none)`、不许提这次压缩、上一次的 `<compacted-summary>` 要合并不要照抄。
  我们自己的 `Already produced` 清单留着——DSH 没有它，而它挡的是同一个失效。
- **05 出口守卫**：`perform!` 现在把拼好的检查点消息过估价器，**不小于被折区间就抛**、
  一个字都不落（`context/compacted` 不写）；摘要调用带 `:max_tokens`（新 `compaction/max-tokens`，
  默认 8192，坏值按名拒绝），端点回 `finish_reason: length`（被截断）时按失败处理。
- **07 缓存前缀**：摘要调用现在带**会话自己的 system 消息 + 工具表**（`http/summary-prefix` +
  `tools/specs`），于是它是上一发请求的真前缀；调用方手里没有这两样（人工 `/compact`、测试）
  就交空，形状与从前一致。

回归（都走真调用路径，不是手搓数组）：`the-summary-skeleton-ends-at-the-seam`、
`a-summary-that-is-not-smaller-is-refused`、`the-output-cap-defaults-and-refuses-a-number-that-is-not-one`、
`a-summary-call-rides-the-conversations-own-prefix`（读的是 `harness.fake` 新开的 `:calls`：
它把「交给 provider 的那个数组」记下来，因为请求的形状否则只能在线上看）。

## Comments

2026-10-01 — 01/02 与 03/04/05/07 都已落地（分支 `compaction-checkpoint`），票按仓库规矩从
`issues/` 删掉：造过什么记在这里与 git 里。**只剩 06**（live 面比记录折叠大出来的那 1/3）——它是
调查，不是照抄；在那之前别再拿估价器给压缩记账。

2026-10-01（同日晚些） — **主人拍定：完全参照 DSH 的策略，注入的 git 事实不要了。** 于是 01 与 02
整体撤销（`environment` 一族、`repository`/`other-trees`、`product-facts`、`summary-content`、
`:environment`/`:blocks` 两个入参、以及「hook 的话随摘要请求走」这一路；hook 点本身留着）。
决策与理由写进 **[ADR 0013](../../docs/adr/0013-the-compaction-prompt-carries-nothing-of-ours.md)**：
git 事实在不该答的树上**答错过**（thread `a0621fce-…`），`Already produced` 只覆盖被折掉的那段、
**从不覆盖尾巴**（a0621 被认错的那笔活就在尾巴里），而且它当时在生产上还是空的；hook 的话参考实现
根本不带。上面 01/02 两节记的是**当时**落地过的东西，按 `docs/agents/domain.md` 的规矩不改旧文。

2026-10-01（夜） — **06 查清了，并落成一处修复。** 差额不是「live 面多带了一类东西」，是
**密度**：527 发实测/估价逐发对照（`dev/scratch_live_vs_fold.clj`），比值 1.02 → 1.44，
压缩后那一发 1.29；两参数拟合残差 p50 0.6%。同一段数组连发两次 prompt 完全一致
（147,357 / 147,357），工具表实测 ~7 字节/token（45 张 schema、45,846 字节 → +6,396）。
动作：`pressure/tools-bytes-per-token = 7` 按实测计价、docstring 记下实测乐观度与三份可重跑脚本、
`pressure_test/a-tool-table-is-priced-at-the-density-it-was-measured-at` 钉住。
证据脚本：`dev/scratch_live_vs_fold.clj`、`dev/scratch_measure_twice.clj`、`dev/scratch_tool_probe.clj`、
`dev/scratch_cjk_share.clj`、`dev/scratch_two_folds.clj`（后两条分别否掉「中文税」与「两个折叠不一致」）。

2026-10-01（夜，续） — **对参考实现的两个欠账补上**（都是会真咬人的那两条）：

1. **过期锁**（参考实现 `dsh-compaction` 有、我们没有）：一个未配对的 `compaction/start` 如果
   比记录里最新的 `session/closed-off` 边界**更早**，那个进程已经死了、它的 `end` 永远不会来，
   锁不该被继承——否则那条会话的每一次压缩都被一个死进程的 start 挡住，且**没有**任何机制能清
   （只能手改记录）。`lock-active?` 现在按边界清账；回归
   `compaction_test/a-start-whose-process-is-gone-is-not-a-lock`。
2. **配置键按名拒绝**（参考实现在装载期拒绝未知键）：`:session :compaction` 里出现一个谁都不读的
   键（`:retain-ration`、`:maxTokens`……）是**静默的 no-op**，而现在 `block` 在读的时候按名拒掉，
   并把已知键一并列出来；回归 `compaction_test/a-compaction-key-nobody-reads-is-refused-by-name`。

仍然没做的（留给下一轮，按「会不会咬人」排）：策略旋钮（`retainTokens` 绝对值、`compactionRetries`、
`auto` 开关、`modelPolicies`、独立的 `summarizationProvider/Model`、默认阈值 0.8）；**取消**
（`AbortSignal` 一路到摘要调用，我们现在一个 abort 都没有）；lifecycle 行的 `:turn` 归属；
不变式伴随插件；`/compact` 报告省下多少 token 与「跑的时候发来的话排队」；pruner 三个数可配。

2026-10-01（夜，再续） — **轮中触发点换了判据：只在「这次请求塞不下」时折**（主人指出：Turn End 不该
触发压缩，该等下一个用户轮）。记录里的证据是主人自己的会话 `3c85b20e-…`：`compaction/start` 夹在
`step/end` 与**最后一**步的 `step/start` 之间，压力只有窗口的 ~75%，而且那一轮随即结束——等于花了
一次摘要调用去做「下一个用户轮本来也会做、而且做得更温和」的事。run 开头的触发点（阈值，默认 0.7）
一个字不动；轮中那个改成 `压力 ≥ 窗口`，被拒的那条路（`recover-overflow!`）照旧兜底。
决策与理由：[ADR 0014](../../docs/adr/0014-the-mid-run-trigger-folds-only-when-the-request-would-not-fit.md)；
回归：`relieve-pressure-test/a-request-that-would-not-fit-is-relieved-before-the-call`（抬到窗口之上）、
`a-crossing-of-the-threshold-alone-is-not-relieved`（新）、`the-fold-takes-the-array-the-trigger-measured`（夹具抬到窗口之上）。
