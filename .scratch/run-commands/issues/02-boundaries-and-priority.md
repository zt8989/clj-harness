# 02 — 三个边界、优先级、只有命令的 run

**What to build:** 队列有人取。

- **三个边界**：每次**模型调用前**（pre-LLM 缝，与 `compact-if-pressured!`、技能正文、
  作业结局同一处）、每次**工具调用前**、每次**工具结果回来后**（执行缝的两个相）。
  三处调用同一个 `drain!`，它按**优先级表**（票 01）把队列排一遍、能做的全做。
- **怎么递给内核**：照 `before-llm` 那条既有形状——**内核被交一个 `drain!`，
  不是它 require 一个会读队列的命名空间**（`harness.kernel.loop` 收的 opts）。ticket 里写明
  三处各接在哪个既有缝上，不许新造第四个缝。
- **只有命令的 run**：没有 run 在跑时，`compact`/`goal`（`no-run` 是 `start-run` 的那两条）
  由 edge 起一场 **`append` 为空**的 run，它取队、执行、收尾。这场 run **一次模型调用都没有**，
  所以 `turn/*`、`model/*` 里要能出现「零次模型调用的 turn」（读者要接受；一条用例钉住）。
  这扇「起一场 run」的门与 `.scratch/goal` 的 driver 是**同一扇**（一处起 run，两处用）。
- **`no-run :refuse` 的那两条**（`interrupt`、`steer`）：没有 run 就按名字拒（409）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 三个边界各有一条用例：命令在模型调用前、工具调用前、工具结果后**各被取走一次**。
- [ ] 取队按优先级表排序，与到达顺序无关：先到一条 `compact`、后到一条 `interrupt`，
      同一个边界上先做 `interrupt`（一条用例）。
- [ ] 一次取队把多条**能做的**全做掉，不是一轮一条。
- [ ] **零次模型调用的 run**：`append` 为空、只有一条 `goal` 命令 → 起一场 run、命令生效、正常收尾；
      `turn/end` 报出来，读者看到它（一条用例）。
- [ ] `interrupt` / `steer` 在没有 run 时被拒（409，句子说「没有正在跑的 run」）。
- [ ] **内核不认识队列**：`harness.kernel.loop` 只多收一个 `drain!` 之类的入参，不 require 任何
      `harness.cap.commands`；一条测试钉住（内核的测试可以直接传一个假 `drain!`）。
- [ ] 命令的取走是**原子的**：并发下同一条命令不会被取两次（一条用例）。

**本票的界线**：`interrupt`、`compact` 的具体执行是票 03/04；`goal` 是 `.scratch/goal` 那边。
