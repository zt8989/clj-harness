# spec: tools-lifecycle（会话级工具注册表 + 工具生命周期事件）

参考 applepi（`packages/core` 的 harness.ts 工具注册表与 ADR-0021 工具执行生命周期）补齐本仓 TOOLS 相关事件定义与运行期动态注册能力。

## ApplePi 清单（2026-09-12 已核对源码）

- **wire 侧**（UI-message-stream v1）：`tool-input-available` {toolCallId, toolName, input}、`tool-output-available` {toolCallId, output}。本仓 AG-UI 出站已 1:1 覆盖：TOOL_CALL_START/ARGS/END ≈ tool-input-available，TOOL_CALL_RESULT ≈ tool-output-available——**wire 零新增**。
- **jsonl 审计侧**（executeTool 三相，ADR-0021）：`tools/pre-execute` {toolCallId, toolName, outcome?/reason?}、`tools/execute` {toolCallId, toolName, error?}、`tools/post-execute` {toolCallId, toolName}。本仓 jsonl（input/event/message 三种 kind）完全缺失——**引入主体**。
- **注册 API**（harness.ts 的 tools Map）：registerTool（重名抛错）、unregisterTool（不存在 no-op）、getTool/getTools。本仓只有全局 register!，无删除、无守卫、无会话概念——补齐并会话化。

## 决策

- **基础工具集不可变。** 启动时（ns 加载）注册，运行期不改。运行期 add/remove 只写会话 overlay，会话结束即弃。会话 = thread-id。
- **会话 overlay 落 `harness.memory`（自省面）。** agent 能看到当前会话工具集，也能经 eval 修改它——呼应 tools.clj 既有注释"agent build itself a toolset"的设计意图，且与 eval-introspection 的两 ns 划分一致。有效工具集 = base ⊕ overlay（add 胜出、remove 隐藏）。
- **add 对 base 已有名：允许会话内覆盖（shadow），牛总确认。** base 原定义不动；unregister 只影响本会话（先撤 overlay 添加，再对 base 名记隐藏；对不存在的名字 no-op）。
- **thread-id 贯通。** http → kernel（run-chan/drive! 签名扩展）→ llm/stream!→tools/specs、drive!→tools/run!。本特性明确允许 kernel 签名变更；此前各 spec 的"kernel 零改动"是各自任务的验收约束，不延用（验收以本 spec 为准）。
- **生命周期事件落点。** 执行缝隙（lookup + 参数校验 + 执行）产出三相：pre-execute（lookup/校验结果，outcome: pass/unknown-tool/missing-args）、execute（error?）、post-execute。jsonl 同文件同行 schema，扩 kind，keyed by toolCallId；wire 侧零新增。
- **爆炸半径**：tools（注册表会话化）、llm（specs 按 thread 取）、loop（thread-id 贯通）、http（传 thread-id + 落三相行）+ 对应测试；ag_ui 出站零改动。

## 非目标

- 不引入 AI-SDK UI-message-stream wire 协议（AG-UI 是本仓唯一 wire）。
- 不做 RunAgentInput.tools 的客户端逐 run 工具集。
- 不做 ApplePi 的 pre-hook veto / needs-approval 审批暂停机制。

## 验收主线

离线全量 `harness.test-runner` 只增不减（基线 46 tests / 188 assertions）。scripted run 断言：LLM 收到的 tools 数组反映该 thread 的 overlay；jsonl 三相行按 toolCallId 对齐、error 场景 error 字段落位；会话结束后 overlay 不影响新会话。

## 状态

- 01（会话级工具注册表）：未开始，blocked by eval-introspection/01（ns 划分）
- 02（三相 jsonl 生命周期事件）：未开始，blocked by 01
- 03（eval 集成 + prompt.md）：未开始，blocked by 01、02
