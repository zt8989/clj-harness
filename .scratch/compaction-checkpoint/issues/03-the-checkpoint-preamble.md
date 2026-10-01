# 03 — 抄壳：检查点要说清它折的是**早先**一段、从后面的消息接着干

**What to build:** 压缩之后替换那段历史的 `user` 消息，现在只有 `<compacted-summary>…</compacted-summary>`
两片标签（`harness.edge.replay/compaction-summary`）。抄 DSH 的壳：标签前面加一段话，明说这是
「自动生成、折的是早先一段」，并且**指示模型从它后面的消息接着干、别去回应这段检查点**。

照抄的原文（`@deepseek-ai/dsh@0.1.5-rc.2`，`dsh-compaction-basic/lib/index.js` 的
`CHECKPOINT_PREAMBLE`；本机拷贝在 `%TEMP%\dsh-ref`）：

> This is an automatically generated checkpoint condensing an earlier span of the conversation to
> free up context. Treat the captured context as established background and build on it without
> restating it. Continue the task directly from the messages that follow, without acknowledging
> this checkpoint.

为什么是这一句：`a0621fce-…` 那次失忆的直接读法就是「摘要被当成了现在」——它当时的第一节小标题
是 `# State`、里面写着 `f1db88b` 与「票还没做」，而模型的近况（提交、未提交的改动）在**后面的
消息里**。DSH 用一句壳把「谁新谁旧」钉住；我们只钉了「这是一段摘要」。

**Blocked by:** —（可立即开始）

**Status:** ready-for-agent

- [ ] 模型面上的那条替换消息 = 这段 preamble + 一个空行 + `<compacted-summary>` + 摘要 + `</compacted-summary>`
- [ ] 文字照抄 DSH（英文原句），并注明来源与版本（`@deepseek-ai/dsh@0.1.5-rc.2`，
      `packages/compaction/compaction-basic`）
- [ ] 第二次压缩把上一次的检查点也折掉时，模型面上**仍然只有一个**检查点，preamble 不叠两句
- [ ] UI 的压缩卡片不受影响（卡片读的是 `context/compacted` 这个 fact / `compacted-frame`，不是这条消息）
- [ ] 回归：折一条真记录，断言模型面的第一条消息以这段 preamble 开头、且摘要仍在两片标签之间
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-10-01 — 和 04 是一对：这一票管**壳**（谁新谁旧），04 管**摘要本身**（干到哪、下一步）。
两条各自可验，互不依赖，但一起才是 DSH 那层的全部。
