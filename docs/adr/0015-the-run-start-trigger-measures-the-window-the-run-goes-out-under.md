# 0015 —— run 开头那次压缩量的是「这一轮要发出去的窗口」，不是记录里上一通的

- **日期**：2026-10-02
- **状态**：**已采纳**
- **边界**：本决定**不推翻** `0014-the-mid-run-trigger-folds-only-when-the-request-would-not-fit.md`
  （轮中那个触发点的判据照旧是**窗口**、run 开头照旧是**阈值**），**不推翻** `0013`（摘要请求的形状），
  也不动读数本身的口径（`harness.edge.pressure` 的锚 + 增量估算）。
  它只改 **run 开头那个触发点把哪一扇窗口放进了分母**——`0014` 里那句「run 开头的触发点一个字不动」
  说的是**判据**不动，不是它的输入也不动。
- **修正**：`harness.edge.http/compact-if-pressured!` 自落地起读的是 band 自己的窗口
  （`state->pressure` 的 `(or (:context-window latest-start) timeline-window)`），而那是**上一次调用**
  那个模型的窗口。

## 背景

1. **两个窗口只在「中间没换过模型」时才相等。** band 的窗口来自记录：最后一条真 `model/start` 行上的
   `:context-window`，或者最后一条 `provider/init` / `provider/changed`。run 开头这个触发点跑在**这一轮
   自己的 provider 解析之前**（`run-agent!` 里 `compact-if-pressured!` 在 `providers/current-provider`
   之前约八十行），于是它拿到的是**前一通调用的模型**。
2. **换一个模型的路径不止一条，而且有一条不留痕。** 会话的 provider 覆盖住在进程内的一个 atom 里
   （`harness.cap.providers/session-overrides`，由 `POST /api/model` 写），**重启即丢**——没有东西把
   `provider/session-changed` 行折回去。于是「记录以旧模型结尾、这一轮却跑在新模型上」不需要主人做任何
   切换动作，重启一次就够了。
3. **它花过钱，而且是在主人明确换了更大的窗口之后。** 主人自己的会话
   （thread `88f8d8eb-3e10-4114-92a9-1b6ce73c4330`，2026-10-02 09:12）：记录最后一条真 `model/start`
   是前一天 `cn:hy3` 的 `256000`；进程当天 09:12:08 重启，落回 config 的 `cn:deepseek-v4.1-flash`
   （`1000000`）。主人发一句「继续」，run 开头那个触发点读到
   `pressureTokens 215524, windowTokens 256000, percent 84`，越过 `179200` 的阈值，折了
   **160524 个估算 token / 587 个节点（seq 2167–5540）**，代价是一次 **237862 prompt token** 的摘要调用。
   同一份压力按这一轮真正的窗口算：`percent 22`，阈值 `700000`——**本来什么都不该发生**。
   折完保留的尾巴只有 `40960`（按 256k 算），本该是 `160000`。

## 决定

**run 开头那个触发点用它自己这一轮的 provider 的窗口。**
`compact-if-pressured!` 自己解一次 `providers/current-provider`，把 `(:context-window provider)` 交给
两次读数——`pressure/with-window` 就是那个「窗口是别人递进来的」的落点，`log-pressure` 走同一条路。

- **PROVIDER 解析提前到读数之前**，而且只解一次：它本来在下面又被解一次，只为了交给 `run-compaction!`。
- **两次读数用同一对比例**（`compaction/config`，也就是 `plan` 自己用的那对），只换窗口。
- **没声明窗口的模型不是「窗口为零」**：`with-window` 收 nil 就把答案原样放回，也就是 band 那一扇——
  与它落地时的行为一致。主人有一个数、却不递进去，才是上面那个 bug；没人有这个数，不是这条决定的事。

## 后果

- 一轮跑在一个记录**还没见过**的窗口上时，量的是它自己的：切到更大的模型不再被白折一次。
- 声明不了窗口的模型行为不变（仍旧拿 band 的数）；`band-pressure` 与「气泡／状态条」读的
  `harness.edge.context` **一个字不动**——那两处答的是「**上一次**调用填了多少」，分母按设计就是
  那次调用自己行上的数（见 `docs/architecture/edge.md`）。同一个进程里两处读不同的窗口是对的：
  一处问「已经发生的那次」，一处问「马上要发的那次」。
- 「记录以旧模型结尾」从此是一个**正常**状态，不再是触发点的一个隐式输入。

## 证据

- 记录：`~/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness/88f8d8eb-….jsonl`——
  `1790903553230` 那条无 runId 的 `compaction/start` 之前，最后一条真 `model/start` 声明 `256000`；
  紧接着摘要调用自己那条 `model/end` 报 `prompt_tokens 237862`；`context/compacted` 的
  `:shadowed` 是 `2167–5540` 那 587 个 id。
- 回归：`harness.edge.compaction-run-test/the-trigger-measures-the-window-the-run-goes-out-under-not-the-records`
  （两半：记录说 8k、这一轮说 128k ⇒ 什么都不做；记录说 100k、这一轮说 8k ⇒ 照折），
  `harness.edge.pressure-test/a-window-handed-in-replaces-the-one-the-record-described`。
  前一条在改之前是**红的**（实测：两半各一条 `FAIL`）。
