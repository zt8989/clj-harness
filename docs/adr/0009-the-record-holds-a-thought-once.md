# 0009 —— 记录里的推理只存一份：来自模型那一行，不来自推理帧

- **日期**：2026-09-26
- **状态**：**已采纳**
- **边界**：本决定**不推翻** `0003-the-feed-is-a-window.md` 的窗口代数（条目号仍然是记录行号）、
  **不推翻** `0004-the-downlink-is-websocket.md`（帧仍然从 `events.mux` 下行）、
  **不推翻** `0005-sessions-own-the-record-stream.md`（会话拥有那条记录流）、
  **不推翻** `0006-turn-and-model-call-are-the-two-levels.md`（轮与模型调用还是两级）、
  **不推翻** `0007-the-record-is-written-synchronously.md`（一行写完当场拿到偏移）、
  **不推翻** `0008-the-log-is-the-truth-and-sqlite-projects-it.md`（记录是真相，库是投影）。
  它只改**记录持有哪一族帧**，而且**只改记录**：线上（帧数、粒度、顺序、`mux` 的高水位与回放）一个字不动。
- **取代**：`harness.edge.http/runner` 的 docstring 里那句 **「log every frame」** 与
  `.scratch/jsonl-two-kinds/spec.md` 的那句 **「同一段记录，今天线上发过什么帧，重建就该得到什么帧（逐帧相同，id 也在里面）」**。
  两句都**只在记录这一侧失效**：线上照旧一帧不少，重建出来的帧流照旧与线上同形——除了那五族。

## 背景

1. **推理的帧是厂商的粒度，推理的事实是模型自己的返回。** `REASONING_MESSAGE_CONTENT` 一行 197 字节、
   delta 中位数 **3 个字符**（实测，`.scratch/reasoning-out-of-the-record/evidence/README.md`），而同一段文字
   已经在 **run 自己那条 `message` 行**的 `reasoning_content` 上——那一行本来就是 provider 形状的一部分
   （`harness.kernel.llm` 的契约：返回的消息按 VERBATIM 交给 history；`harness.edge.trajectory` 读的也是它）。
   一次会话里同一段思考因此有两份，帧那一份被逐 token 撑大约 **52 倍**。
2. **这是今天日志的大头。** 真记录 `ed334c9c-…`：**52,248,775 字节 / 225,918 行**，其中推理五族
   **209,011 行 / 41,896,984 字节 = 80.2% 的字节、92.5% 的行**；去掉之后 **10,351,791 字节**，
   整份解析从 **1010 ms 降到 267 ms**。旧记录 `fa35f356-…`：**68,188,479 字节 / 237,012 行**，
   五族 **44,974,083 字节 = 66.0%**（里面还混着改动前 `model/start` 抄的工具表，那是 0004 另算的账），
   去掉后 **23,214,396 字节**，解析 **1256 ms → 300 ms**。证据与可重跑脚本在
   `.scratch/reasoning-out-of-the-record/evidence/read_routes.{clj,txt}`。
3. **这一类「同一张表抄几百遍」本仓已经改掉一件了**：`model/start` 曾经每次调用都把整张工具表再记一遍
   （ADR 0004：`bbcd4ae4-…` 里 672 行占 50.2 MB）。本决定是同一件事剩下的那一个，而且它是 82%。

## 决策

1. **记录不写 `REASONING_*` 帧**——`REASONING_START` / `REASONING_MESSAGE_START` /
   `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END` / `REASONING_END` **五族一起不写**。
   **线上一帧不少**：`mux-broadcast!`、子 agent 那条 `frame-bus/publish!`，以及 `runner` 收进
   `state` 的 `:frames`（`settle!` 折**内存里**那场会话用的就是它）一个字不动——所以记忆里的会话、
   屏幕上的流、客户端收到的东西与改动前完全一样。
   **只丢 CONTENT 是错的**：`harness.kernel.frames/apply-frames` 光凭 START 就造出一条**空的**
   reasoning 消息，折法里的 `:reasoned?` 守卫于是以为「帧已经带过推理」而跳过模型行那一份。实测过。
2. **折法改从模型那一行取**：assistant `message` 行（信封 `:source "model"`）的 `reasoning_content`
   → 那条 assistant 消息的推理，折出来**仍是 `role "reasoning"` 的那条消息**（形状、位置、id 都不变）。
   于是 `harness.edge.ag_ui/inbound`（把 reasoning 折回 assistant 的 `reasoning_content`）、客户端、
   `rebuild` / `sofar` / `page` 的载荷**一个字段都不用改**。
3. **事实只存一份**：`reasoning_content` 留在模型行上，记录里不再有第二处。
4. **两种日志都要读得对，而且旧日志永远读得对**：折法先看帧（`:reasoned?`）、再看行，不是二选一地赌一种。
5. **匹配规则按本仓已有的那条来：按顺序配，而且只在折里配。** `message` 行不带 AG-UI 的 message id，
   但 `model/start` ↔ `model/end` 就是按顺序配的，并且写明了为什么不记一个 call id
   （`harness.kernel.event/model-start`：「A counter in the record would be the same fact written a second
   time, and two copies drift」）。推理是同一个形状：一次 run 里「帧折出来的 assistant 消息」与
   「它自己写的 assistant 行」各一份、都在记录里。**往信封上补一个 `:message-id` 只会往只追加的记录里放一个
   推得出来的字段**——所以不补。
6. **重建出来的 id 与线上同一个。** `<run>-r<n>` 里的 `<n>` **不是思考的序号**：一个 run 只有**一个**计数器，
   文本、推理、**注入卡**、工具结果都从它取号（`harness.edge.ag-ui` 的 `:n`），所以一次带工具往返的 run 号是
   `r0, m1, t2, r3, m4`，而先被塞进一张卡的那一场是 `ctx1, r1, m2, t3, r4`——两次调用的第二次思考是 **`r3`**、
   有卡时是 **`r4`**。折侧从**这一 run 记下来的帧**里数出来（数到那条 assistant 消息之前开过几个组），
   不写进记录。id 是客户端给消息记账的名字，重建成另一个名字就是同一个思考被画成两条。**这一条是走查抓到
   的**：夹具里那几场 run 一张卡都没有、全绿，真记录里带卡的 run 一折就错——164 条推理消息的 id 比线上小。
   卡不是「模型返回的消息」（`:model-ids` 拿 `string?` 把它排除在配对之外是对的），但它**在线上确实占一个号**。
7. **配不上就不配，而且说出来。** 一次模型调用应当恰好对应一条 assistant 消息；数量不等时
   （一次调用什么都没返回、run 中途断了、行先于帧到达）**不贴、不猜**，并在进程日志里留一行
   （`replay/unpaired-model-row`，一个 run 一行）。

## 后果

- **一场会话的 80% 的字节不再写。** 上表两场各自去掉五族后的实测体积与解析耗时见证据；线上与内存不变。
- **断掉的 run 丢掉自己那半段思考。** 模型那一行是 `:run/done` 才写的，帧是流出来就写的。今天一个被杀掉的
  run 至少留下它想过的那半段；改后一段都不留。**答案不受影响**——文本帧照旧逐条写（把文本 delta 也合批
  是另一件事，`.scratch/event-persistence` 票 03 的一半，未做）。这不是理论：旧记录 `fa35f356-…` 里就有
  **一个这样的 run**（`3f547396-…`：52 条 `REASONING_MESSAGE_START`、0 条 model 行），走查时它是
  328 段推理与 276 段之间的那个差额。**把那个 run 的推理也一并摘掉，两种折法就逐条相等了**（走查 D 段：
  `with those runs' reasoning dropped too, message lists equal: true`）。
- **只含空白的思考：帧那一路会画出一条空消息，行那一路不画。** `attach-reasoning` 把空白行读成「这一次
  调用没报推理」——这是本仓要的那个诚实答案，而一条说空话的消息不是。代价是一处**不可恢复的不对称**：那段
  空白在记录里不留任何痕迹，所以那一组在线上占的那个号补不回来，**同一个 run 里它之后的推理消息 id 都会小
  一**（实测：新那份 52 MB 的记录里有 **4** 组空白思考、其后 **137** 条推理消息的 id 与线上差一，分布在两个
  run 上；正文与顺序一字不差）。这两种日志**不是**逐字节相同，是有意的，ADR 知道它是哪一种。
- **「旧日志永远读得对」是量过的**：`rebuild` / `sofar` / `page` / `trajectory` 四条读路由在那份 68 MB 的真
  旧记录上各跑一遍，推理都在，且与 `trajectory` 读到的 `reasoning_content` 是同一段话（排除上面那个没有
  model 行的 run 之后逐段相等；`page` 是它的连续切片）。证据同上。
- **契约的文字改了**：`runner` 与子 agent sink 的 docstring、`harness.edge.replay/rebuild-post` 的说明、
  `docs/architecture/edge.md` 的帧族、`docs/architecture/home-and-storage.md` 的记录内容、以及
  `docs/architecture/overview.md` 里「已执行的对话记录」那张表。判据那边，
  `harness.edge.http-test/the-record-keeps-every-wire-frame-except-the-reasoning-family` 从「逐帧相同」
  改成**「记录的帧 == 线上的帧减去那一族，且线上确实带了那一族」**——把这件事钉在一条可执行的句子上，
  而不是一句注释。
