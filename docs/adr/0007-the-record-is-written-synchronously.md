# 0007 —— 记录同步写：`log!` 在调用者线程上把行写下去，并当场回答行号

- **日期**：2026-09-25
- **状态**：**已采纳**
- **推翻** `docs/adr/0002-sessions-live-on-the-server.md` 的**决策 3**（「记录异步写、允许落后」）。
  0002 的决策 1（内存是权威）、2、4–10 **不变**——本决定换的是**谁等**，不是谁说了算。
- **参照**：主人 2026-09-25 给的持久化蓝图（`.scratch/event-persistence/spec.md` 是它对本仓的核对）。

## 背景

**异步买到的只有一件事，而它挡住了一件更要紧的事。** `harness.edge.record` 自己的 docstring
把第一句说死了：

> WHAT THE ASYNC BUYS, AND WHAT IT DOES NOT. It buys that IO is not on the response path. It does
> **NOT** buy 'fewer writes': the pace is still ONE WRITE PER FRAME (decision 6).

也就是说写盘量一模一样，异步只是把等待挪到另一条线程上。而它挪走的那点等待，换来的代价是：
**行号要过一条线程才回得来**（`log!` 的 `lands` 回调，「once, on the consumer thread, after the
line is on disk」）。ADR 0006 的四个事实（`turn/*`、`model/*`）每一个都要带记录行号，票 05 的游标
也建在它上面——于是「行号什么时候可得」变成了整族事件的地基问题。

**而它今天已经不在任何用户看得见的路徑上了**：`POST /api/agent` 只回一个 ack，run 从 socket 上读。
异步最初要保护的那条「响应路径」早就不存在了。

## 决策

1. **`append!` 在调用者线程上完成这次写**，写完**当场**交出这一行的偏移；`lands` 就地调用
   （签名不变，调用点一个都不改），只是它不再等一条线程。
2. **不 fsync、不等 ack。** 「崩溃少丢一批」不是这里要买的东西：run 不靠记录继续（0002 决策 1，
   内存是权威）。**fsync 只在三个时机**：会话被放下、进程退出、每 N 批（N 见票 04）。
3. **一行一次写，但句柄拿着不撒手。** 今天每行是 `spit :append true` ⇒ **每行一次 open/write/close**。
   改成一个按 thread 持有的句柄（首次写时打开），一行一次 `write` —— 省的是 syscall，不是 fsync。
4. **失败逐行 catch，记录降级，run 照跑。** 今天这条保证是写手线程给的（队列停在原地、thread 标
   DEGRADED、run 不受影响）；同步之后必须在**调用者**这一层把异常接住并说出去，否则一块满盘会
   从帧循环里带走一次 run。**这条是本决定的代价，不是可选项。**
5. **`pendig` 那套随之塌掉**：`pending-count` 恒 0、`pending?` 恒 false、`retry!` 不必存在（下一行
   自然重试）。它们在读侧的几处调用点（`stats-get` 的 `:behind`、`sessions/evictable?`）逐处核过。

## 代价

- **写落在帧循环与工具线程上**：一次 `write`（不再是 open+write+close）+ 锁。这是被换来的东西，
  要实测记下来（一次 run 的帧循环上多了多少微秒）。
- **失败的落点变了**：从「写手线程降级」变成「调用者逐行接住」。接不住就是 run 被一块满盘带走。
- **`record.clj` 的形状要改**：队列、consumer 线程、`retry!`/`serve!`/`consume!` 那一段退役；
  `prepare`（carry-back）与 `sink`（测试的探针）两条缝**必须原样保住**，否则
  `record_test` 与 `carry-back` 那套证据一起消失。
- **读侧的几处算术踩在 `flushed-seq`/`pending-count` 上**（`:behind`、条目编号），要一起核。

## 边界（不做的事）

- **不改记录格式**：仍是一行一帧、`message` / `event` 两种行（ADR 0003 决策 9 不变）。
- **不在每行存 `seq`**：行号就是它，存一份是同一件事实的第二份（`model/start` 拒绝 call 计数器的
  同一条理由）。
- **不改行号语义**：`seq` 仍是「这一行是文件里的第几行」。
- **不引入 ack**（决策 2）：没有任何调用点需要等一次落盘。

## 落地

`.scratch/event-persistence/spec.md` 票 01（`record.clj` 同步化 + 句柄）与其依赖它的票 02/04。
**ADR 0006 的票 03 卡在行号上，本决定是它的前置。**

## 落地（票 02 / 04，2026-09-26）

**票 02 是这一条的推论，不是新决定。**「有界队列 + 背压」要治的那个**无界队列**，正是决策 1/5 删掉的
那一个：今天写一行就是一次同步写，而写它的那条线程读的是 `harness.kernel.loop/run-chan` 的**无缓冲**
通道（生产者 `>!!` 阻塞），所以磁盘一停 run 就停——压力顶在消费 vendor SSE 的那一处，不是涨内存。
判据是 `harness.edge.http-test/a-stalled-record-write-holds-the-run-instead-of-buffering-it` 那一条：把
断言「只有一行被交出去」并且「这一发 POST 还没被答复」（异步队列那版会让 run 先跑完）。

**票 04 把「三个 fsync 时机」落成动作，并给降级分了四级。**

- **N = 64 行**（`record/fsync-every`）。它是 `def` 而不是调用点的常量，测试才能用 `with-redefs` 把它
  挪小——否则一条要看见「承诺发生」的用例得写 64 行。**0 或 nil 关掉这一个时机**，另外两个才看得清。
- **时机一**：每 N 行，在写锁里紧跟刚落的那一行（`force-due!`）。**时机二**：会话被放下——
  `harness.kernel.session` 新增 `:put-away!` 缝，`sweep!` 与 `drop!` 两个门都敲，装配处
  （`harness.edge.sessions`）接到 `record/fsync!`。**时机三**：进程退出（`shutdown!` → `force-all!`，
  一个文件一次，因为承诺是对文件许的、不是对线程）。
- **fsync 只开一次句柄（调用期间）**：决策 3 的「句柄拿着不撒手」在 Windows 上被撤销过（`real-sink!`
  那一段），而每 N 行一次的 force 不值得把那个决定买回来。**承诺的缝**（`set-forcer!`）与 `set-sink!`
  同形：写进去的字节看得见，问出去的 fsync 也看得见——否则三个时机无法在测试里分开。
- **fsync 失败不改变级别**：字节**在**记录里（读者看得见），悬的只有「崩溃会不会留住它」——那半挂在
  `health` 的 `:fsync` 上，因为两件事的证据不同（一个行数、一个 syscall）。
- **四级**（`record/health`，每级带 `:says` 那句原话）：**L0 `:ok`** 什么都没扣住；**L1 `:behind`** 有行
  被扣住、还没再敲过门（小病）；**L2 `:stuck`** 门敲过了、磁盘又拒了（事故）；**L3 `:lost`** 写手被拆掉
  时还有行扣着——**记录此刻比对话短**，而此后没有任何一次读能分辨。L3 是唯一一个「不是年龄」的级别，
  所以它只能在退出的路上说出来（`give-up!` 一行 `ERROR :record/lost`）。1 与 2 是同一场故障的两个年龄，
  分开正是为了让人不必对一句话脱敏。
- **读侧**：`GET /sofar` 与 `POST /rebuild` 的 `:record` 多两个字段（`:level` / `:says`），`:state` 仍是
  页面认识的那个词（"degraded"）——更细的事实不是新契约。判据在 `record-test`：四个级别各一条、
  一次拒绝的承诺、一次「扣住的线重试成功之后回到 L0」。
