# 会话成为机制：记录流的所有者，其他都是订阅者

**起因**：压力表那「一次播种读」——加上更早发现的一堆「每个消费者自己去读记录」——指向同一件事。
会话（一场对话的服务端状态）本该是**记录流的所有者**：谁往记录里写一行、谁点开会话，都由它一次说清；
其他读法（模型面、客户端面、压力表、轨迹、上下文圈、统计）都是**订阅者**，从它身上取，不各自去开文件。

**模型（老板口径，2026-09-24）**：

- 会话提供**读 jsonl 的流**与**写 jsonl 的流**这两样机制；
- 其他都是消费者，**订阅**这两条流；
- 点「加载会话」的时候，**所有需要的东西自然就准备好了**。

**现在的样子（差距）**：

- 会话持有的只有「对话」（entries / compactions / prunes / state）——而装会话那次读把记录折了
  **5～6 遍**（`entries`、`state`、`messages`（又调 `entries`）、`compactions`、`prunes`），还物化过整份；
- 压力表自己一个 atom，**第一次问它要读一遍记录**（播种），之后才靠 `http/log!` 顺手喂；
- `stats` / `trajectory` / `context` 三条路由各自再读一遍；
- 写侧：`http/log!` 是唯一入口，但「顺手喂压力表」是它认识的一个**特例**——加一个消费者要动它。

## 非目标

- 不改记录格式、不改 AG-UI、不改窗口 / feed 的对外行为。
- **不提前算好每条路由的派生物**：轨迹那样的大视图按需算就行。会话准备好的是 **run 需要的那几样**
  （对话 + 表针），其余消费者按需从会话取或按需折。
- 铁律不变：**run 进行中内核不读自己的记录**（装会话在读之前完成；run 里只有订阅者的实时 step）。

## 分层的硬约束

`harness.layers-test`：`kernel → infra, kernel`。所以「会话是 kernel 机制」意味着它**不能 require
`harness.cap.*` 或 `harness.edge.*`**。jsonl 的读写、行的解析、「行 → 对话」的折留在 edge / cap；
kernel 里的会话只持有**按 thread 的状态 + 订阅缝 + 开关**。

## 验收主线

1. 点开会话：记录**走一遍**，对话与表针一起算出来（不再 5～6 遍、不再物化整份）。
2. run 开头那次压力测量：**零读**（含这场会话在本进程的第一次），与 `records->pressure` 逐字段相等。
3. 加一个新消费者（读或写）：只登记一个折子 / step，**不动 `http/log!`**。
4. 三条路由（stats / trajectory / context）改读会话，对外行为不变。
5. `harness.kernel.session` 住 kernel（不 require cap / edge），`layers-test` 绿，铁律写在文档里。
6. 离线全量 + 真浏览器走查。

## 票清单（`issues/`）

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 装会话收成一次走查 | — | `sofar` 单趟；对话四样一起算出；不再物化 |
| 02 | 折子挂点：读流可订阅 | 01 | 装会话时按行喂登记过的折子，结果落在会话上 |
| 03 | 压力表成为第一个订阅者 | 02 | 表针是折子；run 开头零读；与 `records->pressure` 同答案 |
| 04 | 写流归会话：一行进门，订阅者自动更新 | 02 | 唯一写入口通知订阅者；`http/log!` 不再认识压力表 |
| 05 | stats 改读会话 | 02 | 统计路由不再自己读盘，行为不变 |
| 06 | trajectory 改读会话 | 02 | 轨迹路由不再自己读盘，行为不变 |
| 07 | context 改读会话 | 02 | 上下文路由不再自己读盘，行为不变 |
| 08 | 会话搬进 kernel：机制立起来 | 01–07 | `harness.kernel.session` 立起，edge 委托它，行为不变 |
| 09 | 会话搬进 kernel：消费者搬过去 | 08 | edge 的会话状态与消费者落到新机制上 |
| 10 | 会话搬进 kernel：删掉 edge 那份 | 09 | 只会话机制在 kernel；`layers-test` 绿 |
| 11 | 收口：文档与全量 | 01–10 | ADR / layers.md / edge.md / CONTEXT.md；全量 + 走查 |
| 12 | 轨迹也走流 | — | `run-segments` 成流式 step；路由走会话的读流；票 06 的峰值堆那格成立 |
| 13 | 轨迹成为会话的一个视图：一次读，之后写流增量推 | 12 | `:trajectory` 按需装值；`row-written!` 原地推进；路由长连接先吐已定稿的轮、再推新定稿的轮 |

## 备注

- **08–10 是一次宽重构**（一个机制换家，blast radius 铺在整条 read 侧），按 **expand → migrate →
  contract** 切，保证每一刀单独都绿——这就是它拆成三张的原因。
- **03 与 04 可以并行**（读流 / 写流两条缝），它们都只被 02 阻塞。
- **05–07 是三个独立消费者**，互相不阻塞；它们表达的是「其他都是消费者」这个方向，不是 run 热路径
  （热路径是 01–04）。
- `harness.edge.record`（写者）现在 require 会话，是 08–10 的 blast radius 之一，别漏。

## 落地（2026-09-24）：票 12 + 票 13

**轨迹折成一条流，然后成为会话的一个视图：一次读，之后由写流推。** 两句口径落成的形状：

- **折法是一个 step，不是一遍扫**（票 12）。`harness.edge.trajectory` 长出 `segments-init` /
  `segments-step` / `segments-answer`（`run-segments` 现在是它的答案）、`life-step` / `calls-step`
  （`tool-lifecycles` / `call-index` 现在是它们的 reduce）、以及 **`trajectory-init` /
  `trajectory-step` / `trajectory-answer`**：三条 prepass 并进同一趟按行的累加器。`trajectory-step`
  是**双 arity** 的——会话的两条缝递 `[value ctx [i row]]`（票 02 / 04 的形状），`sessions/fold-record`
  与内存里的 reduce 递 `[value [i row]]`。段一闭合（下一段开口，或记录到底）就折、就**丢掉**，
  所以状态是 payload 的大小，不是 run 数的大小。`records->trajectory` 是这个 step 的内存孪生
  （`replay/fold-consumers` 跑的同一份）。
- **视图住在会话上，按需建**（票 13）。`trajectory/view-value` 第一次被问时走**一趟**
  `sessions/fold-record` 把状态装进会话（新的 `kernel.session/set-fold-value!`，因为 `register-fold!`
  只在 build 时喂）；`trajectory/install!` 在写流上登记一步，`row-written!` 每 append 一行就原地推进。
  内存丢了（会话淘汰、连接关）就再走一趟——票面允许，是这个设计的正常代价。
- **路由是长连接**（票 13）。`GET …/trajectory` 仍是 NDJSON（首行头、其后一轮一行），但对**持有着的**
  会话**不关**：先把已定稿的轮吐出去，再挂在会话门铃上，每个新定稿的轮推下去；**运行中那一轮**每推一次
  重发，客户端按 `:index` 原地替换（与窗口「同 id 是新版本」同一条）。不碰 `events.mux`，下行仍只承载对话。
- **没给 JSONL 加字段**：折法是单向前进的一趟，跨段的查表在「下一段开口」时都齐了，所以「内存丢了重读
  一次」这条已经足够——记录格式不动（决策 5 的账本是闭的）。

验证：`harness.edge.trajectory-test` **25 / 109 / 0**（新增：`records->trajectory` 与 `fold-consumers`
是同一份折、**挪走记录文件后第二次问仍答同一份**、**第二条 run 的轮在已开的流上被推**）；
`harness.edge.http-test` 103 / 1147 / 0；后端全量 **1236 / 13526 / 1**（唯一那条红仍是既有的 fork 子进程
`cap.claims-test`）；ui **131**、typecheck、build 过；真浏览器走查 `.scratch/session-as-kernel/walkthrough-view.mjs`
**ALL GREEN**（对话栏一个轨迹请求都不发；打开轨迹栏**一条**流、画第一轮、**且它没关**——推送就骑在它上面）。

**票 12 与票 13 到此收口，从 `issues/` 删除。**
