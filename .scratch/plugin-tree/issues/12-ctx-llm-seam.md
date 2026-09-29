# 12 — `ctx.llm` 接缝

**What to build:** 「跟哪家厂商说话」是一条缝：**定义**是消息与流的词汇，**实现**是每家厂商的适配器、
目录、三档解析、凭据，**消费**是循环（与压缩）。今天定义在 `kernel.llm`、实现在 `cap.providers`，
而 `prompt.md` 的冻结头也塞在 `kernel.llm` 里（它该去 `ctx.systemPrompt`，票 07 搬）。

**Blocked by:** 09 — `ctx.agentLoop`

**Status:** ready-for-agent

## 验收

- [ ] 三个角色各有名字与落点；凭据与目录明确归**实现侧**（or 单立一条缝——见下）
- [ ] **换一家适配器不改循环**：脚本替身与真厂商各跑一趟，产品行为一致
- [ ] 凭据名由 provider id 派生的规矩、以及「本 provider 优先、全局兜底」的查找顺序不变
- [ ] 三档旋钮（provider / model / reasoning-effort）与「换 provider 不指定 model 就落新厂商默认」不变
- [ ] 冻结头搬走之后，**首消息逐字节稳定**这条判据照旧（前缀缓存的前提）
- [ ] 思考模式的规矩不变：`reasoning_effort` 的空串要原样留着、历史缺字段时只补空串
- [ ] **待定作答**：凭据 / settings 归这条缝的实现侧，还是单立一条缝——选一个并写下理由
- [ ] 换实现的反证：一个假的 provider 文本线（脚本回放）能跑完一轮，且记录形状与真厂商一致
