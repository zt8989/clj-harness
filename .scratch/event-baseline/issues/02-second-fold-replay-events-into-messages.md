# 02 — 第二个 fold：从事件行 + message 行折叠会话，双跑对账

**What to build:** 把 `harness.edge.ag-ui/outbound` 的状态机从「内核事件 → wire 帧」原样
改成（包一层）「记录里的事件行 + message 行 → AG-UI 消息表」，落在 `harness.edge.replay`
旁边（今天 `fold-frames` 的位置）：逐行喂事件，在 assistant 的 message 行处收口文本与
reasoning（`outbound` 本来就是 `:model/end` 才关——现在收口信号换成 message 行本身），
在 `:tool/result` 行处开 tool message，tool call 挂到当前 parent，`:run/interrupt` 行给最后
一条 assistant 挂 `metadata.custom.agui.interrupts`（`harness.kernel.frames/park-on-last-assistant`
的同款）。**id 的复现**：`outbound` 是纯状态机，按序重放事件即得同一套 `<run>-m/r/t/ctx<n>`；
entry 的 `:seq` 规则（终点行收口、message 行切断帧组的等价物）按事件行重述。

**为什么值得做：** 这是「记录不存帧」的可行性判据本身——在帧还在记录里的双写期，两个 fold
可以在**同一条真实记录**上逐 entry 对账；对不上就说明事件流里缺了什么，回票 01 补，
而不是等票 05 停了帧才发现。

**Blocked by:** 01

**Status:** needs-triage

- [ ] 事件 fold 与 `fold-frames` 在同一条记录上回答**逐 entry 相等**的消息表
      （id、role、content、toolCalls、metadata、卡片）——判据主体；
- [ ] 用至少三类真实记录钉住：普通问答 + 工具、一次 parked 的 run、一次被 stop 的 run
      （cut-off 与半截答案都在场）；
- [ ] entry 编号相等：`entries-of-rows` 的事件版给出的 `:seq` 与现 fold 一致
      （`.scratch/entry-numbering` 那条铁律在新方言下重述）；
- [ ] `counter-spelling` 的计数对账规则在新方言下重写（哪些行「数一次」要说死）；
- [ ] 红线用例：把票 01 漏写某一种事件的记录喂进来，fold 要能**点名缺的是哪种**，
      而不是静默折出一条短对话。

## Comments

2026-10-02 — 从对账拆出。对账判据是这条线的保险丝：双写不停、对账不过，票 05 不许开。
