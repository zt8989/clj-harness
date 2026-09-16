# spec: local history（改完能退回去）

harness 动过的每个文件，都留一条「动之前那份」的记录；跑完之后有人（人或模型）说「退回去」，文件逐字节
回到那个状态。参照 VS Code 的 Local History（`workbench.localHistory.*`），但**只抄它答对的那半**——
它的落盘形状、上限、还原语义照搬，触发时机与记录方向按本仓的用途改写，理由在下。

## 为什么不是照抄

VS Code 的历史答的是「这个文件早些时候长什么样」：编辑器里当前状态永远在屏幕上，所以它记录的是
**每次保存之后**的文件。本仓要答的是「把它变回你动之前的样子」——而**改后**记录永远拿不到 harness 碰
它之前的那份状态。所以记录的是**动之前**。

这不是新裁定。`hashline-edit` 的 08 票已经把这个方向写死了：`hashline_undo` 存的是「该文件最近一次
replace/insert **之前**的正文、BOM、行尾、锚点」。本特征就是它的推广——从「一个文件、最近一次编辑、
单行记录」推广到「每个文件、每一次 run、一条链」。**两条机制不能并存**：同一个事实两个存放处是两份真相，
`undo_last_replace` 收敛成本特征 `restore` 的一种读法（不带参数 = 退最新那条）。若 `hashline-edit` 05/08
先落地，那两票的 `hashline_undo` 表就地退役。

## 决策

1. **触发点在执行缝，单位是一次 run。** 本仓没有「保存」事件——文件是 `write` / `edit` 直接写下去的。
   唯一的执行缝是 `harness.tools/run!`，所有工具都从那里过。**一次 run**（一个 RunAgentInput 到
   不再有工具调用为止）里对同一路径的多次改动**合成一条记录**，保留**最早的那份改前正文**。

   VS Code 靠 10 秒合并窗口解决同一问题（自动保存会刷出一串条目），本仓不抄那个常数：**run 边界就是
   「harness 刚做完的那件事」**，比秒数贴近语义，而且现成——`hook/*sink*` 里已经带着 `:run-id`，
   `hook/emit` 就是从工具线程里读到它的（`async/thread` 走 `thread-call`，会捕获并装上 thread binding
   frame，所以动态 var 过得了线程边界）。

   合并时**丢新的、留旧的**，和 VS Code 相反：它以「最新内容」覆盖末条，因为它的条目是改后态；本仓的
   条目是改前态，留着旧的那份才等于「退掉整个 run」。同一个合并窗口，两条语义各自取自己那一端。

2. **捕获与写入是同一个临界区，按解析后的路径串行。** 一趟 run 的工具调用在本仓是并发跑的
   （`harness.loop/drive!` 给每个调用一个 `async/thread`）。同一文件的两笔会这样交错：A 读改前态 →
   B 读改前态 → A 写 → B 写。两条记录都是 A 的改前态，**中间那个状态静默丢失**。这是数据丢失，不是
   体验问题——`hashline-edit` 09 从编辑那个方向撞上了同一件事（「按路径串行」），两票的要求是同一条。

   形状：缝里对带标记的工具调用 `history/with-capture`，它在一把按路径的锁里做三件事——落改前记录、
   跑工具本体、回来补上改后校验和。**没绑 sink 时它就是 `(f)`**：离线工具、replay、脚本化测试一字不动，
   与 hook 引擎同一条纪律（`hook/*sink*` 的 docstring 已把这条说透）。

3. **记录的是逐字节的副本，不是解码后的文本。** BOM、行尾、权限位原样带走，`hashline` 为撤销记录列的
   那几项（「正文、BOM、行尾」）在这里由「拷字节」白拿，不需要各自实现一遍。VS Code 的 `cloneFile`
   同理。

4. **内容进树，索引进库。** 正文落在 home 的 `history/` 树里；元数据进 `harness.db`。这条分工与
   `sessions.path` 配 `session-row` 完全同形——库说「有这一条」，树说「它多大、什么时候动的」——也守住
   `project-sidebar` 决策 2 那条线：**正文是记录（只追加、不被改写），不进库。**

   ```
   ~/.clj-harness/history/<hash(文件绝对路径)>/<millis>-<random><原扩展名>
   ```

   树的键是**文件**，不是项目。VS Code 也是这么做的（hash 掉 resource URI，与 workspace 无关），理由
   在本仓更硬：历史是文件的属性，一个文件可能被多个会话、多个项目碰过，而按项目分桶会在**会话改绑**
   （`POST /api/project`）时把同一份历史劈成两半。键在路径上，改绑就什么都不用搬——`http/move-log!`
   那套「一次会话一份日志」的必要性在这里不存在，因为历史本来就不是会话的。项目维度是**后来加的一层
   滤镜**（路径落在项目目录下），不是存储维度。

   库表 `history_entries`，一行一条：(canonical) `path`、`blob`（树里的相对名）、`created_at`、
   `thread_id`、`run_id`、`tool_call_id`、`tool`、`existed_before`、`post_checksum`、`post_size`。
   路径取 canonical 形式，与 `projects.canonical_path` 同一条规则：一个文件的两种拼法是一条历史，不是
   两条。

   加表要改 `db_test/no-table-in-the-store-mirrors-a-log` 的声明清单——这正是它存在的意义（一个表进这个
   库是要有人写下理由的）。理由就是上面那句：行是**可被淘汰改写的**（保留策略会删它），所以是状态；
   文件正文是**只追加**的，所以是记录。

5. **每条记录同时存「改后」的校验和，回退前先比。** 磁盘现值与记录里的改后校验和不一致 = 这次改动之后
   文件又被改过（人手动改的、`bash` 改的、另一个会话改的）。`hashline-edit` 08 已经裁定这里必须
   **拒绝而不是硬退**——硬退会无声吃掉别人后来的改动。

   **本特征补一个 08 没有的出口：强制回退是允许的，而且强制本身可回退。** 08 只有单行记录，退错了就
   没得救；现在是一条链，强制回退之前照常落一条「改前」记录，所以被覆盖掉的现值本身变成链上的一条。
   **有链才敢强制。**

6. **文件此前不存在 = 一条 `:absent` 记录，回退就是删掉它。** VS Code 的历史里没有这个情形（编辑器里
   打开的文件必然存在）；本仓 `write` 新建一个文件是最常见的动作之一，`existed_before` 必须显式记下来，
   否则「退回去」会变成「写入一份空文件」。

7. **回退本身也是一条记录。** VS Code 就是这样：还原内容 → 保存 → 落一条新条目。回退不是「弹栈」，它是
   一次普通写入，于是「退回去之后又想退回来」是一个自动成立的对称动作，不需要单独设计。

8. **两个面：模型一个工具，人一个端点。** 模型侧 `history`（列一条链）与 `restore`（把某条放回去；
   不带参数 = 最新那条，即 `undo_last_replace` 的语义）。人侧沿用管理边的既有形状
   （`/api/projects`、`/api/threads/<stem>/rebuild` 那一层）：`GET /api/history?path=..`、
   `POST /api/history/restore`。

   `restore` 带 `:fence-paths`（越界照旧 park 等审批），**不**默认 `:requires-approval`——本仓的边界是
   围栏与审批流，不是工具表；一个会话想要更强的闸，`session-require-approval!` 现成。

9. **配置进 `harness.edn` 的 `:history`，与 `:editing` 同形。** 两级、**逐键**合并（沿用 `editing` 为
   这条付过的那笔对 `harness-config` 浅合并的偏离，不重新发明）；坏配置指名失败，不静默回退默认值。

   ```edn
   {:history {:enabled true
              :max-entries-per-path 50      ;; VS Code 默认
              :max-file-size-kb 256         ;; VS Code 默认
              :total-budget-mb 512          ;; 本仓新增，见下
              :exclude ["node_modules" "target" ".cpcache" "*.class"]}}
   ```

   `:total-budget-mb` 是 VS Code 没有的。它的上限只按路径算，历史目录会随项目里的文件数无限长；本仓一个
   home 管多个项目，需要一条总额线，超了从**最旧**的那条开始淘汰（blob 一起删）。每个路径**至少留一条**：
   VS Code 的 `maxFileEntries` 允许 0（一条不留），而那等于静默关掉这个能力，不如让人去写 `:enabled false`。

10. **抓不到的东西要说出来。** `bash` 与 `eval` 都能写文件，而在缝里它们是**不透明的**——缝知道 `write` /
    `edit` 的目标路径（`harness.project/resolve-path` 已经给出解析后的绝对路径），不知道一条 `bash` 会碰
    哪些文件。三个选项：(a) 认了并写进文档与工具描述；(b) 每次 `bash` 前后整树扫描——那是一次全量拷贝，
    代价与项目大小成正比；(c) 只记「这些文件变了但我没有它们之前的样子」——没有正文的记录没法回退，等于
    噪音。

    取 (a)。理由与 `tool-toggles` 的「关闭不是禁止」是同一句：**能力边界必须说出来，装作覆盖了是更坏的事。**
    prompt.md 已经把 `read`/`write`/`edit` 定为改文件的正常路径，而 `prompt.md` 是冻结的资产、工具描述才是
    per-thread 的——所以这条边界写在 `bash` 与 `history` 的工具描述里。（`git` 覆盖的是 `bash` 改过而
    已提交的部分；local history 补的正是**没进过提交**、甚至**根本不在 git 仓库里**的那些改动。）

## 与参考实现的有意偏离

| VS Code | 本次 | 为什么 |
| --- | --- | --- |
| 保存事件触发（`onDidSave` + MOVE 文件操作） | 执行缝触发（`tools/run!`） | 本仓没有保存；而且「harness 改完」这个单位比「编辑器什么时候存盘」更贴近要回退的东西 |
| 记录**改后**的内容（`cloneFile` 存当时的文件） | 记录**改前**的内容 | 用途反过来了：要答的是「变回你动之前」，改后记录永远拿不到 harness 碰它之前的那份状态 |
| 10 秒 `mergeWindow`（同 source 才合并） | run 边界（同 run 才合并） | run 是现成的、语义更准的窗口；秒数是编辑器没有 run 概念时的替代品 |
| 合并时**覆盖末条**（留最新的内容） | 合并时**丢弃新的、留最早的** | 镜像：条目是改前态，留旧的那份才等于退掉整个 run |
| 每条 `<4 随机字符><ext>`，`entries.json` 索引 | 库表 `history_entries` + `<millis>-<random><ext>` | 库已经在了（`project-sidebar` 01），淘汰与合并是「一次提交多个事实」，正是库存在的理由 |
| 上限只按路径（`maxFileEntries` 50、`maxFileSize` 256KB） | 同 + `total-budget-mb` 512 | 一个 home 管多个项目，需要一条总额线 |
| 还原 = 覆盖，只弹一个确认框 | 校验和不一致就**拒绝**；显式强制才覆盖，且强制本身可回退 | 硬退会无声吃掉别人后来的改动；这条 `hashline-edit` 08 已经裁定，本特征只是补上「有链所以强制是安全的」这个出口 |
| 只在 `defaultUriScheme` / `vscodeUserData` / `inMemory` 上跟踪 | 任何路径都跟踪（未绑定会话也跟踪，无围栏） | 与「未绑定 = 恒等」的回归保证一致：没有绑定不该丢掉回退能力 |

## 非目标

- **不替代 git。** 这是一份本地的、按路径的、有上限的短历史；它不产生提交、不做分支、不做跨机器。
- **不跟踪 `bash` / `eval` 写下的文件**（见决策 10）。不做文件系统 watcher——`WatchService` 是另一个量级的
  东西（去抖、忽略规则、常驻资源），而本特征要答的问题在工具缝上就已经答完了。
- **不做跨会话的「谁改了什么」视图**，只做「这个文件的一条链」。会话维度是链上每条的元数据（`thread_id`
  / `run_id`），不是分组方式。
- 不改 `RunAgentInput`、不改 AG-UI 帧形状、**不新增 jsonl 行种类**。每次改动本来就落在该 thread 的
  tool_call + tool 结果行里，历史是可再生的推导物，不是新事实——与 `tool-toggles` / `eval-self-extension`
  的「一份真相源」一致。
- 不在这一版做侧边栏的 history 界面；先把端点做出来，界面单独一票（下面 09）。

## 验收主线

离线全量 `harness.test-runner` 全绿，基线按落地时的 main 算。端到端要覆盖三段真实路径（真实 HTTP 端点 +
脚本化模型）：

1. 模型 `write` 一个新文件、再 `edit` 它两次 → 只有**一条**记录（同 run 合并），回退后文件回到**不存在**；
2. 下一个 run 再改同一文件 → 链上两条；回退一次回到上一个 run 之后的状态，再退一次回到最初；
3. 改动之后外部把文件改了 → 回退**被拒绝且什么都没改**（磁盘、库、树三者不变）；显式强制 → 覆盖成功，
   且被覆盖的那份自己也进了链，可以再退回来。

以及：重启进程（清内存、只读库）后同一批记录仍可列、可回退。

## 状态

全部未开始。**01–08 是后端落地**，09 是界面。存储那一票（02）依赖 `project-sidebar` 01 的迁移链——
若它已落地，本特征只是往那条链上再加一张表。

| # | 票 | 阻塞于 |
| --- | --- | --- |
| 01 | `harness.history` 与 `harness.edn` 的 `:history` 块：逐键两级合并、默认值、坏配置指名失败 | — |
| 02 | 存储：库里加 `history_entries`、树上定 blob 命名、`db_test` 的声明清单跟上 | 01, project-sidebar 01 |
| 03 | 执行缝：`:snapshots-path` 标记、按路径的锁、`with-capture` 一个临界区、未绑 sink 时一字不动 | 02 |
| 04 | 记录的形状：改后校验和、`:absent`、同 run 合并、内容没变就不记 | 03 |
| 05 | 列一条链：`history` 工具 + `GET /api/history`（含路径按会话绑定重根） | 04 |
| 06 | 回退：校验和前置检查、拒绝、强制、回退本身落记录、建文件/删文件两个方向 | 05 |
| 07 | 保留：每路径上限、单文件大小上限、总预算、淘汰时 blob 与行一起删 | 04 |
| 08 | 边界写清楚：`bash`/`eval` 不覆盖进 `bash` 与 `history` 的工具描述 | 06 |
| 09 | 侧边栏里的文件历史（列链 + 一键还原） | 06 |

## 备注

- 参照实现：VS Code 的 `src/vs/workbench/services/workingCopy/common/workingCopyHistoryService.ts`
  （模型的条目/合并/淘汰）、`.../workingCopyHistoryTracker.ts`（触发与准入）、
  `src/vs/workbench/browser/workbench.contribution.ts`（五个设置的默认值：enabled true、
  maxFileSize 256KB、maxFileEntries 50、mergeWindow 10s、exclude）。
- 本仓已裁定的同族决策，落地时不要重新发明，直接引用：
  `hashline-edit` 08（改前正文 + 改后正文 + 校验和不一致就拒绝 + 文件被删可恢复）、
  `hashline-edit` 09（按路径串行、一个回合合成一次提交）、
  `project-sidebar` 决策 2（库装状态、文件装记录）、
  `harness.db` 的迁移链与 `db_test` 的两条元断言。
