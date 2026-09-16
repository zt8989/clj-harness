# spec: tool-parity —— 把工具集补到参考集

参考：一张 9 格的工具清单（`Read` / `Grep` / `Glob` / `Bash` / `Edit` / `Write` / `WebFetch` /
`WebSearch` / `TodoWrite`，其中 `Bash` / `Edit` / `Write` 三格带了红点）。参考的是**要有什么能力**，
不是像素，也不是那张清单上的权限标点。

**一句话**：本仓的工具集今天差四样——`glob` / `todo_write` / `web_fetch` / `web_search`；
补上它们，其中**任务清单进 sqlite**（`harness.db`），其余三个走既有的工具表与执行缝，一个字不改协议。

## 差距只有四个（先把账算清）

| 参考集 | 本仓今天 | 结论 |
|---|---|---|
| `Read` | `read`（两套模式同名，`:describe` 换脸） | 已有 |
| `Grep` | `anchor_grep`（`:hashline` 家族） | 已有 |
| `Glob` | —— | **本特征** |
| `Bash` | `bash` | 已有 |
| `Edit` | `:str-replace` 下是 `edit`；`:hashline` 下是 `replace` / `insert` | 已有，见决策 1 |
| `Write` | `write`（两套模式同名） | 已有 |
| `WebFetch` | —— | **本特征** |
| `WebSearch` | —— | **本特征** |
| `TodoWrite` | —— | **本特征**（进库） |

## 决策

1. **不把 `edit` 塞回 `:hashline`。** 参考集里 `Edit` 是一格，本仓的答案是按模式给不同的手：
   `:hashline` 用 `replace` / `insert`（锚点寻址），`:str-replace` 用 `edit`（精确串）。
   hashline-edit 已落地并明确裁定过"一次只装一套编辑实现"；为了对齐一张清单去推翻它，是拿一个
   已验过的设计换一个名字。`anchor_grep` 同理——`:str-replace` 下没有搜索工具，那一格的出路是 `bash`，
   `harness.editing` 已经写明了这件事。

2. **名字比票先立。** 四个名字是 `glob` / `todo_write` / `web_fetch` / `web_search`，与既有工具同一套写法
   （小写、多词用下划线），进 `CONTEXT.md` 那条"工具名的写法"。名字是**键**：前端的 `TOOL_ICONS` 与
   `subjectOf` 按名查表、`harness.editing/families` 按名判家族、测试名与 `:name` 参数按名对齐——
   `CONTEXT.md` 那句"不给它们起别名"在这个特征里是硬的，不是文风。

3. **`glob` 不属于任何编辑家族，所以两种模式都服务它。** 它列的是**路径**，而路径不是锚点：
   锚点只属于文件里的行，一条路径没有锚点可言。这就是它落在 `harness.glob` 而不是
   `harness.hashline.*` 底下的理由，也是它**不带** `:describe` 的理由（没有第二张脸）。

4. **`glob` 与 `anchor_grep` 共用一份 rg 管道**（新命名空间 `harness.rg`）：二进制叫什么、超时多少、
   它不在 PATH 上时那句指名拒绝——今天这三样埋在 `harness.hashline.grep` 里，第二个用户来了就该搬家。
   **JSON 事件的解析留在 `grep` 自己手里**：那是"命中行要带锚点"的知识，`glob` 用不着。

5. **`glob` 的顺序是路径序，不是 mtime 序。** 两次一样的调用必须给一样的答案——按修改时间排会让
   同一份代码树上同一个问题的答案，取决于谁刚碰过哪个文件。

6. **任务清单是状态，不是记录。** `todo_write` 每次**整表替换**（模型送来的是完整清单，不是增量），
   这正合 `harness.db` 的判据："能被改写的进库，不能被改写的留文件"。落库的形状也照库自己的先例：
   **一个会话一行，清单整存整取**——`hashline_snapshots` 的 `anchors` / `line_checksums` 就是这么放的，
   理由也一样（"每个值都是**一个整体**，写整取整、从不按元素查"）。逐条一行的表在这里买不到东西，
   只会把一次写拆成 N 条。
   `todos` 与它的列名要过 `db_test` 的两条元断言——**新表进库必须有人先写下它是状态还是记录**，
   那段话就写在 `declared-state-tables` / `declared-state-columns` 旁边，本特征照办。

7. **一个回合只许有一次 `todo_write`。** 两次各自"整表替换"，语义上不存在合并；而一个回合的工具调用是
   并发跑的，于是后写的赢、两个都报成功——那正是 `harness.tools` 的批那一节写明不许发生的事
   （"the alternative LOSES DATA"，而且两边都报成功）。所以同一回合的第二次被**指名拒绝**，
   并说清"一次消息一条清单"。判据用现成的：`register-turn!` 已经拿到一整个回合。
   同一会话的**顺序**回合（两条消息）不受影响，那是正常的推进方式。

8. **出网不 park。** `bash` 今天就能 `curl` 任何地址，而且不带审批。给 `web_fetch` 挂 `:requires-approval`
   会让这道坎看起来像护栏，实际从旁边一步就绕过去了——tool-toggles 自己写过的那句话：
   **关闭不是禁止**；装样子的边界比没有边界更坏，因为它让人以为有了。
   要这道坎的会话**自己装规则**：`session-require-approval!` 一行，或者一条 `PreToolUse` /
   `PermissionRequest` hook。机制已经在了（hook-engine 的 26 个点、审批 park/resume 都是现成的），
   策略就该待在那里。**这是一个可以一行推翻的决定**（`(assoc tool :requires-approval true)`）。

9. **`web_fetch` 的正文抽取是刻意的有损。** 不引新依赖：本仓唯一的二进制依赖是 sqlite，理由是
   "一次提交多个事实"必须有事务，而 HTML 解析器没有这个理由。所以抽取规则是小的、写明的：
   丢掉 `<script>` / `<style>` 的内容、块级标签换行、其余标签去掉、少量实体解码。
   `text/plain` 与 `application/json` 原样过；非文本类型（图片、PDF）**指名拒绝**并报出类型。
   抽出来的东西顶部带 `<title>`（若有）——模型要知道自己拿到的是哪个页面。
   代价明写：这是一个**文本抽取器，不是渲染器**，JS 渲染的页面它拿到的是空壳。

10. **上限沿用既有数字，不新发明。** 正文按 `anchor_grep` 的 `max-bytes`（51200 字节）截断并**标注**
    （截断是信息，不是错误——`grep` 就是这么做的）；`glob` 沿用 `anchor_grep` 的 `default-limit`（100 条），
    并报出"共 M 条，这是前 100 条"。两个数字都指回它们的出处，别的地方改一个数字这里就跟着动。

11. ~~**搜索只有一个厂商，键名固定。** `HARNESS_SEARCH_API_KEY`~~ ← **2026-09-16 被牛总推翻，见文末「复议」：
    现在是三个厂商、三个键、哪个在哪个答。** 这一条里**没有被推翻的部分**：解析顺序与 provider 的
    `HARNESS_API_KEY` **完全相同**（配置家的 `.env` 优先，然后环境变量）；这条解析今天写在
    `harness.providers` 里（`parse-dotenv` + `api-key` 私有函数），本特征把它抽到一处
    （`harness.home/env-value`，文件归 `home` 管），两个用户共用——**不复制一份**。
    缺键 = **指名拒绝**，说清要设哪个变量名；不静默返回空结果。
    搜索**不 park**（同决策 8），理由更硬：它的目的地在一条人写的配置里，而不是每次调用由模型挑。

12. ~~**第二个搜索厂商、自定义端点：另一张票。**~~ ← **同样被「复议」推翻：本特征里就落了三个厂商。**
    这一条里**没有被推翻的**：每家厂商的线写在一处（`harness.web.search`，端点与请求形状都在那一张表里），
    客户端 `harness.web` 不知道任何厂商的存在——**这一条正是三厂商能落得这么轻的原因**，
    加第二家、第三家确实是"加一节"，没有改客户端。

13. **库的边界不动。** `todos` 是**追加一个具名步骤**（`{:name "todos" :present? … :run …}`），
    已落地的五步一个字不改（链是 append-only，"改名即重跑"）；`user_version` 依旧只是遗迹、不是闸门。
    老库打开时自动多这张表，**没有"太新"这种拒绝**。

14. **不加帧、不加事件种类、不改协议。** 四个工具都走既有的三相审计行
    （`:tool/pre-execute` / `:tool/execute` / `:tool/post-execute`，零 AG-UI 帧）。
    任务清单在对话里就看得见——工具调用与它的结果本来就在帧里——所以**不做** UI 面板、**不加**端点。

15. **没有会话在作用域里时 `todo_write` 指名拒绝。** 清单的归属是会话（`thread_id` 就是它的主键），
    没有会话就没有这份清单该挂给谁；不像 `session-configure` 那样"警告一句然后落到进程级槽位"——
    那个槽位是一个会话的设置，而这里会变成**一份没有名字的清单**，下一次谁也读不回来。

16. **前端只有两张按工具名开的表要动**（`TOOL_ICONS`、`subjectOf`）。没进表的工具今天**也能渲染**
    （扳手图标 + 第一个字符串参数，这是那张表刻意留的口子），所以本票交付的是**可读性，不是可用性**：
    四行新工具在对话里应当像它邻居一样一眼看懂。

## 非目标

- **不给 `bash` / `write` / `edit` 加审批。** 参考图里那三格带红点，若指"默认 park"，那是**另一张票**
  （见开放问题）——它改的是既有工具的默认行为，不是补能力。
- 不把 `edit` 塞回 `:hashline`，不给 `:str-replace` 补一个搜索工具。
- 不做第二个搜索厂商、不做可配置的搜索端点、不做 SearXNG。
- 不做 `web_fetch` 的渲染 / JS 执行 / `meta refresh` 跟随；不做多页爬取。
- 不做任务清单的 UI 面板与 HTTP 端点（决策 14）。清单的读侧是 `harness.todos` 的一个函数，
  供 `eval`、测试与将来那个面板用——**数据不是只写的**，但今天没有别的消费者。
- 不改 `AG-UI` 形状、不改 jsonl 行种类、不改 `harness.event` 的十一种事件。
- 不预判 `layer-layout` 的分层：新命名空间（`harness.rg` / `harness.glob` / `harness.todos` /
  `harness.web` / `harness.web.search`）都是**能力**，分层落地时按 `cap` 落位。

## 开放问题（要牛总拍，不影响本特征开工）

1. **红点 = 默认 park？** 若是，则是"给 `bash` / `write` / `edit` 挂 `:requires-approval`"或
   "装一条 `PreToolUse` 规则"，与本节 8 / 12 条无关，另开一票即可。今天的现状是：这三个工具
   **只在路径越出围栏时** park（`bash` 连那也不查——命令内容永不判定）。
2. **搜索厂商。** 选 Brave 是因为它的线最简单（`GET` + 一个头 + JSON），但它的 key 要信用卡。
   若换成 Tavily / 自建 SearXNG，本票的 12 条就是那张"第二个厂商"的票，接口形状另定。

## 验收主线

一条会话（离线，脚本 provider），端到端：

1. 模型依次调 `glob` / `web_fetch` / `todo_write`（`web_fetch` 打测试自己起的本机服务），
   四条工具行在真前端里读得出来：图标不是扳手、摘要说的是这件事（截图存票面）。
2. `todo_write` 写完之后**另起一个 JVM**打开同一个 home，能读到那份清单——这是"真的进了库"
   唯一的证法。先例是 `project_test/a-binding-survives-a-real-restart`；
   它的**取答案方式是文件而不是 stdout**（见"状态"里那条环境坑）。
3. `tools_test/specs-expose-every-base-tool` 的两份名单变成 14 条 / 11 条，且两种模式仍然**只差编辑工具**。
4. `db_test` 两条元断言带上 `todos`；`todos` 的表名与列名过 forbidden 正则；老库（五步）打开后
   自动多出这张表，且 `PRAGMA user_version` 不参与任何判断。
5. 两套全量：后端 0 failures（基线见"状态"，本机另有两条**与本特征无关**的环境失败），
   `cd ui && npm test`（`EXPECTED_CASES` 若动就有意地动）+ `cd ui && npm run build`。

## 跨特征对照

- **`hashline-edit`（已落地）**：`harness.editing/families` 表**不动**——四个新名字都在家族之外，
  所以两种模式都服务它们（这正是"两种模式只差编辑工具"那条断言要抓的东西）。
- **`mcp`（已立票，未开工）**：MCP 落地后 `web_search` 本来可以是某个服务器的事。本特征仍把它做进内核，
  因为参考集是"今天就要能用"；MCP 01 的 `:source` 泛化落地后，四种来源走同一个缝，本特征不用改一个字。
- **`hook-engine`（已立票，未开工）**：出网的坎属于它。本特征**不**新建 park 机制，
  只在文档里指过去（决策 8）。
- **`trajectory`（已立票，未开工）**：`model/start` 行里的工具表会多出四个名字，
  轨迹"照发出的样子"显示，读侧不用改。
- **`layer-layout`（已立票，未开工）**：见非目标最后一条。
- **`tool-toggles`（已落地）**：被关的工具仍然**可见但调用被拒**——四个新工具自动继承这条语义，
  不需要任何代码。
- **`CONTEXT.md`**："工具名的写法"那一行要加四个名字；"库装状态、文件装记录"那一段要加一句
  **任务清单**是状态。

## 交付顺序

`01` 立名与立词（谁都要用）→ `02` / `03` / `04` 三条**互不相干**，可以同时开 →
`05` 靠 `04` 的客户端与拒绝词汇（它把 `harness.web` 当既有物用）→
`06` 靠四条都能跑（没有工具就没有行可读）→ `07` 收口。

`02` / `03` / `04` 之间**没有**边，这一点是刻意的：它们各占一个能力，各自能单独落地、单独验收；
为了"看起来有并行"而把它们并成一张大票，就是让一个上下文窗口里同时做路径列举、库迁移与出网客户端。

## 票清单（`.scratch/tool-parity/issues/`）

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 词与名：四个工具名与"任务清单" | — | `CONTEXT.md` 立名与立词；四个名字从此不许有别名 |
| 02 | `glob`：按名字找文件 | 01 | `harness.rg` 抽出来；`harness.glob` + 工具 + 拒绝 + 测试 |
| 03 | `todo_write`：本会话的清单进库 | 01 | `todos` 迁移步骤与表；`harness.todos`；工具与整表替换；`db_test` 两条元断言 |
| 04 | `web_fetch`：取回一个 URL 的正文 | 01 | `harness.web`（客户端 + 有损抽取）+ 工具 + 本机服务的测试 |
| 05 | `web_search`：搜索并拿回结果 | 04 | `harness.home/env-value` 抽出；`harness.web.search` + 工具 + 哨兵值测试 |
| 06 | 前端：四条新工具的步骤行读得出来 | 02, 03, 04, 05 | `TOOL_ICONS` 与 `subjectOf` 各加四条；真机截图 |
| 07 | 收口：现状文档与全量验收 | 02, 03, 04, 05, 06 | `CONTEXT.md` / `docs/architecture` / `README` 跟上；两套全量 + 端到端证据 |

## 状态

**已落地**（七张票全部完成，分支 `tool-parity`，worktree `.worktrees/tool-parity`）。

**基线（立票当日实测，`main` @ `3ac23d8`）**：
`clojure -M:test -m harness.test-runner` → `Ran 607 tests containing 9774 assertions. 2 failures, 0 errors.`
两条失败**都是本机环境**，与本特征无关，位置与原因如下——

- `project_test/a-binding-survives-a-real-restart` 的两个断言（`project_test.clj:344` / `:347`）。
  它 fork 一个 JVM 并**逐字比较它的 stdout**，而这台机器的 JDK 25 在 sqlite-jdbc 加载原生库时
  往 stdout 打四行 `WARNING: A restricted method in java.lang.System has been called … / Use
  --enable-native-access=ALL-UNNAMED …`。列表里任何一条新测试若也 fork JVM 比 stdout，会撞同一面墙：
  **因此 `03` 的"另起一个 JVM 读清单"按"子进程把答案写进文件、父进程读文件"来写**，
  这样 JVM 自己的启动噪音不可能被当成答案。

前端基线：`cd ui && npm test`（11 cases）与 `cd ui && npm run build` 都过。
报数一律带上**分支与提交**（基线随分支变）。

---

## 落地记录（2026-09-16，分支 `tool-parity`）

**提交**：`a3b24a8`（实现，41 个文件）与 `a544559`（把 `docs/architecture.md` 的快照点指向它——
快照点不能指向自己所在的提交，所以由紧跟其后的一次小提交写下，先例是 `ded04d5`）。

七张票一次交付。**落地的数**：

- `clojure -M:test -m harness.test-runner` → `Ran 664 tests containing 9996 assertions. 2 failures, 0 errors.`
  与基线相比 **+57 tests / +222 assertions，没有新增失败**；那 2 条仍是上面那条 JDK 25 环境问题
  （位置、原因逐字不变）。
  落地过程中共跑了三轮全量，逐轮的失败名单如下——**这组数比一句「全绿」有用**：
  - 第一轮（改完四张票、还没删掉 `providers` 里那个死掉的 `clojure.string` require）：
    2 failures，就是基线那两条。
  - 第二轮（提交后的树）：4 failures = 基线那两条 + `http_test/the-projects-listing-joins-the-store-with-the-disk`
    的两条断言（`.length` / `.lastModified` 与列表里那份对不上，差 433 字节——**run 的最后一行审计
    在 SSE 流关闭之后才落盘**，而那条用例紧接着就比）。本特征**不写**那条路径上的任何东西。
  - 第三轮（隔一会儿重跑同一棵树）：2 failures，又是基线那两条。
  所以那条 http_test 是**既有的一次性竞态**（本仓的基线纪律就是「比失败名单，不只比绿不绿」），
  与本特征无关；三轮里的**测试数与断言数完全一致**（664 / 9996）。
- `cd ui && npm test` → 11 passed（`EXPECTED_CASES` **没动**，理由见下）；
  `cd ui && npm run build`（`tsc --noEmit` + vite build）通过。
- 新增测试命名空间四个：`harness.glob-test`（13 tests / 35 断言）、`harness.todos-test`（14 / 49）、
  `harness.web-test`（17 / 72）、`harness.web-search-test`（13 / 64），都在
  `harness.test-runner/test-namespaces` 里。

### 每张票交付了什么

| # | 交付 | 证据 |
|---|---|---|
| 01 | `CONTEXT.md` 立四个工具名 + 立「任务清单」一词；词形（小写、下划线）写进那一条 | 只动一个 md 文件 |
| 02 | `harness.rg`（二进制名 / 超时 / 不在 PATH 上的点名失败）+ `harness.glob` + `glob` 工具 + `harness.glob-test` | 13 tests；`anchor_grep` 侧**一条断言都没改** |
| 03 | `todos` 迁移步骤与表 + `harness.todos`（校验、整份替换、渲染）+ `todo_write` + `harness.todos-test` | 14 tests，含**另起一个 JVM** 读回清单 |
| 04 | `harness.web`（客户端 + 重定向链 + 字节上限 + 解码 + **有损抽取器**）+ `web_fetch` + `harness.web-test` | 17 tests，全部打本机服务 |
| 05 | `harness.home/env-value` / `parse-dotenv` 抽出 + `harness.web.search` + `web_search` + `harness.web-search-test` | 13 tests，含哨兵值与"没配就先拒绝、不发请求" |
| 06 | 前端两张表的四条：`glob` / `todo_write` / `web_fetch` / `web_search` | `.scratch/tool-parity/evidence/`：全景、四行特写、**每一行展开后**的参数与结果（六张，真浏览器 + 真后端） |
| 07 | `CONTEXT.md`、`docs/architecture.md`、`kernel.md`、`home-and-storage.md`、`client.md`、`providers.md`、`README.md` 跟上 | 本文件 + 上面两套全量的数 |

**真机证据怎么跑的**（下一个读者要复现的话）：这台机器的 8080 被主仓的 dev 后端占着，
而前端的 `AGENT_URL` 是**写死** `http://localhost:8080/` 的，所以演示后端起在 **8099**，
浏览器里用 `addInitScript` 把 `fetch` 的 `localhost:8080` 改写成 8099（既有做法）。
`web_search` 那一步要一家厂商，所以另起了一个本机假厂商（8123，Brave 形状的 JSON），
并用 `alter-var-root` 把 `harness.web.search/endpoint` 指过去——**这正是那个 var 存在的理由**。

### 与票面的差异（逐条）

1. **`todo_write`「第二次被拒」改成「两条都不落盘」。** 票面写的是"同一回合的第二次被指名拒绝"，
   但一个回合的工具调用是**并发**跑的——"第二次"不是一个存在的东西，无从拒起。两条都对同一份清单说
   "整份替换"时，消息本身是歧义的，所以**两条都不写**并各回一句"一条消息只发一次"。
   这是确定性的答案，也与 `harness.tools` 批那一节"重叠就整批拒绝"同一个形状。
   落点：`CONTEXT.md`、工具描述、`harness.tools/sole-call-of-its-name?` 的 docstring、
   测试名 `a-message-with-two-todo-writes-writes-neither`、`docs/architecture/kernel.md`。

2. **上限常数各住在自己的命名空间里，没有共用同一个 var。** 票面说 `glob` 的上限"是 `anchor_grep` 的
   `default-limit`"、`web_fetch` 的"沿用 `anchor_grep` 的 `max-bytes`"，读起来像要共用。
   落地是**同一个数字、各自的常量、docstring 里指名出处**：`harness.glob/default-limit`（100）、
   `harness.web/max-bytes`（51200）。理由是本仓既有的先例——预算跟着**那个工具的答案形状**走
   （`harness.tools` 里 `eval` 的 `clip` 8000、`harness.hashline.grep` 的 `max-bytes` 51200 都是各自声明的），
   而为了让 `glob` 与 `web` 能引用 `harness.hashline.grep` 的常量，会把两个**与锚点无关**的命名空间
   硬拽到锚点那个命名空间下面。代价明说：改一个数字要记得看另一处。

3. **`rg` 的"没装"判据从字符串改成退出码。** 票面只要求"`rg` 不在 PATH 上是指名拒绝"。
   落地时发现既有那句判断（找 `No such file or directory`）**在本机永远不成立**（bash 对不存在的程序说
   `command not found`，退出 127），而且在**别的**平台上会**误判**——`rg` 对一个不存在的搜索根说的
   正是 `No such file or directory`，于是"拼错的路径"会拿到"去装 ripgrep"这句话。
   所以判据改成**退出码 127**（加上 `command not found` 兜底），两条都各有测试钉住
   （`a-missing-ripgrep-is-refused-by-name` 与 `a-search-root-that-does-not-exist-blames-the-path-not-the-install`）。

4. **`api-key-source` 只借用了半份。** 票面说"`providers` 改用它、不复制一份"。`api-key` 确实改成了
   一行 `home/env-value`；但 `api-key-source`（报告"有没有、来自 .env 还是环境"）**不能**走 `env-value`
   ——那样它手上就有了那个值，而它的 docstring 明写它不读值。所以它改用 `harness.home/parse-dotenv`
   （同一个解析器，公开出来就是为这个第二个读者），优先级那两行仍是它自己的。
   这是"共用解析器、不共用取用口"，与票面的字面略有出入。

5. **"只碰两处清单"低估了：同一份事实被抄在六处。** 票面 07 预期只动
   `tools_test/specs-expose-every-base-tool` 的两份名单、`db_test` 的两条元断言、`test_runner` 的列表。
   实际还要动：`editing_mode_tools_test` 的 default 名单与两条 `non-editing-names`、
   `db_test` 另外两条表清单、`hashline_store_test` 的两条表清单——六处多余的副本，一处不加就是红的。
   它们**不重复**（每条问的略有不同：模式默认、非编辑工具集合、建库后的表清单、迁移后的表清单），
   所以没有合并，只是记下这个数字。

6. **前端没有加用例，`EXPECTED_CASES` 仍是 11。** UI 套件驱动的是**真后端 + `@ag-ui/client`**，
   断言的是帧与消息，而 `subjectOf` / `TOOL_ICONS` 是**渲染函数**，套件摸不到它们。
   为它们写组件级测试要引入 jsdom 与组件挂载，与这套"从外面驱动真服务器"的形状是两回事。
   所以这一票的证据是**真机截图**（六张，见 `evidence/`），而 `npm test` 的 11 条**有意不动**。

7. **七张票是一个提交。** 仓库的惯例是"一张票一个提交"（`spec.md` 的落地记录里记每张票的提交号），
   这里做不到：`src/harness/tools.clj`、`test/harness/tools_test.clj`、`test/harness/editing_mode_tools_test.clj`
   被**四张票交错**修改（四个工具注册、两份名单各加四次）。按票拆提交会让中间的每一个提交
   都带着**对不上的名单**——也就是红的——比一个提交更坏。所以七张票一次落地，本记录就是它们的票面。

### 看过一次、没能复现的一件事（留给下一个读者）

`harness.web-test` 在**迭代过程中**有一次跑出两条失败：seam 那条用例里的 `web_fetch` 拿到一个
HTTP 404（一个**没有**内容类型的 404），而同一轮里 `/latin1` 的 handler 正抛异常（`byte` 转型）、
`/slow` 的 handler 正被中断——两处都是当时**夹具自身的 bug**，随后修掉了。
修完之后**连跑三次全绿**，之后的每一次全量（含两轮 664 条）也都是绿的，所以没有再追。
症状留在这里：若将来又见到"本机服务回一个没有类型的 404"，那是 http-kit 客户端连接池复用了一条
已经死掉的连接，方向在那个池子，不在这个工具里。


---

## 复议（2026-09-16，牛总裁定）：搜索厂商从一家变三家

**推翻了上面决策 11 与 12 的第一句**（那两处已加删除线，并留住了各自没被推翻的部分）。
牛总的裁定原话：**websearch 支持三个 provider，自动从环境变量中找，也支持 .env 配置**——
Brave（默认首选，`BRAVE_API_KEY`）、Exa（`EXA_API_KEY`）、Tavily（`TAVILY_API_KEY`）。

### 落地成了什么

- **一张表就是全部**：`harness.web.search/vendors`，按优先级 Brave → Exa → Tavily。
  每条是 `{:name :env :label :send :read}`——**一个厂商就是一个请求形状加一个响应形状**，
  两者之间没有任何公共协议可抽象，所以没有"厂商插件系统"，第四条就是第四条。
- **哪个键在，哪个答**：`chosen` 逐个 `home/env-value` 找，第一个非空的赢。
  **没有开关**：一个会话要换厂商的办法就是设哪个键；再加一个"选厂商"的配置键，
  它唯一的本事就是跟"哪个键在"这个事实不一致。（若三个键都在、而你想用第三个，
  现在的答案是"那就别设前两个"——这一点写在这里，是因为它是这个设计的代价。）
- **键名不可配**：三个变量名是固定的，没有 `harness.edn` 条目，也没有"env 变量名由配置指定"这一层。
  与 provider 的 `HARNESS_API_KEY` 同一条纪律，走同一个 `harness.home/env-value`。
- **`harness.web` 长出 POST**：Brave 是 GET（query 在 URL 里、键在自己的头里），
  Exa 与 Tavily 是 POST（JSON body，键分别是 `x-api-key` 与 `Authorization: Bearer`）。
  所以 `web/call` 取代了 `web/get-text`：**GET 与 POST 走同一扇门**，因为决定"这次调用是什么"的东西
  ——超时、手工跟随并封顶的重定向链、字符集规则、三种指名拒绝——两者完全相同，只有方法、body 与调用方的头不同。
  顺带补上了标准语义：**307/308 保留方法与 body，301/302/303 变成没有 body 的 GET**
  （把一个 POST 的 body 带过 303，正是"查询跑到一个只想指向答案的地址去"的原因）。
- **一句话说清是哪个厂商答的**：答案里带 `(via brave)` / `(via exa)` / `(via tavily)`。
- **Exa 的"摘要"要自己讨**：它默认只回标题与 URL，正文要 `contents.text` 才给；
  一个只有链接的结果没什么用，所以请求里就带上（并被 `snippet-chars` = 600 绑住，
  因为 Exa 那段是**页面的切片**而不是摘要，想要整页的模型有 `web_fetch`）。
- **三个键全都没有时的拒绝**一次说清三件事：三个变量名、配置家的 `.env` 是放它的地方、
  以及**顺序**（谁在谁赢）。而且**一个请求都不发**。

### 验证与数

- `harness.web-search-test` 从 13 tests / 64 断言长到 **16 tests / 141 断言**：三厂商各自跑一遍
  "结果按序回、三个字段都读到、请求形状对（方法/body/键放哪）、空结果、形状不认、
  状态码、哨兵不泄漏"，之外加"哪个键在哪个答"与"空键不算键"。
- 后端全量：**667 tests / 10073 assertions**（比上一轮 +3 / +77），失败名单与上一轮**逐字相同**
  （仍是本机 JDK 25 那两条）。
- 文档同步：`README.md`（那一行改成三个键与顺序）、`docs/architecture.md`（模块地图）、
  `kernel.md`、`home-and-storage.md`、`providers.md`（"秘密的取用口"现在有四个用户）。
- `docs/architecture.md` 的快照点指向本次落地的提交（同一条两步法：先落实现，再让快照点指过去）。

### 这一轮**没有**改的

- `web_fetch` 一个字没动（除了它走的 `fetch` 多了方法/body 两个参数）。
- 三个厂商的**端点**仍不是配置：写死在 `endpoints` 那张表里（是 var，理由只有一个——
  测试把它指向自己起的服务）。允许配置一个不同的 host，只会让"按这家厂商的形状解析"这件事悄悄对不上。

### 证据重拍（同一轮，2026-09-16）

改了传输层（`web/call` 取代 `get-text`）之后，`.scratch/tool-parity/evidence/` 那六张**重跑了一遍**
（四条行仍然读得出来，而且 `web_search` 那条的结果现在带 `(via brave)`）。
下一次要复现的人会撞到两件事，先写在这里：

1. **两个端口都被别的会话占着。** 8080 是主仓的 dev 后端，5173 是 `skill-picker` worktree 的 vite
   （同一台机器上的另一个会话在跑）。所以演示后端起在 **8099**、前端起在 **5199**。
2. **CORS 只认 `http://localhost:5173`**（`harness.http/ui-origin`，而且是**加载时**烘进那张
   `cors` 映射的，`alter-var-root` 改 `ui-origin` 已经来不及）。前端换端口之后浏览器的预检就被拒
   （`net::ERR_FAILED`），所以那个一次性浏览器 profile 用 `--disable-web-security` 起——
   取证用的临时 profile，不是改代码。

`web_search` 那一步仍然用本机假厂商（8123，Brave 形状），端点靠 `alter-var-root` 指过去；
这次键是真的放进配置家 `.env` 的 `BRAVE_API_KEY`（三厂商之后，**这就是选它的办法**）。
