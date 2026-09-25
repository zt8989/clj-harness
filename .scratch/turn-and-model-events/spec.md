# spec: 轮与模型调用各有一族下行事件，各管自己那一级

主人（2026-09-25）拍定的五条：

1. **一轮的开始与结束必须有事件**，模型调用的起止也要（此前它们只是记录里的审计行）。
2. **它们走会话级的 WebSocket 订阅（`events.mux`），不走 AG-UI 帧。** 理由是 `docs/architecture/edge.md`
   那句「帧就是会话」：AG-UI 的帧是**会话内容**，刷新要重建、下一轮可能被回发；轮与模型调用是**关于会话的
   事实**，既不该长成消息，也不该被回发。
3. **订阅点只有两个**：家级 `events.host`（会话出现/改名/跑起来这一类清单事实）与会话级 `events.mux`。
   不再开第三条 socket。
4. **两族各管自己那一级，不互相代管**：轮那一族带的是**折叠时要显示的东西**（这次轮调了几次工具、
   思考了几次、几条消息）；模型那一族带的是**模型这一级的数**（这次用了多少、到这一刻的总量多少）。
5. **不背历史**：事件只服务「你看着的时候」；打开一场旧会话仍走读侧（`rebuild` / `page` / `sofar`）。
   过去不作为事件重放，只作为**一次快照**。

## 融合：Turn / Step / Model 是三件东西、两个层级（2026-09-25 续拍）

主人要「把 Turn、Step、Model 融合在一起」。查过 **DSH**（`https://deepseek-harness.github.io/deepseek-harness/en/reference/`
的 "Turn flow"）之后，融合的结论是——**三个名字，两个层级，一对边界各一条**：

| 层 | 事件 | 中文（本仓词表） | 进记录？ |
|---|---|---|---|
| 轮 | `turn/start` / `turn/end` | **轮** | **不进**（记录里算得出来） |
| 一次模型调用 | `model/start` / `model/end` | **一次模型调用**（英文就是 step） | **进**（今天如此，一个字不改） |

### 一、`step` 不是第三个东西 —— 它就是一次模型调用

本仓今天只有 `model/start` / `model/end` 一对，它括住的是**一次模型请求**。DSH 的 step 括住的是
**一次模型请求加上它调的工具**，所以严格说它比我们这一对大一圈。**我们不吃那一圈**，三条理由：

1. **接口上要的三个数都在调用这一级。** 折叠那一行要的 `calls`（这次轮调了几次工具）、`messages`、
   `reasoning`，模型那一族要的 `usage` / 总量 / 上下文圈——**没有一个需要「一次请求 + 它的工具」这个区间**。
2. **工具那一圈已经有自己的一族。** 记录里 `tools/pre-execute` / `execute` / `post-execute`（与 DSH 拼写
   一字不差）加 `TOOL_CALL_*` 帧，已经把「谁调了哪些工具、各自什么结局」说全了。再包一个 `step/*` 在外面，
   是同一个事实的**第二个名字**——正是本票决策 4（两族不互相代管）与 ADR 0006 要禁的那件事。
3. **DSH 那样切是因为它的 loop 拥有 claim 与工具。** 它的 `step/start` 在 `agent/pre-step` 认领输入之后、
   工具跑完之后 `step/end`，那个区间是它**自己的调度单位**。本仓一次 run 就是一次动作，调度单位是轮，
   请求单位是调用——中间不需要第三层。

**这是与 DSH 的一处有意分歧，写进 ADR**：我们借它的词汇（`turn/*`、`model/*`、`tools/*`），
但把它的 `step` 判成**与本仓的 `model` 同一件事**，因此不新增第三对边界。

### 二、融合之后，每一族的边界与内容

- **`turn/start` / `turn/end`**：轮的开合照「决策 三」；`turn/end` 带 `{turnId, calls, reasoning, messages,
  conclusionId, seqFrom, seqTo}`。**只上 wire，不进记录**——轮的边界在记录里由「没见过的 user 消息」
  算得出来（`harness.edge.stats/user-ids`），再写一行就是同一件事的第二份。**没有记录行，就没有历史**，
  这与决策 5「不背历史」是同一条。
- **`model/start` / `model/end`**：**两处都在**——记录里照旧（ADR 0003 决策 9 一个字不改），
  同时上 wire。`model/end` 上 wire 的载荷分两层：`payload` 是厂商逐字的 `:usage` / `:finish-reason` /
  `:model`，`numbers` 是这一刻的总量（`steps` / `usage` / `cacheHitPercent` / `outputTokensPerSecond` /
  `context`）。`model/start` 照旧带它自己那一行的载荷（`:context-window`、工具表签名）。
- **两族的 `seq` 都是记录行号**（ADR 0003 决策 1/9），不是内存计数器：只有这样，推来的那一半才能与
  窗口那半对齐（票 05）。

### 三、于是「两族各管自己那一级」有了确切的形状

```
turn/start                                    ← 人说了话（没见过的 user 消息）
  model/start   {…身份, context-window, 签名}
  model/end     {payload: 厂商逐字, numbers: 到这一刻的总量 + 上下文}
  …（一次轮里的每一次调用都是一对）
turn/end      {turnId, calls, reasoning, messages, conclusionId, seqFrom, seqTo}
```

**轮的结束在返回尾巴落地之后**（决策 三 末），因为它的 `messages` 要数这一 run 的返回侧；
**调用的结束就在那一刻**（厂商的 usage 已经在手上）。这两件事的时机不同，是它们分属两级的直接后果。

## 今天的事实（先把话说准）

- 内核词汇里**没有**轮事件，也没有 step 事件；一次模型调用只有 `model/start` / `model/end` 这一对
  （`harness.kernel.event`）。所以「step 和 model start/end 是一样的」是对的：**它们就是同一个东西**。
- 这一对**一个帧都不发**：`docs/architecture/edge.md` 的事实表里两行都写着「**不上 wire**」，只落 jsonl 审计行。
- 轮今天有两份实现，都不是事件：客户端 `ui/src/lib/turns.ts`（邻接 assistant 消息 = 一轮，
  `turnCounts` 数工具调用与消息条数，`turnSummaryLabel` 出那一行字）与服务端
  `harness.edge.stats/user-ids`（没见过的 user 消息 id = 一轮）。
- 会话级那条下行今天载两族：窗口帧（`lib/mux.ts` 的 `WINDOW_TYPES`）与 run 的 AG-UI 帧。**判据是
  「不在 `WINDOW_TYPES` 里 ⇒ run 帧」**，然后交给 `lib/agent.ts` 拼成 SSE 喂 `@ag-ui/client`。

### composer 今天显示的东西，以及每一个数从哪来

两处，读同一份载荷（`components/composer-numbers.tsx` 一次取，`composer-stats.tsx` 与
`context-ring.tsx` 分头画）：

| 显示 | 载荷字段 | 今天从哪来 |
|---|---|---|
| 轮数 | `turns` | 没见过的 user `message` 行（`stats/user-ids`） |
| 模型调用数 | `steps` / `stepsWithUsage` | `model/start` 的条数（与带 `:usage` 的条数） |
| 速率 | `outputTokensPerSecond` | 报了 completion 与耗时的**那次配对**：Σcompletion / Σms |
| 总 token / 缓存 | `usage.totalTokens` / `cacheHitPercent` | `model/end` 的厂商 `:usage`，逐次求和 |
| 上下文圈 | `context.{usedTokens, windowTokens, percent, parts}` | 最近一次**报了 prompt** 的调用（`harness.edge.context`） |
| 折叠那一行 | —— | `ui/src/lib/turns.ts` 的 `turnCounts` 在**客户端**现算 |

`context.parts` 有一格必须点名：**它是估的，而且要这一 run 的「消息侧」完整才算得出来**
（`harness.edge.context/records->context` 的 `unfinished?`）—— 终帧之后内核才写返回尾巴，所以在
**终帧那一刻**算，只有厂商的数字（`usedTokens` / `windowTokens` / `percent`），**没有** `parts`。
`composer-numbers.tsx` 今天那个「跑完 400 ms 再问一次」就是为这一格存在的。

今天这份载荷来自**另一个单独的** `GET /api/threads/<stem>/stats`，它每次从头解析整份记录
（`harness.edge.http/stats-get` → `sessions/read-records`；`harness.edge.sessions` 的 `:read` 缝**永远读文件**）。
实测（`fa35f356`，68 MB / 237,012 行 / 6 轮 / 392 次调用）：一次 `read-records` ≈ **1.8 s**，
一次 `log-stats` ≈ **1.2 s**；而 `composer-numbers.tsx` 的 effect 依赖里有 `assistantCount`，
一轮 run 里它把 `/stats` 问了 **3 次**。

## 决策

### 一、两族挂在会话级那条下行上，各管自己那一级

1. **`turn/start` / `turn/end` —— 轮这一级。** `turn/end` 带的就是**折叠那一行要显示的东西**：
   `{turnId, calls, reasoning, messages, conclusionId, seqFrom, seqTo}`。今天 `turnCounts` 在客户端现算的
   `calls` / `messages` 就是它，`reasoning` 是新增的那一格（**定义必须先钉死**，见「代价」）；
   带 `conclusionId` 与 `seq` 区间，是为了让展开时按区间把那几轮的原始消息拉回来（`cheap-session-load`
   票 04 的寻址）。**发了之后，这几个数的主人在服务端。**
   **统计只在事件里做，展示照旧。** 这几个数今天只画 `calls` · `messages` 两个（`turnSummaryLabel` 一个字不改）；
   `reasoning` 带在事件里是为了把轮的事实说全，**不上画面**（见决策 五）。
2. **`model/start` / `model/end` —— 模型这一级。**
   - `model/end` 带**这次用了多少**：厂商的 `:usage` / `:finish-reason` / `:model`，**逐字**（记录里怎么写就怎么发，
   不重命名）；**以及到这一刻的总量**：`{steps, usage, cacheHitPercent, outputTokensPerSecond, context}`。
     这个 `context` 就是上面那张表里圈的四个字段（`usedTokens` / `windowTokens` / `percent` / `parts`）——
     **上下文是模型这一级的事实**（最近一次调用的 prompt 与它那次调用声明过的窗口）。
   - `model/start` 不带数（它自己那一行的载荷 —— `:context-window`、工具表签名 —— 照旧带上）。
   - 「这次用了 2000」与「这场一共 2.9M」**分开放**：per-call 的键在 `payload` 下，会话的键在 `numbers` 下。
     同一个键名不承担两个意思。
3. **`turn/start` 不带数**：轮还没跑完，没有可报的东西。

### 二、这要求服务端先有那份折叠

`stats` / `context` 要像 `harness.edge.pressure` 那样注册成会话的读折叠（`register-fold!` 走出生那一遍读、
`register-step!` 跟着写流更新）—— 「总量」与「上下文圈」就是它的当前答案。于是「状态条从内存答」不再是优化，
而是**这件事的前置**：没有内存里那份折叠，`model/end` 就没有「总量」可发。

### 三、轮的开与关这样定

（照 `CONTEXT.md`「轮 ≠ 一次 run」）

- `turn/start` 由**一条没见过的 user 消息**触发，**不是**由 run 触发。悬置恢复会写出第二个 `input`
  （同一个 `runId`、没有新的 user 消息），那是**同一轮的继续**，不开新轮（`harness.edge.trajectory` 的命名、
  `harness.edge.stats/user-ids` 的判据都是这一条）。
- `turn/end` 是 run 的**终局**（`:run/end` / `:run/error` / `:run/stopped`）。**`:run/interrupt` 不是终局** ——
  那正是「停在半路等人」，轮还开着（`ui/src/lib/turns.ts` 的 `turnIsSettled` 对 `requires-action` 也是这么判的）。
  一次轮因此可能跨多个 run，但只有一进一出。
- **`turn/end` 发在返回尾巴落地之后，不是终帧那一刻** —— 它带的 `messages` 数与折叠的那一行要这一 run 的消息侧
  齐了才算得准（同一个 `unfinished?` 的理由）。数字那一刻已经在，条数不是。

### 四、发送点只有一个

`harness.edge.http` 里 run emitter 那一处（`mux-broadcast!`），与 run 的帧**同一颗钟、同一段顺序**。
**不从 `sessions/watch!` 的落盘门铃发** —— 那是另一条时间线，轮和消息的相对顺序会在那里丢掉，而折叠最怕顺序。

### 五、客户端：装配，不是算

- `turn/start` 每来一条，轮数 +1（基准来自打开时那一次快照）；**折叠那一行照旧画 `calls` · `messages`**
  （`turnSummaryLabel` 一个字不改）—— 只是这两个数改由 `turn/end` 带来；`reasoning` 进的是事件，不进画面。
- `model/end` 的 `numbers` **直接落进 composer 那份载荷**（`steps` / `usage` / `cacheHitPercent` /
  `outputTokensPerSecond` / `context`），`ui/src/lib/format.ts` 的 `statsCells` / `contextCells` **一个字不改** ——
  客户端的活只是把两族装成它们今天吃的那个形状，**没有一处估算**。
- 装配是纯函数，有自己的用例（输入是几族事件 + 一次快照，输出是那份载荷）。

### 六、客户端路由器显式分类

`ui/src/lib/mux.ts` 今天那句「不在 `WINDOW_TYPES` 里 ⇒ 当 run 帧」必须变成显式判别：AG-UI 的类型照旧交给
`lib/agent.ts` 拼 SSE，新的两族有自己的订阅者。不分类就会被 `@ag-ui/client` 的 schema 校验当场打死。

### 七、一份规则、一张用例表

轮的边界与它的三个数，在客户端（`ui/src/lib/turns.ts`）与服务端（发送点）各有一份实现；照窗口代数的先例
（`ui/test/suites/turns.ts` 已经存在），两份由**同一张用例表**钉住。折叠时**只有一个主人**：运行中的轮以事件
为准，落定的轮以读侧为准（或反过来），不能两个都读。

### 八、唯一剩下的一次拉

页面打开时问一次 `GET /stats` 拿基准，此后全靠推（`composer-numbers.tsx` 里 `assistantCount` 那个依赖删掉）。
这就是「不背历史」的边界所在。

## 非目标

- **不改 `statsCells` / `contextCells` 的输入形状** —— 正因为事件带的是同一份字段，这两个纯函数一个字不动。
- **不改任何一处展示**：折叠那一行、状态带那五格、上下文圈的字与格式，改前改后逐字一样 —— 变的只是这些数
 的**来源**（事件而不是那一次 GET）。
- **不做历史回放**：新事件不进「打开会话」的载荷；不为旧会话补发。
- **不动 AG-UI 的帧词汇**：轮与模型事件**不是** AG-UI 帧 —— 不进 `apply-frames`、不落 `data` part、不被回发。
- **不改记录格式**（ADR 0003 决策 9），**不动**窗口/游标代数（`baseSeq` / `generation` / `since`）。
- **不开第三条 socket**；家级那条（`events.host`）不加会话内容。

## 代价与风险

- **「思考几次」必须先把定义钉死。** 今天仓里没有这个数：客户端一个 assistant 消息里可以有一段或多段
  `reasoning` part（`components/message-parts.tsx` 把相邻的包成一组），服务端 `harness.kernel.frames` 把推理
  折成 `role: "reasoning"` 的消息。是「几条推理消息」「几次模型调用带推理」还是「几段推理」，**三选一，
  且两份实现必须同一张用例表**。中途变一次口径，折叠那一行就会在两个客户端之间不一样。
- **顺序变了：折叠要先落地。** 事件成了「那份折叠的投递」，所以 `stats` / `context` 注册进会话读流是它的前置。
- **`turn/end` 的时机是个真陷阱**（决策 三 末）：发早了条数是错的，发晚了折叠要等；而且尾巴在**另一个线程**上落盘。
- **两个 `usage` 要守命名纪律**（决策 一.2 末）。
- **`docs/architecture/edge.md` 要改口**：事实表里 `model/start` / `model/end` 两行的「不上 wire」不再成立，
  另加一行说轮的边界也上 wire，但仍然**不进记录**（它本来就是记录算出来的）。`kernel.md` / `client.md` 一并核过。
- **断线要补得上**：事件是推的，推丢一段就少一段；游标按 `seq`（记录的行号）而不是内存计数，才能与窗口那半对齐。
- **每个 step 不出帧的规矩要守住**：`edge.md` 记着「每个条目发一张 CUSTOM 卡」被打回的那次；模型调用按轮的
  量级（392 次调用 vs 6 轮），所以这族只在**开着的那一场、看着的那段时间**发，不做逐条持久缓冲。
- **「思考几次」这一格今天没有出口。** 它带在事件里是为了把轮的事实说全，**画面上不显示**（决策 五）——
  所以它是一格数据，不是一次 UI 改动。口径仍要先钉死（见上一条）。

## 落点（每张票动哪里 —— 2026-09-25 逐处查证）

### 票 01 的落点，以及一处需要注意的实情

- **缝是现成的**：`harness.kernel.session/register-fold!`（`name -> {:init :step}`，跟着会话出生那一遍
  读）与 `register-step!`（跟着 `harness.edge.http/log!` 的写流），读回来用 `sessions/fold-value`。
  `harness.edge.pressure/install!` 是**同时注册两条缝**的现成先例，照它抄。
- **`stats` 容易**：`harness.edge.stats` 的 `stats-init` / `stats-step` / `stats-answer` 已经是那个形状，
  只是现在都是 `defn-`，公开出来即可。
- **`context` 不容易，这是实情**：`harness.edge.context/records->context` 建在
  `harness.edge.trajectory/run-segments`（整份记录切段）上，今天**不是增量的**。要把它注册成折叠，
  得先做一个增量版：它要的三样是「最近一次报了 prompt 的那次调用（它的 start 与 end 两行 payload）」、
  「时间线窗口」、「最后一个 run 的返回侧 + 这一 run 有没有终帧」——都能增量维护，但是一段真活。
  **票 01 的判据（活着的会话答 `/stats` 不再打开那份 jsonl）只有在两者都增量之后才成立。**
- **路由**：`harness.edge.http/stats-get`（约 2956 行）——活着的会话从 `fold-value` 答，冷会话照旧读文件。

### 票 03 的落点，以及一处要修正的实情

**主人票里「发送点只有一个（run emitter 那一处 `mux-broadcast!`）」这句话要修一下。**
`harness.edge.http/runner` 是**帧**的 sink，而 `model/*` **永远不是帧**——
`harness.edge.ag-ui/step` 对这两个类型是**空操作**（它是个穷尽 `case`，所以有分支但不产帧）。
所以能同时看见「内核事件」与「这一 run 的帧」的地方是**那条 drain 循环**（`ag/outbound` 的 `convert` 与
`lifecycle-record` 并排的那一处，run 体里 `(loop [] (when-let [ev (async/<! events)] ...))`）。

四条的发送点因此落在**同一段顺序里的三处**（都在那条 drain 循环的线程上，一颗钟）：

| 事件 | 落在哪 |
|---|---|
| `model/start` / `model/end` | drain 循环里 `lifecycle-record` 那一支；`log!` 的 **land 回调**拿到行号之后再广播，`seq` 就是它 |
| `turn/start` | 写提交侧那些 `message` 行的地方（`added` 的 `doseq`）：**第一条 `:source "client"` 的 user 行**落盘后广播 |
| `turn/end` | `:run/done` 那一段：`log-messages!` **之后**（返回尾巴落地），数字从票 01 的折叠读 |

### 票 04 / 05 的落点

- `ui/src/lib/mux.ts`：今天那句「不在 `WINDOW_TYPES` 里 ⇒ 当 run 帧」必须变成**显式三族**判别
  （窗口帧 / 新的两族 / AG-UI run 帧），否则新的两族会被喂给 `@ag-ui/client`，当场被 schema 校验打死。
- `ui/src/components/composer-numbers.tsx`：删掉 effect 依赖里的 `assistantCount`，改成吃推来的载荷；
  装配写成**纯函数**并配用例（`ui/src/lib/format.ts` 的 `statsCells` / `contextCells` 一个字不改）。
- 票 05：`mux.ts` 里 `runCursors` 那套高水位（`runSince`）是现成的形状，这族照它做自己的游标。
## 票

| # | 票 | 依赖 | 交付 |
|---|---|---|---|
| 01 | stats/context 成为会话的读折叠 | 无 | 照 `harness.edge.pressure`：`register-fold!`（出生那一遍读）+ `register-step!`（写流）；`stats-get` 活着时答内存、冷会话照旧读文件；**判据：活着的会话答 `/stats` 不再打开那份 jsonl** |
| 02 | 「思考几次」的口径 | 无 | 一个定义 + `ui/test/suites/turns.ts` 里那条共享用例（服务端与客户端都读它） |
| 03 | 会话级下行多两族 | 01, 02 | `harness.edge.http` 的 emitter 处多发四帧（`threadId` / `seq` / `payload` / `numbers`）；轮的开合照决策 三；`turn/end` 的时机照决策 三 末 |
| 04 | 客户端认这第三族、composer 直接画它 | 03 | `ui/src/lib/mux.ts` 显式分类 + 订阅面；装配那个纯函数 + 用例；`composer-numbers.tsx` 拿推送的载荷、删 `assistantCount` 依赖；用例钉住「AG-UI 之外的帧不会被喂给 `@ag-ui/client`」 |
| 05 | 游标：断线补齐 | 03 | 这族有自己的一条 `seq` 游标，重连按它补缺口（照 `runSince`）；用例钉住断线一段不漏不重 |
| 06 | 收口 | 01–05 | `edge.md` / `kernel.md` / `client.md` 改口；两套全量表 + 真浏览器走查（含「展示逐字不变」那一条） |

## 验证

- **总量与拉到的相等**（这条最硬）：同一场会话、同一个时刻，`model/end` 里 `numbers` 的那些字段与
  `GET /stats` 的对应字段相等。这一条一立，composer 换载体就只是换载体。
- **轮的那三个数**：同一轮上，`turn/end` 的 `calls` / `reasoning` / `messages` 与客户端 `turns.ts` 的
  同口径答案相等（共享用例表那条）。
- **展示逐字不变**：同一份输入，改前/改后的 `statsCells` / `contextCells` / `turnSummaryLabel` 输出相等；
  真浏览器里状态带与上下文圈的文本、折叠那一行，与改前逐字一样。
- **轮的开合**：`park → resume` 只发一次 `turn/start`、一次 `turn/end`（不是两次）；被停的 run 收轮。
- **顺序**：一轮里 `turn/start` 在任何 `model/start` 之前，`model/end` 的 `seq` 递增，`turn/end` 在最后；
  断言的是**相对顺序**，不是时刻。
- **`parts` 的那一格**：`numbers.context.parts` 三项之和 === `usedTokens`（`apportion` 的既有保证），
  且**只有**返回尾巴落地之后的那些帧带 `parts`。
- **不喂错解析器**：把两族事件发进一条订阅了 run 的连接，`@ag-ui/client` 不报 schema 错误、这一轮不被打死。
- **不改记录**：跑一轮前后，jsonl 的行数只按既有规约增长（不因本特征多出一行）。
- **现场数字进 `evidence/`**：同一场 68 MB 会话，改前/改后各量一次「一次 `/stats` 读了多少字节、花了多久」，
  以及一轮 run 里问了几次。
- 两套全量（后端 `clojure -M:test -m harness.test-runner`；前端 `npm test` / `typecheck` / `build`），
  加一次 `node scripts/dev.mjs --scripted` 的真浏览器走查。

## 词表

**轮**，不是 turn（`CONTEXT.md` 的「别叫成」写死了这三个词：turn / 回合 / 迭代）；**一次模型调用**，
不是 step（中文里是「模型调用」，不是「步」）。

## 落地（票 01，2026-09-25）

**落了。** 落在 `turn-and-model-events` 这个 worktree 里，改动三处源码：

| 文件 | 改了什么 |
|---|---|
| `harness.edge.context` | `records->context` 拆成 `state-init` / `state-step` / `state->context`，`install!` 两条缝 |
| `harness.edge.stats` | `stats-init` / `stats-answer` 公开，`stats-step` 多一个三参数的 arity，`install!` 两条缝 |
| `harness.edge.http` | `start!` 里与 `pressure/install!` 并肩注册两份折叠；`stats-get` 先问 `live-numbers`，冷会话照旧读文件 |

**`context` 那一份没有当初担心的那么难，因为切段本来就是折叠。**
`trajectory/segments-step` 已经是一个 `(fn [state ctx [i row]] state)`，而 `run-segments` 的 docstring 自己写着
本命名空间是它的第二个读者（「a second implementation of it would be a second chance to disagree
about where a run starts」）。所以增量版就是把**那台机器**放进状态里，再加两样只有本读者要的：
provider 时间线的 `[ts window]` 组合、以及最后那条 `event` 行（`stats/incomplete?` 收一个只有一行的
数列就能算）。`window-of` / `timeline-pair` / `window-at` 各只有一份拼写。

**一处实情要记下来：`row-written!` 会对着一个还没有那份折叠的会话调它的 step。**
`register-fold!` 自己写着「a session already in the table does not gain a fold until it is built again」
——于是那个 value 是 nil，而把 nil 喂给一个往值里折的 step 就是 NPE（测试里真的撞到了）。
**修法是让 nil 保持 nil**：`stats-step` 与 `state-step` 都在 nil 上原样返回 nil，
于是 `fold-value` 照样答 nil、路由照样回落到记录——那是对的答案（这份会话的内存里没有整份，
而记录里有）。**不要**在 nil 上新建一份：那是「从建立以来的那些行」冒充整体，形似而数不对。

**判据绿了**：「活着的会话答 `/stats` 不再打开那份 jsonl」现在有一条钉住它的用例
（`a-live-session-answers-stats-without-opening-the-record`：把 `replay/read-records` 改成一调用就抛，
端点仍答 200）。

**它先是红的，而那次红值得**：它把真病根揪出来了，而且**不在** `row-written!` 上——
**一个在「日志还不存在」时出生的会话，`:build` 的回落分支给的是空折叠表**
（`harness.edge.sessions`）。于是消费方的折叠在**第一行**上从 nil 开始：那不是「一场空会话」，
是「一份从没开始过的折叠」，对往值里折的 step 就是异常。修法是在那个分支上
`(replay/folds-init registered)`——**用各自的 `:init` 播种**，那在空记录下就是真话。
（`pressure` 一直走在这条路上，只是它的 step 恰好容得下 nil，于是空 band 与「从没开始」长得一样。）
我自己那个 nil 守卫留着：它现在只在「这份会话确实没持有这份折叠」时说 nil。

**一处还没稳住，照实记**：三个命名空间一起跑的那一轮里，`the-endpoint-folds-what-the-run-wrote`
红过一次——回落到内存的那份数是空的。之后单独跑三遍（`http-test` 两遍、三命名空间一遍）没再现。
它属于「同一时刻内存与记录必须相等」那条判据，所以留在这里当**已知风险**：
票 03 那些数（`model/end` 的 `numbers`）落地时，要用同一条判据一并钉住。

**跑过的门**：`harness.edge.http-test` 108 个用例 / 1199 个断言、
`stats-test` + `context-test` + `sessions-test` 49 个用例 / 210 个断言，**全绿**。
