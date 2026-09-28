# spec: 记录是一条流——一个写队列、两种读法

**Status: ready-for-agent**

主人 2026-09-28 拍的方向，原话：

> kernel 应该需要提供写队列，由 infra 来保证写入成功，kernel 的事件和消息，内核 push，
> edge 的事件和消息依赖 kernel 来 push 队列。读的机制分两种：一种是拉流，挂消费者；
> 还有一种是推流，挂监听器，新增就推送。

## 今天是什么样

| 方向里说的 | 今天 | 差在哪 |
|---|---|---|
| 写队列 + infra 保证写成功 | `harness.edge.record`：**同步写**（ADR 0007），`append!` 当场答行号；写不进去的行**按线程攒住**、`retry!` 重投、**不许留洞** | 承诺是对的，但主人是**边**、只覆盖边的行；内核的事件走另一条路（`run-chan` 一个无缓冲通道） |
| 内核 push 事件与消息 | 内核只 push **事件**（`>!!`）；**行是边写的** | 行由边代写：内核没有把「这条消息要落盘」push 出去 |
| 边的行依赖同一条队列 | 边的行确实只经**一个** writer ✓ | 那是边自己的 writer：两个生产者、一条隐式通道，不是一条队列 |
| 读：拉流 / 推流 | **两种都有，只是没有共同的名字**：拉 = `harness.edge.replay` 折文件（轨迹 NDJSON、窗口取页）；推 = `harness.edge.mux`（事实环 + 订阅）与 `harness.edge.sessions/watch!` | 两套游标、两套语义；而且读者拿到的行**没有「谁写的」这个字段**，只能靠位置猜 |

## 于是

**一条流，一个队列，两个生产者，两种读法。**

```
harness.infra.stream      ;; 新：队列 + 两种读法，唯一主人
  (open!   {:sink …})     ;; infra 收下「写成功」的承诺：重试、fsync、不留洞
  (push!   s item) -> n   ;; 同步答号：n 就是行号，也是 :seq
  (listen! s f) -> off    ;; 推流：新增就推（mux 的订阅、门铃都是它）
  (consume! s cursor f)   ;; 拉流：挂消费者（replay 的折法、轨迹、窗口）
  (after   s cursor)      ;; 断线补齐：同一条游标语义
```

- **两个生产者**：内核 push 它的事与消息；边 push 请求批、帧、事实。**一条队列，一个号源。**
- **每一件 item 自带「谁写的」**（`{:producer :kernel-event | :kernel-message | :request | :frame | :fact}`）。
- **infra 是唯一承诺写成功的人**：重试、fsync、不留洞——今天这些在 `harness.edge.record` 里，
  是边的名字，但一直是 infra 的活。

## 要保住的既有决定（一个字不改）

- **号必须同步答**（`push!` 的返回值，不是回调）；**号 = 文件里的行序**（ADR 0003 决策 1/9，重放性靠它）；
- **写失败攒住 + 点名重投，不许留洞**（今天 `record` 的那条纪律）；
- **`go` 块里不许调它**（写是同步的，会占住 core.async 的调度线程）。

## 它顺手收掉的两处红（`.scratch/record-envelopes` 的尾巴）

1. **轨迹的「哪一侧」**：今天按位置猜（run 的第一个 `event` 之前 / 之后），resume 那种
   「先答后提交」会被判错。有了 `:producer`，这是**读字段**。
2. **pressure 的锚点前缀**：今天折法把答案算进「这次请求已经带了」（活表不算），差 9 token。
   同一批 item 就是「这次请求由谁拼成」，也是读字段。

## 票

| # | 票 | Blocked by | 交付 |
|---|---|---|---|
| 01 | `harness.infra.stream`：队列與两种读法，`record` 搬进来 | 无 | 行为一字不改地换主人：同步答号、攒住重投、不留洞；监听器与消费者各有一条用例 |
| 02 | 内核 push 它的消息 | 01 | 内核拿到这一轮的流，`:message/added` 变成 push 一行；边不再代写内核的消息 |
| 03 | 边的行走同一条流 | 01 | 请求批、帧、事实都 `push!`（并带上自己的 `:producer`） |
| 04 | 两种读法上台面 | 02、03 | mux 与门铃挂 `listen!`；replay / 轨迹 / 窗口挂 `consume!` + `after` |
| 05 | 两处红改读字段 | 04 | 轨迹的哪一侧、pressure 的锚点前缀，都读 `:producer` |
| 06 | 收口：ADR、两份文档、真机走查 | 05 | 新 ADR（谁写、谁保证、两种读法）；`edge.md` / `kernel.md` / `CONTEXT.md` 改口 |

## 判据

- **号**：同一会话连写 N 行，`push!` 依次答 0..N-1，且就是文件里的行序（旧用例 + 一条新的）；
- **不留洞**：写失败时那些行仍然按序攒住、`retry!` 之后字节与从没失败过一样；
- **两种读法**：一条新行既推给监听器、也能被一个从旧游标起的消费者读到（各自一条用例）；
- **旧记录照旧**：读侧两种形状都认（`.scratch/record-envelopes` 已经立过这条规矩）；
- **两处红变绿**：轨迹的哪一侧与 pressure 的活表/折法相等。

## 落地：票 01（2026-09-28）

`harness.edge.record` **整份搬进 `harness.infra.stream`**：文件句柄、fsync 策略、按线程攒住与
`retry!`、`prepare-with!` 的 re-base、`set-sink!`、metrics —— 纪律一条不松，只是换了主人（infra）。
入口改名 `push!`（原先的 `append!`），调用点只换名字：`record/…` → `stream/…`，**事实名那些字符串
（`"record/header"` 等）一个没碰**。

**两种读法上台面**（同一个 `push!` 喂）：`listen!` 是**推流**（新增就推给挂上的监听器，锁外调用，
所以监听器不许阻塞）；`consume!` / `after` 是**拉流**（按游标补齐再继续听）；`reset-readers!` 给用例
清内存那一半。**`push!` 仍然同步答号**（ADR 0007 与 `:seq` 的语义一个字不改）。

**判据**：`harness.infra.stream-test` + `harness.edge.sessions-test` = **47 / 224 / 0**；
`stream-test` + `claims-test` + `replay-test` + `projection-test` + `sessions-test` + `http-test`
= **215 / 1753 / 3**，那 3 条**全是既有的那一处**（`an-answer-lands-behind-its-call…`，即票 05 要收的
「哪一侧」），**这一刀没多出一处红**。

**下一站**：票 02 与票 03（都被 01 解锁），之后 04 → 05 → 06。

## 落地：票 03（2026-09-28）

**每一行都说自己从哪来**：行的信封多一个 `:producer`，取值为
`record`（记录自己的家具：head 行、carry-back 的审计行）／`kernel-event`（由内核事件派生的审计行）／
`kernel-message`（内核产出的消息，含它的注入与重放答复）／`request`（这一轮为某次调用写下的请求批：
prompt、动作的条目、指令更新、压力读数）／`frame`（线上帧）／`fact`（以 CUSTOM 帧形态落行的那些事实）。

- **一个咽喉决定默认值**（`harness.edge.http/producer-of`）：`message` 默认 `kernel-message`、
  `event` 看是不是 CUSTOM（是则 `fact`，否则 `frame`）、其余 `kernel-event`；
- **写批次的两个人自己说**（`*producer*` 动态绑定，在请求批与 message 行两处）：请求批绑 `request`
  （绑定在 thunk 里**重新捕获**，因为那个 thunk 是稍后才跑的），消息行绑 `kernel-message`；
- `harness.infra.stream/push!` 收一个可选 `{:producer …}`，把它带进**内存 item**（读者两种都能用）；
- **一个绕过也补上了**：`carry-audit!` 是直接 `spit` 的（不走 `log!`），行上没有 producer —— 用例抓到了，
  现在它也是 `:record`。

**判据**：`records-the-run-as-jsonl` 里新增一段断言——每一行都有 `:producer`、请求侧说 `request`、
返回的消息说 `kernel-message`、线帧说 `frame`、且**所有取值都落在那六个名字里**（值在文件里是**字符串**：
JSON 没有 keyword）。`http-test` = **115 / 1246 / 3**，那 3 条仍是既有的、票 05 要收的那一处。

**下一站**：票 02（内核 push 它的消息）——它一落地，04 就解锁。

## 落地：票 02（2026-09-28）

**内核拿到这一轮的流，自己写它的消息**，边的角色回到「写它自己那批」。

- **门是边的**（`write!`）：解析文件、拼行的信封、并且替内核办掉边欠一条外来的行的那两件事——
  先把开着的正文收进记录（一条消息行不许切开一次 run 的帧组），再报账（`:reported`），
  于是 `:run/done` 分得清「写过」与「忘了」；两条路由（agent 与 subagent）都接了这道门。
- **顺序靠栅栏**（新事件 `:drained`）：内核写完自己那条之前，先问消费者「前面发的都排空了吗」，
  消费者在**排空循环里**把 promise 兑现。**问的是 run 自己的通道**，不是调用自己那道闸——
  那道闸在 attempt 结束后会丢帧（这才是对的），控制事件走那里会被丢掉，内核就会白等到死线。
- **`:message/added` 退休**：内核不再「报给边、边去写」，那条事件从词表里删掉了。
- **消费者多了一项义务**：`harness.kernel.loop-test` 的离线排空器也要答 `:drained`（答了但不记录，
  像真实边不写行一样），否则栅栏白等五秒，把 idle 守卫踩响——这个坑一跑用例就露出来了。

**判据**：`loop-test`+`ag-ui-test`+`tools-test`+`trajectory-test`+`pressure-test`+`replay-test`+
`stats-test`+`context-test`+`sessions-test`+`stream-test`+`http-test` = **385 / 2496 / 4**，
四条**全是既有的那两处**（pressure 的活表与 resume 的返回侧，都是票 05 的账），**这一刀没多出一处红**。

**下一站**：票 04（两种读法上台面），随后 05（把那两处红改读 `:producer`）与 06（收口）。
