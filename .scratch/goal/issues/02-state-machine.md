# 02 — 状态机与动词：`GoalRef` 栅栏、相位、轮数、armed

**What to build:** `harness.cap.goal` 的状态机。**五个门（票 03 的路由、04 的三个工具、06 的 driver）都调它**，
规则与拒绝只写一份。

相位与字段就是 spec 决定 3 那份（`id` / `revision` / `objective` / `phase` / `rounds` / `max-rounds` /
`blocked` / `updated-at`）。相位 `active | paused | blocked | completed`，`clear` 是墓碑。

动词：`create!` / `edit!` / `pause!` / `resume!` / `complete!` / `block!` / `clear!` / `note-round!`
（driver 用，票 06），以及读者 `goal-for` / `goal-from-records`（票 01）。

- **`{id, revision}` 栅栏**：每个写动词都要一个 `GoalRef`（`create!` 除外，它铸新 id）。
  ref 与当下不符 → `:goal-moved`，句子让人/模型先 `get_goal`。
- **模型能立**：`create!` 在**已有一个未完成的目标**时拒绝（`:goal-exists`），要人/模型先 clear。
- **`edit!` 只改文字**，不动相位、不动激活（dsh 那条）。
- **`resume!` 只对 `active` 但 `disarmed` 有效**（`:paused-by-human` 当 `paused`）；
  **`paused` 只有人的门能回 active**（一个显式的 `:by :human` 参数，不是靠调用点自觉）。
- **`block!` 要同一个 blocker 连续 `block-rounds`（默认 3）轮**：没到时记进 `:pending-block`、相位不动。
- **`armed`**：进程内存（`harness.cap.goal` 里一张按会话的表），`create!` 与人的 `resume!` 置上，
  `pause!`/`clear!`/`complete!`/`block!` 与**会话重建**清掉；它不是 revision、不落盘。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 每个写动词落一条 `goal/change`（或墓碑）行 + 更新投影行（票 01 的两头），`revision +1`；
      `create!` 从 1 起、铸一个新 id；`clear!` 后新建是**新 id**。
- [ ] 栅栏：用旧 `revision`（或别人的 `id`）调任一写动词 → 按名字拒绝 `:goal-moved`，**不写任何一行**。
      用当下 ref 调 → 成功。
- [ ] `create!` 在已有未完成目标时拒绝 `:goal-exists`；在只有 completed/墓碑时**可以**建（新目标、新 id）。
- [ ] `edit!`：改文字、`revision +1`，**相位与激活不变**（一条测试专门断言 paused 的目标 edit 完还是 paused）。
- [ ] `pause!`（模型或人都可）：active → paused，`armed` 掉。
- [ ] `resume!`：`phase=paused` 且 `:by :model` → 拒绝 `:paused-by-human`；`:by :human` → active。
      `phase=active` 但 `armed?` 假（会话 resume/fork 后）→ **模型也能** resume（re-arm），这是唯一一处。
      `phase=completed` → 拒绝。
- [ ] `block!`：第一次报 `{:code .. :reason ..}` → 写进 `:pending-block`，相位**不动**；
      连续同一 `code` 报满 `block-rounds` 轮 → `phase=blocked`、`blocked` 落定、`armed` 掉。
      中间换一个 code → 计数从头开始（一条测试钉住）。`block-rounds` 是 `harness.edn` 的 knob。
- [ ] `complete!`：active/paused → completed；`armed` 掉；拒绝 completed 与 nil。
- [ ] `clear!`：写墓碑、投影行清成 nil、`armed` 掉；没有目标时不抛错（与读者同一个 nil）。
- [ ] `note-round!`（driver 用）：`rounds +1`（写记录，重启后还在），要求 `phase=active`。
- [ ] **`armed` 的边界断言**：`create!` / 人的 `resume!` 之后为真；`pause!`/`clear!`/`complete!`/`block!` 之后为假；
      **会话重建之后为假**（一条用例折一份记录重建，断言 armed? 是假、相位照旧）。
- [ ] `thread_id` 为 nil：写动词拒绝 `:no-session`；`goal-for` 答 nil。
- [ ] 测试：`test/harness/cap/goal_test.clj`，每条迁移与每个拒绝一个用例，名字里说出规则。

**本票的界线**：不做 driver（那是 06）、不做工具表、不做界面。
