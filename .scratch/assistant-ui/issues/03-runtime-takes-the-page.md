# 03 — assistant-ui 运行时接管页面骨架与文本对话

**What to build:** 页面的装配权从 CopilotKit 交给 assistant-ui。运行时由 `@assistant-ui/react-ag-ui` 的
`useAgUiRuntime({ agent })` 包住现成的 `HttpAgent`，界面用官方 `<Thread/>` 元素渲染——消息区、composer、
自动滚动、欢迎屏、运行中状态都由它自带。一轮真实对话从输入框贯通到后端 8080 再逐字流式回来，控制台零
报错。CopilotKit 的 provider 与 `CopilotChat` 从此不在这张页面上。

接线方式已核对过官方文档，按这个形状写：`HttpAgent` 用 `useMemo` 构造（不是模块级单例），把它交给
`useAgUiRuntime`，返回值交给 `AssistantRuntimeProvider`。运行时自己拥有 AG-UI 事件解析与消息重建
（`TEXT_MESSAGE_*`、`TOOL_CALL_*`、`REASONING_*`、`STATE_SNAPSHOT` 等），我们不翻译帧。

这一票同时定下 **threadId 的归属**，并把结论写回 `spec.md` 决策 6：谁铸 id、写在哪个对象上、下一轮
run 怎么带上它。这是本票唯一无法从文档确认的东西——适配器是否把 threadId 写回我们手里的 `HttpAgent`，
必须在浏览器里验一次而不是推理一次。这一条是 06 恢复路径的地基，本票先把它钉死。

**这是本特征唯一的高风险票**：新库与 threadId 未知压在一起。它落地后页面暂时只剩聊天——旧的审批门、
reasoning 组件、会话面板、项目面板的 TS 版本随 CopilotKit 的装配一起退场，由 04–07 一票一票接回来。
这个缺口是分票的代价，在落地说明里写清楚，别让下一个读 git log 的人以为东西丢了。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] Tailwind 与 shadcn 接进构建并成文：`components.json` 里配好 registry 指向、样式入口被一个 entry 文件
      引到、Vite 侧构建接上 Tailwind。**这是本仓库第一次有样式体系**，接线方式要能让人照着复现
- [ ] 抄官方 `thread` 元素进仓库（含它自动展开的 11 个 registryDependencies）。装完通读一遍，落地说明
      里记下最终落了哪些文件、哪些是我们改过的
- [ ] 依赖只增加 assistant-ui 的 React 包、AG-UI 运行时适配器、Tailwind／shadcn 侧的包；`@ag-ui/client`
      已是依赖
- [ ] 页面由 `AssistantRuntimeProvider` 包裹，运行时由**现成的 `HttpAgent`** 构造，URL 仍是
      `http://localhost:8080/`：不插中间层、不动 CORS 放行名单、dev server 仍钉在 5173
- [ ] 助手文本逐字增量出现；运行中与空闲两种状态在界面上可分辨
- [ ] composer 能输入并发送（Enter 发送），运行中能取消
- [ ] 空态用官方元素的欢迎屏呈现，不是空白。官方对「新会话」的判据比「没有消息」窄（整份会话列表还在
      加载时也走欢迎屏，只有切换线程且该线程历史还在飞时才出骨架）；这一票照它的判据走，不自己另写一条
- [ ] **threadId 结论写进 `spec.md` 决策 6**：铸造点、写在哪个对象上、下一轮 run 如何带上它
- [ ] **续同一线程可验**：同一 threadId 连发两轮，服务端两次 run 落在同一个日志文件上；新会话起新 id、
      落新文件
- [ ] 真 Chromium 验收（light 主题）：一轮真实对话贯通，控制台零报错，截图留档
- [ ] `tsc --noEmit` 0 error 且 `npm run build` 全绿
- [ ] `npm test` 四组用例全绿——它们驱动真 `@ag-ui/client`，与 UI 库无关，本票**不改**它们的内容
- [ ] 落地说明诚实记录本票造成的功能缺口（审批、工具卡、reasoning、会话、项目面板尚未接回）
- [ ] 落地说明点名本票是否已碰到 experimental 接口（`unstable_*` 中断、`adapters.threadList`）；碰到就
      写下升级时会破在哪里
