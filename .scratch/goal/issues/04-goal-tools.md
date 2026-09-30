# 04 — 三个工具：`get_goal` / `create_goal` / `update_goal`

**What to build:** 模型面的三个工具，照 dsh 的签名。

```
get_goal()                                      → 当前目标快照（含 id/revision/phase/rounds/…），无则 null
create_goal(objective, max_goal_rounds?)        → 建一个 active+armed 的目标
update_goal(goal_id, revision, action,          → action ∈ edit | pause | resume | complete | block
            objective?, max_goal_rounds?, blocked_reason?)
```

- **`get_goal` 是栅栏的供料口**：`update_goal` 要 `goal_id` + `revision`，模型必须先
  `get_goal` 把准确的 ref 抄下来再写。描述里写死这一点（「Call get_goal before update_goal and copy its
  exact goal_id and revision」）——这正是它存在的理由，不是「模型想知道目标」（提醒一直在它眼前）。
- **`create_goal` 可以从人的直接请求推断目标**（任何语言），但描述里写死
  「routine single-turn work 不要建」；**已有未完成目标时拒绝**，告诉它先 `update_goal complete`
  或等人 `clear`。**一条消息最多调一次**（`sole-call-of-its-name?`，`todo_write` 同款）——
  建目标没有东西可合并，两次都不生效。
- **`update_goal`**：`edit`/`pause`/`resume`/`complete`/`block`。栅栏不对就 `:goal-moved`
  （描述里告诉模型重新 `get_goal`）；`pause` 之后模型的 `resume` 被拒（`:paused-by-human`，
  「人暂停的目标等人恢复」）；`active` 但 disarmed（会话 resume/fork 后）的 `resume` 是**允许**的
  （唯一一处）。
- **`update_goal` 不需要 sole-call 规则**：栅栏自己挡第二次（第二次拿的就是过期 revision）。
- `block` 传 `blocked_reason`（kebab-code + 说明），相位要连续 `block-rounds` 轮才变——
  规则在 `harness.cap.goal`（票 02），工具只转发；描述里说清「一次报错不足以宣布阻塞」。
- 描述里说清模型**不能**让 `paused` 回 active、**不能** clear（那是人的）。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 工具表多三个名字；`get_goal` 无参数，`create_goal` 要 `objective`（可选 `max_goal_rounds`），
      `update_goal` 要 `goal_id`/`revision`/`action`（`objective`/`max_goal_rounds`/`blocked_reason` 按需）。
- [ ] 三个名字进 `harness.kernel.tools/names-hash` 的名字集合——**这会移动指令签名**，下一次 run 走
      `.scratch/instruction-updates` 的指令更新通道；测试照既有那条走，不为此新增机制。
- [ ] `get_goal` 在没有目标时答一个**明确的**「没有目标」（不是空对象），带一句「要立就 `create_goal`」。
- [ ] `create_goal`：成功答新目标快照；已有未完成目标 → `:goal-exists`；一条消息两次调用 →
      `:second-create-in-turn` 且**两次都没落盘**。
- [ ] `update_goal` 五个动作各自落到票 02 的动词；栅栏不符 → `:goal-moved`，句子让模型重新 `get_goal`。
- [ ] **`complete` 之后提醒会停**（相位不再是 active）——一条集成用例：目标 complete、跑一轮，
      断言没有 `<goal>` 注入。
- [ ] **`block` 第一次不生效**：报一次 `blocked_reason`，相位仍 `active`、快照里多一条 pending-block；
      连续第 `block-rounds` 轮才变 `blocked`。
- [ ] 无会话在作用域（`kernel-tools/*thread-id*` 是 nil）按 `:no-session` 拒绝。
- [ ] 测试：`test/harness/cap/tools_test.clj`（或同族）覆盖三工具、主要拒绝、以及「create 一次消息两次不生效」。

**本票的界线**：不做 driver（06）、不做界面。
