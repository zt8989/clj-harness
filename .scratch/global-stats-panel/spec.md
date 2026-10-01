# 全局统计：一整块统计页（global-stats-panel）

**状态：** 已落地（2026-10-01）。票按仓库约定不单独留文件，决定、落点与判据都记在这里。

## 起因

主人的要求，两轮：

1. **按投影数据把统计显示出来**——工具调用量排行、skill 调用排行，**全局级别**（跨项目、整个 home），
   右侧边栏，再加一个「**…**」点开能看到**不同模型的 token 使用量排行**。
2. **再加两点**——(a)「**确保投影都是增量订阅，而不是全量阅读**」；(b) 统计要**一整块**（除了左侧栏全都覆盖，
   连原来的会话和右侧栏一起），页面上能选 **7 天 / 30 天 / 90 天**，并且**能对选中的时间做全量重算投影**。

第一轮的三个设计问题当场问了主人，答复即决定：落位（右栏标题栏的 `…`）、范围（本机所有会话）、数据（扩展投影）。

## 一、投影：只有增量，加到内容表也一样

`harness.infra.db` 多一张内容表 `model_calls`（迁移步骤 `model-calls`）：一次模型调用一行，主键
`(session_id, seq)`，`seq` 是 **`model/start` 那一行的行号**；`model/end` 到达时填进同一行（`end_seq` + vendor
报的 token 数）。**配对状态在库里、不在一次 pass 的内存里**，因为一个字节 offset 可以正好落在一对
start/end 之间——SQL 里就是「这个会话里 `end_seq IS NULL` 的最大 `seq`」，没有 start 的 end 用自己那行兜底。

**它由同一次增量 pass 填，没有第二个读者**：`project-row!` 按 `replay/kind` 认行，`model/start` / `model/end`
两个 fact 行跟 message 行一样来自监听写流的那一趟——判据是
`projection-test/a-model-call-written-now-is-projected-by-the-same-listener`（**全程没有 `project!`**）。

**没有任何自动的全量重读。** 早先有一版在启动时「加表了就把整份重投影一次」（`projection_repairs` 标记 +
`start!` 排一次 `rebuild!`），按主人第二轮那句话**撤掉了**：那正是这一层拒绝的全量读。现在 `start!` 不查库、
不读日志、不排任何东西；偏移那条路的成本特性一个字没变（空闲进程零轮、零连接）。

**要看过去的数据，要有人按一下。** 一份在加表之前就拷过的日志，offset 已经越过那些字节，新表只能是空的——
所以 `harness.edge.projection/rebuild-window!` 把**这个窗口里活跃过的会话**删掉再投影一遍（ADR 0008 决策 6 的
动作），而它是**页面上一个按钮**（`POST /api/stats/rebuild {days}`），不是进程自己的动作。

## 二、服务端：一个窗口、三条路由

- 三条排行都带**时间下限**：工具与 skill 的窗口借**声明它的那条 `messages` 行**的 `at`
  （`JOIN messages m ON m.session_id = t.session_id AND m.seq = t.seq`——**不新增列**，所以已经投影过的行也问得出
  时间）；`model_calls` 自带 `at`，不用 join。窗口是**问题的一部分**，不是盖在一个全量答案上的过滤器。
- `GET /api/stats?days=N`：快照，只数行、不落一笔（`days` 缺省 7，`stats-days` 说了为什么不校验成 7/30/90）。
- `GET /api/events.stats?days=N`：**单独一条下行**（`harness.edge.host` 的第二套 watcher）。投影每写下一行
  `ring-stats!` 一次，只有**正开着统计页**的页面在听。**窗口跟着连接走**，所以换窗口是换一条连接，
  不是拿旧答案套新标签。
- `POST /api/stats/rebuild {days}`：把这一个窗口的拷贝重做一遍，答 `{:days n :scheduled bool}`。
  **排到投影自己的线程上**（请求不等读日志），新数字由那条下行推回来；没有 trigger 在跑时（套件、REPL）
  就地做完再答。

## 三、前端：一个覆盖整页的抽屉

- `components/stats-view.tsx`：**`bg-background absolute inset-0 z-50 flex flex-col`**——盖住整页
  （会话、右栏、**连左侧栏一起**），`z-50` 在 shell 画的每一层之上（侧栏/右栏 `z-30`、角上的按钮 `z-40`）。
  **它不是 flex 行里的兄弟**，所以身后什么都不重排：会话照旧挂着（runtime、run、滚动位置都在），也不画背板
  （整页都盖住了，旁边没有可点的东西）。
- 标题栏：`[回到对话] 统计 … [7天|30天|90天] [重算]`。窗口是三个按钮（`aria-pressed` 说明哪个开着）。
- **它是一张真正的模态对话框**（主人 2026-10-01 补的一层）：`role="dialog"` + `aria-modal="true"`，
  用屏上的标题当名字（`STATS_TITLE_ID`），键盘由 `hooks/use-focus-trap.ts` **关在里面**。
  这两半是一件事：`aria-modal` 是「外头是惰性的」这句**声称**，陷阱是让它成真的那一半——
  只剩一半的话，下一次 Tab 就把它戳穿。
  陷阱做三件事：挂载时**焦点进去**（第一个可聚焦的，没有就把焦点落在容器自己身上，所以 `tabIndex={-1}`）、
  **Tab 首尾环绕**（`Shift+Tab` 从第一个绕到最后一个，Tab 从最后一个绕回第一个；中间那些交还给浏览器），
  卸载时**还给原来的元素**（还在文档里才还）。**Esc 不在这里**——关它是页面的事（`app.tsx` 已经有那个处理器）。
  仓库里同一条规矩的另一处是 `assistant-ui/elements/image.tsx`（它把 zoom 浮层自己夹住）；那一份**故意不改用**
  这个 hook：那个文件是上游的、就地翻译的（它自己的头注释写着），把它挪进本仓库的词汇只会多出一处偏离。
- **Esc 也关它**：`app.tsx` 那个 Escape 处理器里，统计抽屉**排在最前、且不看宽度**（它任何宽度都盖在最上层），
  下面那句宽度判断是给「只在窄屏才是抽屉」的两个面板的。
- 三个分区在宽屏是一行、窄屏是一列，各自滚动；**模型 token 排行还在折叠里**（第一轮那句「点击下拉可看」）。
- `lib/home-stats.ts` + `hooks/use-home-stats.ts`：快照一次 + 订阅那条下行；**窗口是依赖**（换窗口 = 换连接 +
  补一次快照）；三个「不看了」（卸载、页面隐藏、离屏）都停订阅；**没有计时器**。
- 两个语言目录都加了 `rightPane.stats*` 的键（英文 `_one`/`_other`，中文只有 `_other`）。

## 四、判据

- `harness.edge.projection-test`：`a-model-call-is-copied-with-its-model-and-its-tokens-together`（配对、
  `ms` 取两个时间戳之差）、`the-leaderboards-count-tools-skills-and-each-model-s-tokens`（**时间戳围着 now 写**，
  窗口内计数、并列按名、缺席的 token 键不在，且一条 30 天前的调用在 90 天窗口里、不在 7 天窗口里）、
  `a-model-call-written-now-is-projected-by-the-same-listener`（**增量**）、
  `a-window-rebuild-makes-the-copy-again-for-the-window-only`（窗口内的会话补回来、窗口外的**一个字节都不读**）；
  `rebuilding-answers-row-for-row-what-was-there` 加了模型调用，断言整份 rebuild 后 `model_calls` 逐行相同。
- `harness.infra.db-test`：`model_calls` 进了「这个家的表就是声明过的那些」与列形状两张单子。
- `harness.edge.host-test`：统计门铃是自己的、帧带窗口、ring 不到 listing 的 watcher。
- `harness.edge.http-test`：`GET /api/stats?days=30` 回 30 与三个列表、POST 不服务的动词是 405、
  `POST /api/stats/rebuild` 是排掉的动作。
- `ui/test/suites/right-pane.tsx`：两条用例——`…`/关闭两个控件指向 `STATS_VIEW_ID`、页面把会话 `hidden`、
- `ui/test/suites/right-pane.tsx`：两条用例——`…`/关闭两个控件指向 `STATS_VIEW_ID`、抽屉那串 class
  （`absolute inset-0 z-50`）且**不是** flex 兄弟、会话**没有**被 `hidden`、Escape 先答统计、窗口三个按钮的
  `aria-pressed` 与两种语言的词、`重算` 按钮与它带的窗口、折叠默认关着。`ui.test.ts` 的 `EXPECTED_CASES` 203 → 205。
- `npm run typecheck` / `npm run build` 绿；`npm test`（205/205）绿。
- **浏览器走查**（AGENTS.md 要求）：`node scripts/dev.mjs --scripted`，走一条消息，右栏标题栏 `…` → 抽屉盖满整页
  → 工具排行、skill 空句子、点开模型排行看到 `scripted / 1k / 2 次调用`、切到 30 天、按 `重算`、`回到对话`/`Esc`。
  截图：`evidence/stats-drawer.png`（抽屉）、`evidence/stats-page.png`（上一版整块页，留作对比）、
  `evidence/stats-view.png`（第一轮那个窄栏版本）。走查实测：`.stats-view` 的 rect = 整个 viewport（500×296 与
  1280×800 两档），`z-index: 50`，`elementFromPoint` 在原本侧栏那一列拿到的是抽屉自己的内容。

  **焦点那一半是走查里按真键走的**（字符串测不出来）：打开时 `document.activeElement` = `stats-close`（在里面）；
  连按 12 次 Tab 的落点轨迹是 `range-7 → range-30 → range-90 → rebuild → models-toggle → close →`（绕回）`range-7 …`，
  **一次都没出去**；从第一个 `Shift+Tab` 绕到最后一个 `models-toggle`；Esc 关掉之后焦点不在任何残留里（落回 `body`，
  下一次 Tab 从 `sidebar-open` 开始）——这正是上面那条「原来那个控件已经不在」的边界。

## 两处与这一票无关的既有 flake（记录，别当成回归）

**一、`harness.edge.http-test/a-conversation-whose-runs-predate-the-numbering-heals-on-the-next-read`**

`harness.edge.http-test/a-conversation-whose-runs-predate-the-numbering-heals-on-the-next-read`
**在 main 上就会偶发失败**（实测：main 上单独跑三次，第三次红）。它赌的是「run 结束后 `http/running?` 为假」
**在 main 上就会偶发失败**（2026-10-01 实测：单独在改动前的 main 上跑三次，第三次红）。它赌的是「run 结束后
`http/running?` 为假」与 `reconcile-numbers!` 读会话时 `sessions/running?` 之间没有窗口；改动后在同一棵树上
又见过一次（`http_test.clj:6543`）。**不是这一票引入的**，也不该由这一票去修。

**二、`harness.infra.db-test/a-damaged-store-of-ours-is-moved-aside-and-rebuilt-said-out-loud`**

2026-10-01 在合并后的 main 上跑「http + projection + db + host」这一把时红过一次（`db_test.clj:348/358/359/361/365`），
同一条命令紧接着再跑一次全绿，`harness.infra.db-test` 单跑两次也全绿。形状是**残留文件的数量**：这条用例断言
「残骸只留下 1 个 `.corrupt-` 文件」，而隔离要把 `harness.db` 连同**当时存在的** `-wal`/`-shm` 一起搬走——那一跑
搬了 3 个（`expected: 1, actual: 3`，尺寸也从 100 变成 32768）。也就是说它赌的是「隔离那一刻 WAL 在不在」，
而**这条恢复路径这一票一个字没碰**（既不在 `model-calls` 迁移里，也不在投影里）。见到就重跑一次；真要修，
是让那条断言对「WAL 在不在」两种形状都成立。
