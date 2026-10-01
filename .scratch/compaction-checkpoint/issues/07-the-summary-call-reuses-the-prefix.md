# 07 — 抄缓存前缀：摘要调用带上会话自己的 system 与工具表

**What to build:** 摘要那一发请求要写得**尽量等于会话上一发请求的前缀**，让厂商的前缀缓存命中。
DSH 的原话（`dsh-compaction-basic/README.md`）：

> Replaying the system prompt held by the `system/message` at surface node 0, the last routed
> request's tools, and the shadowed-region messages byte-for-byte makes the auxiliary call a
> genuine prefix of the conversation, so only the trailing instruction and the summary output are
> uncached.

我们今天（`run-compaction!` 的 `summarize`）：`:tools []`（**不发工具表**）、消息只有被折区间的
`(:messages head)`——**不带会话的 system 消息**。于是摘要这一发的前缀与会话无关，等于每次都付
一次冷 prefill。这是钱与时延，不是正确性。

**Blocked by:** —（可开始；01/02 改的是 instruction 的内容，不改这一票的形状）

**Status:** ready-for-agent

- [ ] 摘要请求 = 会话的 system 消息 + 上一次路由请求的工具表 + 被折区间的消息（形状折法照旧共用
      `ag/provider-messages`）+ 最后的指令；逐字前缀的性质有断言
- [ ] 工具表取不到（离线 / 无 run）时安静退回今天的形状，不抛
- [ ] 走查：真会话压一次，`logs/llm-debug.jsonl` 里摘要那一发的 `prompt_cache_hit_tokens` 明显 > 0，
      并把两发的对照存进 `evidence/`
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-10-01 — DSH 这一条与它的「指令作为最后一条 user 消息」是同一件事的两半，我们也已经是后一半。
