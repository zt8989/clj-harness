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
  message-parts.tsx 工具卡与 reasoning 的注入点（THREAD_COMPONENTS）
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

抄进来的清单（对账就是不重装直接 diff）：

| 位置 | 是什么 |
|---|---|
| `src/components/assistant-ui/elements/` | 12 份抄自 assistant-ui registry：thread、tool-fallback、tool-group、reasoning、markdown-text、attachment、file、follow-up-suggestions、image、tooltip-icon-button，**一字未改** |
| `src/components/ui/` | 9 份 shadcn 基件：button、dialog、dropdown-menu、input、textarea、tooltip、avatar、collapsible、skeleton |
| `src/hooks/` | 2 份 hook，同样未改 |

**一处例外，改动逐处标注**：`thread-list.aui.tsx` 就地重写过——上游那份是给另一种产品形态的扁平、
按日期分组的线程列表，本仓要的是按**项目**分组、行上带日志体积与 mtime 的列表。保留的是行的骨架与
它那条 running 指示，删掉的是重命名 / 删除菜单项（本仓没有这两个动词）与把 Promise 丢掉的
`ThreadListItemPrimitive.Trigger`（拒绝切换时必须把原因显示在**所点的行**上，那需要我们自己持有
switch 的 Promise）。**每一处改动在文件里都有 `LOCAL:` 标注**，对账就是读那些标注块。

其余的本地差异走两个**自建注入点**，不动抄来的文件：`message-parts.tsx` 的 `THREAD_COMPONENTS`
（经 `components` prop 覆盖工具卡与 reasoning 的默认渲染——**全部默认折叠是有意的差异**，实现见该文件
头注释）与自建面板（`approval-gate.tsx`、`sidebar.tsx`）。

**`skill` 也是一张普通工具卡，前端为它一行未改。** 服务端把技能清单与技能正文当 user 消息塞进模型的
上下文，而那些消息**从不产生任何 AG-UI 帧**——所以前端不是「过滤掉了它们」，是根本收不到；
界面上只有一次普通的 `skill` 调用与它的返回。见
[skills-and-instructions](skills-and-instructions.md#前端零改动wire-零改动)。

## 测试

`cd ui && npm test`（vitest）。整套测试的**驱动只有一个文件**（`test/ui.test.ts`），
`test/suites/{frames,client,turn,approval}.ts` 是被它 import 的普通模块：

- **一次运行一个后端。** vitest 给每个测试**文件**一份独立模块图，所以多一个测试文件就是多一个 JVM。
- **驱动里钉着用例总数**（`EXPECTED_CASES`）：它是一份契约，让「某个套件从清单里掉了」
  或「丢了用例」变成**失败**而不是静默变绿。
- **后端是真的**：`dev/harness/e2e_server.clj` 起真 `harness.http`，在 `--port 0`（OS 分配）上，
  provider 是 `harness.fake` 的脚本替身，日志写进临时 `CLJ_HARNESS_HOME`。
  所以跑多少次结果都一样，也不会写进真实的 `~/.clj-harness`。
- **控制通道是文件不是端点**：服务端在遇到**新的 threadId** 时重读脚本文件。
  测试写这个文件就相当于说「模型下一句回什么」——**生产 HTTP 边因此一个测试专用路由都不长**。
- 套件驱动真的 `@ag-ui/client`，所以它测的是协议与运行时的真实行为，不是替身。

界面侧另有**真 Chromium 走查**，截图留在 `.scratch/<feature>/evidence/`：那是各票验收的一部分
（三段位、归档、移除、设置的哨兵搜索），不是自动化套件。

## 一条从后端来的注意

`harness.http/*directory-chooser*` 这个测试缝用 `alter-var-root` 而不是 `binding`：
**服务跑在另一个线程上**，`binding` 只改当前线程的动态栈，stub 会被静默忽略。
凡是给「服务端在别的线程上调用」的缝注入替身，都得用 `alter-var-root`。
