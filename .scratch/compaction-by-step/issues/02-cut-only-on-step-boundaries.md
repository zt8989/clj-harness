# 02: 压缩只在 step 边界上切

**What to build:** 一次压缩的 head / tail 边界只落在**完整的 step** 之间：head 以某个 `step/end`
收尾，tail 从某个 `step/start`（或它前面那条 user 消息）开始；一步的模型调用与它调的工具永远在
同一侧。从人视角：压缩不再因为切断一次工具调用而失败，摘要的输入永远是一段自洽的对话。

**Blocked by:** step-events 的票 02–06 先做完并合并到 main（本票在它之后开工）

**Status:** ready-for-agent

- [ ] head 的结尾永远在某个 `step/end` 之后；**不落在** `assistant(tool_calls)` 与它
      工具响应之间。
- [ ] tail 从某个 `step/start` 或一条 user 消息开始；不以裸 `tool` 消息开头。
- [ ] 没有 `step/*` 行的旧记录（step 落地之前写的记录）退回今天的「最新 user 消息单元」边界
      （`overflow-plan` 的 `unit-start` 已是这个形状），并保持不切断配对。
- [ ] 回归用例：head 末尾是带 `tool_calls` 的 assistant 的那段记录，压缩**必须成功**
      （今天这里 400）。
- [ ] 摘要请求不再靠运气躲过「配对被切断」；若保留防御动作，断言它不改变正常记录的消息。
- [ ] 一条用例：压缩后的模型视图里，摘要之后的第一条消息不让任何 `tool_calls` 悬空。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（报数带分支与提交）。

**为什么值得进记录（复核用）：** 今天一步的边界只能由读者拿 `model/start` 与随后的 `tools/*`
现推，推法至少两份（`ui/src/lib/turns.ts` 与 `harness.edge.turn`）。边界写进记录之后，压缩读它就好，
不必再猜。
