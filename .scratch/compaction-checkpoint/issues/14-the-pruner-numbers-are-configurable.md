# 14 — 免费剪枝的三个数可配（现在是常量）

**What to build:** `harness.edge.prune` 的阈值与头尾长度现在是源码里的常量（6000 / 2000 / 2000）。
参考实现把这个动作做成**可挂载插件**并给三个数（`thresholdChars 8192` / `headChars 4096` /
`tailChars 1024`），因为它跟窗口大小、跟这个部署怎么用工具结果有关。我们的数字是拍出来的，
不同窗口的会话（100k 与 1M）用同一组阈值。

**Blocked by:** 09（`block`/`check-keys!` 的名单）

**Status:** ready-for-agent

- [ ] `:session :compaction` 加 `:prune-threshold-chars` / `:prune-head-chars` / `:prune-tail-chars`
      （与参考实现同名同义），默认保持今天的数字；
- [ ] `edge.prune` 的常量变成默认值，参数从 `compaction/block` 来；非正数按名拒绝；
- [ ] 与压缩的关系照旧：剪枝先跑，剪够了就不必再折（这条不动）；
- [ ] 回归：改了阈值 ⇒ 同一条目上剪的字符数跟着变；常数不配 ⇒ 与今天逐字相同。

## Comments

2026-10-01 — 从 `.scratch/compaction-checkpoint` 的 delta 清单拆出来；数字对照见参考实现
`dsh-compaction-tool-result-pruner` 的默认值。
