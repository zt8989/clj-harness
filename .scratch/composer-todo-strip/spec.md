# spec: Composer 之上那条任务横条

**参考**（用户给的截图）：输入框**之上**、排队消息之上，一条**折起来**的横条——左边一个清单图标，
中间 `3 已完成 · 1 进行中 · 1 待处理`，右边一个 `^`。点开就能看到每一条的明细。

**一句话**：会话有了任务列表之后，Composer **之上**多一条横条，把服务端那行任务列表折成一行计数；
点一下展开成明细（每一条：**一个状态图标** + 文本），再点收回。空列表一个字都不画。

三张票：`01 → 02 → 03`。

## 问题

1. **任务列表在服务端有行、在界面上没有位置。**
   `harness.cap.todos/items-for` 读得到、`todo_write` 写得进，但**没有任何一条路由**把它说出来——
   翻遍 `harness.edge.http` 的 `thread-verbs` 那个闭合集合，里面没有它。
   界面上唯一能看见任务列表的地方是**对话里那次 `todo_write` 的卡**（`ui/src/components/message-parts.tsx`
   折成 `3/5 完成`）：它会随对话滚走，而且要恰好停在屏幕上才看得见「现在还剩什么没做」。
2. **于是「这个 agent 现在在干什么」只能往上翻几十屏。**
   这恰恰是 `todos.clj` 自己的文档写下的那个理由：列表存进行里，是为了
   「a restart, another process, and a **panel that shows somebody what the agent is working on** all read
   the same row」——那个 reader 今天不存在。

## 决策

1. **数据源是服务端那行，不是对话。** 一条读口子 `GET /api/threads/<stem>/todos`，答
   `harness.cap.todos/items-for` 的原样列表（`content` + `status`，按写入顺序）。
   对话里那条 `todo_write` 会在压缩后被折走，行不会——而「现在还剩什么」恰恰是一个**必须活过压缩**的问题。
   （模型那边还有 `todo_read` 可以自己回读同一行，两者读的是同一份事实。）
2. **口子进 `thread-verbs` 那个闭合集合**（`[:get "todos"]`），与 `stats` / `jobs` / `page` 同形，405 的规矩照旧。
   它是**只读**的：不写审计行，问第二遍是常规用法。
3. **一个动词，没有 POST。** 任务列表的**写**只有模型那条 `todo_write`；人在这条横条上不写、不勾、不改顺序。
   点一条**不是**「把它标成完成」——那是模型的事。横条是**读**，不是第二只手。
4. **横条住在 `ComposerFrame` 里、输入框之上**，与截图同一格：今天那一格是「目录/分支」那条
   （`ComposerContextBar`），而它只在**会话还没开始**时画（`!started`），两者不相遇。
   它**不是**对话的一部分：不随消息滚动，不因为往上翻而消失。
5. **折着是默认态，一个会话里也不记「上次是展开的」**——与 `right-pane-toggle` 那条
   「transient layout preferences」同一条理由。刷新回折着。
6. **空列表不画。** 一条说了「0 待处理」的横条是给一个不存在的东西留家具。
   `items-for` 已经把「没写过」与「写了空表」折成同一个 `[]`，所以这里只有一种空。
7. **折着那行是文字，展开的明细是图标。**
   - 折着：`3 已完成 · 1 进行中 · 1 待处理`，**只列非零项**，顺序照截图（已完成 / 进行中 / 待处理）。
     中文三个词照截图，英文 `done` / `in progress` / `pending`，走 `locales/<lng>/composer.json`，
     跟界面语言走（模型那条 `todos.clj` 的 `distribution` 是**给模型读的英文**，与这份文案两回事）。
   - 展开：**一行只有两样东西——一个状态图标，和模型写的那段原文**。状态词**不在明细里出现**。
     - `completed` ⇒ `CircleCheck`
     - `in_progress` ⇒ `LoaderCircle` **加 `animate-spin`**：这条明细里唯一在动的东西，
       也是「正在做」这件事唯一说得准的画法（系统开了「减少动态效果」时用 `motion-reduce:animate-none` 停住）
     - `pending` ⇒ `Circle`（空心）
     三个图标同一个尺寸、竖着一列对齐，于是扫一眼是**三种形状**，不用读字。文本**不翻译**（那是模型的原文）。
     无障碍上每个图标带一个 `sr-only` 的状态词——那是给屏幕阅读器读的，不是画在屏幕上的字。
8. **数据怎么来这条已有规矩，本特征照它做。** `docs/rules/panel-data.md`：**先拉一次存量，之后由推送走**。
   挂载时、换会话时各一次 `GET`，之后由 run 的帧推着重问（票 03），**不轮询**。
   右侧任务视图那处 1 秒轮询是这条规矩下的**欠账**，另有票（`.scratch/panel-data-push/`）。

## 非目标

- **不给横条加写动词**：不勾、不删、不排序、不新增。任务列表只有一个写者（`todo_write`）。
- **不改 `todo_write` / `todo_read` / `harness.cap.todos` 的形状**，不动 `message-parts.tsx` 里那张卡。
- **不做第二形态**（浮窗、侧栏、历史会话之间的对比），不做「哪条在什么时候变成完成」的时间线。
- **不做没有会话时的形态**：`threadId === null` 时 `ComposerFrame` 本来就不画框（它直接返回 children）。
- **不借这一票去转右侧任务视图那处轮询**：那是 `.scratch/panel-data-push/` 的活，两处动的是同一个机制
  （推送家族），但收尾各自独立。

## 验收主线

1. 让模型写一份任务列表 ⇒ 输入框之上**当场**出现那条横条，计数对得上。
2. 点它 ⇒ 展开成明细（每行：一个状态图标 + 原文，进行中那条在转）；再点 ⇒ 收回。刷新页面 ⇒ 回折着。
3. 列表清空（`todo_write []`）⇒ 横条**不画**。
4. 换会话 ⇒ 横条换成本会话那份；没写过的那场什么都没有。
5. 切语言 ⇒ 折着那行跟着变（明细里的原文不变）。

## 落地（2026-09-29）

三张票一起落地（票文件按约定删除，决定与验证记在这里）。这一票的 worktree 从 Windows 工作区搬来 WSL，
并 rebase 到当时的 `main`（`418a7ed`）。

### 票 01：一条读口子

- `src/harness/edge/http.clj`：`thread-verbs` 那个闭合集合加 `"todos"`，dispatch 加 `[:get "todos"]`，
  新函数 `todos-get` 答 `{:threadId <stem> :todos (todos/items-for stem)}`——**只读、不 locate、不 404、不写审计**，
  `harness.cap.todos` 是它唯一的实现。
- 测试进 `test/harness/edge/http_test.clj`：`the-todos-route-answers-the-row-a-model-wrote`
  （写过后逐字段读到、没写过的答空 200、没听过的 stem 答空 200、读两次同一答案且行与日志不变、POST/PUT/DELETE 405）。

### 票 02：那条横条

- `ui/src/lib/todos.ts`：`todosFor(threadId)`，形状照 `lib/stats.ts`，读不到当 `[]`、不抛。
- `ui/src/components/composer-todos.tsx`：`ComposerTodos`（接线）、`ComposerTodosView`（折/开，纯、可渲染）、
  `TodoRows`（明细，纯）。挂在 `ComposerFrame` 里、输入框之前。
- 折着只列非零项（已完成 · 进行中 · 待处理）；明细每行一个图标加原文，状态词只走 `sr-only`；
  `in_progress` 是 `LoaderCircle` + `animate-spin` + `motion-reduce:animate-none`；明细自己滚（`max-h-40 overflow-y-auto`）。
- 文案进 `locales/{en,zh}/composer.json` 的 `todos` 一节（英文 `_one`/`_other`）。

### 票 03：什么时候重问

- 挂载与换会话各一次 GET；之后 `lib/mux.ts` 的 `subscribeFacts` 在 `model/start` 与 `turn/end` 上重问。
  **无新协议、无新帧、服务端一行未改**；没有定时器。
- `turn/end` 之后**不补一拍**：任务列表是工具执行时同步写库的，排在 `turn/end` 后面写不下东西
  （与统计条那条 400ms 补问不同，理由写在组件注释里）。

### 验证

- `cd ui && npm run typecheck` 绿、`npm run build` 绿。
- `npx vitest run -t composer-todos`：**8/8 绿**。
- `npm test` 全量：183 条里 170 绿、13 红——**红的是本机的环境红**（下节），本套 8 条不在红里。
- 后端 `clojure -M:test -m harness.test-runner`：`harness.cap.todos-test` **20 tests / 76 assertions / 0 失败**；
  `harness.edge.http-test` 118 条跑完，新增那条**不在失败名单里**（其余失败见下节）。
- 真机走查（MCP 浏览器驱动；本机没有 Playwright，`.scratch/composer-todo-strip/walkthrough.mjs`
  与 `script.json` 留着，脚本本身没在本机跑）：横条当场长出（`2 done · 1 in progress · 2 pending`）→ 点开是五行
  原文 + 三形状（进行中那颗带 `animate-spin motion-reduce:animate-none`）→ 收回 → 刷新回来仍是折着的
  （答案来自服务端行）→ 第二条消息把行改成 `3 done · 1 in progress · 1 pending`（**没有刷新**）→
  空转 5 秒 `/todos` 请求数不动（无定时器）→ `todo_write []` 后整条消失。截图在 `evidence/`。

### 机器差异（不是本改动的红）

这台 WSL 上**另一个 agent 的测试进程同时在跑**，`harness.edge.http-test` 与 `npm test` 都撞上
`SQLITE_BUSY: database file is locked`——库是每进程隔离的临时库，锁来自一个进程内 http-kit 线程并发写
加上 CPU 被抢（load ≈ 5–10）。`http-test` 1545s 跑完全程、86 failures / 14 errors，全部是那串锁与它的连带；
它第一条红是已知 flake `a-running-session-reads-what-has-arrived-and-nothing-is-written`。
`npm test` 的红同样是 3 条 120s 超时 + 被超时连累的 `mux` 一条 + `SQLITE_BUSY`，`main` 基线在这台机器上同样红。
新增的用例两边都不在红名单里。
