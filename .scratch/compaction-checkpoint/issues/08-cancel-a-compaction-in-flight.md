# 08 — 取消：跑在半路的压缩要能被停掉

**What to build:** 「停一次 run」现在只停得住模型调用，停不住**压缩**：`perform!` 拿到 head 之后
一路 `summarize` 到写完两行，中间没有任何中断点。参考实现把 `AbortSignal` 一路传进摘要调用，并在
每一段之后 `throwIfAborted()`——人一停，摘要调用跟着死，**括号仍然闭合**（`compaction/start` 已经
写了就一定要写 `compaction/end`），记录里说得出这次压缩是被取消的。

**为什么值得做：** 一次压缩是一次真模型调用，一次冷 prefill（真实数据：一次摘要 249k prompt token）。
人按下停止之后还在花这笔钱、还在写行，是我们这条路上唯一一处「说不听」的动作。

**Blocked by:** —（可开始）

**Status:** ready-for-agent

- [ ] `run-compaction!` / `perform!` 接受一个「还活着吗」的判据（不是 `Thread/interrupt`：这个进程
      的停止是一个会话级的状态，`harness.edge.http` 的 `running?` 与 run 自己的 sink 已经有）；
- [ ] 摘要调用前、调用后各查一次：前一次查中就直接不开始（**不写 start**），后一次查中就把这次
      压缩按失败收尾（`compaction/end` 写明 `:cancelled true`），`context/compacted` 一个字不落；
- [ ] 手动 `/compact` 与自动触发走同一条判据；
- [ ] 回归：一个 fake provider（流慢/永不结束）＋停掉 run ⇒ 断言 `compaction/end` 在合理时间内落地、
      `context/compacted` 不存在、锁是自由的；
- [ ] 记录与 UI 说得清「这次压缩被取消」（卡片或行上有痕迹，别再画一次成功）。

## Comments

2026-10-01 — 从 `.scratch/compaction-checkpoint` 的 delta 清单拆出来；参考实现
`dsh-compaction-basic` 的 `summarizeCompaction` 每一段之后都 `throwIfAborted`。
