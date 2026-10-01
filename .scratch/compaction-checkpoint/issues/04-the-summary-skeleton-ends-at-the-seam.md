# 04 — 抄骨架：摘要以 Current Work / Next Step 收尾，空节写 `(none)`，不许写 provenance

**What to build:** 喂给摘要模型的 instruction（`compaction/summary-instruction`）换成 DSH 的固定骨架：
章节固定、顺序固定、空节也要写 `(none)`（不许整节丢掉），末两节是**此刻在做什么**与**下一步是什么**；
外加两条规则：不许提这次压缩、不许写「谁做的/不是谁做的」这类来历话。

照抄的骨架（`@deepseek-ai/dsh@0.1.5-rc.2`，`dsh-compaction-basic/lib/index.js`，
`COMPACTION_INSTRUCTION`；本机拷贝在 `%TEMP%\dsh-ref`）：

```
## Primary Request and Intent
## Key Technical Concepts
## Files and Code
## Errors and Fixes
## Pending Jobs
## Current Work
## Next Step
## Critical Context
```

规则里要带上的两条（同文件）：
`Do NOT mention this summarization request or that the context was compacted.`
以及「如果对话里已经有一个 `<compacted-summary>`，那是上一次的检查点：不要照抄，保留仍为真的、
丢掉过期的、把新信息并进去」。

为什么：`a0621fce-…` 那份摘要的第一节是 `# State`，写的是旧提交与「票还没做」——**没有任何一节
回答「此刻干到哪、下一步是什么」**；它还把 git 事实转述成「not created by me — reported by git now」，
一句来历话，正是该禁掉的。我们那份 `Already produced` 清单（02 修好之后）留着，DSH 没有它，
但它的 docstring 讲了它挡的是什么，两半并存。

**Blocked by:** —（可立即开始）

**Status:** ready-for-agent

- [ ] instruction 里出现这八个小标题，且顺序与 DSH 一致；每条都有一句「空节写 `(none)`」
- [ ] 规则里有「不许提这次压缩」与「上一次的 `<compacted-summary>` 要合并不要照抄」两条
- [ ] `Already produced` 与 `Where this work is happening` 两节的位置与今天一致（facts → 环境 → hook 的话），
      换句话说：换骨架不许把 01/02 修好的两段挤掉
- [ ] 走查（真 provider，隔离家里折一份真记录）：出来的摘要末两节确实是 Current Work / Next Step，
      且正文里找不到「这件事被压缩过」「不是我做的那笔」这类话；走查的输出存进 `evidence/`
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-10-01 — 上一轮真机做过的 A/B（`dev/scratch_fold_prompt_ab.clj`）里，「明确告诉模型压了多少历史」
那一变体**没有**可测差别（四种说法全答对）。所以这里不抄「告诉它压了多少」，只抄「钉住谁新谁旧 +
把摘要的落点放在接缝上」。
