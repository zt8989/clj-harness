# spec: 标题落进 sqlite，侧边栏照着它写

主人两句：*「侧边栏会话也显示标题而不是 thread id」*、*「这个标题在用户第一次发送的时候就存在
sqlite，老的不管」*。两条合起来是一次改动：**标题从「客户端每次算出来的东西」变成「store 里的一列」**，
侧边栏那一行照着这列写，没有就退回 thread id。

上一版（`.scratch/brand-header`）把标题定成**纯派生、不落盘**，并把「列表不显示标题、后端不动」
写成了非目标。那两条**由这一版取代**——不是它们错了，是主人要的东西变了：顶栏一个标题可以从
这一场的内存里算，一列 40 行的列表不能（那些会话在这个页面里根本没有运行时）。

## 标题是什么、什么时候写、写什么

**一列 `sessions.title`（TEXT，可空）。** 迁移加一步 `sessions-remember-their-title`
（`ALTER TABLE sessions ADD COLUMN title TEXT`），**不回填**：主人说老的不管。

**写在每一次 run 的 input 落盘的那一行旁边**（`http.clj` 的 `run-agent!`，紧挨
`(log! thread-id run-id "input" input)`）。那是「用户按了发送」在服务端唯一的事实。
写的是**这次 input 里第一条 `user` 消息的文本**，`WHERE id = ? AND title IS NULL` —— 一条幂等的
UPDATE，**已有的标题永不改写**，之后的每次 run 都是空转。

**因此老会话会在它下一次跑的时候自己补上**：run 的 input 带着整段历史，里面第一条 user 消息
仍旧是这个会话的第一句。这是不回填的自然结果，不是为老会话写的特例代码。

**存原文，不存「标题」**：只去首尾空白 + 一个**存储护栏**（200 个码点，防一条粘贴进来的长文
让列表 payload 变成兆级）。**显示规则仍旧只有客户端一处**（`lib/session-title.ts` 的 60 码点 + `…`）。
两个数各说一件事：一个是存不存得下，一个是怎么给眼睛看。服务端**不读日志**——这是这一版比
「流式读每条日志的头几行」便宜得多的地方（真实日志到 111 MB，53 条里每条的头两行是几十 KB）。

## 读：列表带 `:title`，行照它写

- `project/sessions` / `tasks` 的 SELECT 多一列（`session-columns` 一处改），`session-row` /
  `task-row` 带 `:title`。
- 行上：**第一行 = 标题**（正常字体，不是 mono——它是人话）；**没有标题就退回 thread id**（主人选的），
  那串 id 同时也是 `title` 属性（hover 能看到、能选中复制）。原来那个 `data-slot="thread-list-item-id"`
  改名 `thread-list-item-title`：它现在装的是「这一行叫什么」，id 只是它没有名字时的样子。
- **不需要新词**：回退是 id，不是词表里的句子 ✓（顶栏才用 `session.untitled`）。

## 活的覆盖：刚发第一条就有标题

列表是一个时刻的快照；**这个页面自己握着运行时的那些会话，事实比快照新**。所以：
`SessionStatusReporter` 顺手读一次第一句（`useAuiState((s) => firstUserText(s.thread.messages))`），
经由与 `running` **同一条上报路**交给页面，页面按 id 存一份，侧栏行用它覆盖 store 那份。

这不是新机制，是这个文件里已有的形态：`running` 就是客户端登记的那份压过列表快照那份
（`statuses[threadId] ?? IDLE`）。**好处在三处**：刚发第一条消息侧栏立刻变（不用等下一次列表、
也不会把列表重新排序）；老会话**被打开一次**就显示了它原本的标题（历史重建之后第一句就在内存里，
一个字的回填都没写）；服务端那句存没存进去，眼睛看到的都对。

## 非目标

- **不回填老会话的标题**（主人明说）；不按标题排序或搜索（列表顺序仍是最近活动优先）。
- **不在服务端裁标题**：存的是原文 + 护栏，怎么给眼睛看只有客户端一处规则。
- 顶栏（`SessionTitle`）不动：它读的是这一场的消息，本来就活。

## 落到哪几个文件

| 层 | 文件 | 什么 |
|---|---|---|
| store | `infra/db.clj` | 迁移一步 `sessions-remember-their-title`（`column?` 探针，不回填） |
| store | `cap/project.clj` | `session-columns` 多一列、`as-session` 带上、新 `remember-title!`（幂等） |
| 协议 | `edge/ag_ui.clj` | 从 RunAgentInput 里取第一条 user 消息的文本（+ 200 码点护栏） |
| 边 | `edge/http.clj` | `run-agent!` 写入；`session-row`/`task-row` 读出 `:title` |
| 客户端 | `lib/projects.ts`、`lib/session-title.ts` | `SessionSummary.title`；`titleOf`（清理 + 裁切）一处 |
| 客户端 | `components/assistant-ui/elements/thread-list.aui.tsx` | 第一行改标题、id 进 `title`、活的覆盖入参 |
| 客户端 | `app.tsx`（上报）、`components/sidebar.tsx`（传下去） | 与 `statuses` 同一条路 |

## 套件与走查

- 后端：`db_test`（老 store 加列、旧行是 NULL 而不是被回填）、`project_test`（写一次、改写不动、
  200 码点护栏不劈开 emoji）、`ag_ui_test`（第一条 user 消息怎么取）、`http_test`（列表带 `:title`）。
- 前端：`suites/session-title.ts` 加 `titleOf`；`suites/sidebar.tsx` 把「第一行是 id」那几条改成
  「有标题是标题、没有是 id、id 在 tooltip 里」；`EXPECTED_CASES` 跟着涨。
- 走查：新建任务（无标题 ⇒ 显示 id）→ 发第一条 ⇒ 侧栏当场变标题、hover 是 id；刷新页面
  （列表来自 store）仍是标题；打开一个老会话 ⇒ 也显示它的标题。

## 走查实测（2026-09-21，真 Chromium + 脚本家 + 隔离家）

`.scratch/session-titles-in-the-store/walkthrough.mjs`，四格全绿，截图 `evidence/01`–`04`：

1. 新建任务那一行第一行是 thread-id（`never run · no log yet`），tooltip 也是它。
2. 发第一句**不点刷新**，行上当场变成那句话（列表还是那份没名字的快照 ⇒ 这句只可能来自页面自己那份），
   tooltip 仍是完整 id。
3. 第二句不改写它。
4. **刷新页面之后还在**——这一格是库那一列在起作用（刷新会丢掉页面握着的所有运行时）。

库里再看一眼（临时家的 `harness.db`）：`sessions` 有 `title`，值是**第一句**而不是第二句。

**外加一格在主人自己那台活页面上量的**（5173 对 8080，那个 8080 还是改动前的后端，所以列表里没有
`firstUserText`）：点开一条 263 KB 的老会话 `e90e827d…`，那一行的第一行**当场变成「你是什么模型？」**
（tooltip 里仍旧是完整 id），别的没被打开的行照旧显示 id，无 page error。截图 `evidence/05`。
这一格同时说明两件事：覆盖那份**不依赖后端改动**（页面自己握着运行时就够了），以及
**老会话不回填也能有标题**——被打开一次，历史重建后的第一句就在内存里了。

## 落地时与计划的出入

- **计划是「流式读每条日志的头几行」，实现是「store 一列」**，而且是主人拍板的：spec 起草时我按
  「读日志太贵」写了 sqlite，量出来才发现那个假设只对客户端成立——后端读 53 条日志的头一共
  0.15 MB / 39 ms。两条路都摆出来了（含「要改掉一条写明理由的守卫」这个代价），主人选了 sqlite。
  所以 spec 里「读日志头」那一段被这一节取代，`spec.md` 正文里凡是说「读头」的地方都以本节的
  「store 一列 + 客户端裁切」为准。
- **写的位置比计划更简单**：不在「run 开始的钩子」上加东西，就在 `run-agent!` 里 `log!` 那行旁边，
  输入帧与标题来自同一次到达。
- **多了一个计划里没有的东西：活的覆盖**（`liveTitles`）。计划里担心的是「发完第一句侧栏要等下一次列表」，
  两个办法里选了「host 上报」（与 `running` 同一条路）：不额外发请求、不会让列表重新排序，
  而且顺带解决了老会话——**被打开一次**就显示出它原本的标题（历史重建后第一句就在内存里）。
- **取消息的规则收敛成「第一条非空的 user 消息」**：实测真日志的 `input` 帧里只有客户端发的东西，
  服务端拼的 context / 指令块走 `inbound`、不进这一帧，所以不需要额外的「哪条才算人说的」判据。
- **空标题的回退在行与顶栏是相反的**（行 → thread-id，顶栏 → `New session`），这一点是主人选的，
  写进了两处 docstring：行要在四十行里认出**哪一个**，顶栏要说出**这是什么**。

## 票

| # | 什么 | blocked by |
|---|---|---|
| 01 | store 一列 + 写入 + 列表带 `:title`（后端五个面） | — |
| 02 | 侧边栏行显示标题、id 进 tooltip、活的覆盖 | 01 |
| 03 | 套件、走查与收口 | 01, 02 |
