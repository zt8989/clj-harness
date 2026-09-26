# 03 — 侧栏每行的状态由推的（parked 进 listing，重问退场）

**What to build:** 侧栏每一行说的「**运行中**」与「**等你回应**」是服务端说的，而且是**推**过来的：
一场会话在别的标签页或别的进程里跑起来、park 住、结束，这一页的侧栏那一行跟着变，**一声不问**。

今天缺的不是铃（`run-started!` / `run-finished!` 已经在敲 `events.host`），是**字段**：`session-row` 只有
`:running`，「等你回应」只在**这一页自己开着那场会话**时才被知道。所以这一条要做的是把会话的**状态**
放进 listing，并让客户端不再靠自己的注册表去补。

**Blocked by:** 无 — 可以立刻开始。

**Status:** ready-for-agent

- [ ] `GET /api/projects` 的每一行多一个**状态**字段（在跑 / 等你回应 / 都不是），对**服务端自己持有的**
      会话是真话；对不持有的会话照旧**不答**（与今天 `:running` 的能力边界同一条线——宁可不知道，
      不为一行去读日志尾巴）。
- [ ] 另一扇窗里把某场会话跑起来、再让它 park 住 ⇒ 这一页侧栏那一行跟着变（后者显示「等你回应」），
      **没有一次 `/api/projects` 读**。
- [ ] 新会话发第一句：那一行**自己出现**，带标题与发送时间，**没有重问**。`lib/sidebar-refetch.ts` 的
      「最多 5 次、400ms 间隔」（`ASK_AGAIN_LIMIT` / `ASK_AGAIN_AFTER_MS` / `nextAsk` / `countAsk`）
      与它那个 `setTimeout`、以及它那个套件一起退场。
- [ ] 页面自己那两张表（`liveTitles` 的标题注册表、`liveRunning` 对账）随重问一起退场——listing 是推的，
      标题那一次写（`remember-send!`）与行那一次写都敲铃，所以「快照落后于本页事实」这个理由不再成立。
      留下的部分要在注释里说清为什么留。
- [ ] 归档 / 移除项目那两句拒绝照旧（`blocked()` 仍按 running **或** parked 判），
      并且现在**别的标签页**跑起来的那一场也能挡住这两个动作。
- [ ] 后端全量绿；`cd ui && npm test` 与 `npm run build` 全绿。
