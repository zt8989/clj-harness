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
  settings-panel.tsx 「设置」打开的只读报告（生效配置 / 各来自哪一档 / 家目录 / 有没有 key）
  approval-gate.tsx 审批门（自建：上游的 approval seam 认的 reason 与本仓不同）
  message-parts.tsx 步骤行（工具调用与思考）的注入点（THREAD_COMPONENTS）
  assistant-ui/elements/  11 份抄自 assistant-ui registry，一字未改（thread-list 例外，见下）
  ui/               9 份 shadcn 基件，同样未改
lib/
  threads.ts        AGENT_URL + rebuild 调用
  projects.ts       GET /api/projects 的类型化薄封装 + 移除项目
  settings.ts       GET /api/settings 的类型化薄封装
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
- **侧边栏管的不只是切换**：新建任务（**先有项目**，没有就先去添加一个）、归档 / 取消归档、
  移除项目（只解绑，日志不动；确认框说的是「会话留在磁盘上」）、以及「设置」那份只读报告。
  这些动作都在**请求进行中一起禁用**，失败的服务端原话落在**被点的那一行**下面。
- **run 进行中拒绝切换与新建**，拒绝的话显示在**所点的行上**；`isRunning` 自己会随 run 结束而解除。

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

## 测试

`cd ui && npm test`（vitest）。整套测试的**驱动只有一个文件**（`test/ui.test.ts`），
`test/suites/{frames,client,turn,approval,skills}.ts` 是被它 import 的普通模块：

- **一次运行一个后端。** vitest 给每个测试**文件**一份独立模块图，所以多一个测试文件就是多一个 JVM。
- **驱动里钉着用例总数**（`EXPECTED_CASES`）：它是一份契约，让「某个套件从清单里掉了」
  或「丢了用例」变成**失败**而不是静默变绿。
- **后端是真的**：`dev/harness/e2e_server.clj` 起真 `harness.http`，在 `--port 0`（OS 分配）上，
  provider 是 `harness.fake` 的脚本替身，日志写进临时 `CLJ_HARNESS_HOME`。
  所以跑多少次结果都一样，也不会写进真实的 `~/.clj-harness`。
- **控制通道是文件不是端点**：服务端在遇到**新的 threadId** 时重读脚本文件。
  测试写这个文件就相当于说「模型下一句回什么」——**生产 HTTP 边因此一个测试专用路由都不长**。
- 套件驱动真的 `@ag-ui/client`，所以它测的是协议与运行时的真实行为，不是替身。
- **两个家目录都交到用例手上**（`configure` 的 `home` 与 `userHome`）。`userHome` 由 spawner 造好、
  用 `--user-home` 交给后端，所以一个用例能往 OS 家目录里**播一份系统级技能**——
  「机器上的技能是两层之一」这件事在界面上能验，靠的就是这一条缝。
- **一个套件测什么，写在自己文件头上**：`suites/skills.ts` 断的是**端点**（两层、同名归谁、只读不留痕），
  它**不**断菜单怎么画、哪个键选什么——那部分在真 Chromium 里量（下一段），因为套件**不 import `src/`**。

界面侧另有**真 Chromium 走查**，截图留在 `.scratch/<feature>/evidence/`：那是各票验收的一部分
（三段位、归档、移除、设置的哨兵搜索、**技能列表的弹层与键盘**），不是自动化套件。

## 一条从后端来的注意

`harness.http/*directory-chooser*` 这个测试缝用 `alter-var-root` 而不是 `binding`：
**服务跑在另一个线程上**，`binding` 只改当前线程的动态栈，stub 会被静默忽略。
凡是给「服务端在别的线程上调用」的缝注入替身，都得用 `alter-var-root`。
