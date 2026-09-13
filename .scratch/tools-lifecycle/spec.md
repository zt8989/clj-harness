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

- 01（会话级工具注册表）：已落地（`57c3a00`）
- 02（三相 jsonl 生命周期事件）：已落地（`51a055c`）
- 03（eval 集成 + prompt.md）：已落地

## 已验证到什么程度（2026-09-12，三票全部落地）

- **01**：base 不可变 + per-thread overlay（add 覆盖 base 仅本会话胜出、remove 单调：先撤 added 再隐藏 base 名、不存在 no-op）；thread-id 贯通 http→run-chan/drive!→llm/stream!（multimethod 四参）→tools/specs、tools/run!（`*thread-id*` 在工具体周围绑定）；spy provider 断言 tools 数组按 thread 生效、跨 thread 隔离。
- **02**：kernel 词汇表 7→10 种（:tool/pre-execute/execute/post-execute）；missing-args 在 pre 拒绝（跳过 execute 段）、unknown-tool 同、pass 三相齐全、execute 段带 error 消息、post 恒闭合；jsonl 行 kind "tools/*" 按 toolCallId 键控、pass 无 outcome 键；ag_ui 对三类事件零帧（**踩坑：case 连续裸常量是逐个配对，多常量共享结果必须加列表**——曾致 go loop 死于 Keyword→Associative CCE，生产者 >!! 永久阻塞，表现为集成测试挂死）。
- **03**：eval tool-call 全形态走通 session-register! → 下一 run tools 数组含新工具且 dispatch 真实执行；session-unregister! base 工具 → 模型收到 "unknown tool: read"；prompt.md 增 session tools 章节。
- **附带修复**：run! 内局部 `name` 遮蔽 core/name 的 CCE；loop_test 的 "slow" 从全局注册迁到会话 overlay（base 不再被测试污染）；**畸形 JSON 参数在 JSON 解析处炸出外层 catch 时，:tool/execute（带 error）与 :tool/post-execute 原漏报，已补齐——"post 恒闭合"至此对所有路径成立（测试锁定：无 :tool/pre-execute、后两相齐全）**。
- **最终全量**：55 tests / 249 assertions，全绿。

## 反转记录（2026-09-13，`tool-toggles` 推翻两处）

**反转一：remove 不再隐藏 base 名，也不再单调。** 本 spec 当时的决策写「remove 单调：先撤 overlay 添加，再对 base 名记隐藏」，其 03 验收断言「`session-unregister!` base 工具 → 模型收到 `unknown tool: read`」。`tool-toggles` 推翻：

- **`session-unregister!` 收窄为「只撤本会话新增的定义」**，对 base 名是 no-op——`overlay` 的 `:removed` 集合**整个退役**。
- **可用性改由 `session-disable!` / `session-enable!` 一对可逆操作承担**：被关的工具**仍在工具表里**，调用由执行缝报 `:disabled`（**不是** `:unknown-tool`——它存在，只是被关，说 unknown 是谎话）。
- 理由：**「隐藏」会让模型把「被策略关掉」误读成「这能力不存在」**，进而去找 bash 绕路（与 pre-tool-approval 拒绝「错误消息是给模型的信息」同一条精神）。「可见但被拒」让模型知道限制存在，并能自己用 eval 打开它——在「自我扩展」定位下这是更好的行为。
- 代价明写：**本特性之后，任何工具都不能再从工具表里消失。** 「从本会话移除」只对会话自己新增的定义成立。

**反转二（连带）：`:tool/pre-execute` 的 outcome 词汇表新增 `:disabled`**，且**检查先于审批**——关闭是硬拒绝，没有理由为一个注定不执行的调用去 park 等人。

**先例说明**：本仓允许后出特性推翻前 spec 的决策，只需记明。本例与「tools-lifecycle 非目标里的『不做审批暂停』已被 pre-tool-approval 推翻」同类——**本 spec 的「非目标」段不是永久承诺，是当时的边界声明。** 本 spec 的「已验证到什么程度」段作为历史事实**不回改**。

**未被推翻的部分**（仍然生效）：base 不可变、会话 overlay 落 `harness.memory`、add 对 base 名 shadow、thread-id 贯通、三相生命周期事件与 `post 恒闭合`。这些是 `tool-toggles` 的地基。
