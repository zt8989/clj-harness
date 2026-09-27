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
| 01 | 摘要记住「已经产出了什么」 | 无 |
| 02 | 压缩只在 step 边界上切 | step-events 票 02–06 |
| 03 | 开场块永远不被压缩 | 无 |
