# 0005 —— 会话成为机制：记录流的所有者，其他都是订阅者

- **日期**：2026-09-24
- **状态**：**已采纳**
- **不推翻** `docs/adr/0002-sessions-live-on-the-server.md`（会话归服务端：内存是权威、记录异步落后）
  与 `docs/adr/0003-the-feed-is-a-window.md`（记录格式与窗口的契约）——本决定是它们之下的一次**分层**，
  不是一次行为变更。
- **推翻**「一次装会话折记录五到六遍」「压力表自己一个 atom、第一次问它读一遍记录再靠写入口顺手喂」
  「每个读法各自开文件」这三件事（`.scratch/session-as-kernel/spec.md`）。

## 背景

**一份记录，五个读者，各折各的。** 这是本决定的全部由来。

1. **装会话折五到六遍**：`entries` 一遍、`state` 一遍、`messages`（又调 `entries`）一遍、
   `compactions` 一遍、`prunes` 一遍，而且 `read-records` 把整份文件物化。一次打开会话的成本因此
   不是它的长度，是文件的大小——而窗口、feed、rebuild、state 都是这条路的入口。
2. **压力表有一条旁路**：它自己一个 atom，第一次被问时**读一遍记录**（播种），之后靠
   `harness.edge.http/log!` 顺手喂。run 开头那次测量因此要么付一次读，要么等一次读——而它是
   run 自己的路径。
3. **读法各自开文件**：`stats` / `trajectory` / `context` 三条路由各自 `read-records`；它们说的是
   同一份记录的同一件事的不同侧面，却各自去问文件。
4. **写侧认识一个具体消费者**：`log!` 里有一段「顺手喂压力表」的特殊分支——加一个消费者要动它。

四条指向同一句话：**会话（一场对话的服务端状态）本该是记录流的所有者**。谁往记录里写一行、谁点开
一场会话，都由它一次说清；其他读法都是**订阅者**，从它身上取，不各自去开文件。

## 决策

1. **会话提供两条流**：读 jsonl 的流、写 jsonl 的流。其他都是消费者，**订阅**这两条流。点「加载
   会话」时，run 需要的那几样（对话 + 表针）自然就准备好了。
2. **装会话一次走查**：`replay/fold-sofar` 在一条流上把对话、状态、压缩/剪枝事实**和每个登记过的
   折子**一起折出来，结果落在会话行上。峰值堆是**对话**而不是文件。
3. **两道订阅缝，由机制定义**：
   - **读流**：`(register-fold! name {:init .. :step (fn [acc ctx [line-index row]] acc)})`——装会话
     时按文件顺序逐行喂给每个折子，最终值落在会话上（`fold-value`）。`ctx` 是**走查到此刻的对话**，
     因为有的消费者（压力表的锚点）要的是「那一行当时那份 prompt」。
   - **写流**：`(register-step! name step)`——唯一写入口 `harness.edge.http/log!` 把每一行交给会话
     （`row-written!`），订阅者**原地**推进自己的值。`log!` 从此**不认识任何具体消费者**。
4. **压力表是第一个订阅者**：表针（band）就是 `band-step`，同一份实现在三个调用点跑——装会话那次
   走查（读流）、每写一行（写流）、离线的 `meter-of-records`。于是 run 开头那次测量**零读**，且与
   离线折出来的答案逐字段相等（`harness.edge.pressure-test/the-live-band-and-the-record-fold-answer-the-same-thing`）。
5. **机制住 kernel，适配住 edge**：`harness.kernel.session` 持有按 thread 的状态、两道缝、窗口算术、
   run / stop 钉子，而且**不 require `harness.cap.*` 或 `harness.edge.*`**（`harness.layers-test` 看着
   这条）。外来的事实从 `install!` 的**缝**里进：
   `:build`（记录是什么）、`:model-messages`（provider 能拿到什么）、`:read` / `:fold`（记录的字怎么读）、
   `:claim`（谁在服务这场会话）。`harness.edge.sessions` 只装这些缝，再把机制按原名再导出。
   **组合根 `harness.edge.http/start!` 装上它们**——没有「require 即注册」。
6. **铁律不变，并写进机制**：**run 进行中内核不读自己的记录**。会话一生只读一次，就是出生那次走查
   （`:build`）；run 里只有订阅者的实时 step。

## 后果

- **加一个消费者**只登记一个折子或一个 step，**不动 `log!`**（验收：`.scratch/session-as-kernel`
  票 02–04）。
- **三条读路由**（`stats` / `trajectory` / `context`）向会话要记录（`read-records`），不再自己开文件
  （票 05–07）。
- **记录格式、AG-UI、窗口/feed 的对外行为一字不变**（守 0003）。
- **会话机制只有一份**（`harness.kernel.session`）；`harness.edge.sessions` 不含状态，只有缝与再导出。
- **边界有代价，写在明处**：`harness.kernel.session` 为每一道缝装了一个默认值，所以没有适配时它照样
  加载、照样答——空走查、不认领、原样交回 entries、读流说「没有记录」。因此「为什么这个测试有真会话」
  的答案是**安装那一行**（`sessions/install!`），不是「别的命名空间被 require 过」——
  与 `harness.kernel.tools/install!` 同一条规矩（见 `docs/architecture/layers.md` 的「能力怎么进核心」）。
- **不走 `log!` 的写者没有实时 step**：记录仍由会话的 fold 在出生时折进来（写者只要往文件写，
  下一次装会话就有），实时的那份只有一条路——所以这条路的诚实说法是「订阅者看到的是**走这条路的
  行**」，不是「所有行」。今天唯一的写者就是 `log!`。
