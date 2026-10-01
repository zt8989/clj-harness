# 13 — `/compact` 报告省下多少，并给「跑的时候发来的话」排队

**What to build:** 两件人工入口的事：

1. **报告**：参考实现的回答是 `Compacted N history items (~T tokens).`；我们的
   `POST /api/threads/<stem>/compact` 只回 `:compacted` 与 `:shadowed`（条数），tokens 只在
   `compacted-context` 卡片里有。人问「省了多少」，路由应该直接答得出。
2. **排队**：参考实现里，压缩跑的时候人发来的话**被接受**、压缩结束之后接着跑；我们现在
   run 在飞就 409（「compact between turns」）——「等」与「拒」是两种产品选择，参考实现选了等。

**Blocked by:** —（报告是纯加法；排队要先决定「等多久 / 超时怎么办」）

**Status:** ready-for-agent

- [ ] 路由的答案加 `:tokens`（那次被折区间的估价，与 `context/compacted` 上的同一个数）与
      `:messages`（`(count :shadowed)`），措辞与卡片对齐；
- [ ] 人工 `/compact` 与自动压缩**都说得出**这一次省了多少（同一个数，不另算）；
- [ ] 排队：压缩进行中收到的 run 请求**先收下**，压缩结束后按收到的顺序开跑；压缩失败/被取消时
      排队的话照样跑（不许因为压缩死了就把人问的话吞掉）；
- [ ] 若不做排队：在 spec 里写明**为什么**（「等」会让 UI 看起来像卡住；超时语义要另定），并让
      409 的错误话说清「等一会儿再发」。

## Comments

2026-10-01 — 从 `.scratch/compaction-checkpoint` 的 delta 清单拆出来；参考实现
`dsh-command-compact` 的成功话术是 `Compacted ${result.shadowedSeqs.length} history items
(~${result.shadowedTokenCount} tokens).`。
