# spec: UI 工具调用卡与 reasoning 常展开（ui-tool-cards）

CopilotKit v2 的 CopilotChat 默认**不渲染工具调用详情**，而数据链路是完整的——wire 四类帧早已发出、客户端状态早已收全，缺口纯在渲染层。本特性把这块补上，并顺带把 reasoning 的自动收起改为保持展开。

## 问题（实测，2026-09-13 对 bundle 的考古）

读 `@copilotkit/react-core` dist bundle（`copilotkit-snbJkMqQ.mjs`）逐处核实：

1. **工具调用默认 return null**：`CopilotChatToolCallsView` → `useRenderToolCall` 按注册表查渲染器（工具名精确匹配 / agentId 匹配 / `"*"` 通配），无匹配即 `null`。dev 下仅 console 警告（`warnAboutUnrenderedToolCalls`），production 无声无息。
2. **role "tool" 无分派分支**：`renderMessageBlock` 只分派 assistant / user / activity / reasoning 四种 role，工具结果永不独立成气泡，只作 `toolMessage` 喂给工具渲染器。
3. **reasoning 自动收起**：默认组件流式时展开（"Thinking…"），run 结束且用户未手动 toggle 过时 `setIsOpen(false)`，折叠成 "Thought for Xs" 一行，内容要点开才见。
4. **数据全在**：`ag_ui` outbound 对每次 `:tool/call` 发 `TOOL_CALL_START`（名）/ `TOOL_CALL_ARGS`（参数）/ `TOOL_CALL_END`，对 `:tool/result` 发 `TOOL_CALL_RESULT`（含结果 content）——协议层无需任何改动。`@ag-ui/client` 收帧后 assistant 消息带 `toolCalls{name, arguments}`、tool 消息带 content。注册渲染器后 `ToolCallRenderer` 把 `name` / `args`（已 parse）/ `status`（InProgress / Executing / Complete）/ `result` 全量传给组件。
5. **CopilotKit 自带两个等价的内置出口**（本票采其一）：`useDefaultRenderTool()` hook（一行注册内置 `DefaultToolCallRenderer`，`name: "*"` 通配可折叠卡）与 `WildcardToolCallRender`（`defineToolCallRenderer` 产物对象，直接进 CopilotKit 的 `renderToolCalls` prop）。两者 render 均可传自定义覆盖。

## 决策（牛总裁定 2026-09-13）

- **单票 + 内置卡**：不逐工具注册，用 `"*"` 通配渲染器覆盖全部工具调用；卡片用 CopilotKit 内置的，不自写。真机不满意再议是否开自写卡票。
- **连 reasoning 一起**：run 结束后 reasoning 内容**保持展开**、不自动收起；流式中 "Thinking…" 行为不变；手动折叠/展开仍可用。
- 验收以本 spec 的验收主线为准，不引入其他度量。

## 非目标

- 不自写 helix 渲染卡（内置卡效果不行时另开票，届时再裁定）。
- 不动协议层——`ag_ui` 的四类帧与 `@ag-ui/client` 的状态收纳已验证可用，零改动。
- 不处理 activity 消息（本仓不发 activity 事件）与 system/developer role（无分派分支是合理设计）。
- 不改审批卡的形态与流程（审批 interrupt 是既有特性，与工具卡并存即可）。

## 验收主线

真机（浏览器实跑，agent-browser + Chromium，dev 5173 + 后端 8080）：

1. 让模型调用 `read` / `write`，聊天流中出现工具卡：工具名 + 参数 JSON + 状态点（Running → Done）+ 结果内容。
2. 被否决的调用：卡内可见 veto 文本（`TOOL_CALL_RESULT` 的 content 是 "vetoed by human: …"）。
3. 审批 park 期间：工具卡呈进行中状态，与审批卡并存、互不遮挡。
4. reasoning：run 结束后内容保持展开；手动折叠后不再被自动弹开。

## CLJS 落地注意（历轮踩过的坑，不重蹈）

- **helix props 铁律**：外层 props 用 CLJS map，内层给 JS 库的值用 `#js`（`renderToolCalls` 数组、agents 表都是"值"）。
- **【helix 组件 props 是 bean——2026-09-13 世纪坑，曾把消息列表读空】**defnc 组件运行时经 `extract_cljs_props` 把 React props 包成 cljs-bean：读 props **必须 keyword 解构/查找**（`{:keys [...]}` / `(:messages props)`）；`(.-field props)` 读的是 bean 自身内部字段，**静默返回 nil**。props 的**值**（messages 数组、message 对象）是原生 JS，`.-field` / `aget` 对值有效。「不要 map 解构」只适用于 hook 返回值等原生 JS 对象（如 approval_gate 的 `.-agent ctx`）——两种对象两条规则，先分清拿到的是哪种。
- **hook 须在组件顶层调用**（`useDefaultRenderTool` 是 JS hook，需要一个 CLJS 挂载组件）。
- CopilotKit v2 的 CSS 须在 `index.html` 显式导入（生产构建丢副作用导入的坑已修过，勿回退）。
- `npm run dev` 的 PATH 须前置 openjdk21；dev 端口钉 5173（后端 CORS 契约）。

## 已验证到什么程度

真机验收（2026-09-13，playwright MCP + Chromium，dev 5173 + 后端 8080）：**四条 AC 全过**。

1. **工具调用卡 ✅** —— 通配渲染器下任意工具调用在聊天流渲染卡：工具名 + 状态徽章（`inProgress` → `complete` 流转）+ 展开后 Arguments / Result 区。实证过 read / bash / eval 卡与 session-configure 卡。
2. **veto 文本入卡 ✅** —— 否决 session-configure 后：工具卡状态转 `complete`，展开卡内 Result 区显示 `vetoed by human: the call was not executed.`（`TOOL_CALL_RESULT` 的 content）；模型收到被否决的工具结果并继续作答。
3. **park 期间两卡并存 ✅** —— park 时 session-configure 工具卡呈 `inProgress`，与"需要你批准这次工具调用"审批卡（工具名 + 参数 + 批准/否决按钮 + 说明文案）同时渲染、互不遮挡。
4. **reasoning 常展开 ✅** —— run 结束后 "Thought for Xs" 保持 `[expanded]`；手动折叠一段后新 run 不弹开（保持折叠）；未折叠的段与新 run 的 reasoning 自动展开；流式中显示 "Thinking…"。

**过程发现并修复的阻塞 bug（超出票面，必须记录）**：首次真机验收消息列表全空。CopilotKit 状态层完好（Web Inspector 实证 261 REASONING_MESSAGE_CONTENT / 229 TEXT_MESSAGE_CONTENT / Tool Calls: 1 / Errors: 0），断点纯在渲染层：helix `extract_cljs_props` 把 React props 包成 cljs-bean，message-view wrapper 用 `(.-messages props)` 读到 bean 内部字段静默 nil，`CopilotChatMessageView` 收到 `messages: undefined` 落进默认参 `messages = []`。修复：`message-view`（app.cljs）与 `reasoning-message`（reasoning_message.cljs）改 keyword 解构。诊断方法：React fiber 挖 `memoizedProps` 逐层上溯定位断点组件；Inspector 先二分「状态层 vs 渲染层」。

已知非阻塞现象（README 已载，与本特性无关）：停止 run 后新 threadId 携带被超长 reasoning 污染的全量历史，Free 模型冷启动返回仅 `RUN_STARTED`→`RUN_FINISHED` 的空流；刷新页面重置 thread 可恢复。

## 状态

- 01（内置通配渲染器 + reasoning 常展开 + 真机验收）：**完成**（2026-09-13，四条 AC 真机全过；实现见 `ui/src/harness/ui/{app,reasoning_message}.cljs`）
