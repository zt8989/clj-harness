# 客户端：TypeScript + React + assistant-ui

`ui/` 是纯 TypeScript：`.tsx` 是 React 源码，`.ts` 是测试与非渲染代码，`.js` 只剩 Vite 配置。
**没有第二套工具链**——构建与开发都是 Vite（`@vitejs/plugin-react` + `@tailwindcss/vite`），
不需要 Java，也没有 shadow-cljs。

**它曾经是 ClojureScript + helix + CopilotKit。** 那次换语言换库的完整记录在
`.scratch/assistant-ui/`（历史文档，记的是当时）。协议侧一字未改——
AG-UI 帧的形状、interrupt/resume 的语义、以及「服务端不持有会话」这条，换客户端都没有碰。

## 装配

```
main.tsx            React root
app.tsx             **一场会话一份 runtime（一份 `SessionHost`）**，各带自己的 HttpAgent、
                    `useAgUiRuntime`、`ApprovalBatchProvider` 与 <Thread/>。App 持有的是
                    「现在看哪一场」（`shown`）、「哪些会话有活着的 host」（roster）、
                    以及每场自己的 `{:running? :parked?}` 注册表（侧边栏按 id 查）
                    侧边栏在 provider **之外**（它管全部会话），THREAD_COMPONENTS 仍在此注入
                    （附件适配器也在这里交出：`adapters.attachments` 一行）
components/
  sidebar.tsx       三段位：钉住的「新建任务」、唯一滚动的项目区、钉住的「设置」
  settings-panel.tsx 「设置」：两页左导航（General / Models），**两页都会写**
  approval-gate.tsx 审批门（自建：上游的 approval seam 认的 reason 与本仓不同）
  message-parts.tsx 步骤行（工具调用与思考）的注入点（THREAD_COMPONENTS）
  turn-steps.tsx    一整轮的**折叠**：结束的那一轮把步骤收起来、只留答案，
                    行那条摘要（`N 次工具调用 · M 条消息`）与它背后的 store 都在这儿
  picker.tsx        四个选择器共用的那一份：可搜索的浮层（项目 / 分支 / model / 思考档）
  composer-chrome.tsx   composer 上下两条、三个 LOCAL: 插入点
                        （ComposerFrame / ComposerTools / ComposerAddAttachment），
                        以及附件那条判据在界面上的两处（禁用的 `+`、那句拒话）
  composer-stats.tsx    composer **下面**那条状态条（会话统计的五格）
  assistant-ui/elements/  12 份抄自 assistant-ui registry；**只有 thread 与 thread-list 两份带
                        `LOCAL:` 改动**，其余原样未改（见下）
  ui/               9 份 shadcn 基件，同样未改
lib/
  threads.ts        两个地址：`API_BASE`（`${HARNESS}api/`，管理调用挂的地方）与 `AGENT_URL`
                    （`${API_BASE}agent`，`HttpAgent` 用的那一个端点）。`HARNESS` 默认 `/`（本 origin），
                    `VITE_AGENT_URL` 可指绝对地址。外加 rebuild 调用
  projects.ts       GET /api/projects 的类型化薄封装 + 移除项目
  settings.ts       GET /api/settings 的类型化薄封装
  providers.ts      GET /api/providers + 三条写入 + 厂商探询的类型化薄封装
  stats.ts          GET /api/threads/<stem>/stats 的类型化薄封装（`404` 也是普通答案）
  format.ts         给**人看**的数字：字节、时间，以及状态条那五格的字符串（`statsCells`）。
                    **零 import**，所以 UI 套件能直接测它
  attachment-rules.ts  附件那两条判据（模型收不收图、源字节有没有过 2 MB）与它们各自的拒话。
                    同样**零 import**，同样被 UI 套件直接测
  turns.ts          一轮的**算术**：哪几条消息是同一轮、它停没停、它做了几次调用几条消息、
                    摘要那行写什么。**零 import**（`turnBounds` / `turnIsSettled` /
                    `turnCounts` / `turnSummaryLabel`），被 UI 套件当成数来测
  picker.ts         选择器那份清单的**过滤与分组**：查什么（标签 / hint / 组名）、
                    同组的连续段怎么并、顺序为什么不动。**零 import**，同样被 UI 套件直接测
  attachments.ts    附件适配器（这一份就是「composer 有没有附件能力」这个开关本身）+
                    它往里写、界面往外读的那个小 store
  session-status.ts 一场会话的 `{:running? :parked?}`（host 报上去、侧边栏按 id 查）
                    与仍然要拒的两句话（归档 / 删项目）。**改名自 `run-state.ts`**：
                    旧名字说的是「整页在跑」，而那个前提没了
```

**页面只跟自己的 origin 说话，dev server 把它转出去。** 整个后端在**一个前缀**下——run 端点是
`POST /api/agent`，其余都是 `/api/<什么>`——所以 `ui/vite.config.js` 只要**一条** `/api` 前缀规则。
`lib/threads.ts` 因此导出两个地址：`API_BASE`（管理调用挂的地方，`${HARNESS}api/`）与
`AGENT_URL`（`HttpAgent({url})` 用的那一个端点，`${API_BASE}agent`）。目标来自
`HARNESS_BACKEND_URL`，由 `node scripts/dev.mjs` 填：它让后端**在 0 号端口绑**（OS 分配）、读后端**自己报
出来的**那个端口，所以源码里没有端口号，也不会有「8080 被上次忘了关的会话占着」这件事。
浏览器因此**一个跨域请求都不发**（没有 preflight，CORS 白名单也不再是前端要跟着改的东西），
构建产物里也不带我们的地址——换到任何部署自己的反代后面都一样。
要直连后端就走 `VITE_AGENT_URL=http://127.0.0.1:<port>/`，那条路才是后端 CORS 放行存在的理由。
`strictPort: true` 留着：第二个 dev server 悄悄落到 5174，比启动失败更让人意外。

## 状态的归属

- **一场会话一份 runtime，切换不再经过 runtime。** 每个开过的会话挂一份 `SessionHost`：它自己造
  HttpAgent、自己调一次 `useAgUiRuntime`，于是自己有一份 core。`threadList` 适配器里**只传 threadId**，
  `onSwitchToThread` / `onSwitchToNewThread` 两个回调退场——它们的效果是「先清空当前 core、再灌新消息」，
  而那份 core 现在正在流。host 一旦存在就**不再卸载、也不再重建**（core 归 hook 的 ref 所有，不归 DOM 子树），
  所以「这个会话没在显示」不等于「它的 run 死了」；`agent.threadId` 也不再有回写者（`adoptThread` 已删），
  服务端照旧零会话状态。
- **「现在看哪一场」是 App 的 state**（`shown`），带两个动作：`onShow`（这场有 conversation，第一次
  host 时重建）与 `onShowFresh`（这场是客户端刚 mint 的，没有日志可重建，host 空着起）。
- **第一次打开才 rebuild，判据是「那份 host 在不在」**：重建走运行时自己的 `history` 适配器，它每个
  core 只 `load()` 一次（`__internal_load`）。已经活着的 host 再显示多少次都不重建——它 core 里那份
  conversation 可能还在长，拿 rebuild 的结果盖上去就是又一次孤儿。
- **恢复的转换仍与 runtime 自己的快照导入路径逐字相同**（引上游，不另写）：`fromAgUiMessages` +
  `fromThreadMessageLike`，只是交给适配器的形状是 `{messages: [{parentId, message}]}`。
- **history 适配器的 `append`/`update` 是空实现**：日志归服务端所有，客户端一个字节都不往回写。
  host 的 `load()` 失败（截断 / 损坏的日志）走 `onError` 上报，句子仍旧落在**所点的行**上，
  而失败的那份 host 会被丢掉、页面退回上一场——所以点它一次就是重试一次。
- **侧边栏的数据是另一份**：`GET /api/projects`（不是运行时的 thread 形状——那个形状里没有项目，
  也没有日志的体积与 mtime）。**列表是快照**，切换会话 / 当前会话变化 / 按刷新键时重取，
  界面上明说这一点。
- **添加项目有两个入口但只有一条路**：正常情况是一次点击直接开**原生选目录窗**，
  窗答什么就加什么（那份被删掉的表单见 sidebar 头部）；服务端答 501（这台机器上**没有**窗可开，
  见 [edge](edge.md#管理边路由表) 的三态）时才多一样东西——一行绝对路径输入框。
  两种入口走同一个 `addProject`，所以「选一个目录」与「填一个路径」不是两个功能：
  同一个 canonical 路径回填、同一行项目、同一份服务端校验（目录不存在 / 不是目录由服务端指名）。
  **取消永远静默**：`pickFolder` 答 `null` 就是什么都不做——人关了窗不需要被告知；
  「没有窗」是可说的另一件事，它必须说得出来，否则一颗点不动的按钮看起来只是慢。
  输入框**只**为这个原因出现，从不为别的失败出现：它是死路的路，不是第二扇门。
- **切换与新建不再被 run 拦住**（一场会话一份 runtime，切走不打扰任何一场的 run）。仍然拒绝的是**归档 /
  删掉一场没完（在跑或悬置）的会话**，判据是**那条会话自己**在不在跑（App 的注册表），不是当前页在不在跑；
  句子落在**那一行**上（归档）或**项目那一行**上（删项目，且点名是哪一场），说辞在 `lib/session-status.ts`。
- **状态条那五格读的是记录，不是客户端手里的对话。** 客户端确实持有 conversation，所以它数得出轮与步、
  也估算得出 tok/s（运行时的 `chars ÷ 4`），但**它不这么做**：缓存命中它根本不知道，而估算出来的用量
  冒充厂商报的量就是编。那五个数由 `GET /api/threads/<stem>/stats` 从会话的 jsonl 折出来
  （服务端见 [edge](edge.md#管理边路由表)），**缺的数就是缺的**，页面把它留空而不是写 0。
- **它什么时候问**：挂载、会话切换、**助手消息多一条**（一轮 ReAct 在这个客户端就是一条助手消息，
  所以这约等于「一次模型调用结束了」）、run 结束——**不轮询**。一次调用的数只有在它的 `model/end`
  行写下来之后才存在，所以一次长调用进行中这条就停在上一格，那是不撒谎的代价。

## 审批门

`approval-gate.tsx` 接的是 AG-UI 的 **interrupt 缝**：`useAgUiInterrupts` 读待决中断，
`useAgUiSubmitInterruptResponses` 把 `resume` 数组写回去。它按 `reason === "tool-approval"` 认领属于
自己的 interrupt，工具名与参数从**客户端自己的 `toolCalls`** 里读（不让服务端回显）。

- **不走上游的 approval seam**：`ToolCallMessagePart.approval` 那条路只收 `reason === "tool_call"` 的闸，
  本仓的 reason 不是它，接上去会画不出任何东西（该文件头注释完整记了这次核对）。
- **卡片要收成批**：AG-UI 恢复时一个 run 要为**每条开着的** interrupt 各带一条 resume，
  只回答一部分会被运行时按名拒绝；所以决定存在一张比单张卡活得久的 store 里，最后一张卡交完才提交。
- **审批门开着时 composer 由 `isSendDisabled` 关掉**：那时发的消息会被运行时静默吃掉
  （文本清空、哪儿都不落地），堵死发送是唯一不吞用户输入的处理。
- **门是每场会话一份**：`boolean` 住在那份 host 里，`ApprovalBatchProvider` 也每份 host 一个（它读的正是
  它上面那个 provider 的待决中断）。所以 A 停在等人决定时，只有 A 的输入框关着，B 照常能发。
- **悬置不是「在跑」**：`isRunning` 在悬置时是 `false`（那一轮 run 已经以 interrupt 结束），
  所以注册表里的 `:parked?` 单独一格，侧边栏那一行在悬置时说 `Waiting on you`——在跑说转圈。

## 样式体系：Tailwind v4 + shadcn，抄源码路线

- **Tailwind v4，CSS-first**：入口是 `ui/src/styles.css`（`@import "tailwindcss"` + 主题变量 +
  `@custom-variant dark`），**没有** `tailwind.config.js`——v4 的配置就写在 CSS 里。构建由
  `@tailwindcss/vite` 插件接进 `vite.config.js`，扫源码树里的工具类。
- **shadcn，抄源码路线**：`ui/components.json` 声明别名（`@/components`、`@/lib/utils` 等，与
  `vite.config.js` 的 `@` alias 对齐）与 registry：`@assistant-ui` 指向
  `https://r.assistant-ui.com/styles/{style}/{name}.json`。
  `npx shadcn@latest add "@assistant-ui/thread"` 由此把 `thread.aui.tsx` 连同 11 个
  registryDependencies **抄进仓库**。
- **字体是平台自己的那一串**：`--font-sans` 是 `-apple-system, BlinkMacSystemFont, "Segoe UI",
  "PingFang SC", …`，中文直接用系统字体（macOS 上是 PingFang SC）。它换掉了一个要先下载的 webfont
  ——构建产物里因此少了三份 `.woff2` 与那几条 `@font-face`。`--font-mono` 没动，代码块照旧是等宽。
- **对话区两档字号，第三个数字是载荷**：正文**14px**（对话**两侧**都是：答案、问题，以及问题在
  编辑态的那只输入框），步骤行（工具行、思考行）**13px**，参数与结果块 **12px**。正文那条规则写在
  `ui/src/styles.css` 里，**故意不放进 `@layer base`**——编辑框自带 `text-base`（上游的），
  而 Tailwind v4 把 utilities 声明在 base 之后，在层里写多高的特异性也压不过它。命中的钩子两侧
  **不同名**：答案的内容 div 带 `data-slot="aui_assistant-message-content"` 而没有 `aui-*` class，
  问题的气泡反过来带 `aui-user-message-content` class 而没有 `data-slot`——两边都是上游自己的命名，
  而改那份抄来的文件就破坏了「不重装直接 diff」。步骤行的 13px 写在 `message-parts.tsx` 的行上
  （行是我们自己的）。**没动的是 composer 的输入框**：它照旧 16px，输入框的惯例。
- **对话区只跟着末尾走，那颗 `arrow-down` 只在读者自己走开时出现**：viewport **不锚最后一段的顶部**
  （上游 registry 给的是 `turnAnchor="top"`，而那个属性同时把自动跟随**关掉**：新内容在折线下方生长、
  没人跟，按钮从这一段的第一行起就一直挂着）。`thread.aui.tsx` 里这**一处 `LOCAL:` 改动**把它去掉，
  `turnAnchor` 于是回到默认的 `bottom`：run 期间视口跟着末尾走，手动往上滚才脱开，回到末尾
  （自己滚回，或点那颗按钮）就立刻重新跟随，按钮同时消失。
- **一轮结束就折起来，只留最后一个 message**：助手那几条消息是这一轮的**步骤**，最后一条是**答案**，
  而长的对话里九成内容都是步骤。所以一轮只要**停下来**，页面上就只剩**答案**，上面挂一行
  `N 次工具调用 · M 条消息`，点开把步骤放回来、再点收起。**折着是默认、开着是例外**：记住的是
  「读者手动开过哪几轮」，于是「自动折叠」不需要任何 effect（停下来就不再是例外），刷新之后回到折着。
  停下来的判据是**这一轮最后一条消息的状态**：`running` 还在写、`requires-action` 停在人身上
  （审批卡就在步骤里，折起来会把它藏掉），`complete` 与 `incomplete` 都算停了——中断的一轮同样算结束。
  轮的**边界是数出来的**（相邻的助手消息，两端的邻居说话），算术全在 `lib/turns.ts`（零 import，
  UI 套件直接当数测），UI 在 `components/turn-steps.tsx`。**那一行不是当年删掉的「N tool call」组头回来**：
  那个头在**每个工具调用**前面、计数恒为 1，这一行是**一整轮**一行。
- **composer 的四个选择器是一个可搜索的浮层，不是原生 `<select>`**（`components/picker.tsx`）：
  项目、分支、model、思考档都是「点一下 → 弹出一个带搜索框的列表」。列表**可以按组，但只有一层**——
  model 按**供应商**一行一组、底下是它自己的 model，一条平铺的清单，不是「先选厂商、再选 model」；
  键盘是上/下/Home/End/Enter/Esc，焦点落在当前值上、关掉时回触发器。**搜索匹配三样东西**：
  标签、`hint`、组名——项目行的 `hint` 是完整路径（所以「workspace」也能找到一条只有末段做标签的项目），
  model 的 id 不含厂商名（所以「deepseek」要能找到它全部 model）。算术在**零 import** 的
  `lib/picker.ts`，UI 套件直接测。**思考档没有搜索框**（三个选项一眼读完，搜索框在那里是陈设），
  其余三个有。两个「画得出来」的口子也在这里：model 选择器允许一行**不在目录里**的当前 model
  （会话由 inline provider 服务时），正如项目选择器允许一个本 home 没登记过的目录。
- **会话开始之后，composer 底下不留空隙**：抄来那份 footer 带着上游的 `pb-4 md:pb-6`，于是停靠的
  composer 与窗口底边之间留着 16–24px 的页面底色——一段读起来像「剩下来的地方」的空白。
  规则写在 `ui/src/styles.css`：`.aui-thread-viewport-footer:has([data-started]) { padding-bottom: 0 }`，
  `:has()` 把范围钉在**已开始**那一态（`data-started` 由 composer 那圈框在会话有消息时挂上），
  首次会话居中的时候仍是上游的间距。

抄进来的清单（对账就是不重装直接 diff）：

| 位置 | 是什么 |
|---|---|
| `src/components/assistant-ui/elements/` | 12 份抄自 assistant-ui registry：thread、tool-fallback、tool-group、reasoning、markdown-text、attachment、file、follow-up-suggestions、image、tooltip-icon-button、**其中 `thread.aui.tsx` 与 `thread-list.aui.tsx` 两份带 `LOCAL:` 改动**（前者的五处见下，后者见再下面），其余十份一字未改；`attachment.aui.tsx` 与 `image.tsx` 都在**原样未改**那一组里，而它们今天真的被用上了——composer 的缩略图、对话里那张图与点开放大，画的就是这两份（`tool-group.aui.tsx` 仍在清单里、仍只被抄来的 `thread.aui.tsx` 用；注入点已不再导入它，见下） |
| `src/components/ui/` | 9 份 shadcn 基件：button、dialog、dropdown-menu、input、textarea、tooltip、avatar、collapsible、skeleton |
| `src/hooks/` | 2 份 hook，同样未改 |

**两份带改动，改动逐处标注**。`thread.aui.tsx` 不是被重写的，是被**加了三个 `LOCAL:` 插入点**
（`ComposerFrame` 套在 composer 外面、`ComposerTools` 画在动作行右侧、`ComposerAddAttachment` 顶替动作行
左侧那颗附图按钮），三处都只为让 `composer-chrome.tsx` 有地方可接；**第四处不是插入点，是删了一个
属性**——viewport 的 `turnAnchor="top"`（见上一节「只跟着末尾走」）；**第五处是消息级的**——`AssistantMessage`
读一次折叠钩子（`useStepFold` / `useTurnFolded`），据此把整条消息 `hidden`、或在轮首画那一行摘要，
**逻辑一行都不在这份文件里**（`components/turn-steps.tsx` 与 `lib/turns.ts`），它只问「我该被收起来吗」。
五处之外，这份文件的行、样式与结构其余部分与上游一致。`thread-list.aui.tsx` 则是**就地重写过**：上游那份是给另一种产品形态的扁平、
按日期分组的线程列表，本仓要的是按**项目**分组、行上带日志体积与 mtime 的列表。保留的是行的骨架与
它那条 running 指示（**这一行的 `running` 是这一行自己的会话在不在跑**，不再是「当前页在不在跑」；
另加一格 `parked`，悬置时那行说 `Waiting on you`——`isRunning` 在悬置时是 `false`，两种说法是两件事），
删掉的是重命名 / 删除菜单项（本仓没有这两个动词）与把 Promise 丢掉的 `ThreadListItemPrimitive.Trigger`
（拒绝的句子必须显示在**所点的行**上）。**每一处改动在文件里都有 `LOCAL:` 标注**，对账就是读那些标注块。

其余的本地差异走**自建注入点**，不动抄来的文件：`message-parts.tsx` 的 `THREAD_COMPONENTS`
与自建面板（`approval-gate.tsx`、`sidebar.tsx`）。

`THREAD_COMPONENTS` 里分两组槽位。**步骤行那一组**三个各有分工：`ToolFallback` 是一种调用长什么样，
`ToolGroup` **什么都不画**（组的头「N tool call」已去掉，而槽位不能空着——空着抄来的
`thread.aui.tsx` 会画它自己那个头），`ReasoningGroup` 是思考。工具行与思考行是**同一形状的一行**：
`类型图标 · 名字 · 摘要`，状态（转圈 / 对勾 / 叉 / 感叹号）在**行尾**、词进 `sr-only`，
参数与结果仍在行里点开才见（**默认折叠是有意的差异**，实现与理由见该文件头注释）。
**唯一的例外是正在流式的那段思考**：token 到达期间它自己展开，用上游那扇「跟随最新 token」的
窗口（`max-h-64` + 底部渐隐）滚动显示；最后一个 token 落下就折回去，行上留**首行**。
历史会话（不流式的）永远是折的，手动开合过的面板也不再被自动改动（`userOpen ?? streaming`）。
**轮那一层另有一行摘要**（`N 次工具调用 · M 条消息`，见上「一轮结束就折起来」）：它不是组头的回归——
组头在**每个调用**前面、计数恒为 1，那一行在**一整轮**前面、数的是这一轮做了多少。
「摘要是投影不是截断」这条是硬约束：认不出的工具落到「第一个字符串参数」，所以新增工具
（含 MCP 的）不改前端就能看见它的调用。
**composer 那一组**是上面那三个插入点（`ComposerFrame` / `ComposerTools` / `ComposerAddAttachment`），
属于 `composer-chrome.tsx`，与步骤行没有关系。

**两张按工具名开的表就是「认得它」的全部**（都在 `message-parts.tsx`；键是 `harness.kernel.tools` 注册的那个
名字，`CONTEXT.md` 说不许起别名——改了名，图标会**静默**丢回扳手）：

| 表 | 答什么 | 认得的名字 |
|---|---|---|
| `TOOL_ICONS` | **这是哪一只手**（kind，不是状态） | `read` `write` `edit` `replace` `insert` `undo_last_replace` `anchor_grep` `glob` `bash` `eval` `skill` `session-configure` `todo_write` `web_fetch` `web_search`；认不出的给 `WrenchIcon`，刻意不长得像其中任何一个 |
| `subjectOf` | **这一步在干什么**（只读参数，不做解析） | 同上一列。各自的形状：`glob` 是模式（给了根就带上根）、`todo_write` 是进度（`2/3 完成`，空清单是「清空」）、`web_fetch` 是 URL、`web_search` 是查询串；认不出的是「第一个字符串参数」 |

新增一个工具**不动**这两张表也能用（默认分支与扳手图标就是留好的口子）；动它们是**可读性**，
不是可用性：一行是「扳手 + 一段 JSON」还是「一眼看出这是按名字找文件、进度 2/3」。
真机证据（四条新工具的步骤行与各自展开后的参数、结果）在
`.scratch/tool-parity/evidence/`。

**`skill` 也是一次普通工具调用，前端为它一行未改。** 服务端把技能清单与技能正文当 user 消息塞进模型的
上下文，而那些消息**从不产生任何 AG-UI 帧**——所以前端不是「过滤掉了它们」，是根本收不到；
界面上只有一次普通的 `skill` 调用与它的返回。见
[skills-and-instructions](skills-and-instructions.md#前端零改动wire-零改动)。

**这句话有一个例外，只有一行**：`/name` 那条**人的**加载路径现在有输入面了——技能列表（下一节）。
注入本身照旧零帧；多出来的是「有哪些名字可选」这一屏，而它读的是服务端一条只读端点。
两者不是一回事：一个是模型看到什么，一个是人挑什么。

## 技能列表（输入框里打 `/` 弹出的那份菜单）

打 `/` 弹出的那张表是**上游的触发面板**驱动的：`assistant-ui` 自带
`ComposerPrimitive.Unstable_TriggerPopoverRoot` / `.Unstable_TriggerPopover` / `.Items` / `.Item`
一套，本仓接的是「挂在哪、名字从哪来、哪些能选、一行画什么」。

- **挂点仍是一个自建插入点，抄来的文件里没有为此加过一行**：`composer-chrome.tsx` 的 `ComposerFrame`
  本来就套在 composer 外面，而触发面板必须包住**输入框**（它给输入框发 combobox 的四个属性、并让面板
  在发送前吃掉方向键与 Enter），所以 `TriggerPopoverRoot` 就挂在那一层。这一节用到的
  `elements/` 文件（`thread.aui.tsx` 与其余各份）**没有为技能列表改过**——`thread.aui.tsx` 那三个
  `LOCAL:` 插入点是 composer 附件与选择器共用，与这份菜单无关。
- **三个默认值都换掉了**，因为它们是为另一种语义写的：`matcher`（上游默认「前面是空白就算触发」，
  本仓只认**消息开头**的 `/`，与服务端的 `slash-pattern` 同形状）、`formatter`（`serialize` 成
  `/名字`，上游补尾随空格并把光标放到空格后）、`search`（没有 categories 时上游那条回落路径会走空表，
  所以过滤是这里的：名字与描述、大小写无关、顺序照服务端给的）。
- **没有 categories，这是决定不是省事**：一张平铺的表、每行带自己的层徽标，不是「先选层再选技能」的两级。
- **状态只有三样**：正在取（一行说明）、取不回来（`role="alert"`，把服务端那句话显示在面板里）、
  什么都没有（**面板根本不出现**——把一个空盒子摆出来，比不摆更糟）。
- 数据与措辞在 `src/lib/skills.ts`（层关键词 → 屏幕上的词、坏技能的原因关键词 → 一句话），
  面板与行在 `composer-chrome.tsx`。

## 附件：composer 里的图

输入框里可以粘一张图（或拖进来、或用 `+` 从文件框里选），它显示成一个可删的缩略图，随这条用户消息一起
发出去，并在这条消息旁边**带着图显示出来**（点一下放大、Esc 关）。三条路今天都是活的，而**把它们一起
打开的是同一个东西**。

- **适配器就是那个能力位。** `capabilities.attachments` 在上游就是 `!!adapters.attachments`，而粘贴
  （`ComposerInput` 的 paste handler）、拖放（`Dropzone` 的 drop handler）与 `+` 三条路**都先问这个布尔**。
  不给运行时适配器，三条路会以同一个方式安静下来：粘贴不被消费、拖放被拒、`+` 弹出文件框然后什么都不落地。
  所以适配器不在 composer 里，它是 composer **能不能有附件**这件事本身——住在 `lib/attachments.ts`，
  在 `app.tsx` 的 `adapters.attachments` 上交出去，一行。
- **没有上传，字节躺在消息里。** 上游那份 `SimpleImageAttachmentAdapter` 的两个方法正好是这个界面要的：
  `add` 留下那个 `File`（草稿期间画的缩略图就是它），`send` 把字节读成 **data URL**。那个 data URL
  **就是 wire**，不是通往 wire 的一站：AG-UI 客户端把它转回
  `{type: "image", source: {type: "data", value: <base64>, mimeType}}`，服务端再翻成厂商的
  `image_url`（见 [edge](edge.md#管理边路由表) 那张表）。没有收字节的端点、没有中间存储、没有 URL、
  没有生命周期——所以它不进库、不落盘，也不从库里读回来（`CONTEXT.md` 的**附件**词条）。
- **判据与 `undeclared-input` 是同一条，这是这一节最要紧的一句。** 一个模型声明收不收图，
  服务端在**调用厂商之前**用它拦一次（`harness.edge.ag_ui/undeclared-input`）；界面在**文件变成附件
  之前**用它拦一次（`lib/attachment-rules.ts` 的 `acceptsImages`）。**两个读者、一条规则**，
  因为两个方向都错：比服务端**严**（把「没声明」当成「不收」）会把一个今天跑得通的配置挡在门外，
  而「没有声明就是没有承诺」是那条规则的原话；比服务端**松**则整条消息被 `RUN_ERROR` 吃掉——
  composer 已经清空，打的字和那张图一起没了，正是本仓「不许吃掉别人打的字」要防的那件事。
  **「缺字段」与「空集」是两个答案**：服务端对 nil 不拦、对 `#{}` 拦（`undeclared-input` 实测
  `nil → []`、`#{} → [:image]`），线上也分得开（没声明就不写这个键，声明了空集写 `[]`），
  所以界面照同一个分法读。
- **2 MB 的上限量的是源文件字节。** 客户端每一轮都把整段历史重发，所以一张图会跟着每一轮的 `input`
  行被重记一遍（2 MB 的截图约 2.7 MB base64，二十轮就是五十多兆的记录）。量源文件而不是 base64 长度或
  解码后的像素，是因为**人手里那张图的体积是人唯一看得见、也唯一能自己动手改的数**。
  **不许偷偷改字节**：不做客户端压缩、不做缩放、不做重编码——改掉别人给的字节再发出去，等于在记录与
  「模型到底看到了什么」之间多一层没人能复盘的东西。超限就是拒，并说清拒的是什么。
  判据只有一处（`overByteLimit`），所以不会出现一处量 `file.size`、另一处量 base64 长度。
- **拒绝在适配器里发生，所以三条路都绕不过去。** `add` 是三种入口唯一汇合的地方；被拒时**抛**
  （这是上游 `add` 自己的契约，三个调用方各自接住自己的 rejection），此刻什么都还没挂上去，
  所以**输入框里的字与已经挂着的附件一个都不动**。
- **拒话只画一处。** `ComposerFrame` 是画它的地方（`role="alert"`、`data-slot="composer-attachment-refusal"`），
  两条判据的句子都往那儿去——这是「拒绝长什么样」只有一份的意思。`+` 那颗按钮在不受图的会话里
  **留在原地、变成 disabled，理由挂在包着它的 `span` 的 `title` 上**（disabled 的按钮在值得在意的浏览器
  里收不到指针事件，挂它自己身上的 `title` 是一句没人看得见的提示）。**留着而不是拿掉**是决定：
  一个悄悄消失的按钮与一颗从来没做出来的按钮从外面看一模一样，而这两件事里更难查的那件不该是 bug 的产物
  ——与技能列表把坏技能仍列出来同源；而且 `+` 是人决定要不要试的那一刻，理由必须在那之前就在，
  粘贴与拖放只能在被拒之后才说得上话。
- **那个事实住在一个小 store 里，不在 React state 里。** 判据要的是「本会话的模型收不收图」，
  读它的有三处：适配器（拒）、`+`（自禁）、`ComposerFrame`（画句子）。而适配器是从**上游自己的事件
  处理函数**里被调的——那里够不着任何 React 树——所以这个事实落在 `lib/attachments.ts` 的
  subscribe/getSnapshot 上，适配器写、界面读。**谁刷新它**：`ComposerTools` 每次取（挂载、会话切换、
  模型改完）都顺带问一次 `GET /api/model?threadId=…`（那个端点存在就是为了这件事，它的 docstring 写着
  「for a client deciding whether to offer an image picker」），所以**换了模型不用重载页面，判据当次就变**；
  这个请求失败被折成「什么都没声明」（服务端自己的语义），不让它把选择器一起拖掉。
  `GET /api/choices` 的形状一个字节没改，附件这一问没有新增端点。
- **对话那一侧用的是抄来的两份元素**：缩略图与消息旁那张图是 `elements/attachment.aui.tsx`，
  点开放大、Esc 关闭是 `elements/image.tsx`（`ImageZoom`）。两份都在**原样未改**那一组里，
  今天真的被用上了——这一票没有为了它们改过任何抄来的文件。
- 真机证据（粘贴 / 拖放 / `+` 三条路、被拒的两句话、记录里那两条行）在
  `.scratch/composer-image/evidence/`。

## 轨迹（`Conversation` / `Trajectory` 两个视图）

线程列上方有一条切换：`Conversation` 是今天这个页面，`Trajectory` 换成**轨迹视图**——
按**轮**列出**模型当时手里到底有什么**，上方一条 `input` / `model` / `tools` 三条 lane 的时间轴。

**默认只有那份列表**，点某一行才在右侧展开**那一条**（再点一次、或点 × 收回，宽度还给列表）。
这里**没有固定的第二列、也没有一对固定页签**——因为记录里本来就没有「这一轮的 system 提示词」这种东西：
有的只是二十条里的某一条，而那一条正是读者问的那一条。固定页签想显示的两样东西**都是条目**，所以都还在：
**工具跟着 system 消息一起给**：点开 `system` 那一行（第一轮出现，字节变了再出现一次），
面板里是**两个页签**——`system prompt` 与 `tools`。工具那一页是一张**可折叠的表**：一行一个工具，
折着只显示 `名字 + 描述的第一行`，展开是该工具的完整描述与它**照发出去的定义 JSON**。
两样东西共用一个面板但不是上下堆着：它们是**同一次请求的两面**——模型这一轮被告知的规矩，与它这一轮能用的手，
把提示词压在一大段 JSON 上面就没人看得到提示词了。页签是**条目级**的（只有 system 这一种条目有第二个面），
点开另一条 system 行会回到提示词那一页（`ItemDetail` 按条目 key 重建）。
它跟着 system 走不是排版上的巧合：两者本来就是**同一次请求**带出去的两样东西，
所以该一起看。某一次调用是否带了表、带了几张，在对应的 `assistant` 行上有一个计数
（表本身不在那里重复第二遍）。

- **它读的是记录，不是运行时。** 这是它与对话页签的根本区别：system 消息的字节、拼在它旁边的指令文件
  与技能清单、技能正文、以及每次调用**照发出**的工具表，客户端一个都没有——它从来没有过，
  AG-UI 帧里也没有。所以这一半由服务端从 jsonl 折出来（`harness.edge.trajectory`，
  见 [edge](edge.md)），从 `GET /api/threads/<stem>/trajectory` 吐出去，
  客户端只画折好的东西（`src/lib/trajectory.ts`、`src/components/trajectory-view.tsx`、
  `trajectory-timeline.tsx`）。**它不数、不算、不重排**：记录里没有的格子它说没有，
  绝不拿「这个会话今天有什么」去填。
- **注入物整场只画一次。** 服务端没有会话，所以每个 run 都会把开场块重新拼一遍、把历史里还留着的
  `/<名字>` 重新派生一遍——照搬「这个 run 扛了什么」，同一段字节就会画在每个 turn 底下，5 轮的会话看起来像
  开场发生了 5 次，**那是自造**。所以判据是**整段文本的字节**：没变就不再画（开场块只在第一轮），变了的那一轮再画一次
  （与 `system` 条同一条规矩）。技能的正文也一样：**用一次画一次**，画在用它的那一轮。
  折法在 `harness.edge.trajectory/add-context`（去重记在会话这一层），客户端照旧只画折好的东西。
- **切换住在 `app.tsx`，整列换掉，输入框也一起没有**——轨迹是读一份已发生的东西，不是一个能打字的地方。
  它**不写任何存储**：这是看会话的一种方式，不是关于会话的偏好。轨迹那半边只画**当前显示的那一场**
  （每份 host 只在 visible 时渲染整列），所以不显示的会话只挂着 runtime，不渲染消息。
- **取数时机与 composer 下面那条状态条同一个**（`composer-stats.tsx`）：挂载时、会话变化时、
  以及一次模型调用结束时（本侧一轮 ReAct 就是一条 assistant 消息，所以那个计数涨了就是有调用刚回来；
  `isRunning` 收尾）。读取它的 hook 必须**在 runtime provider 之内**——`App` 自己渲染那个 provider，
  在它的函数体里读会直接抛（浏览器里验过）。
- **两种看法是同一批标记的两种排法**，不是两份数据：`duration` 是真实时间轴（空档就是空档，
  等审批那两分钟看得见），`turns` 每轮等宽（长的安静的轮与短的吵的轮变得可比）。
  并发的工具调用在 lane 内**堆叠**，不排成首尾相接。
- **段与行是同一件东西的两次画法**：每条 lane 上的每个记号都能点，点的效果与点那一行**完全一样**
  （打开右侧详情；再点一次收回），因为记号本来就带着它对应条目在前面的位置。反过来也成立：
  点一行，条上那一段会被圈出来；从条上点进来的，那一行会被滚进视野——**两边对不上的时候，
  就是折法错了**，这是这个视图的自查手段。没有对应条目的记号（比如一次还没配对到回答的模型调用）
  照旧画出来，但**不是按钮**：它是一个事实，不是一扇通往空面板的门。
- **配色只有一份**（`trajectory-colors.ts`）：`input`/`model`/`tools` 三条 lane 的颜色
  就是 `user`/`assistant`/`tool` 三个标签的颜色，因为一条 lane 上的记号**就是**同一类条目
  ——lane 与条目说同一种颜色语言，读者不必学第二套。`LANE_KIND` 把这份对应写下来，
  而不是留给下一个加 lane 的人去猜。
- 一格里没有的都不编：老记录（早于 `model/*` 那两行）**没有** `calls`、模型 lane 空着并如实说；
  被否决的调用**没有** `startedAt`（它不是「0 秒」），画成空心记号。
- 文案与 UI 其余部分同语言（英文），`data-slot` 是它的挂点（`trajectory-view` / `trajectory-turn` /
  `trajectory-item` / `trajectory-pane` / `trajectory-segment` …），真机证据在
  `.scratch/trajectory/evidence/`。

## 测试

**怎么跑**用 `node scripts/test.mjs --ui`（它起的就是 `cd ui && npm test`，即 vitest；全套三条腿
见 `AGENTS.md`）。整套测试的**驱动只有一个文件**（`test/ui.test.ts`），
`test/suites/{frames,client,turn,approval,skills,stats,elicitation,attachments,turns,picker,concurrent}.ts`
是被它 import 的普通模块：

- **一次运行一个后端。** vitest 给每个测试**文件**一份独立模块图，所以多一个测试文件就是多一个 JVM。
- **驱动里钉着用例总数**（`EXPECTED_CASES`）：它是一份契约，让「某个套件从清单里掉了」
  或「丢了用例」变成**失败**而不是静默变绿。
- **后端是真的**：`dev/harness/e2e_server.clj` 起真 `harness.edge.http`，在 `--port 0`（OS 分配）上，
  provider 是 `harness.fake` 的脚本替身，日志写进临时 `CLJ_HARNESS_HOME`。
  所以跑多少次结果都一样，也不会写进真实的 `~/.clj-harness`。
- **控制通道是文件不是端点**：服务端在遇到**新的 threadId** 时重读脚本文件。
  测试写这个文件就相当于说「模型下一句回什么」——**生产 HTTP 边因此一个测试专用路由都不长**。
- 套件驱动真的 `@ag-ui/client`，所以它测的是协议与运行时的真实行为，不是替身。
- **两个家目录都交到用例手上**（`configure` 的 `home` 与 `userHome`）。`userHome` 由 spawner 造好、
  用 `--user-home` 交给后端，所以一个用例能往 OS 家目录里**播一份系统级技能**——
  「机器上的技能是两层之一」这件事在界面上能验，靠的就是这一条缝。
- **套件不 import 任何要浏览器的 `src/`**（React、DOM、`@` 别名都不行——`vitest.config.ts` 只跑 node，
  也不加载 `vite.config.js` 的别名）。**唯一例外是零 import 的纯模块，按相对路径引**：
  `suites/stats.ts` 引 `src/lib/format.ts`，为的是把「`2.9M tok` 是这么写出来的」钉住
  ——不然那句话只有一个没测的格式化函数守着；`suites/attachments.ts` 引
  `src/lib/attachment-rules.ts`，为的是把**与 `undeclared-input` 同一条**的那个判据钉住，
  外加 2 MB 那个边界的两侧；`suites/turns.ts` 引 `src/lib/turns.ts`，为的是把折起来那条规则的
  **算术**钉住——轮的边界、什么时候算停、那一行数出来是几（这三件事在浏览器里只看得到结果）；
  `suites/picker.ts` 引 `src/lib/picker.ts`，为的是把「查什么」与「同组怎么并」钉住（同样是
  只在浏览器里看结果、看不出规则的那一类）。
- **一个套件测什么，写在自己文件头上**：`suites/skills.ts` 断的是**端点**（两层、同名归谁、只读不留痕），
  它**不**断菜单怎么画、哪个键选什么；`suites/stats.ts` 断的是端点折出来的数**与那五格的字符串**，
  它**不**断那条灰线的位置与字号；`suites/attachments.ts` 两条**都是纯的**，它**不**断那颗按钮的
  disabled 状态与那句拒话画在哪——那些在真 Chromium 里量（下一段）。

界面侧另有**真 Chromium 走查**，截图留在 `.scratch/<feature>/evidence/`：那是各票验收的一部分
（三段位、归档、移除、设置的哨兵搜索、技能列表的弹层与键盘、**设置两页与 provider 表单的整条路**、
**composer 下面那条状态条**、**附件的粘贴 / 拖放 / `+` 三条路与两句拒话**），
不是自动化套件。

### 设置面板：两页，两页都会写

**General**（本会话在用什么 + **默认档**三个控件）与 **Models**（provider 目录与表单）。
页面选择是组件里的一个 `useState`，**不是路由**——不引路由依赖，URL 指不到某一页。

- **两页都会写**：General 的 Save 写 `config.edn` 的 `:default`，Models 的表单写 `:providers`
  （以及，填了密钥时，`.env` 的一行）。
- **这一页的下拉仍是原生 `<select>`**，与 composer 那四个不一样：这里是一张表单，选项是四五条，
  一眼读完，而 composer 那边面对的是三十个项目 / 一年的分支 / 一整个厂商目录（见上）。
- **曾经还有两页**（「API key」与「Config home」），主人看过后删掉了：它们报的东西——密钥有没有、
  从哪来、是哪一行、家目录在哪、哪几份文件在——Models 的每一行（`ACME_GATEWAY_API_KEY` 与 `key ✓`）
  与 composer 那边已经在眼前，**一页只装已经看得见的东西就是一步多余的路**。
  （历史与理由在 `.scratch/custom-providers/spec.md` 的复议段，本目录只记现在没有它们。）
- **表单不问「哪一行是默认 model」**：没有单选钮，**第一行就是这家厂商的默认 model**（目录要求每个
  provider 声明一个默认 model，而「默认 model」在人心里指的是「一轮跑在哪个 model 上」——
  那件事在 General 设）。控件旁边写明这一条，免得有人以为顺序只是顺序。
- **默认档的模型必须从列表里选**：没有「— 厂商自己的默认 —」这一项。厂商一定有一个默认 model
  （目录不接受没有 model 的 provider），所以那个空选项除了把这句话再说一遍没有别的内容；
  换厂商时控件直接落在新厂商的默认 model 上——服务端本来也会解析到它，控件只是把它说出来。
- **弹窗尺寸是定的，滚动发生在页里**：一份 provider 表单比面板高，会自己长大的 modal 会在人打字时
  把导航与按钮挪走。所以高度定住（`min(30rem, 62vh)`），只有右侧那一页滚。
- **两个请求、两份失败**：`GET /api/settings` 要**解析**配置，`GET /api/providers` 只读它。
  「`:default` 指着一个刚被删掉的 provider」正是那个状态——报告拒答，目录照答——
  所以两边各自失败、各自清空，页面才能既**说出**坏在哪，又留着手把修好它的**控件**。
  读失败时**不留旧值**：一行陈旧的解析结果摆在拒绝句子旁边，是面板一次说两件事。
- **首次跑通的顺序**是它们各自的形状决定的：设置面板 → Models → Add provider → 填 → Create
  → 列表里立刻有它（`catalog` 每轮重读）→ composer 的选择器里也有它（分组标签用**显示名**，
  发出去的仍是 id）→ General 把默认档指过去 → **新会话**从它开始。

## 一条从后端来的注意

`harness.edge.http/*directory-chooser*` 这个测试缝用 `alter-var-root` 而不是 `binding`：
**服务跑在另一个线程上**，`binding` 只改当前线程的动态栈，stub 会被静默忽略。
凡是给「服务端在别的线程上调用」的缝注入替身，都得用 `alter-var-root`。
