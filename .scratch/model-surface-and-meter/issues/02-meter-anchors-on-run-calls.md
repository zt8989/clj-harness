# 02 — 压力表只认 run 调用（失败的压缩不许打歪锚点）

**What to build:** `harness.edge.pressure/latest-start` 取的是记录里**最新的一条 `model/start`**，
不管它属不属于一个 run。压缩的摘要调用自己也会写 `model/start`（`run-compaction!` 里 `specs` 为空，
所以那一行**没有 tools**，`runId` 为 null）。它一落盘，`latest-start` 就读到它：

```clojure
anchored? (and anchor prompt
               (= (:tools start-p) tools)   ; 98 ≠ 0 → false
               …)
```

`:baseline` 从 `"usage"` 掉回 `"estimated"`，压力退化成按字符估算（对中文严重低估），阈值再也够不着。
真实记录实测（thread `bbcd4ae4-…`）：

```
压缩前 records->pressure = 825,840 tok / 79% / baseline usage / latest tools=98
压缩后（纯抄记录）      = 634,404 tok / 61% / baseline estimated / latest tools=0
```

一次性失败于是变成「整个会话不再压缩」。**摘要调用不是「下一次调用从哪继续」**，它不进 latest-start、不进锚点。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] `latest-start`（以及 `route-of` / window / tools 标识的取值）只认**真正的 run 调用**：忽略
      `runId` 为 null 的 `model/start`（压缩摘要调用就是这种），或等价地在 `trajectory/run-segments`
      的 run 集合里排除它
- [ ] `last-reporting-call` 同样不把摘要调用的 `model/end` 当锚（它是空 payload，本来不会报 usage，
      但要有一个「锚来自 run 调用」的显式判据，不能靠它恰好为空）
- [ ] 回归：构造「一条 run 调用（带 tools）→ 一次压缩的 start/end（无 tools）→ 再问压力」的记录，
      答案仍是 `:baseline "usage"`，且 `:pressureTokens` 认那次数值
- [ ] 回归：`latest-start` 不再返回压缩那一行
- [ ] 离线全量 `harness.test-runner` 全绿

## Comments

2026-09-24 — 和 01 是两个独立的 bug：01 让压缩**失败**，02 让**失败之后压力表失明**。
只要 01 不修，02 就会不断被触发；但 02 该单独修，因为它对「任何一次失败的模型调用」都成立。
