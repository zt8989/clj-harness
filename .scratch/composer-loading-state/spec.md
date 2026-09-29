# spec: composer 只有两种形态 —— 「加载中」不许是第三种

**来源**：主人 2026-09-29 报的（截图：窗口 1780×1400 @2x，右边一场绑了 `lisp-harness` / `main` 的旧会话）。

**一句话**：composer 只有两种形态——**新建**（页面中间、带项目/分支条）与**会话中**（贴底、上面是任务横条）；
「这场会话的历史还在读」那一瞬必须落进**会话中**这一态。今天它落在两者中间。

## 症状

侧栏点开一场**还没在本页挂载过**的旧会话（`POST /api/threads/<id>/rebuild` 在飞、历史还没到），
屏幕上同时出现两套本该互斥的家具：

| 本该只属于「新建」的 | 本该只属于「会话中」的 |
|---|---|
| 项目/分支条（`lisp-harness` / `main`） | 消息区顶部的历史骨架屏 |
| footer 的 `pb-4 md:pb-6` —— composer 底下那条 24px 空白 | composer 已经贴底（`sticky bottom-0 mt-auto`） |
| | 任务横条（`8 已完成`）与统计条（`7 轮 · 275 次调用 · 97 tok/s`） |

主人原话：「composer 界面只有两种情况……现在出现了三种情况，加载中，底部有空隙，且出现了项目目录选择和 git 选择，
还有 todo。项目目录和 git 明显不应该存在。」

**「项目目录 + git 分支」是这一态里唯一说不通的家具**：那条条子回答的是「这场会话要在哪个目录、哪个分支上跑」，
而这一场**已经有了**目录——它正是从这场会话读出来的。问第二遍没有意义。

## 机制（实测，不是推断）

真 Chromium 1280×720、真后端（`node scripts/dev.mjs --scripted`），
用 `page.route` 把 `POST /api/threads/**/rebuild` 拖住 90 秒造出加载窗口（两场会话 + 刷新 + 点另一场，走 rebuild 门），
两种形态各量一次：

| 量什么 | 会话中（有消息） | 加载中（rebuild 在飞） |
|---|---|---|
| `[data-slot="composer-frame"]` 的 `data-started` | 有 | **没有** |
| `[data-slot="composer-context"]`（项目/分支条） | 不在 DOM 里 | **在** |
| `[data-slot="aui_thread-history-skeleton"]` | 无 | 有（248px 高） |
| `[data-slot="composer-todos"]` | 列表非空就画 | 同一个条件，**也画** |
| `[data-slot="composer-stats"]` | 有数就画 | **也画**（这一场的数） |
| `.aui-thread-viewport-footer` 的计算 `padding-bottom` | `0px` | **`24px`** |
| composer frame 底边 / 窗口底边 | `720 / 720`（贴底） | **`696 / 720`（差 24px）** |
| footer 的 `sticky bottom-0 mt-auto` | 是 | 是（布局判的是「不是新会话」） |

四个判据，四句话，各答各的：

1. **布局**用 `ui/src/components/assistant-ui/elements/thread.aui.tsx` 的 `isNewChatView`（`isEmpty`，今天 193 行）。
   加载中 = `messages 空 && thread.isLoading && !threads.isLoading` ⇒ `isNewChatView` 为 **false** ⇒ 不居中、
   footer 加 `sticky bottom-0 mt-auto`。**这一半是对的。**
2. **composer 的 chrome** 用 `ui/src/components/composer-chrome.tsx` 的
   `const started = useAuiState((s) => s.thread.messages.length > 0)`（今天 791 行）。加载中它是 **false**，
   于是 `{!started && <ComposerContextBar threadId={threadId} />}`（今天 817 行）**画出来**，
   同时 `data-started` 属性缺席（今天 806 行）。
3. **底部那 24px** 来自 `ui/src/styles.css:192`：
   `.aui-thread-viewport-footer:has([data-started]) { padding-bottom: 0; }`。
   它按的也是同一枚 `data-started`（它的注释写着存在的理由：贴上之后底下那条 16–24px 读起来像「剩下的地方」）。
   加载中命不中，上游 footer 的 `pb-4 md:pb-6` 回来 ⇒ composer 底下多一条 24px。
4. **骨架屏**是第三句判据：`isHistoryLoadingView`（同文件 198 行）。

于是「加载中」= 几个判据各投一票的结果：**dock 的外壳 + 新建的家具 + 会话中的内容**。
`.scratch/composer-todo-strip/spec.md` 决策 4 那句「上下文条只在 `!started` 画、与任务横条同占一格、两者不相遇」
在今天不成立：这一瞬两条同时占那一格。

## 决策

1. **「这个 composer 是新建的，还是已经在会话里」只准有一个答案。** composer 的 `started` 改成读布局那句判
   （`isNewChatView` / `isEmpty`），不再自己数 `messages.length`。布局已经用它决定居中还是贴底；chrome 再数一遍消息，
   就是同一个问题两个答案——加载中正好落在两个答案中间。这不是边界情况，是设计缺口。
2. **判据实现一次，两处读同一个函数。** 把 `isNewChatView` 从抄来的 `thread.aui.tsx` 里搬进 `ui/src/lib/`
   （例如 `lib/thread-view.ts`），`thread.aui.tsx` 与 `composer-chrome.tsx` 都从它 import。
   **不许**在 composer 里复制一遍条件。
3. **`data-started` 这个名字和位置不动。** `styles.css` 那条 `:has([data-started])` 靠的就是它。
   判据对了，那 24px 自己就没了——一个判据、两个消费者，**不新加 CSS 钩子**。
4. **加载中 = 会话中那一态**：不画项目/分支条、不要底部空隙。任务横条与统计条照旧
   （它们读的是服务端那两行，与会话有没有消息无关；主人也只点名了「项目目录和 git」），
   此时横条**独占**那一格——与 `composer-todo-strip` 决策 4 的原话重新对上。
5. **骨架屏与 dock 布局不动**：那是布局那半句，今天已经是对的。

## 非目标

- 不改骨架屏的样子，不改 `isHistoryLoadingView`（「历史没到」与「历史到了」是两件事，前者该有骨架屏）。
- 不动 `ComposerContextBar` 里项目/分支的读写（`HeldSession`、`gitStateFor`、`rebind` 那套），也不改它长什么样。
- 不为「加载中」加第三种视觉：不加文案、不加禁用态、不加 spinner。
- 不给任务横条加开关，不动它读的是哪一行。
- 不动 `thread.isLoading` 的来源（assistant-ui runtime 的 history 适配器），也不动「哪扇门读历史」
  （`none` / `rebuild` / `window`，见 `app.tsx` 的 `HistoryRead`）。

## 验收主线

1. 侧栏点一场有历史的会话、把 `POST .../rebuild` 拖住 ⇒ composer 贴底、**没有**项目/分支条、**没有**那 24px 空隙
   （frame 底边 = 窗口底边），骨架屏还在。
2. 同一场加载完 ⇒ 一切照旧（消息、任务横条、统计条），`data-started` 仍然在。
3. 新建（居中）一场 ⇒ 项目/分支条仍在，composer 仍在页面中间，`data-started` 仍然缺席。
4. 刷新落在会话中（window 门）⇒ 与改动前一样（贴底、`padding-bottom: 0`）。
5. 一场有任务列表的会话在加载中 ⇒ 横条还在，且**不**与项目/分支条同框。
6. `cd ui && npm run typecheck` 与 `npm run build` 绿；composer / todos 相关组件套件绿。
7. 浏览器走查一遍，照本文的量表再量一次（`node scripts/dev.mjs --scripted` + playwright 拖 `rebuild`）。

## 怎么复现（走查配方）

```bash
node scripts/dev.mjs --scripted        # 隔离家、OS 分配端口，页面由后端从 ui/dist 发出
```

打开它打印的地址：

1. 发一条消息 ⇒ 第一场会话有了历史。
2. `New task` + 再发一条 ⇒ 两场会话。
3. 刷新页面（会话从 window 门读回来）。
4. 拦住 `POST /api/threads/**/rebuild`（playwright `page.route` 里 sleep），再点侧栏里**另一场** ⇒ 加载中。

第 4 步之前不用拦也看得见（本机 rebuild 很快，只是"闪"），拦是为了把那一瞬钉住好量。

## 落地（2026-09-29）

一张票落地（票文件按约定删除，决定与验证记在这里）。判据搬了家，两处读同一个函数：

- `ui/src/lib/thread-view.ts`（新）：`isNewChatView` 从抄来的 `thread.aui.tsx` 搬过来，
  连它上面那段「启动占位当新会话」的解释一起搬；文件头记下这个问题的**两个**读者。
- `ui/src/components/assistant-ui/elements/thread.aui.tsx`：本地那个 `const isNewChatView` 删掉，
  改成一行 import（抄来的文件只多这一行，带 LOCAL 标记）。`isHistoryLoadingView` 留在原处。
- `ui/src/components/composer-chrome.tsx`：`started` 由 `s.thread.messages.length > 0` 改成
  `!isNewChatView(s)`；`data-started` 与 `{!started && <ComposerContextBar/>}` 两处一行未动。
- `ui/src/styles.css`：**一条 CSS 都没改**。只改了那段注释里对 `data-started` 的描述
  （原先写的是「会话有消息时」，现在是「不是新建的 composer 时」）。
- 套件：新增 `ui/test/suites/composer-state.ts`（2 条）——判据那张表逐行钉住（含加载中那一行），
  另一半读源码：frame 问的是同一个函数、这问题在整棵树里只有一处定义。`EXPECTED_CASES` 183 → 185。

**做到的不变量**：布局按 `isNewChatView` 决定居中还是贴底，composer 的 chrome 现在按同一个函数决定
要不要画那条条子——两者**不可能**再各说各话。这不是「把加载中修好看了」，是让第三种形态在构造上不存在。

### 验证

- `npm run typecheck` 绿；`npm run build` 绿（`node scripts/dev.mjs --scripted` 自己那次构建）。
- `npx vitest run -t composer`：**11 条绿**（`composer-state` 2 条 + `composer-todos` 8 条 + 1 条），
  收集总数 185 与 `EXPECTED_CASES` 对上。
- **真 Chromium 走查**（`node scripts/dev.mjs --scripted`；两场会话 + 刷新 + `page.route` 把
  `POST /api/threads/**/rebuild` 拖 5 秒），四态各量一次：

| 量什么 | 会话中 | 新建 | **加载中** | 加载完 |
|---|---|---|---|---|
| `data-started` | 有 | 无 | **有** | 有 |
| `[data-slot="composer-context"]`（项目/分支条） | 不在 | 在 | **不在** | 不在 |
| footer 计算 `padding-bottom` | `0px` | `24px` | **`0px`** | `0px` |
| composer frame 底边 / 窗口底边 | 720 / 720 | 334–466（居中） | **720 / 720** | 720 / 720 |
| 网格 `justify-content` | normal | **center** | normal | normal |
| 历史骨架屏 | 无 | 无 | **有** | 无 |

改前「加载中」那一行是：`data-started` **无**、条子**在**、`padding-bottom: 24px`、frame 底边 **696**（差 24px）。
改后四条全变——正是主人报的那个「第三种情况」。

任务横条那一格没有实测：隔离家的回放脚本里没有 `todo_write`，造不出任务列表。它的判据一行未动
（`todos !== null && todos.length > 0` 才画），而它原来唯一的同伴——项目/分支条——现在不在那一态里了，
所以「独占那一格」是它没改过的代码加上这里量到的那条结论。

### 机器差异（不是本改动的红）

`npm test` 全量在本机是红的，且与本次改动无关——三次连跑，**未改动的 `main` 红 15 条**，
本分支两次红 9 条与 11 条，红的是同一批套件（`turn` / `approval` / `client` / `context` / `elicitation` /
`stats` / `mux`），大多是 120s 超时与它们的连带（`mux > a-page-following-nothing-declares-nothing`
断言的是模块级状态在加载时为空，被前面超时的套件污染）。`composer-state` 与 `composer-todos` 一次次都不在红里。
