# 01 — 模型面只有一份：脱卡／还原，压缩消费它

**What to build:** 把「客户端面和模型面之间那一步」提成**唯一一处**实现，压缩必须消费它。今天这一步只写在
`harness.edge.sessions/model-view`（去掉 `data` part；整条只是卡的消息按卡里的 role+text 还原），
而 `harness.edge.replay/model-nodes` 自称 "MODEL-FACING SURFACE"，实现上却是 `entries` 的直接投影、
**从不脱卡**。`compaction/plan`（以及 `overflow-plan`）交给摘要模型的就是这份带卡的数组，于是厂商 422。

证据：thread `bbcd4ae4-…jsonl` 第 347794–347797 行，摘要调用返回
`HTTP 422 … messages[4]: unknown variant \`data\`, expected one of \`text\`, \`image_url\`, \`file\``；
按当时的记录重建 `compaction/plan` 的 head，里面有 **25 个 `{:type "data" :name "injected-context"}`**，
第一个正好在第 4 条。卡来自 `harness.kernel.frames/apply-frames`（`injected-context` 帧 → 一条 `data` 卡）。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] 一个共用实现（放 `replay` 或 `ag`：`sessions` 依赖 `replay`，反过来不行；候选是 `harness.edge.replay`
      或 `harness.edge.ag-ui`），把「客户端面消息 → 可交给 provider 的消息」这件事做完整：去 `data` part，
      **卡-only 的消息按 `:data` 的 `:role`/`:text` 还原**（`text` 为空才丢）
- [ ] `harness.edge.sessions/model-view` 改成调用它，`replay/model-nodes` 也调用它——
      两处不再各写一份「什么是模型面」
- [ ] **还原而不是丢弃**：`model-nodes` 的节点 id 是 record seq，`context/compacted` 的 `:shadowed` 就是
      节点 id 列表；还原保留一条节点（内容换成普通 user text），节点数与 id 不变，压缩范围不动
- [ ] `compaction/plan` / `overflow-plan` 交给摘要模型的 `:messages` 里**不再出现任何 `data` part**
- [ ] 回归（构造性，不靠真厂商）：记录里放一个 `injected-context` 帧 + 一段对话 → `compaction/plan` 的
      `:messages` 中该注入变成 `{:role "user" :content "…"}`，且 `:shadowed` / 节点数和改动前逐一相等
- [ ] 回归：`overflow-plan`（aggressive）同样不带 `data` part
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-09-24 — 由真实 thread `bbcd4ae4-…` 的失败压缩撞出来。`pres` 层的 `records->pressure` 其实已经是在
`entries` 上套 `sessions/model-view`（`pressure/messages-in`），所以卡的问题**只在压缩这一条路径**。
