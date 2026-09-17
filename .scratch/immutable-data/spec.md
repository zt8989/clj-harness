# spec: 不可变数据的纪律（写进 AGENTS.md，并让代码合规）

2026-09-16 立。两件事：

1. **把 Clojure 的不可变数据原则写进 `AGENTS.md`**，重点是**多线程下的读写**——这份代码里
   「只有主线程会碰它」这句话基本都是假的。
2. **按这些原则审一遍代码**，把真正违反规则的代码逐条立票。

审出来的东西分三类，都写在下面：**违反**（→ 票）、**查过不是问题**（别再审一遍）、
**看过、决定不修**（写清理由，免得下次有人当成漏的）。

## 规矩写在哪：三处边界，各自只写它该写的

这份仓库已经把「文档写哪」分了工（`docs/architecture.md` 开头那张表）。本特征遵守它，不新开一处：

| 地方 | 写什么 | 不写什么 |
|---|---|---|
| **`AGENTS.md`**（票 01） | **规矩本身**：可做与不可做，以及每条规矩的**正例**（到哪里看一个对的写法） | 反例的位置。`AGENTS.md` 是要求，不是一个会过期的清单 |
| **本文件**（`.scratch/immutable-data/`） | **反例与它们的 `file:line`**，带日期 | 现状描述 |
| `docs/architecture/` | 不动 | —— |

**为什么反例不进 `AGENTS.md`：** 反例只要被修掉就变成假话，而 `AGENTS.md` 是被每个 agent 读、
且没人会回头改的。正例相反：`take-provider-changes!` 的 `swap-vals!` drain 是对的，修完还是对的。
所以规矩配正例，反例连位置一起留在本文件里——本文件是**历史**，写着日期，不会被当成现状。

## 违反（逐条对应一张票）

先把结论放前面：**两条今天就能咬人**（票 02、03），三条是会丢更新/写错账的（票 04、05、06），
两条是账不对（票 07）或首次开库抢跑（票 08）。

| # | 现场 | 是什么问题 | 票 |
|---|---|---|---|
| 1 | `kernel/tools.clj:546` `turn-plan` 是**一个全局单槽**（`reset!`），`loop.clj:134` 又在**工具线程已经跑起来之后**才 `register-turn!` | 两个会话同时在跑时互相清对方的计划；锚点批（「一条消息里的多次编辑合成一次写入」）静默降级成并发独立编辑，而它存在的理由正是防丢更新。`loop.clj:127` 那句注释（「在任何东西跑起来之前就把整个 turn 告诉缝」）与代码不符 | **02** |
| 2 | `hashline/write.clj:112` 与 `hashline/undo.clj:109` 先拿 **path 锁**，再经 auto-read / `undo-rows` 进 `serve/read!` 拿 **session 锁**（`serve.clj:76`） | **锁顺序反了**。规矩写在 `store.clj:411`（session 永远是外层）；正确的调用方是 `replace.clj:558`。两个工具线程（一条消息里的 `write` 与 `replace`/`read` 同一个文件）互相等，**真死锁**，那一轮 run 永远不结束。`ReentrantLock` 的可重入只帮同一个线程 | **03** |
| 3 | `edge/http.clj:214-228` 的 `init-logged?`/`session-started?` 与 `mark-*!` 是两次 atom 操作，中间夹着副作用（`hook/emit :session-start`、`log!`） | check-then-act：同一 `thread-id` 的两个并发 run 都会看到 `false`，于是 `SessionStart` 触发两次、`provider/init` 行写两条——而 `docs/architecture/edge.md:110` 说的是「每 thread 恰好一行」 | **04** |
| 4 | `edge/http.clj:1138-1147` 与 `cap/tools.clj:294-303`：`(override-for id)` → 算 → `(set-override! id (merge before change))` | 读-改-写不是一个原子操作。两个并发改（HTTP 端点与 `session-configure` 工具）丢更新，且两条 `provider/session-changed` 审计行都宣称一个**从未存在过**的 before→after | **05** |
| 5 | `infra/logging.clj:184` 的 `(when (not= root @configured-root) (configure! ..))`，而 `configure!`（`:163-174`）是「全摘掉再装上」 | 两个线程同时看到旧值就同时重配：`detach/detach/add/add` 交错 → **每个 appender 装两遍，每行日志写两次**——正是 `:160` 那句注释说要防的「stacked」。另外 detach 与 add 之间到达的日志会被静默丢掉 | **06** |
| 6 | `hashline/serve.clj:114` 与 `hashline/grep.clj:282-283`：`sync!`（锁内）之后才 `store/mark-served!`（**锁外**） | 夹在中间的一次并发编辑会在 `advance-on!` 里修剪 `served`，然后 `mark-served!` 再把已经释放的锚点并回去——「已展示」这份账对不上 | **07** |
| 7 | `infra/db.clj:845-862`：`(inspect f)` 决定 `created?`，**在事务之外**，之后才 `connect-and-migrate!` | 新家/坏库的首次开库 TOCTOU：两个并发请求都算出 `created? = true`，都跑迁移；抢输的那个撞 `CREATE TABLE` 已存在，`damage?` 判它不是损坏，于是那个请求以 `:open-failed` 失败。`http.clj` 里**没有任何 `db/` 调用**，所以库是**第一个碰库的请求**才开的，这个窗口就在开机后头两个请求上 | **08** |

## 查过、不是问题（附理由，别再审第二遍）

- **工具结果进 history 的顺序。** 一度可疑：调用并发跑、`done` 按**完成**顺序写。实际是对的——
  `loop.clj:146` 按 `calls` 顺序从 `@done` 取回，`:150-152` 再按那个顺序 append；`history` 全程只由
  run 的生产者线程 `swap!`。`emit` 走完成顺序，`loop.clj:53-58` 的 docstring 明说了这是故意的。
- **sqlite 连接。** `db.clj:866` `with-connection` 每次调用**新开一条**并 `finally` 关掉，没有
  `defonce` 连接、没有 `DataSource`、没有跨线程共享的 `Connection`。跨连接竞争交给 sqlite 自己
  （busy timeout 5000 + `BEGIN IMMEDIATE`）。
- **函数内的局部 atom。** `db.clj:354` `settled`、`db.clj:199` `moves`、`http.clj:277` `first?`、
  `replay.clj:291` `frames`、`ag_ui.clj:139` `s` 都只在一条线程上被碰（run 的消费线程），
  `moves` 的两次 deref 之间也没有写。不是共享状态。
- **lazy seq 跨线程。** 可疑的几处都在跨界前**已经实化**：`hooks.clj:579` `effective-hooks` 与
  `skills.clj:588` 用 `into {}` 包住 `for`；`hooks.clj:635` `declarations-at` 以 `vec` 结尾；
  `project.clj:633` `fence` 确实返回 lazy `concat`，但每个消费方都在同一次调用里实化它，从不存、
 从不跨线程。
- **一次性初始化。** `anchors.clj:195` 的 `pool` 是 `delay`，且被 force 之后是不可变值——`delay`
  保证只算一次且安全发布。`anchors.clj:337` 的 `ThreadLocal` `MessageDigest` 是每线程一个，
  这个用法是对的。
- **`store.clj` / `project.clj` 的 docstring 声明。** 「没有 atom、没有 delay、重启即无事」
  （`store.clj:6`）与「绑定是一行而不是 atom 里的一条」（`project.clj:2-7`）**都是真的**，
  对着代码核过。
- **`scripted-pins`（`providers.clj:882`）。** 生产代码里没有任何写入方，只有测试，且测试都在
  `finally` 里清理。它是测试缝，不是请求路径上的泄漏。

## 看过、决定不修（写清理由，别当成漏的）

- **按 `thread-id` 长大的进程内容器**：`tools/overlays`、`tools/session-approvals`、
  `hooks/overlays`、`hooks/counters`、`providers/session-overrides`、`http/init-logged`、
  `http/session-started`。它们**按会话分家是对的**（这正是规矩要的形状），长大的是「这个进程
  服务过多少个会话」。要回收就得知道会话**结束**了，而今天没有任何事件宣告这件事（`SessionStart`
  有，`SessionEnd` 没接线）。判据：**量级是「一个进程里的会话数」，不是「每次调用一条」**，
  在一个本地单用户的进程里可以接受。**与 `turn-plan` 的区别要说清**：那个是**单槽**且每轮
  `reset!`，被两个会话共用就会立刻出错，不是长大得慢，是当下就错——所以它进票，这些不进。
- **`parked-registry`（`tools.clj:343`）。** `take-decision!` 只标 `:consumed`，条目不删，于是
  每次审批留一条。删掉就会让 UI 的「待决清单」问不出来（`parked-calls` 读它），而它的量级是
  「这台机器上的人点过多少次审批」。**留**。
- **`path-locks` / `session-locks`（`store.clj:365-366`）。** 每个改过的路径/会话一个
  `ReentrantLock`，`store.clj:388-390` 已经写明这是「不要注册表来失效」的代价。**留**。

## 没做的（说清楚，别让它悄悄发生）

- **`docs/architecture/overview.md` 的「状态存在哪」只写了进程内存一行**（「待决审批、会话 overlay、
  hook 连接 → 进程内存，重启即失是特性不是缺陷」），没有说**哪一份是进程级、哪一份按 `thread-id`
  分家、哪一份由哪把锁看着**。本特征**不加**这段：`AGENTS.md` 写规矩、本文件写这次审出来的位置，
  足够；要把它做成现状文档里的表，是另一个特征（那时它必须写在**票 02-08 之后**，否则一落地就
  在描述改掉之前的形状）。
- **`AGENTS.md` 不加「怎么并行工具调用」的技法。** 编排属于 `kernel.loop` 的实现细节，
  不是一条别人要照着做的要求。

## 票的顺序与提醒

八张票，**都没有阻塞边**（01 与 02-08 互不依赖：规矩写得早不早，不影响某一处代码本来就该改成什么样）。
按严重度读：

- **02、03** 今天就能咬人（一条静默降级、一条死锁）。先做这两张。
- **04、05、06** 会丢更新或把审计行写错。
- **07、08** 账不对 / 首次开库抢跑。

**04 与 05 都改 `src/harness/edge/http.clj`**（改的是不相邻的两个函数区域：`:214-228` 与
`:1125-1147`）。工作树是与别的 agent 共用的：提交要按路径点名，别把别人的在办改动一起带走。
