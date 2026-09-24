# spec: 右侧一栏的开关——子代理与后台任务

**一句话**：右侧那一列（今天是「某个子代理的镜子」，只能靠点 `agent` 卡打开）多一个**开关**。开着 =
并排一栏，里面是**任务视图**：上面一段**子代理**（本会话委派出去的，名字 / 用途 / 状态），下面一段
**后台任务**（本会话的后台作业，id / 命令 / 状态 / 跑了多久 / 一个停止按钮）。点子代理那一行就是今天那面
镜子；停止是**真停**，并且**下一通调用**会把「**人**从面板上停了它」注入给模型。

2026-09-24 立。五张票：`01 → 02 → 03 → 04 → 05`。

## 问题

1. **右栏今天只有一条路能开，而且只能看一个子代理。** `SubagentViewPanel` 在 `App` 里由
   `subagentView !== null` 挂上，`subagentView` 只有点主对话那张 `agent` 卡才会被写。于是「这一会话
   现在有什么在跑」在界面上没有答案——图里那两段（子代理 / 后台任务），今天界面上一个字都没有。
2. **后台作业在界面上完全不可见。** 作业注册表在服务端进程内、按会话（`harness.cap.jobs`），记录是
   配置家下的一份文件，而**没有任何一条路由**把「本会话有哪些作业、各自怎么样」说出来。模型那边有
   `job_output`（要一个 id），人这边什么都没有——`bash` 那一族的答案只进对话，不进任何一栏。
3. **一个作业只能由模型自己停。** 人在看着一条跑疯了的命令时，唯一的办法是回聊天里让模型去
   `job_kill`——而模型可能正忙，也可能正是它把这条起出来的。
4. **人停了它，模型也该知道。** 既有的注入（`<job-ended id="…">[exit N]</job-ended>` + `<command>` +
   一句读法）只在作业的结局**还没被认领**时发；而 `stop!` 是**认领**的（模型的 `job_kill` 的答案
   本身就是那次告知，`.scratch/job-tools` 的规矩）。人停的这一条不认领，就必须自己说明**是谁停的**。

## 决策

1. **一列，两个态。** `任务视图`（列表）与`镜像`（`.scratch/subagent-view` 那个面板，一个字不改地复用）。
   开关只回答「这一栏在不在」；镜子只回答「看哪一个」。状态是
   `null | {:kind :tasks} | {:kind :mirror :threadId .. :subagent ..}`，住在 `App` 里今天 `subagentView`
   的那个位子（`ui/src/app.tsx`）。
2. **开关照左侧那一对的做法，落在右边。** `components/sidebar-toggle.tsx` 今天是一件契约的两个地方：
   收起那颗画在左栏自己的 brand 行尾端，打开那颗是页面左下的浮动角落（栏关着时才画），两处共用同一个
   导出的 id 与 `aria-controls`。右栏照抄这条：**收起**在右栏自己头部的**前缘**（`PanelRightCloseIcon`，
   镜像头部那颗 X 因此退场——同一个动词不在同一行里放两遍），**打开**是页面**右上角**的浮动一颗
   （`PanelRightIcon`，只在栏关着时画）。两处共用同一个 id，两态各说自己要什么（一条说了「打开」却画着
   「关闭」的图标，是这个仓里最容易被读到的一类谎话）。
   `md` 以下右栏本来就不画（`subagent-view` 05 的决定：「宁可看不到镜子，也不许把主对话压到不能用」），
   所以那一对也都不画——一条点了没反应的开关比没有开关更坏。
3. **右栏关掉不记「上次看的是哪个」。** 再打开是任务列表。这是个短暂看法问题，不是工作的事实
   （与 `sidebar-toggle` 那条「transient layout preferences」同一条理由）。
4. **子代理那一段不加端点。** `GET /api/subagents` 的 `runs` 已经是「这个家委派过谁、还跑不跑」
   （`:parent` 收窄到本会话；`:running` 是服务端进程内那张表，重启之后一律 false——照实说，不撒谎），
   名字的说明在同一份答案的 `definitions` 里。一行 = 名字 + 说明 + 状态 + 什么时候开的。
5. **后台任务那一段加一条路由（一个动词、两种方法）**：`GET /api/threads/<stem>/jobs` 列本会话的作业、
   `POST /api/threads/<stem>/jobs {job}` 停一个。它落进 `edge/http.clj` 那个闭合的 `thread-verbs` 集合
   （`[:get "jobs"]` / `[:post "jobs"]`），405 的规矩照旧。
   状态**仍取自记录的末行**（复用 `ending-of` / `terminal?`，没有那一行就是还在跑），**不新立枚举**。
   `start!` 给作业条目补一个 `:started-at`——今天没有这个字段，而「跑了多久」得有个起点。
6. **停是真的停，而且**不认领**。** 模型走的 `job_kill` 照旧认领 `:told?`（它的答案就是那次告知）；
   人走的这一条**记下是人停的**、**不认领**——于是 `take-notices!` 那条既有机制在下一通调用前把它送进
   历史：`<job-ended id="j1" by="user">[stopped]</job-ended>` + `<command>…</command>` + 一句
   「这是人从面板上停的；用 `job_output` 读它」。**标签仍是 `job-ended`**（`edge/http.clj` 的注入分类
   按这个前缀认它，界面的卡也按它画），`by="user"` 是属性——命令本来就在元素里，不必为一个属性加转义。
7. **轮询只在任务视图开着时跑**：1 秒一问（作业与 runs 两条读），栏一关或页面不可见就停——与
   「关掉镜子就挂断跟随通道」同一条纪律（一条没人看的订阅是漏）。

## 非目标

- **不做多开镜子**（一次一个，照旧），**不在面板里给子代理插话**，不做「续接」。
- **不给作业加重跑 / 删记录 / 清树的按钮**：记录的回收仍只有字节封顶那一条。
- **不改 `job_output` / `job_kill` / 通知的形状**，`job_kill` 的 told 规矩一个字不动。
- **不动窄窗口下右栏不画的决定**，不动镜像里没有 composer 的决定。
- **不进库、不进 jsonl**：作业与 runs 都是进程内的事实，刷新就空——那是老实话，不是这个特性的债。

## 验收主线

1. **开关**：开 → 右栏出现、主对话还在；关 → 右栏不画、主对话复原。点 `agent` 卡照旧开这一栏。
2. **后台任务**：起一条后台作业 → 那一段有它，状态「运行中」、秒数在长；跑完 → 变它自己的末行。
3. **子代理**：委派一个 → 那一段有它，点开是那个子会话的镜像；跑完之后仍在列表里（已结束）。
4. **停**：按 ■ → 那行变 `[stopped]`；下一通消息发给模型的历史里有那一条 `by="user"`，会话里看得见
   那张卡。
5. **刷新页面**：两段都空（作业随进程、`running` 也是进程内）——不撒谎；记录仍在盘上。

## 跨特征对照

- **`.scratch/subagent-view`**（右栏并排、一次一个、没有 composer、`md` 以下不画）：组件一个字不改地复用；
  本特征给它加**谁打开它**的那一对开关（收起草在右栏头部前缘、打开那颗在页面右上角，与 `Sidebar` 那一对
  同形），并把它头部那颗 X 让给「返回列表」——关整栏是前缘那颗收起的事。
- **`.scratch/readback-verbs`**（~~上一批：`job_list` / `todo_read` / 状态行归位~~）：~~`job_list` 是**模型**
  那一只「列出」，本特征是**人**这一只。~~ 本特征这一栏是**人**那一只「列出」。**状态话只能有一处**
  （`job_output` 与这一栏都走 `cap.jobs/status-of`，它读记录的末行），别长出两套。差别在听众与范围：
  模型那份（还没落地，见下）要「回头找一条命令说过什么」，所以把前台溢出的 `c*` 记录也列上；
  人这一栏是「**后台任务**」，`c*` 不是作业、没有进程可停，不列。
  **2026-09-24（收口那天核过代码）：** 划掉的那半句不成立——`job_list` 与 `todo_read` 在代码里
  **一行都没有**：`.scratch/readback-verbs/` 只有票面（四张票仍是 `ready-for-agent`，`git grep job_list src/` 为空），
  所以这条对照是与**一份计划**对齐，不是与已经落地的上一批。名字也差一处：那一版计划写的是
  「同一个 `ending-of`」，而两处状态话真正共用的函数是 `cap.jobs/status-of`（`output` 与 `listing` 都调它；
  `ending-of` 是它的邻居，两者共用那个读末行的纯函数 `ending-line`）。**「状态只有一处出处」这条纪律本身成立**，就是 `status-of`。
- **`.scratch/job-receipt-no-path`**（回执不报路径、读的那一个报）：列出来的那一栏**就是**读的那一个，
  行上报路径合规。
- **`.scratch/bash-record-persistence`**（记录不随进程消失、字节封顶）：一个字不改。
- **`.scratch/job-tools`**（起 / 读 / 停三个动词，`stop!` 认领 told）：本特征给「停」加第二个**发起人**，
  认领的规矩分家，别的字不动。

## 交付顺序

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 开关：能开、能关，打开是任务视图 | — | `App` 的 `rightPane` 状态、右栏头部前缘那颗收起 + 页面右上角那颗打开（与 `sidebar-toggle` 同一条契约：共用 id 与 `aria-controls`）、任务视图两段标题与空话、两套文案；点 `agent` 卡照旧直进镜像 |
| 02 | 后台任务那一段 | 01 | `GET /api/threads/<stem>/jobs` + `start!` 的 `:started-at`；一行：id / 命令 / 状态 / 跑了多久；行画在任务视图里；开着才轮询 |
| 03 | 子代理那一段 | 01 | `GET /api/subagents` 的 `runs` 按 `:parent` 收窄 + 定义里的说明；一行：名字 / 说明 / 状态；点一行进镜像、镜像头部一颗返回 |
| 04 | 停，与「人停的」注入 | 02 | `POST /api/threads/<stem>/jobs {job}`、不认领 told 的第二个发起人、`<job-ended id="…" by="user">` 那条注入；行右端那颗 ■（带在飞态） |
| 05 | 收口 | 02, 03, 04 | `docs/architecture.md` 与 `docs/architecture/client.md` 该改的那几行、`CONTEXT.md` 立词、两套全量 + 一次浏览器走查（含窄窗口与「关掉之后轮询停了」）、落地记录写进本文件、票面删除 |

## 落地记录

**2026-09-24，分支 `right-pane-tasks`，`29b52a8`(01) → `75919c5`(02) → `2e3388a`(03) → `d95520e`(04) → 本票(05)。**
下面是每票建了什么、真撞上的坑是什么；票面按仓库约定删除，记录留在这里。

### 01 — 右栏的开关（`29b52a8`）

- **建了什么：** `components/subagent-view-context.ts` 的 `RightPane`（`null | {kind:"tasks"} |
  {kind:"mirror", threadId, subagent}`）住进 `App` 今天 `subagentView` 的那个位子；`components/right-pane-toggle.tsx`
  是 `sidebar-toggle.tsx` 那条契约的右侧版本（一个导出的 `RIGHT_PANE_ID`、两处控件共用 `aria-controls`、
  `aria-expanded` 报**区域**的状态、不持久化）；任务视图先立两段标题与各自空话；`components/task-pane.tsx` 的骨架。
- **坑：** 镜子的 X 与新的「收起」是**同一个动词**，X 退场、它的位置留给 03 的「返回」——`subagent-view.tsx` 套件里
  那几条断言 X 的用例**改到**收起那颗上（不许删断言）。那两颗都 `hidden md:flex`：`md` 以下那一列本来就不画，
  一条点了没反应的开关比没有更坏。

### 02 — 后台任务那一段（`75919c5`）

- **建了什么：** `GET /api/threads/<stem>/jobs`（`jobs-get`，闭合集合 `thread-verbs` 里的 `jobs`）读的是**本进程的
  作业注册表**而不是记录——没有作业、或作业随上一个进程死掉都是 `[]`、**不 404**；`cap.jobs/listing` 给出
  `[:id :command :status :startedAt :path]`；`start!` 补 `:started-at`；界面里 `hooks/use-task-pane.ts` 是**唯一的
  1 秒 tick**（栏挂着 + 页面可见才跑，关掉即 `clearInterval` 并中止在飞请求），`lib/jobs.ts` 取数、`stopJob` 停。
- **坑（值得单记）：** 状态抽成 `cap.jobs/status-of`（`terminal?` + 记录末行），`output` 与 `listing` 都走它。
  一条**记录已关却还没写下末行**的作业，`output` 从前把命令自己打的最后一行当状态；现在答 `[exit ?]`。
  这是有意的、更诚实的答法——但它是**行为上看得见的改动**，别当成零风险。
- **坑：** 01 立的「`task-pane.tsx` 里不许有 fetch / 定时器」那条边界用例是**改写**不是删（读落在 `lib/jobs.ts`
  与那个 hook 上，pane 自己仍不取数）。

### 03 — 子代理那一段（`2e3388a`）

- **建了什么：** `lib/subagents-runs.ts` 把 `GET /api/subagents` 的 `runs` 按 `:parent` 收窄到本会话、按名字 join
  定义里的说明（**定义已删的 run 只画名字与状态**）；点一行开的是**那一行自己的 `threadId`**；镜像头部**尾端**
  多一颗「返回列表」（`RightPaneBackButton`，与收起共用 `aria-controls`，但不带 `aria-expanded`——
  区域没变、它也不是 disclosure）。这一读接在 02 的**同一个 tick** 上，不另起定时器。
- **坑：** `running` 是服务端进程内那张表，重启之后一律 false——按实话说（行上写「已结束」而它可能在别的进程里跑）。

### 04 — 停，与「人停的」注入（`d95520e`）

- **建了什么：** `cap.jobs/stop!` 多了 `{:by}`（缺省 `:model` **逐字**保持旧行为：无条件认领 `:told?`；`:user` 只在
  真的还在跑时记 `:stopped-by`、**不认领**）；`POST /api/threads/<stem>/jobs {job}`（未知 id 复用 `unknown-job` → 404、
  坏 body 400、**不带审批**，照 `cancel-post`）；通知标签仍是 `job-ended`，多一个 `by="user"` 与一句
  「A person stopped it from the pane…」。界面：■ 只画在 RUNNING 的行上、带在飞态、失败一句话；行的 `[stopped]`
  由既有 1 秒轮询带回来，不另刷。
- **坑：** 「停」一步做两件事（停 + 认领告知），这对 `job_kill` 是对的、对人按的那颗 ■ 是错的——所以加的是
  **第二个发起人**，不是改第一个；人按一条**已经结束**的作业什么都不改（那不是人停的）。

### 跨特征对照（逐条核过代码；该划线的已划线）

- **`.scratch/subagent-view`**：右栏并排 / 一次一个 / 没有 composer / `md` 以下不画——四个性质一个字没改，
  组件复用成立；那一对开关与「返回」都在。**划线：** `issues/06-remove-the-sidebar-block.md` 里
  「前端不再读 `GET /api/subagents`」被本特征翻掉（票 03 的任务视图正是那个读者），已划线 + 2026-09-24 注；
  `spec.md` 决策 2（入口是那张 `agent` 卡）加了一条日期注：**第二条入口**从任务视图那一行进来，卡那条路未动。
- **`.scratch/readback-verbs`**：**这一条的前提是错的**——`job_list` / `todo_read` 在代码里一行都没有
  （`.scratch/readback-verbs/` 只有票面，四张票仍是 `ready-for-agent`，`git grep job_list src/` 为空），
  「上一批」只在纸面上。已在本文件那条对照上划线 + 2026-09-24 注。「状态只有一处出处」这条纪律本身成立，
  而且比那条对照写得更准：共用的是 `cap.jobs/status-of`（`output` 与 `listing` 都调它），不是 `ending-of`。
- **`.scratch/job-receipt-no-path`**：合规。列出来的那一栏**就是**读者，行上的 `:path` 进那一行的 `title`；
  两份回执仍不报路径。旧文件加了一条日期注：**读的那一处现在是两处**（`job_output` 与 `listing`）。
- **`.scratch/job-tools`**：`stop!` 的旧行为逐字保持（缺省 `:model`），认领的规矩分家。旧文件加日期注。
- **`.scratch/bash-record-persistence`**：一个字没动（核过）。

### 实测（都在 `right-pane-tasks` 上，工作树里跑）

- **后端全量**：`clojure -M:test -m harness.test-runner` → **`Ran 1247 tests containing 13644 assertions. 0 failures, 0 errors.`**，退出码 0。
  判据只有一行 `ISOLATION NOTE`（真家 `harness.db` 被动过，而**本进程没开过它**——那是开发者自己那个活着的
  harness 会话写的），**没有 `ISOLATION FAILURE`**。
- **前端全量**：`cd ui && npm test` → **138 passed**（1 个文件），退出码 0；stderr 有一行「临时家删不掉」（EPERM，
  Windows 上既有的凑巧，不是失败）。`cd ui && npm run build`（`tsc --noEmit` + `vite build`）绿。
- **那条已知的间歇性**：02 报过的 `http_test` 里直接 `slurp` 记录的那条
  （`a-configuration-naming-a-count-is-refused-rather-than-silently-dropped`）**这一轮没出现**——它只在后端全量
  与前端套件**并发**跑时见过一次；本次两套是**串行**跑的。这不是「已经修好」，只是这次没撞上。
- **票面写的命令有两个已经不存在**：`node scripts/test.mjs --backend` / `--ui`——那个入口在 `334082d` 就被删了
  （「测试入口收进原生命令：删 `scripts/test.mjs`」）。用的是 `AGENTS.md` 里的原生命令。

### 浏览器走查（`node scripts/dev.mjs --scripted .scratch/right-pane-tasks/walkthrough-script.json`，1400×900）

脚本：主 agent 起一条 `echo 起点; sleep 120` 的后台作业，再委派 `general` 一个子代理。下面每一条都是当场量的数：

1. **开关开 / 关**：点右上角「打开任务视图」→ `aside#app-right-pane` 出现，`data-slot="task-pane"`，宽 **416**；
   主栏还在（宽 936，主 composer 与最新那句提问都在）。点头部前缘那颗「收起任务视图」→ 栏不画，
   **主栏 936 → 1352**（正好收回 416），右上角那颗打开键回来（32×32，`aria-controls=app-right-pane`，`aria-expanded=false`）。
2. **一行 `[running]`、秒数在长**：`j1 | echo 起点; sleep 120 | [running] | 已跑 10 秒` → 17 秒 → 52 秒；栏宽 416 没被撑开。
3. **■ 真停**：按下之后那行读 **`[stopped]`**，秒数不见，■ 也不再画（只画在 RUNNING 的行上）。
   **没采到「在飞态」**：本机这一次 POST 在一个采样间隔内就答了，按钮已经是 `[stopped]` 的样子。
   那一位由 ui 套件的源码读钉着（`setPressing(true)` / `disabled={pressing}` / `<SquareIcon`）。
4. **人停的注入**：再发一句 → 会话里出现那张注入卡（`注入的上下文 · job-ended · 176 B`），展开后**第一行就是**
   `<job-ended id="j1" by="user">[stopped]</job-ended>`，跟着 `<command>echo 起点; sleep 120</command>` 与
   `A person stopped it from the pane; read what it said with job_output {"job": "j1"}.`
5. **子代理一行 → 镜像 → 返回**：点 `general` 那一行 → `aside#app-right-pane`（**同一个 id**）变成镜像，
   `aria-label="子agent · general"`，宽 416，`textarea` / `form` **各 0 个**（没有 composer），里面是那个子会话的
   提问与回答；点尾端「返回列表」→ 任务视图回来、镜像不在（`taskPaneBack: true` / `mirrorGone: true`）。
6. **767px 两者都不画**：栏开着时 `aside` 是 `display:none`、宽 **0**、`offsetParent` 为 false；栏关着时
   右上角那颗打开键 `display:none`、**0×0**，收起那颗随栏不在 DOM 里；页面不横向滚。
7. **栏关掉之后轮询停了**：用 `performance.getEntriesByType('resource')` 数 `/jobs`——关栏那一刻 **111**，
   6 秒后**还是 111**（`/subagents` 也停在 110 不动）；栏开着时两者每秒各 +1。
8. **控制台**：3 条 error 全是既有的——`favicon.ico` 404 与两条 `/api/threads/<id>/stats` 404（还没有统计时的
   普通答案），与本特征无关。

截图在 `.scratch/right-pane-tasks/evidence/`（`v05-01` … `v05-05`），走查脚本是本目录的
`walkthrough-script.json`。

### 收口那天发现、**没有**改的（留给主人定）

- `ui/src/components/right-pane-toggle.tsx:15-17` 与 `ui/src/components/task-pane.tsx:17-18` 各有一处**重复的注释句**
  （同义句写了两遍，且第一句在一个从句中间断掉）——像是 01→03 两次编辑叠出来的。没动它：那是 `ui/src` 的源码，
  本票的规矩是不碰代码。
- `ui/src/components/subagent-list.tsx:186-196` 的注释说「the front end simply has no reader for that half
  any more」——**已经不成立**（票 03 让前端重新读 `runs`）。同上，没动，只在这里点名。
- `CONTEXT.md` 立词时发现两处措辞碰撞（都写进词条里、没有改代码）：栏头今天写的是「任务」/`Tasks`，而本表把
  「任务」留给无家会话；作业那一段在界面上叫「后台任务」/`Background jobs`，本表的词是「后台作业」。
- `docs/architecture/edge.md` 那张路由表本来就不全：`compact` / `delegations` / `follow` / `cancel` 都不在，
  `GET /api/subagents` 也不在。本票只补了 `jobs` 两行——其余几条属于别的特征，没顺手补。
- `docs/architecture.md` 的快照点**没动**：它写的是合进 `main` 的那一提交，而本分支还没合。
