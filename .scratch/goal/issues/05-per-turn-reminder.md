# 05 — 每轮提醒：派生注入、按内容幂等、只在 active 注入

**What to build:** 让模型每一轮都看得到目标——`harness.cap.goal/before-llm`，与
`harness.cap.jobs/before-llm` 同形同缝，由 `harness.cap.project/before-llm` 接在技能正文、作业结局之后
（离问题最近：它是此刻要看的立场，别的是这个问题用到的材料）。

正文（spec 决定 8）：

```
<goal revision="3">
把登录模块重构完，补齐测试和迁移说明
round 7/25
朝着这个目标推进。用 `get_goal` 看清现状、`update_goal` 报进展或标完成；做不下去就说明卡在哪。
</goal>
```

- **只在 `active` 注入**：`paused` / `blocked` / `completed` / 无目标都原样返回。
- **按内容幂等**：历史里已有一条与当前提醒**逐字相同**的 `<goal>` 块就不再追加；目标一改、轮数一动，
  就在**末尾**追加一条新的（旧的留着）。识别是认**这个块**（标签+内容），不是认「最后一条 user 消息」。
- **派生**：每个模型调用现算；一次压缩把它折掉，下一次调用带回来。
- 注入随 `:run/start` 成一张卡（`injected-frame`，id 前缀 `-pre<i>`），客户端**不回发**；
  它照旧进 jsonl 的 `message` 行（模型看到了什么，日志就有什么）。
- 还有一条**给 driver 用的**：`harness.cap.goal/round-turn`，产出那一条 round 开场
  （objective + round n/max + 「做到就 complete，做不下去就说卡在哪」），票 06 用它。两者同源，
  正文由 `harness.cap.goal` **一处**产出。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `active` 目标：`before-llm` 在**末尾**追加一条 `<goal>` user 消息（不是改中间某条）。
- [ ] **幂等**：把输出再喂一次，逐字节不变。
- [ ] 无目标 / `paused` / `blocked` / `completed`：历史**原样返回**（同一份，不是等值另一份）。
- [ ] 目标一改（或轮数一动）就在末尾多一条新的，旧的那条**还在**。
- [ ] 识别认块本身：一句话里夹着技能正文、作业结局也认得出来。
- [ ] 顺序钉住：`project/before-llm` 的输出里 技能正文 → 作业结局 → 目标提醒，一条测试读出这个顺序。
- [ ] **端到端可见**：跑一轮后（a）jsonl 里那条提醒作为 `message` 行在，（b）重建会话后那张注入卡还在。
- [ ] `harness.cap.project/before-llm` 的 docstring 把第三个注入者写进去（现在说两半，加上就是三半）。
- [ ] `round-turn` 的正文与提醒同源：一条测试断言两处对 objective / round / 上限的措辞出自
      `harness.cap.goal` 的同一个函数（不是两段手写文本）。

**本票的界线**：不改 `prompt.md`、不做 driver（06）、不做界面。
