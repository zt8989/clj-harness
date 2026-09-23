# 票 06 的走查：parked 的那一场，刷新回来还能答

**怎么起的**（`AGENTS.md` 那条铁律：家自己造，不碰 `~/.clj-harness`）：

```
node scripts/dev.mjs --scripted .scratch/session-after-refresh/evidence/06-go.json --ui-port 5319
node .scratch/session-after-refresh/park-walkthrough.mjs
```

脚本替身三轮（`06-go.json`）：第一轮调 **`session-configure`**（这个工具标了 `:requires-approval`，所以
**真会 park**，不是画出来的），第二轮是批准之后那句回答。浏览器是 agent 那台 Playwright 里的真 Chromium。
**判据是服务端那句话**：`GET /api/threads/<stem>/page` 的 `state` 在悬置期间是 `parked`。

## ALL GREEN

```
ok   the send remembers a conversation -- d677b91c-…
ok   the run parks on the approval (the SERVER says parked) -- parked
ok   ...and the approval card is on screen
ok   ...and the composer is shut while it waits (even with text typed) -- sendOffered=false sendPresent=true
ok   the reload lands back in the parked conversation
ok   ...and the CARD IS STILL THERE (this is the ticket)
ok   ...and the server still says parked -- parked
ok   ...and the composer is still shut -- a door with a way through it -- sendOffered=false
ok   approving resumes the run (the server leaves parked) -- settled
ok   ...and the parked call gets its result, so the run answers -- ["session-configure · reasoning-effort=lowDone","批准之后这一轮的回答。"]
ok   ...and the card is gone
ok   ...and the ask is still in the same conversation
```

截图：`06-01-parked-with-the-card.png`、`06-02-the-card-survived-the-reload.png`、
`06-03-approved-and-answered.png`。

要紧的三格：

1. **刷新之前卡片在**（作为对照），`state=parked`，composer 关着——连输入框里打了字也发不出去。
2. **刷新之后卡片**在（票面那半：以前这里什么都没有，那次悬置的调用**永远没有人能答**），`state` 仍
   `parked`，composer 仍关着。卡片上那句「The message box stays closed until this is decided」是真的：
   现在门后面有那张卡。
3. **点批准**之后：服务端离开 `parked`、那次调用拿到结果（工具行 `Done`）、答案落进**同一场**对话、卡片消失。

## 两半是怎么合起来的

- **服务端**：`harness.kernel.frames/apply-frames` 现在认得带 `outcome.interrupts` 的 `RUN_FINISHED`，
  把它折进**最后一条 assistant 消息**的 `metadata.custom.agui.interrupts`。记录里那帧本来就在，这是**投影**。
- **客户端**：`app.tsx` 的 `toThreadMessages` 以前把每条消息都盖成 `complete/unknown`，把上游
  `fromAgUiMessages` 已经从 metadata 读出来的 `requires-action`/`interrupt` 扔掉了。现在只覆盖**最后一条
  且窗口说 running** 的那条，其余保留消息自己的状态。
- **顺手**：`statusOf` 取服务端的 `parked`、`isSendDisabled` 加上 `parked`——卡片回得来，门才有出口。

## 它没证明什么

- **elicitation 那一类悬置**（服务端提问）：走查只摆了 `tool-approval`。两者共用同一套卡与同一份
  resume（`approval-gate.tsx` 的 `isParkedInterrupt`），但没有在真浏览器里各摆一次。
- **多张卡一起**（一次 park 多个调用）：后端用例覆盖多 interrupt 的形状，走查只有一张。
- **布局**：截图三张；卡片与 composer 的间距、悬置行的画法这类问题只有眼睛说得清。
