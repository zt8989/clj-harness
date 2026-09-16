# 03 — 客户端直接把 `reasoning_content` 带上来时，别把它丢掉

**What to build:** `harness.edge.ag_ui/provider-assistant` 是**白名单重建**：它只认
`:content` / `:toolCalls` / 前面那条 `reasoning` 角色消息，其余字段一律丢掉。可是 AG-UI 的客户端
**也可以**把一个 assistant 消息连同 `reasoning_content` 字段一起送回来（provider 形状的历史、
从日志回灌的历史、别人写的客户端都会这么做）。那种消息现在会让这个字段**在入站处静默消失**，
于是下一跳就撞上同一个 400。

改成：**这个字段要是已经在消息上，就原样带过去**；前面那条 `reasoning` 角色消息的折叠**照旧**
（两者都在时以消息自己带的那个为准，并在注释里说明为什么——它长在这条消息上，而折叠来的那条
是它的邻居）。

从用户视角：换一个客户端、或者从日志回灌历史，都不会因为消息形状不同而丢掉推理。

**Blocked by:** None — can start immediately（与 01/02 互不阻塞：那条修的是「键不在时补」，
这条修的是「键在时别丢」）

**Status:** ready-for-agent

## 验收

- [ ] 一条 assistant 消息同时带 `:reasoning_content` 与 `:content`／`:toolCalls` 时，重建结果里
      这个字段**原样在**（值与入站逐字相同）。
- [ ] 老形状不变：只有前置 `reasoning` 角色消息、assistant 上没有字段时，折叠结果与今天一致
      （`ag_ui_test.clj` 的 `outbound-then-inbound-preserves-reasoning` 一个字不改就过）。
- [ ] 两者都在时，取消息自己带的那个，并且有一条用例把这个先后写下来（别只写在注释里）。
- [ ] 端到端一条：走真 edge 发一个「历史里 assistant 自带 `reasoning_content` 字段」的输入，
      断言 provider 收到的那份历史里该字段还在（用 01 的严格档最直接——字段丢了它会 400）。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单逐条不变。
