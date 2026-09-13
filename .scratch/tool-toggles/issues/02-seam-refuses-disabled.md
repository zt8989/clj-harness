# 02: 执行缝拒绝被关的调用 —— `:disabled` 取代误报的 unknown-tool

**What to build:** 被关闭的工具被调用时，执行缝不执行它、不 park，而是给出一个明确的状态，而不是今天那句 `unknown tool`。`harness.event` 的 pre-execute outcome 词汇加一个 `:disabled`（文档枚举，加一个值与一句说明）；执行缝在 lookup 命中后先查关闭，命中则 pre-execute(`:disabled`) 加 post-execute 收尾、跳过 execute 相；结果作为信息回给模型，措辞要说清"这个工具在本会话被关闭了，打开它即可使用"。jsonl 沿用既有三相 schema，只多一个 outcome 取值。

**Blocked by:** 01: 会话级开关 —— 可用性维度与四个正交入口

**Status:** ready-for-agent

- [ ] 被关的工具被调用时：pre-execute outcome 为 `:disabled`，没有 execute 相，post-execute 恰好一次（post 恒闭合）
- [ ] 模型收到的内容是"在本会话被关闭"，不是 "unknown tool"；并且有一条测试证明模型经 eval 打开之后同一个工具立刻可执行——「可见但被拒」这个选择的收益必须被锁住
- [ ] `:disabled` 严格先于审批：一个既被关又需审批的工具报 `:disabled`，不 park、不落 `:needs-approval`
- [ ] 真正不存在的工具仍报 `:unknown-tool`，两条路径不合并
- [ ] jsonl 落 `outcome: "disabled"`；ag_ui 对三种工具事件仍零帧，wire 无变化
- [ ] 离线全量 harness.test-runner 全绿
