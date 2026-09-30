# 01 — 锚点按生命周期清理，而不是靠人手

**Status:** needs-triage
**Blocked by:** None

**症状（2026-09-30 实测）**：`hashline_ownership` 是这台机器上唯一**每天都在变坏**的东西。

- 当天早上：**2,154,032 行 / 266 MB payload**，库 641.9 MB；
- 人手清过一次（删掉 4 个已不存在的 thread 的 10,405 行 + 7 天前的 226 行 undo，再 VACUUM）
  → 703,770 行、库 **226 MB**；
- **一小时后**：**713,353 行**（+9,583 行/小时）、库 **233.8 MB**——而这一小时里只是在正常干活。

**机制**：`harness.cap.hashline/store.clj` 的读取路径每返回一行就插一行归属
（`thread_id, anchor, path`），只在**同一个 thread 重读同一个文件**时才删（`forget-file!`）。
没有任何按时间的清理。这正是 `.scratch/record-window/spec.md`「另一处」记着的那张票。

**要定的（所以是 needs-triage）**：什么时候收、收哪些。三条候选，互不排斥：

1. **会话被放下时收**（`sessions/drop!` / 空闲 TTL）：会话一被 put away，它的锚点就没有下一个编辑
   要用它们了。代价是"下次接着编辑要先重新 `read`"——对冷会话本来就是如此；
2. **按年龄收**：`hashline_ownership` 现在**没有时间列**，要么加一列（迁移），要么借
   `hashline_sessions.updated_at` / `sessions.last_sent_at` 做判据；
3. **给表一个上限**（按 thread 或按行数），超了就丢最老的。

**明确不要的**：`hashline_undo` 按 **path** 键、不属于任何 thread，清理**一个会话时不能动它**
（理由写在 `harness.edge.forget` 的 ns docstring 里）。会话被删时那三张 `hashline_*` 表已经跟着
走了（同一个 commit，见 `.scratch/session-lifecycle/spec.md` 票 01）。

**验收**：

- 一条用例证明"放下的会话不再持有锚点"（或所选策略的等价断言）；
- `harness.cap.hashline.store-test` 保持绿；
- 跑一天之后 `hashline_ownership` 的行数不再单调上升。
