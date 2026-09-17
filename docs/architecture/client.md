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
app.tsx             HttpAgent({url: "http://localhost:8080/"}) → useAgUiRuntime → <Thread/>
                    侧边栏 + 审批批次 provider + THREAD_COMPONENTS 注入
components/
  sidebar.tsx       三段位：钉住的「新建任务」、唯一滚动的项目区、钉住的「设置」
  settings-panel.tsx 「设置」：两页左导航（General / Models），**两页都会写**
  approval-gate.tsx 审批门（自建：上游的 approval seam 认的 reason 与本仓不同）
  message-parts.tsx 步骤行（工具调用与思考）的注入点（THREAD_COMPONENTS）
  composer-chrome.tsx   composer 上下两条与两个 LOCAL: 插入点（ComposerFrame / ComposerTools）
  composer-stats.tsx    composer **下面**那条状态条（会话统计的五格）
  assistant-ui/elements/  11 份抄自 assistant-ui registry，一字未改（thread-list 例外，见下）
  ui/               9 份 shadcn 基件，同样未改
lib/
  threads.ts        AGENT_URL + rebuild 调用
  projects.ts       GET /api/projects 的类型化薄封装 + 移除项目
  settings.ts       GET /api/settings 的类型化薄封装
  providers.ts      GET /api/providers + 三条写入 + 厂商探询的类型化薄封装
  stats.ts          GET /api/threads/<stem>/stats 的类型化薄封装（`404` 也是普通答案）
  format.ts         给**人看**的数字：字节、时间，以及状态条那五格的字符串（`statsCells`）。
                    **零 import**，所以 UI 套件能直接测它
  run-state.ts      「run 进行中」的拒绝句子（适配器与侧边栏共用一份）
```

**5173 是 CORS 契约不是偏好**：后端只放行 `http://localhost:5173`，`vite.config.js` 里
`server.port: 5173, strictPort: true` 把这句话钉死——换端口要同时改两处契约。

## 状态的归属

- **`threadId` 的主人是 React state**（`app.tsx` 的 `useState`）。agent 只在 `adoptThread` 一处被回写，
  而 `prepareRunAgentInput` 照旧从 agent 读——所以下一条输入续写**同一个日志**，服务端零会话状态。
- **恢复** = `rebuildThread`（POST rebuild）→ `fromAgUiMessages` + `fromThreadMessageLike`
  → `onSwitchToThread` 把重建消息灌回运行时。转换与 runtime 自己的快照导入路径**逐字相同**（引上游，不另写）。
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
- **run 进行中拒绝切换与新建**，拒绝的话显示在**所点的行上**；`isRunning` 自己会随 run 结束而解除。
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
- **对话区两档字号，第三个数字是载荷**：正文（助手答案）**14px**，步骤行（工具行、思考行）
  **13px**，参数与结果块 **12px**。正文那条规则写在 `ui/src/styles.css` 里、按
  `[data-slot="aui_assistant-message-content"]` 命中——那个元素是抄来的文件里的，它没有 `aui-*`
  class 可挂，而改那份文件就破坏了「不重装直接 diff」；步骤行的 13px 写在
  `message-parts.tsx` 的行上（行是我们自己的）。

抄进来的清单（对账就是不重装直接 diff）：

| 位置 | 是什么 |
|---|---|
| `src/components/assistant-ui/elements/` | 12 份抄自 assistant-ui registry：thread、tool-fallback、tool-group、reasoning、markdown-text、attachment、file、follow-up-suggestions、image、tooltip-icon-button，**一字未改**（`tool-group.aui.tsx` 仍在清单里、仍只被抄来的 `thread.aui.tsx` 用；注入点已不再导入它，见下） |
| `src/components/ui/` | 9 份 shadcn 基件：button、dialog、dropdown-menu、input、textarea、tooltip、avatar、collapsible、skeleton |
| `src/hooks/` | 2 份 hook，同样未改 |

**一处例外，改动逐处标注**：`thread-list.aui.tsx` 就地重写过——上游那份是给另一种产品形态的扁平、
按日期分组的线程列表，本仓要的是按**项目**分组、行上带日志体积与 mtime 的列表。保留的是行的骨架与
它那条 running 指示，删掉的是重命名 / 删除菜单项（本仓没有这两个动词）与把 Promise 丢掉的
`ThreadListItemPrimitive.Trigger`（拒绝切换时必须把原因显示在**所点的行**上，那需要我们自己持有
switch 的 Promise）。**每一处改动在文件里都有 `LOCAL:` 标注**，对账就是读那些标注块。

其余的本地差异走两个**自建注入点**，不动抄来的文件：`message-parts.tsx` 的 `THREAD_COMPONENTS`
与自建面板（`approval-gate.tsx`、`sidebar.tsx`）。

`THREAD_COMPONENTS` 填的是**步骤行**，三个槽位各有分工：`ToolFallback` 是一种调用长什么样，
`ToolGroup` **什么都不画**（组的头「N tool call」已去掉，而槽位不能空着——空着抄来的
`thread.aui.tsx` 会画它自己那个头），`ReasoningGroup` 是思考。工具行与思考行是**同一形状的一行**：
`类型图标 · 名字 · 摘要`，状态（转圈 / 对勾 / 叉 / 感叹号）在**行尾**、词进 `sr-only`，
参数与结果仍在行里点开才见（**默认折叠是有意的差异**，实现与理由见该文件头注释）。
「摘要是投影不是截断」这条是硬约束：认不出的工具落到「第一个字符串参数」，所以新增工具
（含 MCP 的）不改前端就能看见它的调用。

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

- **挂点仍是一个自建插入点，抄来的文件一行未改**：`composer-chrome.tsx` 的 `ComposerFrame` 本来就套在
  composer 外面，而触发面板必须包住**输入框**（它给输入框发 combobox 的四个属性、并让面板在发送前吃掉
  方向键与 Enter），所以 `TriggerPopoverRoot` 就挂在那一层。`thread.aui.tsx` 与
  `elements/` 里那 12 份**一个字节没动**。
- **三个默认值都换掉了**，因为它们是为另一种语义写的：`matcher`（上游默认「前面是空白就算触发」，
  本仓只认**消息开头**的 `/`，与服务端的 `slash-pattern` 同形状）、`formatter`（`serialize` 成
  `/名字`，上游补尾随空格并把光标放到空格后）、`search`（没有 categories 时上游那条回落路径会走空表，
  所以过滤是这里的：名字与描述、大小写无关、顺序照服务端给的）。
- **没有 categories，这是决定不是省事**：一张平铺的表、每行带自己的层徽标，不是「先选层再选技能」的两级。
- **状态只有三样**：正在取（一行说明）、取不回来（`role="alert"`，把服务端那句话显示在面板里）、
  什么都没有（**面板根本不出现**——把一个空盒子摆出来，比不摆更糟）。
- 数据与措辞在 `src/lib/skills.ts`（层关键词 → 屏幕上的词、坏技能的原因关键词 → 一句话），
  面板与行在 `composer-chrome.tsx`。

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
- **切换住在 `app.tsx`，整列换掉，输入框也一起没有**——轨迹是读一份已发生的东西，不是一个能打字的地方。
  它**不写任何存储**：这是看会话的一种方式，不是关于会话的偏好。
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

`cd ui && npm test`（vitest）。整套测试的**驱动只有一个文件**（`test/ui.test.ts`），
`test/suites/{frames,client,turn,approval,skills,stats}.ts` 是被它 import 的普通模块：

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
  ——不然那句话只有一个没测的格式化函数守着。
- **一个套件测什么，写在自己文件头上**：`suites/skills.ts` 断的是**端点**（两层、同名归谁、只读不留痕），
  它**不**断菜单怎么画、哪个键选什么；`suites/stats.ts` 断的是端点折出来的数**与那五格的字符串**，
  它**不**断那条灰线的位置与字号——那些在真 Chromium 里量（下一段）。

界面侧另有**真 Chromium 走查**，截图留在 `.scratch/<feature>/evidence/`：那是各票验收的一部分
（三段位、归档、移除、设置的哨兵搜索、技能列表的弹层与键盘、**设置两页与 provider 表单的整条路**、
**composer 下面那条状态条**），
不是自动化套件。

### 设置面板：两页，两页都会写

**General**（本会话在用什么 + **默认档**三个控件）与 **Models**（provider 目录与表单）。
页面选择是组件里的一个 `useState`，**不是路由**——不引路由依赖，URL 指不到某一页。

- **两页都会写**：General 的 Save 写 `config.edn` 的 `:default`，Models 的表单写 `:providers`
  （以及，填了密钥时，`.env` 的一行）。
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
