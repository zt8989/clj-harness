# 0013 —— 压缩的摘要请求里只放参考实现放的东西：我们自己注入的事实一条不留

- **日期**：2026-10-01
- **状态**：**已采纳**
- **边界**：本决定**不推翻** `0008-the-log-is-the-truth-and-sqlite-projects-it.md`（记录是真相，库是投影）、
  **不推翻** `0011-a-step-is-a-request-plus-its-tools.md`（一步 = 一次请求加它要的工具）、
  **不推翻** `0012-the-record-is-a-stream.md`（记录是一条流）。
  它只改**一次压缩喂给摘要模型的那条 user 消息里放什么**：折哪些、留多少、写哪几行、锁怎么拿，一个字不动。
- **取代**：
  - `harness.edge.compaction` 的 `environment` / `environment-block`（**主人 2026-09-27 为自己那次
    thread `068fd63f` 加的**「Where this work is happening」），以及 2026-10-01 加在它上面的
    `repository` / `repository-block` / `other-trees`（把仓库里所有有未提交改动的树列出来）；
  - `product-facts`（本仓自创的 `Already produced` 清单）与把三者拼起来的 `summary-content`；
  - 「`:pre-compact` hook 打印的话随摘要请求一起发」这一路（**hook 点本身留着**：它是观察者，
    `PreCompact` 仍在摘要请求之前触发，只是它的话不再上那条请求）。

## 背景

1. **参考实现怎么做的。** 本机装着 `@deepseek-ai/dsh@0.1.5-rc.2`（上游
   `github.com/deepseek-ai/deepseek-harness`），压缩在 `packages/compaction/*`。它的摘要调用
   （`compaction-basic` 的 `summarizeWithLlm`）是：**会话自己的 system 消息 + 上一次路由请求的工具表 +
   被折区间的消息 + 最后一条 user 消息（instruction）**——`instruction` 之外**什么事实都不注入**。
   身份与「干到哪了」靠三样：**原样保留的尾巴**、**检查点的 preamble**（「折的是早先一段，从后面的消息
   接着干」）、**骨架末两节 `## Current Work` / `## Next Step`**。这三样本仓 2026-10-01 已经照抄
   （`.scratch/compaction-checkpoint/spec.md`）。
2. **我们注入的三段，各自都出过具体的事：**
   - **git 事实答错过。** thread `a0621fce-…`（2026-10-01 09:15 那次压缩）的 `compaction/start` 里写着
     `worktree: …/lisp-harness`、`branch: main`、`uncommitted: M .gitignore / ?? .claude/`——那是**绑定目录**，
     而那一票的活（两处未提交的 test 文件）在 `.worktrees/shell-03-07`。一个**说错**的答案比不说更坏：
     摘要还把它转述成「not created by me」，而模型醒来后把自己 17 分钟前提交的 `a8a7bfb` 认成了
     「另一个 agent」在同一棵 worktree 里干的。这一段读的是**绑定目录**，而 harness 并不知道会话实际在哪棵树
     （agent 的 `cd` 在命令串里）；把它改成「列出所有有未提交改动的树」只是把错的答案换成一个更长的答案。
   - **`Already produced` 挡不住它要挡的东西。** 它只覆盖**正在被折掉的那一段**，而那次被认错的活在
     **原样保留的尾巴里**——它一次也没站在路上。而且它在生产上是**空的**（读的是 provider 形状、面是
     kernel 形状，2026-10-01 才查出），即修好形状也只是把一条从不生效的防线修得更整齐。
   - **hook 的话**在参考实现的请求里根本没有。
3. **机械事实与摘要正文重复。** `Already produced` 列的路径，摘要正文里本来就要在 `## Files and Code` 下写；
   模型读的是**摘要**，不是那条 instruction。

## 决定

一次压缩喂给摘要模型的，就是参考实现喂的那些：**会话自己的 system 消息 + 工具表 + 被折区间的消息 +
`summary-instruction`**（DSH 的固定八节与规则，原文照抄）。**一个字不多。**

- `compaction/summary-instruction` 是那条消息的**全部内容**，`perform!` 直接把它交给 `summarize`。
- `:environment` / `:blocks` 不再是 `perform!` 的入参，`harness.edge.http` 也不再组装它们。
- `product-facts` / `environment*` / `repository*` / `other-trees` / `summary-content` 与其测试一并删除。

## 后果

- 一个在被折区间里写过的文件，只由**摘要正文**（`## Files and Code` / `## Current Work`）和**尾巴里的工具调用**
  来证明——这正是参考实现的信任模型。
- `.scratch/compaction-checkpoint/spec.md` 里 01/02 两节记的是**当时落地过**的东西；本轮撤销在它的
  Comments 里点明，不改旧文（`.scratch/` 是历史，见 `docs/agents/domain.md`）。
- 仍然留着的**策略旋钮**差异（阈值 0.7 vs 0.8、没有按模型的 policy 表、没有独立的 summarization provider、
  没有绝对 `retainTokens`、没有 `compactionRetries`）不在本决定内；要照抄再开一张票。
