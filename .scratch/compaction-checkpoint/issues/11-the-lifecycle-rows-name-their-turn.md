# 11 — lifecycle 行说出它属于哪个轮

**What to build:** 我们的 `compaction/start` / `compaction/end` 只带 `:compactionId`（start 上还有
`:instruction`），**没有归属**：读记录的人分不清这次压缩是「某个 run 里的自动压缩」还是「人按的
`/compact`」。参考实现的 lifecycle 事件带 `:turn`：自动压缩必须落在一个**打开着的轮**里，
人工压缩是 `turn: null` 加一次空闲预约（admission）。

**Blocked by:** —

**Status:** ready-for-agent

- [ ] `run-compaction!` 接受 `:turn`（自动路径上就是当前 run 的 id / 当前 step 的轮，人工路径 nil）
      并写进 start 与 end 两行；
- [ ] `compaction` 的读侧跟着走：`replay/compaction-facts` 不必变（`:shadowed` 仍是权威），但
      「这次压缩属于谁」要能从行上读到；
- [ ] 与「过期锁」（票 06 那条修复）对齐：人工压缩（`turn: null`）的行不该被误当成陈旧；
- [ ] 回归：自动压缩的行带 turn、人工的不带；重建之后仍然读得出来。

## Comments

2026-10-01 — 从 `.scratch/compaction-checkpoint` 的 delta 清单拆出来；参考实现在
`dsh-compaction` 的 `lifecycle` 事件里写 `turn`，并在 `compactionNow` 上做空闲预约。
