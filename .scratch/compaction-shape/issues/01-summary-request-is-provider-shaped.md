# 01 — 压缩的摘要调用先折成 provider 形状（送出去的数组里不许有 `reasoning` 角色）

**What to build:** 一次压缩的摘要调用，是 `compaction/perform!` 拿 `replay/model-nodes` 的计划、
把 `(:messages head)` **原样**交给 `llm/stream!`。那是 **AG-UI 形状**的数组：思考是**独立的消息**
（`role "reasoning"`）。运行路径送出去的不是这个形状——它过 `ag/inbound` → `absorbed`，
把思考折进它前面那条 assistant 的 `reasoning_content`。摘要这条路没折，于是厂商看到
`role "reasoning"`，直接拒收：

```
HTTP 422 ... messages[4].role: unknown variant `reasoning`,
expected one of `system`, `user`, `assistant`, `tool`, `latest_reminder`
```

真实记录实测（`f59c09dd-…jsonl`，2026-09-25）：这个会话 **15 次压缩、15 次全挂在同一条 422 上**，
记录里 `context/compacted` **0 行**——从来没有一次压缩成功过。拿它的 `compaction/overflow-plan`
实算：head 2641 条里 **588 条 `role "reasoning"`**，而厂商点名的索引 4 正好是其中一条
（`{:id "7460258f-…-r0" :role "reasoning"}`）；同样的数组过一遍 `ag/provider-messages`，
它变成 `{:role "assistant" :reasoning_content …}`，其余 588 条同理。

**不要在这里再写一份折叠。** 折法是 `harness.edge.ag_ui` 的那一个实现（`provider-messages` /
`absorbed`），运行路径用的就是它；压缩再写一份，两条路就会各折各的。

**Blocked by:** —（可立即开始）

**Status:** ready-for-agent

- [ ] 摘要调用送出去的每一条消息，`role` 都在厂商接受的那一组里（`system` / `user` /
      `assistant` / `tool`）；`reasoning` 这个角色一条都不许出现在请求里
- [ ] 折过之后思考的文字**不许丢**：同一条 assistant 上仍带着 `reasoning_content`，
      内容与折之前那条 `role "reasoning"` 的消息逐字相同
- [ ] 回归：造一条含 `role "reasoning"` 消息的记录，跑一次压缩，断言摘要调用收到的数组里
      没有任何 `reasoning` 角色，且 assistant 上的 `reasoning_content` 就是原来那段文字
- [ ] 回归：那次压缩按正常路径落 `context/compacted`（不再记一条只有 error 的 `compaction/end`）
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-09-26 — 和 02 是两个独立的 bug：01 让压缩**送不出去**，02 让**该不该压缩**算错。
02 不修，压缩根本不会被叫起来；01 不修，叫起来了也必然失败。票 03 拿真实记录端到端验这两条。

2026-09-26 — **已落地**（分支 `compaction-shape`，commit `77f65f1`）：`run-compaction!` 的摘要调用
送出去之前过一遍 `ag/provider-messages`；`test/harness/fake.clj` 补上厂商那条 422 的复刻（请求里
出现 `role "reasoning"` 就拒收），回归 `a-summary-call-goes-out-in-a-shape-the-vendor-reads`
**先红后绿**（摘掉源码修复后它连同另外 6 处断言一起红）。真记录走查见 `spec.md` 与 `evidence/`。
