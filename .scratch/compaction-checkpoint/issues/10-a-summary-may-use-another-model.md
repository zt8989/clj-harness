# 10 — 摘要可以换一个模型写（并按路由覆盖策略）

**What to build:** 参考实现允许把**写摘要**这件事指给另一个 provider/model
（`summarizationProvider` / `summarizationModel`，默认回落到会话自己的那一发），并允许按
`provider/model` 覆盖任意策略键（`modelPolicies`）。我们只有「用会话自己的模型写摘要」一种。

**为什么值得做：** 摘要是一份**结构化转述**，不是推理：拿一个更便宜、上下文更大的模型写它，
省的是每一次压缩的 prompt 钱（真实数据：一次摘要 249k prompt token，而摘要输出 ~650 token）。
`modelPolicies` 的价值是窗口差别很大的模型能各自定阈值/保留比例，而不用改全局配置。

**Blocked by:** 09（`block`/`check-keys!` 的名单要先有地方放这些键）

**Status:** ready-for-agent

- [ ] `compaction/summarize-provider`（新）：读 `:summarization-provider` / `:summarization-model`，
      按同一个 tier 折法解析（`providers/resolve-provider` 已经支持「按名字选 provider」）；
- [ ] `run-compaction!` 用解析出来的 provider 发摘要请求——**缓存前缀那一套跟着变**：前缀是
      「那一发的会话自己的 system + 工具表」，换模型之后前缀是否还算数要说清楚（不同 provider
      的缓存不共享，票 07 的收益在换模型时归零，这是取舍不是 bug）；
- [ ] 没有配置时**逐字**保持今天的行为（用会话自己的 provider）；
- [ ] `modelPolicies`：`{:provider/model {...策略键}}`，命中时覆盖 `block`（优先级写在 docstring 里）；
- [ ] 回归：配了另指模型 ⇒ `model/start` 行上写的是那个模型；没配 ⇒ 与会话一致；policy 命中/不命中
      各一条。

## Comments

2026-10-01 — 从 `.scratch/compaction-checkpoint` 的 delta 清单拆出来。
