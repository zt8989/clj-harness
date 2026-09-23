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
