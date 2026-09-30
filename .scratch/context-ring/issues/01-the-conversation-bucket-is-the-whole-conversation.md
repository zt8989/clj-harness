# 01 — 环的「对话」那一桶要数整段对话（顺带处理「刚压过」的窗口期）

**What to build:** `harness.edge.context/shares` 现在把「对话」桶算成**这一轮 run 自己的消息**
（`(:submitted run)` + `(:returned run)`）。ADR 0002 之后客户端只交新消息、老历史在会话里，于是这一桶
漏掉整段历史；`apportion` 再按这三个（被漏算的）尺寸把厂商的总数摊下来，三个桶的**比例**就飘了。
真会话 `62f30024-…`（2026-09-30）实测：环画的是 system 6.5% / **tools 68.7%** / conversation 24.8%，
而那一发真正带出去的是约 240 万字符、工具表只有 **55,405 字符（53 个工具）** —— 工具真实不到 3%。

**要什么**：环的百分比（厂商的 `prompt_tokens`）一个字节都不动；**拆分的三个桶要描述那一发真的带了什么**。
「对话」桶应当是**那一发当时的对话**（会话那一侧的消息面），不是这一轮的行。

**位置**：`harness.edge.context` 的 `shares`（与它的调用者 `records->context`）。它拿到的是
`(run start-payload)` 两样；要数整段对话就得再多一样东西 —— 那一发当时的消息面。这条路的机器**已经
有了**，别新写一份折法：

- `harness.edge.pressure` 的锚点就是「按调用当时的价格去量一段消息面」：`messages-of` / `messages-in`
  把记录折成「系统消息 + 对话（卡片已摘）+ 本轮注入」的数组，并且**在 `model/start` 那一行上取快照**
  （`pressure/band-step` 的 `:start-messages`）。环要的是同一个数；
- 若不想依赖 pressure 的 band，另一条门是记录本身：把 `entries` + `compaction-facts` + `prune-facts`
  折到**那一发那一行**（`replay/compacted-messages` 的前缀），再 `size-of` 整个数组。

选哪条由实现的人定，但**不要**在 `context` 里再写一份「哪些消息算对话」的规则（同一件事三个地方算，
必漂 —— `harness.edge.pressure` 的 docstring 已经踩过这个坑）。

**顺带一并处理（同一处、同一票）**：**刚压过、还没发过新调用**那一段，环显示的是压缩前那一发的数
（今天：会话已折到 ~104k est、压力计 44%，环仍 58%）。机制上没错（上一发确实那么满），但读的人正是
刚按过「压缩」想知道有没有用的时刻。两个做法，实现时定一个并写在注释里：

- (a) 最近一次 `compaction/end` 比最后一次「有数的调用」更新时，环显示**这份对话自己的尺寸**
      （走上面那条消息面的路，不用等下一发）；
- (b) 明确不改，环就老实说「上一发」是什么时候的（加个时间/「压缩前」的标注），并在 docstring 里
      写清为什么不改。

**非目标**：不改百分比的口径；不改 `apportion`（它按比例摊是对的，错的是喂给它的尺寸）；不动
`own-calls`（摘要那一发继续不算）。

**Blocked by:** 无。

**Status:** ready-for-agent

- [ ] `shares` 的「对话」桶改成**那一发当时的对话**（记录/会话折出来的消息面），不是本轮的行
- [ ] 用它算的三个桶比例在真记录上说得出话：`62f30024-…` 上工具应落在百分之几（表 55,405 字符），
      而不是 68.7%；且三个桶仍然**加起来等于** `usedTokens`（`apportion` 的既有保证）
- [ ] 「刚压过」那一段：按 (a) 或 (b) 落一件，并把理由写进 `harness.edge.context` 的 docstring
- [ ] 回归：`harness.edge.context-test`（或 `pressure-test` 里那条环的路）钉住「三桶之和 = 厂商总数」
      与「对话桶包含历史消息」两条；老记录（没有 `:tools-bytes`、没有 model 行）仍然给得出缺省答案
- [ ] 离线全量 `harness.test-runner`（本机本来就红的 12 failures + 1 error 除外：
      `harness.kernel.tools-test` 8F+1E、`harness.cap.mcp-wired-test` 3F、`harness.cap.claims-test` 1F、
      `harness.kernel.hooks-test` 1F）
- [ ] 动过 `ui/` 的画法就照 `AGENTS.md` 走一次 `node scripts/dev.mjs --scripted` + 真浏览器

## Comments

2026-09-30 — 主人问「为什么我点进 `62f30024` 这个会话，上下文长度是 58%」时量出来的：58% 本身
**是对的**（厂商那一发的 `prompt_tokens` 612,375 ÷ 1,048,576，见 `.scratch/context-ring/spec.md`），
错的是它下面的拆分。同一处发现的第二个毛病（刚压过的窗口期）一并写进上面了。
