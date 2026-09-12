# 02: 工具执行生命周期 jsonl 事件 —— pre-execute / execute / post-execute

**What to build:** 引入 ApplePi ADR-0021 的工具执行三相审计事件。每次工具调用在执行缝隙产出三行 jsonl（同文件、同行 schema `{ts, runId, kind, payload}`，扩 kind）：`tools/pre-execute`（lookup + 参数校验结果，outcome 为 pass / unknown-tool / missing-args，后者带缺失参数名）、`tools/execute`（执行结果，失败带 error）、`tools/post-execute`（收尾），全部按 toolCallId 键控并带 toolName。wire 侧零新增——AG-UI 的 TOOL_CALL_START/ARGS/END 与 TOOL_CALL_RESULT 已 1:1 覆盖 ApplePi 的 tool-input-available / tool-output-available。

**Blocked by:** 01: 会话级工具注册表 —— base 不可变 + per-thread overlay

**Status:** ready-for-agent

- [ ] scripted run 后，每次工具调用在 jsonl 中三相齐全，toolCallId 三行对齐且等于该调用的 id，payload 含 toolName
- [ ] unknown-tool 与 missing-args 场景：pre-execute 行 outcome 落位，无 execute/post-execute 之外的行错位（post-execute 仍闭合）
- [ ] 执行抛错的场景：execute 行带 error，assistant/tool 消息行不受影响
- [ ] wire 帧零变化：既有 AG-UI 帧序列断言逐字不变；replay 读侧（input/event 过滤）不受新 kind 影响
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
