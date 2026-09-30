# 01 — 记录与投影：`goal/change` 事件 + fold + `goals` 行

**What to build:** 目标住在**记录**里，`goals` 那一行是它的物化 fold——先把这条管道的两头接通，
状态机（票 02）再长在上面。

- **写**：`harness.cap.goal/append-change!` 往这场会话的 jsonl 追加一条 `event` 行，kind 是 `goal/change`，
  载荷是**完整后态快照**（票 02 的字段）；`clear` 追加一条**墓碑行**（`{:phase "cleared"}`，带 revision），
  不是删除。写点走 `harness.edge.stream/push!` 那条既有路（它自己说「路由跑在 http-kit 线程上，没问题」），
  在 run 内与 run 外都能调——`POST /api/compact` 早就这么写过 `context/compacted`。
- **折**：`harness.edge.replay/sofar-step` 多一个分支（照 `context/compacted` 那条）：`goal/change` 行
  → 会话的当前目标（快照覆盖式，墓碑折叠成 nil）。`folds-init` 里那条 fold 的初值是 nil。
- **投影**：`goals` 表，`thread_id` 主键 + 一列 `goal`（JSON 整份）+ 一列 `updated_at`，
  按 `sessions.numbers` 那条**物化 fold** 的既有形状：写整份、就地改、fold 是修复路径。
- **读**：`harness.cap.goal/goal-for`（投影行）与 `harness.cap.goal/goal-from-records`（折一份记录，
  重建用），两者答同一份——一条测试钉住这一点。

**Blocked by:** None — 可以立即开始

**Status:** ready-for-agent

- [ ] 迁移链多一步 `goals`（`table?` 探针 + `CREATE TABLE`），照 `todos-table` 的形状；
      全新库与已有库都要能开。**列名是 `goal`**（不是 `content`——要过 `db_test` 那条命名守卫，
      与 `todos.items` 选掉 `content` 同一理由）。
- [ ] `test/harness/infra/db_test.clj` 的元断言被满足：`goals` 进 `declared-state-columns`，
      带一句「为什么它是**物化 fold** 而不是投影内容、也不是它自己的真相」的论证
      （`sessions.numbers` 那条注释就是样本）。**只有这一处要改**，加表即漏。
- [ ] 一条 `goal/change` 事件行落进 jsonl 后：`harness.edge.replay` 折出来的当前目标 = 那条行的快照；
      **墓碑行**折成 nil；**没有过任何变更**与 **clear 过**对读者是同一个 nil。
- [ ] `goal-for`（读投影行）与 `goal-from-records`（折记录）在同一场会话上答**逐字段相同**的一份值：
      一条测试同时跑两条路，断言相等（`sessions.numbers` 的「折 = 那一行」同款）。
      投影片缺失而记录里有事件时，`goal-for` 走 fold 重建（修复路径），不是答 nil。
- [ ] run 之外也能写：一条用例在一个**没有 run** 的会话上 `append-change!`，jsonl 里那条行在、
      投影行跟着更新（这正是 `POST /api/goal` 要用的能力）。
- [ ] `CONTEXT.md` 的「状态住在哪」一节多一条词条：**目标**（跨多轮/多步的完成目标，人立人撤、
      模型可立可报；`goal/change` 是记录、`goals` 一行是其物化 fold；
      `*别叫成*` 任务/待办（`todo_write` 的模型计划）、计划（模型自己拆的步骤）、`/goal`（人的入口））。
- [ ] 测试：`test/harness/cap/goal_test.clj`（或同族）+ `harness.edge.replay-test` 里那条 fold 的用例，
      走 `harness.test-runner` 的隔离（不手设 `CLJ_HARNESS_HOME`）。

**本票的界线**：不碰状态机、不碰 HTTP、不碰工具、不碰注入、不碰 frontend。
