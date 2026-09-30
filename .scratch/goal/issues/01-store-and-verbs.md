# 01 — `harness.cap.goal`：一行状态、五个动词、校验只写一份

**What to build:** 一个会话一个目标的**存储与规则**，不做接口、不做注入、不做界面。

一张新表 `goals`：`thread_id` 主键，**整个目标一个 JSON 值一列**，加一个 `updated_at`。
记录形状就是 spec 决定 2 那四个字段（`text` / `status` / `progress` / `updated-at`）。

`harness.cap.goal` 是这份状态的**唯一读写处**，照 `harness.cap.todos` 的骨架：
`statuses` 是数据、`goal-for` 是读者、`write!`（内部）落盘并答**存进去的那份**，
每个动词的前置条件在**这里**判、按名字拒绝——两个门（票 02 的路由、票 04 的工具）都调这里，
拒绝不写第二份。

动词（人那半边）`set!` / `edit!` / `pause!` / `resume!` / `clear!`；
（模型那半边）`progress!` / `complete!`。前置条件与状态迁移见 spec 决定 4。

**Blocked by:** None — 可以立即开始

**Status:** ready-for-agent

- [ ] `harness.infra.db` 的迁移链多一步 `goals`（`table?` 探针 + `CREATE TABLE`），
      照 `todos-table` 的形状：`thread_id` 主键、一列 JSON（叫 `goal`，不是 `content`——列名要选得
      过得了下面那条守卫，与 `todos.items` 选掉 `content` 是同一个理由）、一列 `updated_at`。
      一个全新的库与一个已有 `todos` 的旧库都要能开——旧库跑完迁移后 `goals` 在，`todos` 不动。
- [ ] **它是状态不是记录**：`test/harness/infra/db_test.clj` 的 `sessions-hold-no-conversation-content`
      里那张 `declared-state-columns` 表多一行 `"goals" #{"thread_id" "goal" "updated_at"}`，
      并把「为什么它是状态而不是记录」写在那行旁边（`todos` 那条注释就是样本：
      整体替换、无 append、无历史）。**只有这一处要改**，加表即失败。
- [ ] `goal-for` 对「没有行」答 `nil`，对「有行」答**规范化的四个字段**（顺序就是 spec 决定 2 的顺序）；
      「从没立过」与「立过又 clear 了」都是 nil，是同一个答案（`todos/items-for` 收口的那条）。
- [ ] `set!`：无目标时写入并答那份；**已有目标（`active` / `paused` / `completed` 任一）时拒绝**
      （`:already-set`），句子点名先 `edit` 或 `clear`。写入后 `status` 是 `active`、`progress` 是 nil。
- [ ] `edit!`：无目标拒绝（`:no-goal`）；有则换文字、**清 `progress`**、置 `active`。
- [ ] `pause!`：只有 `active` 可暂停（`:not-active`）；`resume!`：只有 `paused` 可恢复（`:not-paused`）。
      两者都**保留 `progress`**。
- [ ] `clear!`：删掉那一行；**没有目标时不抛错**，答出与读者同一句「这场会话没有目标」，且**不写**。
- [ ] `progress!`：只有 `active` 的目标可写（`:no-goal` / `:not-active`）；`text` 是**必填非空**字符串。
- [ ] `complete!`：只有 `active` 可标完成（`:no-goal` / `:not-active`）；`progress` 保留、`status` 变 `completed`。
- [ ] **校验拒绝的是能说清的东西**：`text` 非字符串/空白 → `:no-text`；`status` 不在 `statuses` → `:unknown-status`；
      `text` 超长封顶（一个数，写在 `harness.cap.goal` 里，照 `todos/max-items`），超了按名字拒绝。
      每个拒绝都带**合法集合或上限**（「手写的一句话就是两份会漂的答案」那条）。
- [ ] 每个写动词答**存进去的那份**（规范化的，或 nil），不是调用者发的那份。
- [ ] `thread_id` 为 nil 时：**写**的七个动词都按名字拒绝（`:no-session`），句子指向「目标属于会话」；
      **读**（`goal-for`）答 `nil`——一个只要值、没有对象可告知的调用者不该被抛错
      （与 `todos/items-for` 对 nil 答 `[]`、而工具那条路才 `no-session!` 同一处分界）。
- [ ] `CONTEXT.md` 的「状态住在哪」一节多一条词条：**目标**（一个**会话级**的方向，人立、
      模型只能汇报和标完成；`goals` 一行一个会话；`*别叫成*` 任务/待办（那是 `todo_write` 的模型计划）、
      计划（那是模型自己拆的步骤）、`/goal`（那是人的入口，不是这个东西本身））。
- [ ] 测试：`test/harness/cap/goal_test.clj`，走 `harness.test-runner` 的隔离（不手设 `CLJ_HARNESS_HOME`），
      每条前置条件一个用例，测试名里说出规则不靠注释。

**本票的界线**：不碰 HTTP、不碰工具表、不碰 pre-LLM 缝、不碰前端。
