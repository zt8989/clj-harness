# 03 — 每轮提醒：派生注入、按内容幂等、只提醒活着的目标

**What to build:** 让模型**每一轮**都看得到目标——不是人再说一遍，是 pre-LLM 缝追加的一条
`role=user` 消息。

`harness.cap.goal/before-llm`（`history thread-id` → history），与 `harness.cap.jobs/before-llm`
同形同缝，由 `harness.cap.project/before-llm` **接在技能正文、作业结局之后**（离问题最近：
它是此刻要看的立场，别的是这个问题用到的材料）。

提醒正文（spec 决定 6）：

```
<goal>
<text>
progress: <progress>        （只有 progress 非 nil 时这一行）
朝着这个目标推进。用 `goal` 工具报告进展，做到时把它标为完成。
</goal>
```

- **只在 `active` 时注入**：`paused`（「别再推我」）与 `completed` 都不注入。
- **按内容幂等**：历史里已有与当前提醒**逐字相同**的 `<goal>` 块就不再追加。
- **派生**：每个模型调用现算；一次压缩把它折掉，下一次调用带回来。
- 注入随 `:run/start` 变成一张卡（`injected-frame`，id 前缀 `-pre<i>`），
  客户端**不回发**它——所以它照旧进 jsonl 的 `message` 行（模型看到了什么，日志就有什么）。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 一个 `active` 的目标，`before-llm` 在**末尾**追加一条 > 一条 `<goal>` user 消息（不是改中间某条）。
- [ ] **幂等**：把 `before-llm` 的输出再喂它一次，结果逐字节不变。
- [ ] **没有目标 / `paused` / `completed`**：历史**原样返回**（同一份，不是等值的另一份）。
- [ ] **目标一改就在末尾多一条**：把同一份历史先喂给「目标 A」的提醒、再喂给「目标 B」的提醒，
      末尾那条是 B，A 那条**还在**（模型确实读过 A）——不是删除、不是就地改。
- [ ] `progress` 有值时多出 `progress:` 那一行；nil 时**整行不出现**（不是空串占位）。
- [ ] `<goal>` 的识别是**认这个块本身**（标签 + 内容），不是认「最后一条 user 消息」，
      所以一句话里夹着别的注入（技能正文、作业结局）也认得出来。
- [ ] 顺序钉住：`project/before-llm` 的输出里，技能正文 → 作业结局 → 目标提醒，三段各在自己的位置；
      有一条测试读出这个顺序（不是靠注释）。
- [ ] **端到端可见**：一个 active 的目标，跑一轮之后
      （a）jsonl 里那条提醒作为一条 `message` 行在（就是模型读到的那份），
      （b）刷新页面重建会话，那张注入卡还在（`apply-frames` 折得回来）。
- [ ] `harness.cap.project/before-llm` 的 docstring 把第三个注入者写进去
      （现在是技能与作业两半，加上目标就是三半），说清它为什么在同一个缝里。

**本票的界线**：不改 `prompt.md`、不做界面、不做工具。
