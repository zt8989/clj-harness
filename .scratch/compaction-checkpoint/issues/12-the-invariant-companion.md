# 12 — 不变式伴随：校验 start→end 的括号与 shadowed 的一致性

**What to build:** 参考实现有一个独立的伴随插件（`dsh-compaction/lib/invariant.js`）校验这个包
写进记录的所有行：`start→summary→end` 的括号顺序、归属、checkpoint 与 `shadowedSeqs` 的首末一致、
`shadowedSeqs` 与当时 surface 的位置一致。我们一个校验器都没有——`compaction-facts` 只是**读**，
一条坏记录（半截的括号、`:shadowed` 里混进了不存在的 seq、`:range` 与 `:shadowed` 不一致）会被
安静地折进模型视图。

**Blocked by:** —（可开始；先有一条真实坏记录再写会更准，但括号与 seq 一致性是纯函数，今天就能写）

**Status:** ready-for-agent

- [ ] 一个纯函数（`harness.edge.compaction/check-records!` 或独立命名空间）：RECORDS -> 报告/抛，
      校验 (a) 每个 `compaction/start` 恰好配一个 `compaction/end`，且中间只夹着属于它的事实；
      (b) `context/compacted` 的 `:shadowed` 非空、`(first :shadowed)`/`(last :shadowed)` 与 `:range`
      的首末一致、每一个 seq 都**真的存在**于记录里（且不是 message 之外的种类）；
      (c) 被遮蔽的节点在折叠时确实从模型面上消失了；
- [ ] 接到「读一份记录」的那条路上（`replay/read-records` 的严格读者，或它的一个可选校验入口），
      测试与 `--scripted` 走查里能开；
- [ ] 回归：一条好记录通过；四种坏记录各一条（半截括号、seq 不存在、range 与 shadowed 不一致、
      shadowed 里混进一个 message 行）。

## Comments

2026-10-01 — 从 `.scratch/compaction-checkpoint` 的 delta 清单拆出来。
