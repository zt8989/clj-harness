# spec: ui 换语言（CLJS → TypeScript），界面换库（CopilotKit → assistant-ui）

先把 `ui/` 从 ClojureScript 搬到 TypeScript，行为一字不变；再在 TypeScript 上把界面从 CopilotKit 换成
assistant-ui。两步不并行，理由见决策 1。

## 背景与定位

内核与协议不动。唯一对外接口仍是 AG-UI 帧，浏览器仍直连后端、无中间层，5173 仍是 CORS 契约。

`ui/` 现在是一套 ClojureScript 客户端：shadow-cljs 双编译器（由自写的 `vite-plugin-cljs.js` 接到 Vite 上）
+ helix + React 19，四份源码共 678 行（React root、页面装配、审批卡、reasoning）；验收侧另有六份 CLJS
共 688 行，经 `cljs-test/tests.cjs` 桥进 vitest。

搬语言的理由不是「TS 比 CLJS 好」这种口味问题，而是**这个界面接下来的每一行都是 JS 库的界面**。
assistant-ui 是 TS 库，它的渲染函数、状态选择器、part 形状都按 TS 设计。在 CLJS 里用它要付三笔税，
三笔都在上一个尝试里付过现钱：

1. 组件 render prop 收到的是裸 JS 对象，读字段得用 `.-field`；用关键字解构会静默拿到 nil。
   这个仓库真被坑过一次——整张消息列表空白。
2. 渲染函数建在 render 里会毁掉库自己的 memo，必须提到命名空间层级。CLJS 里这件事没有类型提示兜着。
3. `:esm` + `:js-options {:js-provider :import}` 让 npm 包对 Closure 是外部的，而 `:advanced` 会改名
   外部包的属性（`ThreadPrimitive.Root` 编译成 `P.K`），整个 build 只能永久降到 `:simple`。为几百行
   胶水放弃优化，只为绕开一个语言边界的坑。

这三笔税与界面复杂度成正比，而界面才刚开始接——工具卡、审批、会话、项目面板都还没写。现在换便宜，
越往后越贵。

## 决策

1. **两步走：先语言，后库。** 01/02 只搬语言——CopilotKit 留着、视觉留着、行为一字不变；03 起才换库。
   好处是每一阶段只有一个变量：01/02 的验收是「页面与今天逐条对位」，那时没有任何新库可以为差异背锅；
   03 起界面才变样，那时语言已经是熟的。一步到位会让「移植错了」和「新库本来就是这样」混在一起分不清。
   代价是那批 CopilotKit 界面代码写完 TS 之后在 08 被删掉——这笔钱买的是可审性，认了。

2. **搬完之后，仓库里只有 TypeScript。** 不留 `.cljs`、不留 shadow-cljs、不留 helix、不抽 `.cljc`
   共享层、不留双实现、不留注释掉的尸体。CLJS 在这个仓库里剩下的全部价值就是那四份源码，而它们
   正在被搬走。

3. **样式走官方那条路：Tailwind + shadcn 抄源码。** 官方文档现在只讲这一条：
   `npx shadcn@latest add "@assistant-ui/thread"` —— 抄一份 `thread.aui.tsx` 进仓库，连带 11 个
   registryDependencies（tool-fallback、tool-group、reasoning、markdown-text、button、skeleton、
   attachment、file、follow-up-suggestions、image、tooltip-icon-button）。源码里 Tailwind 工具类与
   `aui-*` 语义类名是混写的。

   这**推翻了本特征初版决策 3**（原定「官方预编译 CSS、零 Tailwind」）。推翻的依据是维护状态：
   那条路的两包是 `@assistant-ui/react-ui`（0.2.1 / 2025-10-17）与 `@assistant-ui/styles`
   （0.3.7 / 2026-02-06），分别停更十一个月与七个月，而 `@assistant-ui/react` 是一周一版
   （0.15.19 / 2026-09-11），且官方文档里已经不再提这两个包。拿一个停更的包去配一个周更的包，
   省下来的自写代码要用版本风险付账，不划算。

   代价记清楚：**Tailwind 与 Radix / Base UI 进仓库，`ui/` 下多出十几份抄来的 TSX**。这是本特征
   第二处立场反转——`cljs-ui` 当年刻意不引样式体系，现在引了。理由直接：抄来的组件由上游维护，
   自拼的布局由我们维护，而后者的成本正在把前者省下的时间吃回去。

   抄进来的组件**只在必要时就地改，改处标注**（本地修改要能一眼认出来，否则将来与上游对账分不清
   谁改的）。不整包搬回仓库、不另存副本。

4. **运行时用 `@assistant-ui/react-ag-ui` 的 `useAgUiRuntime`，喂现成的 `HttpAgent`**（`@ag-ui/client`
   已在依赖里）。适配器自己拥有 AG-UI 的事件解析与消息重建，我们只提供 agent，不翻译帧。

5. **内核、AG-UI 帧、CORS 放行名单、5173 端口一行不动。** 后端始终不知道前端是什么语言写的、用了
   哪个组件库。

6. **threadId 的归属由 03 先定、06 收终，结论写回本节**：谁铸 id、写在哪个对象上、下一轮 run 怎么带上它。
   已知的未知点：适配器是否会把 threadId 写回我们手里的 `HttpAgent`。文档给的口子有两条，两条对「谁是
   id 的主人」给的答案不一样——`adapters.threadList`（**experimental**，`threadId` 由我们持有并传进去）
   与 `adapters.history`（`fromAgUiMessages` + `ExportedMessageRepository.fromArray`，适合单线程恢复）。
   03 用最小路径验一次并写下当时的结论；06 落到最终归属，若归属因此易主就更新本节，不许留下两个都自称
   是主人的实现。这两条在浏览器里验，不推理。

   **01 的浏览器验收给出了这个问题的实测答案（当前的 CopilotKit 装配下）**：主人是 **agent 对象**，
   不是页面状态。证据是三条一致的：面板一律从 `agent.threadId` 读绑定；「新建会话」把一个新 UUID 写到
   `agent.threadId` 上；「恢复」把重建出的 id 与消息写回同一个对象，而下一轮 run 续的是**同一个日志
   文件**（实测 `b511a0af-…jsonl` 从 4 831 字节长到 22 565 字节，中间只有一行 `session/rebuilt`）。
   所以「恢复后历史归本页持有」这句话要读作「归 agent 持有」。

   03 要确认的只有一件事：换成 `useAgUiRuntime` 之后这一点是否还成立。初期探针里量到的是适配器**只读**
   `agent.threadId`（缺省落到 `"main"`），自己不铸 id——若如此，归属不变，本节结论直接沿用；若走了
   `adapters.threadList`，主人就挪到我们手里，那时必须回来改本节。

   **03 的浏览器验收把这个「若如此」钉成了事实，归属不变**。03 没走 `adapters.threadList`（那是 06 的选择），
   所以两个问题分开回答：

   - **铸造点**：仍然是 **`HttpAgent` 的构造函数**。`new HttpAgent({url})` 当场铸一个 UUID 写进
     `agent.threadId`，`threadId` 是可写的普通属性。实测两次构造得到两个不同 UUID。
   - **谁读它**：适配器**只读**，从不铸。`AgUiThreadRuntimeCore` 里两处取值都是
     `this.agent.threadId || "main"`，随后把算出的值写回那次 run 的实例（`runAgentInstance.threadId = input.threadId`）。
     因为 `HttpAgent` 一定有 id，`|| "main"` 这条兜底在这个仓库里永不触发。
   - **写在哪个对象上**：**agent 对象**，没有第二个持有者。页面用 `useMemo(() => new HttpAgent(...), [])`
     构造它，于是一张页面挂载期 = 一个 agent = 一个线程（依赖数组是空的，重渲染不会换 agent）。
   - **下一轮 run 如何带上它**：`prepareRunAgentInput` 从 `this.threadId` + `this.messages` 组装
     `RunAgentInput`，**两轮之间无需任何显式传递**——这就是「续同一线程」的全部机制。
   - **实测两条**：同一页面连发两轮 → 同一个日志文件从 4 939 字节长到 507 708 字节，会话总数不变（第二轮的
     `input.threadId` 与第一轮逐字相同）；刷新页面（新 agent）→ 出现全新 id 与新文件，会话数 5 → 6。

   06 若改走 `adapters.threadList`，主人会从 agent 挪到我们的状态，那时按本节最后一段更新；只要仍走
   「agent 自持」这条路，本节就是终稿。

7. **分票顺序：01 → 02 → 03 → 扇出 04–07 → 08 扇入。** 承认 03 与 07 之间 main 上的页面短暂变薄
   （聊天先能用，工具卡、审批、会话、项目面板一票一票接回来）。把 04–07 塞进 03 会让首张高风险票
   大到不成比例——03 已经背着「新库 + threadId 未知」两件事，不该再加四张面板。

## 非目标

- 不重写内核，JVM 侧一行不动
- 不改 AG-UI 协议行为、不改后端任何帧、不改 CORS 放行名单
- 不引 Tailwind 之外的第二套样式体系：不引 CSS-in-JS、不引别的 UI kit。自建的几个面板用 Tailwind 写，
  不另起一套类名习惯
- 不为 CLJS 保留任何后路：不抽 `.cljc`、不留双实现、不留注释掉的尸体
- 不为抄进来的组件建 fork 流程：必要时就地改并标注，不整包搬回、不另存副本、不改名
- 不做多线程侧边栏、附件、语音、听写、反馈这些 assistant-ui 的附加能力——当前界面没有这些需求。
  抄进来的 thread 元素自带的附件、分支、复制这些槽位不主动接，但也不为了去掉它们而改上游代码
- 不借换库之机重新设计界面：项目 / 会话两个自建面板的视觉与行为按对位还原

## 验收主线

1. **01 落地后页面与今天逐条对位**：聊天、工具卡、reasoning、审批、会话、项目面板六项行为一致，
   真 Chromium 截图比对。严格程度是「对位」，不是「看着差不多」。
2. **02 落地后仓库里 0 个 `.cljs`、0 处 shadow-cljs 引用**；11 个用例以 TypeScript 形式全绿，
   审批链路端到端仍通过；构建不再需要 Java。
3. `npm run build` 全绿：`tsc --noEmit` 0 error + Vite 打包成功。
4. `npm run dev` 起在 **5173**；一轮真实对话贯通后端 8080，控制台零报错。
5. 工具调用卡与 reasoning 的行为与现状对位（工具名 / 参数 / 状态 / 结果）。**与初版不同的一处**：04 落地时
   改为工具卡与 reasoning 一律默认折叠（初版要的是「reasoning 流结束后仍展开」），「进行中」的信息挪到
   折叠头上——shimmer 与状态词。05 落地时停泊调用同样**不自动展开**，由审批卡自己的形状决定。
6. 审批链路行为不变：park ⇒ 审批卡出现 ⇒ 批准则执行 / 否决则工具不执行且模型收到被否决的工具结果。
7. 会话：列表 / 恢复 / 新建可用，**恢复的验收点是日志**——续聊追加到同一个文件。
8. 项目目录面板的读、绑定、系统目录选择三条路可用。
9. 仓库里不再有 CopilotKit：依赖、源码、打包产物三处都搜不到。
10. README 的前端章节与新工具链一致（语言、依赖、启动、构建、5173 契约、验收方式）。
11. 抄进来的组件能一眼看出动了哪些：本地改动有标注，Tailwind 与 shadcn 的接线成文（`components.json`
    的 registry 指向、样式入口文件、构建怎么接上 Tailwind）。

## 状态

八票，票在 `issues/`：

- 01 构建换成 TypeScript，页面直译后行为零变化（无阻塞，可立即开始）
- 02 四组用例跟着搬到 TypeScript，CLJS 工具链整体离场（阻塞于 01）
- 03 assistant-ui 运行时接管页面骨架与文本对话（阻塞于 02）
- 04 消息部件：工具调用卡与默认折叠的 reasoning（阻塞于 03，已落地）
- 05 审批门（阻塞于 03，已落地）
- 06 会话：列表 / 恢复 / 新建（阻塞于 03）
- 07 项目目录面板（阻塞于 03）
- 08 CopilotKit 出局与文档收口（阻塞于 04, 05, 06, 07）

## 已知风险

- **03 是全特征唯一的高风险票**：新库 + threadId 未知两件事压在同一票上。它落地后页面暂时只剩聊天，
  旧的审批门、reasoning、会话面板、项目面板的 TS 版本随 CopilotKit 的装配一起退场，由 04–07 接回来。
- **Tailwind 与 shadcn 是本特征新引入的两套构建依赖**：`ui/` 此前刻意没有样式体系，现在有了。这是个
  一次性入库动作，装完要有人看一眼；此后抄来的组件改动必须标注，否则与上游对账时分不清谁改的。
- **registry 依赖会自动展开**：`@assistant-ui/thread` 带 11 个 registryDependencies，装一次落十几份
  TSX。不是意外，但不要装完不看。
- **`adapters.threadList` 是 experimental**：06 压在它上面，文档自己写着「可能在不通知的情况下更改」。
  它同时是 06 的两条路之一（另一条是 `adapters.history` + `fromAgUiMessages`），哪条可用要实测。
- **待处理中断不会被自动清掉**：文档写明中断打开时再发消息会抛错（`autoCancelPendingToolCalls` 只管
  未解决的客户端工具调用，不管中断）。这是 05 里一个具体的失败形态，界面必须处理而不是撞上。
- **审批门压在实验性 API 上**：AG-UI 适配器读待决中断、提交答复的接口带 `unstable_` 前缀，升级会破，
  且破的方式是审批静默失灵——最坏的失败模式。05 里点名，并写下升级时的检查方式。

  **03 读包时先碰到了这条，结论对 05 有直接影响：`unstable_getPendingInterrupts()` 与
  `unstable_submitInterruptResponses()` 在 `@assistant-ui/react-ag-ui@0.0.59` 里已经标了 `@deprecated`**
  ——类型注释写着「改用 `useAgUiInterrupts()` / `useAgUiSubmitInterruptResponses()`，保留只是为了向后兼容，
  将在下一个大版本移除」。也就是说这两个方法**不是新口子，是行将删除的旧口子**。05 落地时应优先用那对
  hooks；若最终仍用了 `unstable_*`，升级检查就变成「下一个大版本会直接编译失败」——比静默失灵好抓，
  但同样是 breaking。这两条路都要在 05 的验收里点名。

  **05 落地后的结论：用的是那对稳定 hooks（`useAgUiInterrupts` / `useAgUiSubmitInterruptResponses`），
  `unstable_*` 一处未引。** 升级检查随之变轻：这两个 hooks 若改名，`tsc` 直接编译失败，不会静默失灵；
  真正仍需人工看的只剩行为契约——`useAgUiInterrupts` 读的是 `RUN_FINISHED.outcome.interrupts` 快照、
  提交侧仍按「每个开着的 interrupt 一条应答」校验。判法：升级后跑一遍批准/否决，若卡出现但点击后
  composer 永不复原，就是提交缝断了。
- **05 落地后给 06 留的接点**：park 的中断只活在客户端内存，刷新即失，而服务端的 park 记录还在进程里；
  06 的恢复要么把 park 状态一并恢复，要么明确「刷新即弃」并保证重放不出无法回答的卡。另外 05 的
  composer 拦截由 `ApprovalBatchProvider` 的 hold 驱动 `isSendDisabled`，06 若加线程切换，切走时 hold
  要跟着断。批次路径还有一个已修复的坑要 06 知道：一发多 park 的调用会落进多条客户端消息，只有最后
  一条带 interrupt 元数据——任何「按 part status 找停泊调用」的逻辑都会漏掉前面的（05 在
  `ToolCallCard` 里用中断缝补的，正是这个）。
- **04 落地后给 05 留的两个接点**：① 停泊调用（`requires-action`）在 04 里**不自动展开**——04 的卡是一张
  折叠的工具卡，`Needs approval` 只是折叠头上的一个状态词，批准/否决的按钮一个都没有，05 要么沿用折叠
  （点一下才看见按钮），要么自己控制 `open`；② 上游 `tool-fallback.aui.tsx` 里**已经带了一整套
  `ToolFallbackApproval`**（选项列表、二次确认、自由文本答复，都是抄来组件自带的），05 先读它再决定
  复用还是自写，别从零造。
- **threadId 归属与恢复路径未定**：见决策 6，由 03 收口。
