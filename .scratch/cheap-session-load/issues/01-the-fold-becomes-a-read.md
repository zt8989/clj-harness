# 01 — 读侧的「一轮折成摘要」：一轮一张卡，最后一轮不折

**做什么**

`harness.edge.replay` 长出一层**投影**：给一串记录，答出「屏幕上该看到的会话」——
**已经结束、而且不是最后那轮的**每一轮折成一张摘要卡，**最后一轮原样**。

一轮的形状照 `ui/src/lib/turns.ts` 已经写死的规则（一条也不新发明）：

- **边界**：相邻的 assistant 消息是一轮（`turnBounds`）；user 消息是下一轮的开始。
- **结论**：`turnConclusion` —— 那一轮里**最后一条**带非空文本的消息。
- **计数**：`turnCounts` —— 它的 `tool-call` 部件数，以及它自己的 assistant 消息数。
- **那行字**：`turnSummaryLabel` 的算术（`72 次工具调用 · 25 条消息`），翻译仍由界面做。
- **折不折**：`turnIsSettled` —— 只有结束了的轮才折；最后一轮永远不折（它还在长）。

**形状**：一张卡长这样，挂在那一轮的答案消息上，用一个 `data` part 带出去（客户端画卡的那套机制
`ui/src/lib/injections.ts` 已经在，照它走）：

```clojure
{:calls 497 :messages 25 :from-seq 12 :to-seq 1904}
```

**步骤不发**：折掉的那一轮的 `reasoning`、`tool-call` 部件与中间的 assistant 消息，**根本不出现在答案里**。
`from-seq` / `to-seq` 是它的记录行号区间，票 04 靠它把原始步骤拉回来。

**纯函数，而且只有这一半是新的。** 折本身是「在现有的 `entries` / `fold-frames` 之上换个粒度」，
不要另起一条自己读文件的实现：`fold-entries` 那条流式路（`entries-step`）就是它该坐的地方。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] `harness.edge.replay` 新增投影函数，输入是记录（或它的流），输出是「折好的轮 + 最后一轮原样」；
      它是**纯函数**，测试不需要文件、不需要服务。
- [ ] 规则与 `ui/src/lib/turns.ts` 一字不差，而这件事由**一份共享用例表**钉住：现在 `ui/test/suites/turns.ts`
      里的那些字面消息列表与期望搬成一份 fixture，Clojure 侧读同一份（规格的决策 8）。
- [ ] `test/harness/edge/replay_test.clj` 钉三条：折好的轮是什么形状；最后一轮不折；一轮都没结束时什么都不折。
- [ ] 「折出来的和没折的那份说的是同一件事」有一条用例：同一份记录，折过的投影与 `entries` 的完整答案，
      在「user 文本、答案文本、计数」上逐条相同。
- [ ] `calls` 为 0 时不画「0 次工具调用」（现在是 `turnSummaryLabel` 的规则，别在服务端把它变成 0）。
