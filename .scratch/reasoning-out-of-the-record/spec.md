# spec: 思考只存一份 —— 推理帧不再进记录

主人（2026-09-25）拍定：那些思考帧（今天日志的 **81.7%**）按 **「只从记录里去掉推理帧，线上照发」** 走。
另一条（在记录里把 delta 合批）被否，理由是它会让**同一个事实在记录里仍留两份**：合批后的帧一行、
模型那一行 `reasoning_content` 一处。

## 症状与现场

**最近写下来的一场**（`ed334c9c-….jsonl`，主人今天还在用）：**39.93 MB**，按内容拆开——

| | |
|---|---|
| 推理五族（`REASONING_*`） | **170,409 行 / 32.59 MB = 81.7%** |
| 它们携带的思考文字 | **641,298 字符 ≈ 0.63 MB** |
| 其余 | **31.7 MB = 98.1% 是帧的壳** |
| 每行开销 | 约 **196.8 字节**（delta 中位数 **3 个字符**，p90 是 7，最长 50） |
| 有推理的那些秒（`fa35f356` 那一场量的） | 每秒 **p50 102 帧**、p90 170、p99 186、峰值 290 |

而**同一段思考已经在记录里**：模型那一行 `message`（envelope `:source "model"`）自带
`reasoning_content`（同一段文字，0.6–0.69 MB 量级）—— `harness.edge.trajectory` 读的就是它（`trajectory.clj:524`、`:815`）。

所以那 32.59 MB 是**同一个事实的第二份**，被厂商逐 token 的粒度撑大了约 52 倍。

**这一类「同一张表抄 392 遍」的事，本仓已经改掉一件了**：`model/start` 曾经每次模型调用都把 45,410 字节的
工具表再记一遍（同一个 `bbcd4ae4-…` 事故：672 行、129.7 MB 的日志里 50.2 MB 是它），2026-09-24 改成只留
signature（`harness.kernel.event/model-start` 的 docstring 记着），整张表挪到 system 那一行的 envelope 上。
本特征是**同一件事剩下的那一个**，而且它是今天日志的 82%。

一帧的全部内容（197 字节，`delta` 三个字符）：

```json
{"ts":1790161130998,"runId":"65837d52-…","type":"event",
 "payload":{"type":"REASONING_MESSAGE_CONTENT","messageId":"65837d52-…-r0","delta":"Let"}}
```

## 根因

`harness.edge.http/runner`（以及子 agent 那条 sink，同一文件 2024 行附近）对**每一帧**做两件事：
写一行记录、广播出去。这条「记录与线上逐帧相同」是**有意**的契约，原话就在 `runner` 的 docstring 里。

问题在于：**推理的帧是厂商的粒度，推理的事实是模型自己的返回**。后者已经作为 provider 形状的消息
落在 `message` 行上（`harness.kernel.llm` 的契约：「返回的消息按 VERBATIM 交给 history」），前者只是
同一段文字的传输形式。

## 决策

1. **记录不写 `REASONING_*` 帧**——`REASONING_START` / `REASONING_MESSAGE_START` /
   `REASONING_MESSAGE_CONTENT` / `REASONING_MESSAGE_END` / `REASONING_END` 五族。**线上一帧不少**：
   `mux-broadcast!` 一个字不动，`runner` 收进 `state` 的 `:frames` 也一个字不动（`settle!` 折叠内存里
   那场会话用的就是它），所以**记忆里的会话与今天完全一样**。
2. **折法改从模型那一行取**：assistant `message` 行（envelope `:source "model"`）的 `reasoning_content`
   → 那条 assistant 消息的推理。折出来的**形状与今天逐字节相同**（仍是那条 `reasoning` role 的消息），
   于是 `harness.edge.ag_ui/inbound`（它把 reasoning 折回 assistant 的 `reasoning_content`）、
   客户端、`rebuild` / `sofar` / `page` 的载荷**一个字段都不改**。
3. **事实只存一份**：`reasoning_content` 留在模型行上（它本来就是 provider 形状的一部分，`trajectory`
   也读它），记录里不再有第二处。
4. **两种日志都要读得对**：旧日志（有推理帧）与新日志（没有）并存，而且**旧日志永远读得对**——折法先看
   帧、再看行，不是二选一地赌一种。
5. **匹配规则按本仓已有的那条来：按顺序配，而且只在折里配。** `message` 行不带 AG-UI 的 message id
   （`.scratch/reasoning-round-trip/spec.md` 决策 7 已经点名），但 `model/start` ↔ `model/end` 就是**按顺序**
   配的，并且写明了为什么**不**在记录里记一个 call id（`harness.kernel.event/model-start`：「A counter in the
   record would be the same fact written a second time, and two copies drift」）。推理是同一个形状：一次 run 里
   两份 assistant 列表，都在记录里。写侧与折侧手上是**同样这两份表**，所以往 envelope 上补一个 `:message-id`
   只会往只追加的记录里放一个推得出来的字段。**配不上就不配，并且说出来**——数量不等时不许猜。

## 代价（照实记）

- **契约改了，必须进 ADR。** `runner` 的「记录与线上逐帧相同」与 `.scratch/jsonl-two-kinds` 的「记录持有
  它能被重建出来的那套词汇」这两句都要改。不改的后果不是学术的：下一个人会照旧契约读代码，以为推理在帧里。
- **断掉的 run 丢掉自己那半段推理。** 模型那一行是 `:run/done` 才写的，帧是流出来就写的。今天一个被杀掉的
  run 至少留下它想过的那半段；改后一段都不留。**答案不受影响**——文本帧照旧写。
- **配对靠顺序，和 `model/start` ↔ `model/end` 同一条规矩**（那条规矩的代价也一样：一份对不上的时候，读侧只能
  说不出来，说不出来就得说「说不出来」）。

## 非目标

- **不改线上**：帧数、粒度、顺序、`mux` 的高水位与回放一个字不动。
- **不动工具表那一件事**：`model/start` 上的 signature 与 system 行 envelope 上的 `:tools` 都照旧（那件事
  2026-09-24 已经改过了，是另一个特征）。
- **不改 `message` 行的形状**：它仍是 provider 形状的消息逐字，`reasoning_content` 仍在 payload 里。
- **不动** `harness.kernel.llm/consume-sse` 与 `thinking-mode-history` 那条往返
  （`.scratch/reasoning-round-trip`）。
- **不改厂商的行为**：不 400 这条今天已经成立（空串也被接受，真机验过），本特征连碰都不碰。

## 验证

- **记录里一条 `REASONING_*` 都没有**，而 mux 上与今天**逐帧一致**（同一次 run 的帧集合、顺序、内容相同）。
- **新日志折出来的会话与旧日志折出来的逐字节相同**——同一场对话，一份带推理帧、一份不带，`entries` 的结果
  相等。这是最强的判据，也是这一票的验收标准。
- 匹配失败**不猜**：有一条用例钉住「贴不上就贴不上」，而且这件事**说得出来**。
- 内存里的会话不受影响：`settle!` 之后 `sessions/messages` 仍带推理。
- **实测数字**（两场，新旧各一）：
  - 旧日志 `fa35f356`（65.03 MB，其中 16.61 MB 还是改动前 `model/start` 抄的表）：推理五族 42.89 MB，
    去掉后约 **22 MB**。
  - 今天的形状 `ed334c9c`（39.93 MB，`model/start` 已经只剩 0.22 MB）：推理五族 32.59 MB，去掉后约 **7.3 MB**
    —— 也就是说**照今天的代码记一遍，推理帧是这份日志的 82%**。
  - 两场里那 0.6–0.69 MB 的思考文字都仍在模型那一行上。
- `clojure -M:test -m harness.test-runner`；动过 `ui/src/` 的话 `cd ui && npm test` 与一次
  `node scripts/dev.mjs --scripted` 的真浏览器走查（这一票按说一个 `ui/src` 文件都不用动）。

## 落地（2026-09-26）——三张票面已删，这一段是「做了什么」

**代码。** `harness.edge.http` 的两个帧 sink（agent 路线的 `runner`、子 agent 那条）不再把 `REASONING_*`
**五族**交给记录；判据收成一处具名谓词 `reasoning-frame?`（放在 `terminal` 旁边，有 docstring 说清为什么是
整族而不是只丢 CONTENT）。`harness.edge.replay` 的折法从模型那一行取回推理（`reasoning-row?` /
`insert-entry-before` / `attach-reasoning`），按「这一 run 的帧造出的第 k 条 assistant 消息 ↔ 它写下的第 k
条 assistant 行」配对。`rebuild` 与 `runner` 的 docstring 改到为真。

**落地时发现两件票面没写到的事**，都改了：

1. **推理消息的 id 编号不是「第几个思考」。** 一个 run 只有**一个**计数器，文本、推理、**注入卡**、工具结果都从
   它取号（`harness.edge.ag-ui` 的 `:n`）：一次带工具往返的 run 是 `r0, m1, t2, r3, m4`，先被塞进一张卡的那一场
   是 `ctx1, r1, m2, t3, r4`——两次调用的第二次思考是 **`r3`**、有卡时是 **`r4`**。折侧从**这一 run 记下来的帧**
   里数出来（`frame-groups-before`：这条消息之前开过几个组），一个字段都不往记录里加。
   **卡那一格是先漏后补的**：夹具里那几场 run 一张卡都没有，用例全绿；真记录（走查 D 段）一折就露馅——带卡的
   run 里 164 条推理消息的 id 比线上小，因为 `frame-groups-before` 当时按「只数模型返回的消息」在数，而卡在线上
   确实占一个号。补上之后是 137（那 137 条另有原因，见下）。
2. **配不上也不能沉默。** 数量不等时（一次调用什么都没返回、run 中途断了、行先于帧到达）折法**不贴、不猜**，
   并在进程日志里留一行 `replay/unpaired-model-row`（一个 run 一行）。

**判据（都用例钉住）。**

- `replay-test/a-run-without-its-reasoning-frames-rebuilds-the-same-conversation` —— 同一场 run 两种写法，
  消息与 provider 形状逐字节相等（不比 `:seq`：两种写法的行数不同，行号是各文件自己的事实，ADR 0003 决策 9）。
- `replay-test/a-two-call-runs-second-thought-lands-on-the-second-message` —— **两次调用**：k 配对 + id 是线上
  那个拼法。
- `replay-test/a-run-whose-rows-outrun-its-messages-is-not-paired-and-not-silent` —— 数量不等：没贴上，且说了。
- `http-test/the-record-keeps-every-wire-frame-except-the-reasoning-family` —— 从「逐帧相同」改成**「记录的帧 ==
  线上的帧减去那一族，且线上确实带了那一族」**（不真空：先在线上找那一族）。
- `http-test/a-run-that-stops-mid-thought-keeps-its-words-and-not-its-thinking` —— **断掉的 run 那条代价**钉住：
  记录里一条推理都没有、文本帧照旧在、内存里的会话仍然带着它。

**走查（`evidence/read_routes.{clj,txt}`，可重跑；`~/.clj-harness` 只读）。** 两场真记录各量一次大小、行数与
整份解析耗时，并在**旧那份 68 MB 的真文件**上跑通 `rebuild` / `sofar` / `page` / `trajectory` 四条读路由：
推理都在，且与 `trajectory` 读到的 `reasoning_content` 是同一段话。

| | 原样 | 推理五族 | 去掉之后 | 解析（原样 → 去掉） |
|---|---|---|---|---|
| 新 `ed334c9c` | 52,248,775 B / 225,918 行 | 209,011 行 / 41,896,984 B = **80.2% 的字节** | 10,351,791 B | 1010 ms → **267 ms** |
| 旧 `fa35f356` | 68,188,479 B / 237,012 行 | 224,308 行 / 44,974,083 B = **66.0%** | 23,214,396 B | 1256 ms → **300 ms** |

（比 spec 里那两行旧数字大：这两场在被量之后又长了。）

**「两种写法逐条相同」在真文件上量到的是一句话加两笔账。** 走查 D 段把**两份真记录各自去掉推理族再折一遍**，
与原件比：

- **旧那份**：只差上面那**一个没有 model 行的 run**（52 段，逐段对得上）。**把那个 run 的推理也摘掉，两份
  逐条相等**（`with those runs' reasoning dropped too, message lists equal: true`）——旧记录 100% 读得对。
- **新那份**：**4 段只含空白的思考**（一个空格）帧那一路画出空消息、行那一路按 `str/blank?` 守卫不画（故意的，
  一条说空话的消息不是本仓要的答案）；而且**那段空白在记录里不留痕迹**，那一组在线上占的号补不回来，所以
  同一个 run 里它**之后的 137 条推理消息 id 比线上小一**（正文与顺序一字不差，分布在两个 run 上）。**这一格
  补不了**——记录里没有「这里曾经有过一个空白思考」这条信息，猜就是错的。所以两种日志**不是**逐字节相同，
  ADR 0009 把它写成知道的代价，不是通过。

**测试**：`harness.edge.replay-test` 34 用例 / 190 断言，`harness.edge.http-test` 109 / 1207，全量 1289 / 13810
（唯一的红是这台机器上本来就红的 `pressure_test.clj:344`，`.scratch/compaction-shape/spec.md` 有记录）；
`cd ui && npm test`（158 通过）/ `npm run typecheck` / `npm run build` 各一遍——**一个 `ui/src` 文件都没改**，
跑它是为了证明这一点（这三条在主树跑，worktree 里没有 `node_modules`；两棵树里的 `ui/src` 逐字节相同）。

**ADR。** `docs/adr/0009-the-record-holds-a-thought-once.md`：写清它**不推翻** 0003 的窗口代数、0004 的下行、
0005 的「会话拥有记录流」、0006 的两级、0007 的同步写、0008 的投影，只改**记录持有哪一族帧**；并引原文写明它
**取代**哪两句（`runner` 的「log every frame」与 `jsonl-two-kinds` 的「逐帧相同，id 也在里面」）。票面写的
`0006` 是写票面时还没被 0007/0008 占掉的号。三处 architecture 文档改到为真（`edge.md` 的帧族、
`home-and-storage.md` 的记录内容与读侧、`overview.md` 那张表），`rg` 过一遍没有漏下的副本。

**还没做的（不在本目录三张票里）。** 文本 delta（2%）照旧逐条写；把**它**合并成快照是
`.scratch/event-persistence` 票 03 的另一半，没动。
