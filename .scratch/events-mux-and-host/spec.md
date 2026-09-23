# spec: 下行搬到 WebSocket —— `events.mux` 与 `events.host`

主人定的方向（2026-09-23，参照 DSH 的架构笔记）：**浏览器的下行载体从 SSE 换成 WebSocket**，因为 SSE
长期占用连接槽位，会和同源标签页、插件资源与普通 RPC 争抢；上行调用仍走 HTTP，socket **只下行**。

这个仓今天有三条 SSE 流，全都是下行：

| 今天 | 是什么 |
|---|---|
| `POST /api/agent` | 一轮 run 的 AG-UI 帧，挂在 POST 的响应上 |
| `GET /api/threads/<stem>/feed` | 一场会话的窗口（尾页 + 增量推送） |
| `GET /api/threads/<stem>/follow` | 一个子 agent 的帧 |

**为什么要换**：host 永不被回收，而**隐藏**的 host 也照旧攥着自己的 feed（`useWindowFeed` 不看
`visible`），服务端那边「一条连着的窗口就是一个 pin」（`sessions/evictable?`）。于是每开一场会话就多占
一个连接、多钉住一份内存，标签页一多就撞浏览器的同源连接上限——这正是 DSH 遇到的那件事。

**不换的是什么**：窗口/游标那套代数（`baseSeq`/`hasMore`/`generation`/`since`）、拉取（尾页、补页、
`sofar`/`rebuild`）仍走 HTTP；ADR 0003 决策 1–9 全部照旧。变的只是**推的载体**。

## 两条下行类别（照 DSH）

- **`events.mux`** —— 每场会话的帧：run 的 AG-UI 帧、子 agent 的帧、窗口条目。一条 socket，帧按
  `threadId` 标。
- **`events.host`** —— 家级事实：会话出现/改名/发送时间/跑起来/停下、项目增删、run 注册表变化。
  侧栏据此实时更新。

## 订阅：是 HTTP 事实，不是 socket 消息

socket 只下行，所以**订阅在 HTTP 层完成**：

1. **握手那一刻**：`GET /api/events.mux` 的 Upgrade 请求带上要订阅的会话与各自游标。
2. **之后变化**：用普通 `POST /api/*` 告诉服务端「我现在持有/不再持有这一场」。
3. 服务端为**这条连接**维护一个订阅集合，连接断开即释放。
4. **推帧时按集合过滤**——DSH 当前的实现是全量广播给每个连接（Discussion #1316 指出的卡顿根因之一），
   **这一格不许重演**。

「服务端不记谁在订阅」这条 ADR 0003 决策 7 的措辞要按「**除了活着的连接**」改准：连接级的订阅集合
是允许的，进程不持有连接之外的订阅状态。

## 五票

| # | 票 | 依赖 | 交付 |
|---|---|---|---|
| 01 | 一条 `events.mux`，先承载窗口帧 | 无 | 每页一条下行 socket 承载会话窗口；握手 URL 声明持有集与游标，HTTP 更新，服务端按连接过滤。SSE `feed` 并行保留（expand/回滚）。记录决策、修订 ADR 0003。 |
| 02 | `events.host`：家级事实，侧栏实时 | 01 | 第二条 socket；侧栏行出现/改名/点灯/摘灯不再靠刷新键。 |
| 03 | run 帧改走 `events.mux`，`POST /api/agent` 只起跑 | 01 | 发送只起跑并回执；AG-UI 帧改从 mux 到达；换掉客户端「从 POST 响应读 SSE」的读取器。 |
| 04 | subagent 的 `follow` 并入 `events.mux` | 03 | 第三条流退休进 mux。 |
| 05 | 收口：删掉三条 SSE 路由与旧读取器 | 01,02,03,04 | contract；文档与 ADR 收口。 |

票面见 `issues/01-…md` … `issues/05-…md`。

另一个 feature `.scratch/refreshed-turn-keeps-growing/`（窗口原地更新、动作条）**不受影响、照旧要落**：
把同 id 的新版本当更新是**载体无关**的，那一轮要长出来仍然需要它。

## 非目标

- **不改窗口/游标的语义**：帧的形状、`seq`/`generation`/`since` 的规则、拉页与修理都不动。
- **不改上行**：`POST /api/*` 仍旧是 HTTP。
- **不改记录格式**。
- **不为列表新增轮询**：host 流是推的。

## 落地（2026-09-23）：票 01

**一条 `events.mux` 承载窗口帧，页面侧不再每场会话一条 SSE。** 落的形状：

- **服务端**：`harness.edge.mux` 是连接级注册表（token → 连接与它的订阅集合；`subscribe!` 注册一个
  门铃到 `sessions/watch!`，`detach!` 是 close handler 收回去的东西）；`GET /api/events.mux`
  按握手 URL 的 `?subscriber=<token>&sessions=<json>` 建连接并逐场订阅，
  `POST /api/events.mux/subscribe` 在 socket 活着时增删（socket 只下行，发不了订阅）。
  **按集合过滤**：没订阅的会话没有 watch，ring 到不了它——写了两条用例钉住。
- **客户端**：`lib/mux.ts` 是**页面级单例** socket（一页一条，不论持有几场会话），按 `threadId`
  分发窗口帧；重连在握手 URL 重新声明整份集合，`POST` 只在活着时更新。`app.tsx` 的窗口跟随
  （`useWindowFeed`）从 `feedThread` 换成 `subscribeMux`，窗口/游标代数一个字没动。
- **决策**：新增 `docs/adr/0004-the-downlink-is-websocket.md`；0003 决策 7 的措辞按「服务端不记
  **连接之外**的订阅」改准。`docs/architecture/edge.md` 加两条路由、`client.md` 加 `mux.ts`。

**验证**：

| 层 | 做了什么 | 结果 |
|---|---|---|
| 后端 | `clojure -M:test -m harness.test-runner harness.edge.mux-test` | 7 tests / 22 assertions / 0 failures（`a-connection-hears-…and-no-others`、`a-second-connection-…is-not-cc-d` 钉住过滤；`detaching-…`、`a-stale-generation-…`、订阅路由的 200/404/400） |
| 后端全量 | `clojure -M:test -m harness.test-runner` | **1147 tests / 13223 assertions / 0 failures**（基线 1140 / 13198） |
| 前端 | `npm test` / `npm run typecheck` / `npm run build` | 126 用例过（新增 `mux` 两条）、tsc 与 vite 都过 |
| 真浏览器 | `node scripts/dev.mjs --scripted .scratch/session-after-refresh/evidence/09-go.json --ui-port 5319` + `node .scratch/session-after-refresh/walkthrough.mjs http://localhost:5319/` | **ALL GREEN**——刷新落回还在跑的那一场，窗口（`runningSpinners: 1`、`foldedTurns: 0`、Stop 在位）经**新的 WebSocket** 到达；刷新后按停、跑完能发，都照旧 |

**一处与票面的出入，说清楚**：被**隐藏**的 host **不**退订。票面「打开 / 切走 / 关闭一场会话经 HTTP
更新集合」里的「切走」在实现里**没有**退订——因为侧栏每行的 `running` 这一格仍来自那个 host 的窗口
状态（`onStatus`），退订会让它在别的窗口开跑时变旧。连接数不受影响（一页仍只有一条 socket），
被订阅场的**内存 pin** 与「只订阅屏幕上的那一场」这一格属于 `events.host`（票 02）的收口。

## 落地（2026-09-23）：票 02

**第二条 socket：`events.host`，侧栏的列表是推的。** 形状：

- **服务端**：`harness.edge.host` 是家级门铃（`watch!`/`unwatch!`/`ring!`，一个 watcher 抛了不影响
  别人）；`GET /api/events.host` 一开就发整份列表（`projects-body`，与 `GET /api/projects` 同一份，
  加 `:type "projects"`），此后每次家级事实变化再发一份。**没有订阅**——每个连接要的是同一份清单。
  门铃从**变更点**响，不从定时器、也不从每条条目：`run-started!`/`run-finished!`（进程内 run 注册表）、
  `remember-send!`（标题与发送时间）、`sessions-post`、`project-post`、`add-project-post`、
  `remove-project-post`、`archive-post`（后六处走 `rung` 包住成功答复）。
- **客户端**：`ui/src/lib/host.ts` 是页面级单例 socket（第二类、第二个地址，`downlinkUrl(path, params)`）；
  `sidebar.tsx` 挂上它，收到列表就 `setListing` + `onListed`——刷新键原样留着做手动兜底。
- **顺带补的一格**：侧栏的项目会话行与任务行原来只读 `statuses`（本页自己的 host），**不读列表里的
  `running`**，所以别的窗口跑起来的行不会点灯。现在两处都按「本页注册表 OR 列表的 `running`」取或，
  与收起的归档块那行（`session.running || …`）同一判据——列表里的 `running` 仍来自服务端的 run 注册表。

**验证**：

| 层 | 做了什么 | 结果 |
|---|---|---|
| 后端 | `clojure -M:test -m harness.test.runner harness.edge.host-test` | 4 tests / 11 assertions / 0 failures（ring 达每个 watcher、抛出的不挡别人；开流即发列表、变化再发、关闭收回；`sessions-post` 真的响门铃） |
| 后端全量 | `clojure -M:test -m harness.test-runner` | 见本轮全量行 |
| 前端 | `npm test` / `npm run typecheck` / `npm run build` | 127 用例（新增 host 地址一条）、tsc、vite |
| 真浏览器 | `node scripts/dev.mjs --scripted .scratch/events-mux-and-host/slow.json --ui-port 5319` + `walkthrough-host.mjs` | **ALL GREEN**：两个窗口都先开着，A 发送 ⇒ B 的侧栏**不刷新**就出现那一行、带「运行中」、跑完自己摘；另一个写者 POST 一个项目 ⇒ B 的侧栏自己出现该项目（截图 `evidence/host-01-two-windows.png`） |

## 进行中（2026-09-23）：票 03 的 expand 半边

**run 的帧已经上了下行，POST 的 SSE 还在流。** 票 03 是一次载体迁移，不是窗口代数的改动，所以
按展开-收口做——先让两半都在：

- **服务端**：`harness.edge.mux/channels-for` 答出订阅了某场会话的每一条连接；`http/mux-broadcast!`
  把一帧发给它们；run 的 emitter（`runner`）在收下每一帧的**同时**广播（带 `threadId` 与 `runId`）。
  `POST /api/agent` 的 SSE 响应**一个字没动**，驱动页照旧从响应里读——这一步是纯加。
- **客户端**：`lib/mux.ts` 把 socket 上的帧分两类路由（窗口类型 → 窗口跟随者；其余 = run 事件 →
  `subscribeRun` 的订阅者），订阅集合 = 跟随的窗口 ∪ 驱动的 run；`subscribeRun` 答一个「服务端已经
  知道这场了吗」的 Promise（run 帧按集合过滤，声明没落地就起跑会丢头几帧）。

**还差的一半（票 03 的主体）**：`POST /api/agent` 只起跑并回 ack，客户端把这个 run 的帧从 socket
收回来、重新拼成 `@ag-ui/client` 能解析的 SSE（`HarnessAgent` 的 transport 与 abort/取消那几条缝要
跟着搬；e2e 里直接解析 POST SSE 的用例也要搬）。**半做会让人发不出消息**，所以停在这里：两半都在，
只是新的这一半还没有调用者。票 04、05 都排在这一半之后。

### 迁移也落了（同日）

**客户端现在真的从 socket 收 run 帧了。** `HarnessAgent` 多了一个 `runAck` 选项（`app.tsx` 打开）：
置真时，它的 fetch 先 `subscribeRun` 并向服务端**等到声明落地**（run 帧按连接集合过滤，早跑会丢头几帧），
再带 `X-Clj-Harness-Run-Ack: 1` 发这一轮；服务端 `start-run` 只回 `{threadId, runId}`（`silent-channel`
吞掉 SSE 的写），帧由 emitter 广播下行走 socket，客户端把它们**重新拼成 SSE** 交给
`@ag-ui/client` 原来的解析器——base class 的 reader、abort、取消那几条缝一个字没动。不置 `runAck`
的调用者（e2e 套件、任何别的前端）照旧拿 POST 的 SSE 响应（expand/contract）。

**顺带修的**：`Access-Control-Allow-Headers` 要认这颗自定义头——开发环是跨源的，不认就是浏览器
在预检就拒了，run edge 一条 `run/start` 都不会记（实测：debug 脚本里 `POST /api/agent` 被 CORS 拦下，
后端日志空空）。

**验证**：

- `session-after-refresh` 的 reload-mid-run 走查（09-go.json，两个会话、停、刷新、记录）：**ALL GREEN**——
  发送、刷新后按停、跑完能发、两条会话并行，全在新的 ack+socket 载体上。
- `walkthrough-host.mjs`（本特征的两窗口走查）：**ALL GREEN**。
- 后端全量 **1152 tests / 13242 assertions / 0 failures**；ui 127、typecheck、build 过。

**一处没做到的，说清楚**：票面「断线重连后这一轮接着到达，不漏不重」**没有做到**。服务端不缓存
run 帧，socket 一断，断开那一段的 AG-UI 帧就没了；重连后只续上新的帧（窗口那半有拉页对齐，run 这半
没有）。要按票面做成，得给每条 run 一个可回放的帧缓冲（像窗口的 `seq` 那样给个游标），或让重连走
一次「重放 + 续传」。**票 03 因此没有按「完成即删除」处理，留在 `issues/` 里。**

## 落地（2026-09-23）：票 04

**子 agent 的对话改从页面那一条下行读，面板不再攥着自己的一条 SSE。**

- **服务端**：新增 `GET /api/threads/<stem>/frames`——`follow` 的**重放半边**作为 JSON
  （`RUN_STARTED` 起头、快照随后、记录里的帧按序，而且**保留 `:seq`**），另给一个 `:running`。
  `run-subagent!` 在把每帧交给 record 与 frame-bus 的**同一处**也 `mux-broadcast!` 下行——同一颗
  `:seq`，所以「重放 + 实时尾巴」的边界是精确的，重复的那段按号丢掉。`thread-verbs` 收进 `frames`
  （闭集，计数改成 EIGHT OF THE ELEVEN）。
- **客户端**：`lib/follow.ts` 的 `FollowAgent` 的 transport 换成「**先订阅下行、再读重放、按 `:seq`
  去重**、拼成 SSE 交给 `@ag-ui/client` 的解析器」；面板构造时同时给出 frames URL 与 threadId
  （一个是读、一个是实时尾巴的声明）。

**验证**：`harness.edge.follow-route-test` **6 tests / 41 assertions / 0 failures**（新增两条：
重放 JSON 的形状与 `:seq` 保留、未知 stem 的 404）；真浏览器
`.scratch/events-mux-and-host/walkthrough-follow.mjs` **ALL GREEN**——面板打开、并显示子 agent
被交办的任务（**只有记录重放能重建的那一帧**），且无错误。
