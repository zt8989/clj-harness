# 05 — 摘要必须真的更小；摘要调用要有输出上限

**What to build:** 抄 DSH 的两条硬规矩（`dsh-compaction-basic/lib/index.js`，`summarizeCompaction` /
`resolveCompactSpec`）：

1. **出来的检查点必须比它顶掉的那段便宜**：把拼好的检查点消息（preamble + 标签 + 摘要）过一遍估价器，
   若不小于被折区间的价钱就**抛**，不落 `context/compacted`——
   `framedSummaryTokenCount >= shadowedRouteTokenCount` 那句。
2. **摘要调用带输出上限**：DSH 默认 `maxTokens 8192`，且「被上限截断」是硬错误
   （`summarization truncated at the token cap (incomplete checkpoint)`），不是「就这样吧」。

我们今天只有 `min-head-tokens`（头部至少要有多少才值得折）——那是**入口**的守卫；出口没有守卫，
一个不比原文短的摘要会照样落库。

**Blocked by:** 03（04 的骨架也影响长度，但不阻断：校验的是拼好的那条消息）

**Status:** ready-for-agent

- [ ] 摘要拼好的那条消息估价 ≥ 被折区间的估价时，这次压缩**不落** `context/compacted`，
      按现有失败路径收尾（一次 `compaction/end` + 错误），压缩卡片不谎报成功
- [ ] 摘要调用带上输出上限（默认抄 DSH 的 8192，可配），且撞上限时按失败处理并在日志/行里点名
- [ ] 上限与「必须更小」都写进 `compaction/config` 的可用键与校验（坏值按名拒绝）
- [ ] 回归：一个「只会回一大段废话」的假 summarizer 上，断言不落 `context/compacted`；正常长度时照旧落
- [ ] 回归：撞输出上限的那条路径断言错误里点的是「截断」而不是「厂商出错」
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-10-01 — `.scratch/compaction-shape` 记过一次「折了一个摘要、面没变、压力没动，
于是 2 分 22 秒里连折四次」；这条出口守卫是同一类问题的另一半。
