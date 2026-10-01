# 0014 —— 轮中压缩只在「这次请求塞不下」时动手；阈值是下一个用户轮的题目

- **日期**：2026-10-01
- **状态**：**已采纳**
- **边界**：本决定**不推翻** `0008-the-log-is-the-truth-and-sqlite-projects-it.md`（记录是真相，库是投影）、
  **不推翻** `0009-the-record-holds-a-thought-once.md`、**不推翻** `0011-a-step-is-a-request-plus-its-tools.md`、
  **不推翻** `0012-the-record-is-a-stream.md`，也**不动**压缩的折法、保留比例、摘要请求的形状（那是
  `0013`）。
  它只改**自动压缩在什么时候动手**：run 开头的那个触发点一个字不动，轮中那个换了判据。
- **取代**：`.scratch/compaction-shape` 票 04 的那句「**同一个问题**（窗口是不是要满了）在**每次模型
  调用前**再问一遍」——它把「阈值」当成了轮中触发点的判据。判据换成**窗口本身**。

## 背景

1. **两次触发点本来问的不是一个问题，却被写成了同一个。**
   - **run 开头**（`compact-if-pressured!`）问的是：「**下一个用户轮**要不要从一个更干净的会话开始？」
     这是**阈值**（默认七成）的题目——早一点折，折得温和，尾巴留得完整。
   - **轮中**（`relieve-pressure!`）问的应该是：「**这次就要发出去的请求**塞得下吗？」这是**窗口**的
     题目（厂商会在超过窗口时用 `finish_reason: length` 拒掉）。
   票 04 把第二个也写成「是不是过阈值了」，于是**任何一次轮中越过七成**都会折一次。
2. **这个错法在真事上花过钱。** 主人自己的会话（thread `3c85b20e-…`，2026-10-01 16:41）：记录里
   `compaction/start` 夹在一个 `step/end` 与**最后一**步的 `step/start` 之间——即它折的是那一轮
   **最后一次**模型调用，压力只有窗口的 **75%（1M 窗口里 ~750k）**，请求本来塞得下；折完之后那一轮
   就结束了，下一次读这个会话要等主人再开口——而那时 run 开头的触发点本来就会折，且折得更温和。
   一笔摘要调用的钱（真实数据：一次摘要 249k prompt token）换来的是「同一件事晚一点做」。
3. **票 04 原本要挡的事仍然要挡**：轮中会长的是工具结果，一个巨型结果就能把一次请求顶过窗口，
   而厂商的拒绝发生在请求**已经发出去**之后（`62f30024-…`：十二分钟里从 62% 涨到 100% 以上）。
   所以轮中这个触发点不能删，只能换判据。

## 决定

**轮中触发点只在 `压力 ≥ 窗口` 时折**（`relieve-pressure!`：`(:windowTokens answer)`，
不再是 `(:thresholdTokens answer)`）。阈值继续是 **run 开头**那个触发点的题目。

- 阈值与窗口之间（例如 1M 窗口里的 700k–1M）**不动手**：下一次请求是主人的下一个问题，run 开头的
  触发点会折它，而且折得温和。
- `压力 ≥ 窗口` 时**仍然先折再发**：省掉一次被拒的请求，也省掉被拒之后那条更狠的
  `recover-overflow!`（它只留最新一个不可分单元）。
- 真的还是塞不下（估价偏低、窗口中途变小）：`recover-overflow!` 照旧兜底。

## 后果

- 一次轮中压缩现在**必然**发生在「不折就会失败」的时刻，不再有「折了也白折」的那一类。
- 阈值的效果变成「下一个用户轮开始时有多干净」，与代码里 `threshold-ratio` 的注释一致。
- 有人想「轮中稍微折一点、别等到窗口」的话，那是另一条策略：它要一个新的判据（例如窗口的 95%），
  并且要把理由写在这里，而不是把厂家的窗口当阈值用。

## 证据

- 记录：`~/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness/3c85b20e-….jsonl`
  行 11648（`compaction/start`，`runId` 为空的摘要调用）夹在行 11647 `step/end` 与行 11654
  `step/start` 之间，前后都是同一个 run `d46ea433`。
- 回归：`harness.edge.relieve-pressure-test/a-request-that-would-not-fit-is-relieved-before-the-call`
  与 `a-crossing-of-the-threshold-alone-is-not-relieved`（新），
  `the-fold-takes-the-array-the-trigger-measured` 的夹具抬到窗口之上。
