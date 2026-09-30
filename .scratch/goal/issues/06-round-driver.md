# 06 — `goal-round-driver`：一轮收尾后自动开下一轮

**What to build:** 让活着的目标自己往前走。

**触发**：一场 run 以正常终局收尾（`:run/done`，不是 interrupt、不是 error）时，看这个会话的目标：

```
phase = active  ∧  armed?  ∧  rounds < max-rounds  ∧  上一轮有进展
```

全部成立 → 追加一条 `round-turn`（票 05）作这一轮的**真 user 消息**、`note-round!`、开下一轮；
任何一个不成立就什么都不做（`blocked`/`completed`/`paused`/disarmed/撞上限都算不成立）。

- **有进展** = 刚收尾这一轮的记录里，至少有**一次 `:outcome :pass` 的改文件工具调用**
  （`write` / `edit` / `replace` / `insert` / `undo_last_replace` 那一家）。
  **一个都没有 = 零进展**：driver **不开下一轮**，调 `block!`（`code: no-progress`）并停下，
  目标条告诉人。这是 dsh 那次 642M token 事故的直接教训，不是保守。
- **多开一轮**照 `harness.edge.http/run-subagent!` 那条既有形状：服务端主动开一场 run、
  自己的帧汇、经 mux 广播给看客——**不造第二套 run 机制**。
- **每一轮开始前重读**相位与 armed：`pause`/`clear` 立刻生效；撞上限立即停；进程重启后 armed 是空的，
  所以**重启不会自己接着烧钱**——要人再说一句（发条消息或 `/goal resume`），正是 spec 决定 3/9。
- **`rounds` 落在记录里**（`note-round!`），重启后折得回来；不是进程内存。
- **driver 只在有 run 的会话上可能触发**：一场会话没有 run 收尾，就没有驱动它的机会。

**Blocked by:** 02、05

**Status:** ready-for-agent

- [ ] 四个条件全成立：收尾后**自动**出现下一轮的 run，且这一轮的历史里有一条 `round-turn` user 消息。
- [ ] 四条各自**单独**不成立时都不开：`paused`、`completed`、`blocked`、`rounds = max-rounds`。
- [ ] **零进展刹车**：一轮里没有通过的改文件调用 → **不开下一轮**，目标变 `blocked`（`code: no-progress`），
      一条用例专门钉它（这是本票最重要的一条）。
- [ ] **撞上限停**：`rounds` 到 `max-rounds` 时停，相位不动（仍 active），目标条能告诉人「跑满了」。
- [ ] **人随时能停**：一轮在飞时 `pause`/`clear`，下一轮不再开（每轮开始前重读）。
- [ ] **重启即 disarmed**：折一份记录重建会话后，`armed?` 是假、driver 不触发；一条消息或人的 `resume`
      之后才再开。
- [ ] **run 的结局类型**：interrupt（park 了审批）与 error 都**不**触发（那个人还没答 / 那是失败）。
- [ ] `max-rounds` 默认 **25**，`harness.edn` 的 `:goal {:max-rounds …}` 可覆盖；`block-rounds` 同处
      （默认 3）。**默认值写在 `harness.cap.goal`，配置按 key 覆盖**（与 `:compaction` 同一纪律）。
- [ ] 帧与推送：每一轮照常推 `model/*`、`turn/*`、`goal`；看客不需要做任何特别的事就跟着长大。
- [ ] 测试：driver 的四条闸与刹车各一条**集成**用例（跑一场真 run 收尾），走 `harness.test-runner` 的隔离；
      时间与并发不用时钟、不用 sleep，靠 run 的收尾缝。

**本票的界线**：这是这套里最重最险的一块，别的票不替它兜底；`prompt.md` 不动。
