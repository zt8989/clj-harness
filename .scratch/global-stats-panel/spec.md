# 全局统计：右栏第三个状态（global-stats-panel）

**状态：** 已落地（2026-10-01）。票按仓库约定不单独留文件，决定、落点与判据都记在这里。

## 起因

主人的要求一句话：**按投影数据，把统计显示出来**——工具调用量排行、skill 调用排行，**全局级别**（跨项目、
整个 home），放在**右侧边栏**，再加一个「**…**」点开能看到**不同模型的 token 使用量排行**。

三个设计问题当场问了主人，答复即决定：

1. **落位**：右栏标题栏加一个 **`…`** 按钮，点开一个**整栏的「统计」视图**（带返回按钮）。不是第三个分区，
   也不是浮层——统计是**同一个右栏元素的第三个状态**（`.scratch/right-pane-tasks` 决策 1：一个元素，几个状态）。
2. **范围**：**本机所有会话**（跨项目、整个 home）。
3. **数据**：**扩展投影**——新增一张 `model_calls` 表（含 schema 迁移与 rebuild 语义），三条排行全部走投影，
   不每次去折记录。

## 一、数据：投影多了两张表

`harness.infra.db` 新增迁移步骤 `model-calls`（`model_calls` + `projection_repairs`），`harness.edge.projection`
多认两种 fact 行：

- **`model/start`** → `model_calls` 一行，主键 `(session_id, seq)`，`seq` 是**那一行自己的行号**，模型名在这里；
- **`model/end`** → 填进同一行（`end_seq` 与 vendor 报的 token 数）。

**按序配对，而且配对状态在库里而不是在一次 pass 的内存里**：一个字节 offset 可以正好落在一对 start/end
之间（上一趟读了 start，下一趟才读 end），所以「谁在等一个 end」必须能被下一趟查到。SQL 里就是
「这个会话里 `end_seq IS NULL` 的最大 `seq`」。没有 start 的 end 也照样记（用 end 自己的行号）——它是一次
真发生过的调用。

**token 的读法只有一处**：`harness.edge.stats/usage-fields`（新公开），投影写它、`usage-of` 累加它，
所以「按模型看的那个数」和 composer 条上那个数不可能对不上。

## 二、offset 不说话的那张表：`projection_repairs`

**这是这一票最要紧的诚实问题。** `projection_offsets` 说的话是「这个字节之前的内容**都**投影了」，而它说的
是**当时存在的那些表**。加一张内容表，这句话对新表就是**假的**：老 home 的 `messages` / `tool_calls` 是齐的，
`model_calls` 一个字节都没有。

所以 `model-calls` 这一步在**已经有 offset**（即这是一份既有的拷贝）时，往 `projection_repairs` 写一行；
`projection/start!` 启动时**同步问一次**（一条 `SELECT`），有就调度一次**整份重投影**（ADR 0008 决策 6 的动作，
`rebuild!` 的 0 元形——顺手把它从「其实什么都没删」改成真的删干净），完成后删掉那一行。

- 全新的家没有 offset 可对不起，**不写这一行**，启动时那一次问答完就结束；
- **轮数不变**：repair 走的是自己的任务，不碰 `rounds-run`，所以
  `projection-test/an-idle-projection-does-not-walk-the-store`（「空闲进程零轮、零连接」）照旧成立——
  那一次问答发生在 `start!` **返回之前**，落在测试取样点之外。
- 失败的 repair **不删标记**，下次启动再试。

## 三、服务端：一个路由 + 一条自己的下行

- `harness.edge.projection`：`tool-leaderboard`（按 `tool_calls.name`）、`skill-leaderboard`（`skill` 调用的
  `name` 参数——**不新增列**，否则投影要跟着工具自己的 schema 走）、`model-leaderboard`（按 `model` 汇总 token）。
  排序稳定：并列按名字。缺席不带 0（`harness.edge.stats` 的老规矩）。
- `GET /api/stats`：三条排行的快照。**不做成 `/api/projects` 的一个键**——侧栏每次 host 变化都读那份载荷，
  它不需要「全库有多少次工具调用」。
- `GET /api/events.stats`：**单独一条下行**（`harness.edge.host` 里的第二套 watcher）。投影每写下一行就
  `ring-stats!` 一次；只有**正开着统计视图**的页面在听，所以只开侧栏的页面不为这次扫描付钱。
  帧是**整份**（没有游标，和 `events.host` 同形），重连时服务端**立刻再给一份**——推丢了由这一份补。

## 四、前端：右栏第三个状态

- `components/subagent-view-context.ts`：`RightPane` 多一形 `{ kind: "stats" }`。
- `components/right-pane-toggle.tsx`：`RightPaneStatsButton`——`…`（lucide 的 `EllipsisIcon`），
  名字是它的 `title` + `sr-only`，`aria-controls` 指同一个 `RIGHT_PANE_ID`；**没有** `aria-expanded`
  （这是导航，不是折叠——mirror 的返回按钮说过同一句话）。
- `components/task-pane.tsx`：标题栏**尾端**放它（`onStats` 由页面给），那正是 mirror 放自己返回键的地方。
- `components/stats-view.tsx`：整栏。工具排行、skill 排行**直接可见**；**模型 token 排行在折叠里**
  （`aria-expanded` + 展开才画行）——主人那句「点击下拉可看」就落在这一格。
  空家每节各自一句话；**没有用量的模型行说「无用量」而不是 0**。
- `lib/home-stats.ts` + `hooks/use-home-stats.ts`：快照一次 + 订阅那条下行，三个「不看了」（卸载、页面隐藏、
  栏离开屏幕）都停订阅，回来先补一次快照。**没有计时器**。
- `app.tsx`：`rightPane.kind === "stats"` 画它；`onStats={() => openPane({ kind: "stats" })}`——和另外两个
  门走**同一个 writer**，所以 `md` 以下的抽屉规矩对它一样成立。
- 两个语言目录都加了 `rightPane.stats*` 的键（英文有 `_one`/`_other`，中文只有 `_other`）。

## 五、判据

- `harness.edge.projection-test`：`a-model-call-is-copied-with-its-model-and-its-tokens-together`（一行两行配对、
  `ms` 取两个时间戳之差）、`the-leaderboards-count-tools-skills-and-each-model-s-tokens`（跨会话计数、并列按名、
  缺席的 token 键不在）、`a-repair-fills-what-the-offsets-never-covered`（种一行 repair、`start!` 补齐、标记被收走）；
  `rebuilding-answers-row-for-row-what-was-there` 加了模型调用，断言整份 rebuild 后 `model_calls` 逐行相同。
- `harness.infra.db-test`：两张新表进了「这个家的表就是声明过的那些」与列形状两张单子。
- `harness.edge.host-test`：`the-statistics-have-a-doorbell-and-a-downlink-of-their-own`——帧带三条排行、
  `ring-stats!` 到统计 watcher 而**不到** listing watcher、关闭释放。
- `harness.edge.http-test`：`the-statistics-route-is-a-question-of-its-own`（200 + 三个列表；POST 405）。
- `ui/test/suites/right-pane.tsx`：两条新用例——`…` 控件（名字两种语言、`aria-controls`、无 `aria-expanded`、
  页面三个状态与 writer）与统计视图本身（同一列 class / 两种语言的四个标题 / 两节空句子不相同 /
  折叠默认关着且行不画）。`ui.test.ts` 的 `EXPECTED_CASES` 203 → 205。
- `npm run typecheck` / `npm run build` 绿；`npm test` **205/205** 绿。
- **浏览器走查**（AGENTS.md 要求）：`node scripts/dev.mjs --scripted` 起一个隔离家，走一条消息之后的右栏——
  标题栏的 `…` 打开统计视图（同一列：收起 / 统计 / 返回列表），工具排行「read 1」、skill 排行空句子，
  点开「各模型 token 用量」看到 `scripted 1k / 2 次调用`。截图在 `evidence/stats-view.png`。

## 一个与这一票无关的既有 flake（记录，别当成回归）

`harness.edge.http-test/a-conversation-whose-runs-predate-the-numbering-heals-on-the-next-read`
**在 main 上就会偶发失败**（实测：main 上单独跑三次，第三次红；本票的树上单独跑两次红一次绿）。
它赌的是「run 结束后 `http/running?` 为假」与 `reconcile-numbers!` 读会话时 `sessions/running?` 之间没有窗口，
而这一票让一轮投影多写几张 `model_calls` 行，只是把那个窗口的开合时机挪了几毫秒。
**不是这一票引入的**，也不该由这一票去修——修它要在 `http_test` 里换一条「等什么」的判据。

## 代价与不做的事

- **一次性的整份重投影**：老 home 第一次启动会重读所有日志（后台、一次、可重建），这是加内容表必须付的账。
- **三条 `GROUP BY`**：只在有人开着统计视图时、每投影一轮跑一次；`tool_calls` / `model_calls` 各自的名字列建了索引。
- **不动**：记录格式、`messages` / `tool_calls` 的语义、以及「投影不进写路径」。
