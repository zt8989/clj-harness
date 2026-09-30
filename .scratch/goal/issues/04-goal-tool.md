# 04 — `goal` 工具：报告进展、标完成

**What to build:** 模型的唯一一只手：`goal`。

```
goal  {"action": "progress" | "complete", "text": "…"}
```

- `progress` → `harness.cap.goal/progress!`，`text` 必填；答一句**收据**（写成了什么、目标现在什么状态）。
- `complete` → `harness.cap.goal/complete!`；答一句「目标标记完成」。
- **模型不能** `set` / `edit` / `pause` / `resume` / `clear`——工具里没有这些动作，
  这是「文字与开关归人」那条界线在**工具表上**的落点（spec 决定 3）。
- **没有目标时拒绝**，句子告诉模型目标由**人**立，它不能凭空造一个。
- **一条消息最多调一次**（`sole-call-of-its-name?`，`todo_write` 同款）：状态整体替换，
  一条消息里两次调用没有东西可合并，**两次都不生效**。
- 描述里写清它是**汇报**不是**下达**，以及「只有人立目标、只有人能暂停/清除」。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 工具表里多一个 `goal`，参数 schema 只有 `action`（enum 两项）与 `text`（字符串）；
      `complete` 不要求 `text`，`progress` 要求。
- [ ] `goal` 出现在 `harness.kernel.tools/names-hash` 的名字集合里——**这会移动指令签名**，
      下一次 run 走 `:in-place` 的指令更新通道（`.scratch/instruction-updates`）；测试照既有那条走，
      不为此新增机制。
- [ ] 报进展成功：答一句收据，**不重复**模型刚发的那句话本身（`todo_write/render` 那条
      「收据不是回声」的理由），答出「记成了什么」。
- [ ] 没有目标时拒绝，错误句点出「目标由人立」；`action` 不在 enum 里时按名字拒绝并列出合法集合。
- [ ] 一条消息里两次 `goal` 调用：第二次抛 `:second-goal-call-in-turn`，**两次都没有落盘**
      （`todo_write` 那条「两个都不生效」逐字对齐）。
- [ ] 无会话在作用域（`kernel-tools/*thread-id*` 是 nil）时按 `:no-session` 拒绝，
      句子指向目标属于会话这件事（`harness.cap.goal` 自己的 `no-session!`）。
- [ ] 测试：`test/harness/cap/tools_test.clj`（或同族）覆盖两种动作、三种拒绝、
      以及「一次消息两次调用两次都不生效」。
- [ ] 工具调用的结果照常进 jsonl，是模型读到的那一句（不需要额外记录机制）。

**本票的界线**：不改注入、不改界面。
