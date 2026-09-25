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
