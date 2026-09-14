# 03 — assistant-ui 运行时接管页面骨架与文本对话

**What to build:** 页面的装配权从 CopilotKit 交给 assistant-ui。运行时由 `@assistant-ui/react-ag-ui` 的
`useAgUiRuntime({ agent })` 包住现成的 `HttpAgent`，界面用官方 `<Thread/>` 元素渲染——消息区、composer、
自动滚动、欢迎屏、运行中状态都由它自带。一轮真实对话从输入框贯通到后端 8080 再逐字流式回来，控制台零
报错。CopilotKit 的 provider 与 `CopilotChat` 从此不在这张页面上。

**Blocked by:** 02

**Status:** done

- [x] Tailwind 与 shadcn 接进构建并成文：`components.json` 里配好 registry 指向、样式入口被一个 entry 文件
      引到、Vite 侧构建接上 Tailwind。**这是本仓库第一次有样式体系**，接线方式要能让人照着复现
- [x] 抄官方 `thread` 元素进仓库（含它自动展开的 11 个 registryDependencies）。装完通读一遍，落地说明
      里记下最终落了哪些文件、哪些是我们改过的
- [x] 依赖只增加 assistant-ui 的 React 包、AG-UI 运行时适配器、Tailwind／shadcn 侧的包；`@ag-ui/client`
      已是依赖
- [x] 页面由 `AssistantRuntimeProvider` 包裹，运行时由**现成的 `HttpAgent`** 构造，URL 仍是
      `http://localhost:8080/`：不插中间层、不动 CORS 放行名单、dev server 仍钉在 5173
- [x] 助手文本逐字增量出现；运行中与空闲两种状态在界面上可分辨
- [x] composer 能输入并发送（Enter 发送），运行中能取消
- [x] 空态用官方元素的欢迎屏呈现，不是空白。官方对「新会话」的判据比「没有消息」窄（整份会话列表还在
      加载时也走欢迎屏，只有切换线程且该线程历史还在飞时才出骨架）；这一票照它的判据走，不自己另写一条
- [x] **threadId 结论写进 `spec.md` 决策 6**：铸造点、写在哪个对象上、下一轮 run 如何带上它
- [x] **续同一线程可验**：同一 threadId 连发两轮，服务端两次 run 落在同一个日志文件上；新会话起新 id、
      落新文件
- [x] 真 Chromium 验收（light 主题）：一轮真实对话贯通，控制台零报错，截图留档
- [x] `tsc --noEmit` 0 error 且 `npm run build` 全绿
- [x] `npm test` 四组用例全绿——它们驱动真 `@ag-ui/client`，与 UI 库无关，本票**不改**它们的内容
- [x] 落地说明诚实记录本票造成的功能缺口（审批、工具卡、reasoning、会话、项目面板尚未接回）
- [x] 落地说明点名本票是否已碰到 experimental 接口（`unstable_*` 中断、`adapters.threadList`）；碰到就
      写下升级时会破在哪里

---

## 落地说明

### 1. Tailwind 与 shadcn 怎么接进来的（照着能复现）

**这是本仓库第一次有样式体系。** 四件事，一件不少：

**(a) 依赖。** `tailwindcss@4.3.3` 与 `@tailwindcss/vite@4.3.3` 进 devDependencies。之后 shadcn CLI
自己装了它那边的一批（见第 3 节）。

**(b) 初始化。** 在 `ui/` 下跑：

```
npx shadcn@latest init -b radix -p nova -y
```

它认出了 Vite + Tailwind v4 + `@` 别名，写了三份东西：

| 落点 | 内容 |
|---|---|
| `ui/components.json` | 工程与 registry 的配置（见下） |
| `ui/src/lib/utils.ts` | 一行：`export { cn } from "cn"` |
| `ui/src/styles.css` | 主题令牌、`@theme inline`、`@layer base`（在 (d) 里说） |

`-b radix` 是**显式选的**。理由：`@assistant-ui` 的 registry 分两味，`base-` 开头走 Base UI、其余走 Radix；
票面列的 11 个依赖与官方 plain URL 就是 Radix 那味，选它两边一致。落下来的 `style` 是 `radix-nova`。

**(c) registry 指向。** `components.json` 装完是 `"registries": {}`，手动补上一条：

```json
"registries": {
  "@assistant-ui": "https://r.assistant-ui.com/styles/{style}/{name}.json"
}
```

`{style}` 会被换成 `radix-nova`。**为什么不用官方文档里那条 plain URL**（`https://r.assistant-ui.com/{name}.json`）：
两条都通（实测都 200），但 style-aware 那条才是官方现在推荐的形状，且在换 style 时不会被落下。列表里的
`button` / `skeleton` 是 shadcn 自己的组件，`r.assistant-ui.com` 上没有（实测 404），由 CLI 从默认 registry 取。

**(d) 样式入口链。** 三层，只有一个地方点名样式表：

```
index.html  ->  <script type="module" src="/src/main.tsx">
main.tsx    ->  import "./styles.css";
styles.css  ->  @import "tailwindcss";  +  shadcn 主题令牌 + tw-shimmer + collapsible keyframes
```

`index.html` 从「两条 import」缩成一条：CopilotKit 那条样式表跟着库走，现在没有第二个样式源了。

**(e) 构建与别名。** `vite.config.js` 加 `@tailwindcss/vite` 插件，并加 `@` 别名——抄来的组件全部按
`@/lib/utils`、`@/components/ui/button` 写。**别名用 `fileURLToPath(new URL("./src", import.meta.url))`
而不是官方文档写的 `path.resolve(__dirname, ...)`**，因为这个文件是 ESM，没有 `__dirname`。

`tsconfig.json` 同步加 `"paths": { "@/*": ["./src/*"] }`。**没有 `baseUrl`**：官方文档仍教你加它，但
TypeScript 7 已经把它移除了，加了直接 `error TS5102: Option 'baseUrl' has been removed`。这一条是编译器
逼出来的，不是取舍。

### 2. 抄进来的 20 份文件，以及「哪些是我们改过的」

```
src/components/assistant-ui/elements/   11 份
  thread.aui.tsx (649)           ← 主角
  tool-fallback.aui.tsx (753)    tool-group.aui.tsx (230)    reasoning.aui.tsx (120)
  reasoning.tsx (329)            markdown-text.tsx (268)     attachment.aui.tsx (268)
  file.tsx (305)                 image.tsx (545)             follow-up-suggestions.aui.tsx (82)
  tooltip-icon-button.tsx (48)

src/components/ui/                       7 份
  button.tsx  skeleton.tsx  tooltip.tsx  avatar.tsx  collapsible.tsx  dialog.tsx  textarea.tsx

src/hooks/                               2 份
  use-attachment-src.ts  use-copy-to-clipboard.ts
```

20 = thread 自己 + 11 个 registryDependencies（`button`、`skeleton`、`attachment`、`file`、
`follow-up-suggestions`、`image`、`markdown-text`、`reasoning`、`tooltip-icon-button`、`tool-fallback`、
`tool-group`）+ 递归展开又带出的 8 份（`elements-reasoning`、`avatar`、`collapsible`、`dialog`、
`textarea`、`tooltip`、`use-attachment-src`、`use-copy-to-clipboard`）。`src/lib/utils.ts` 是 `init` 那一步
落的，不在这 20 份里。

**本地改动：0 处。** 把 20 份与上游 registry 原文逐字节比过，**17 份完全相同**；3 份有差异，但差异
**全部是 CLI 安装时的机械变换，不是手改**：

| 文件 | 差异 | 内容 |
|---|---|---|
| `ui/tooltip.tsx` | 上游首行 `"use client"` 本地没有（差 14 字符） | 指令处理 |
| `ui/collapsible.tsx` | 同上（差 14 字符） | 同上 |
| `ui/dialog.tsx` | `"use client"` **保留**；另有 3 处占位符被替换 | `IconPlaceholder`（带 lucide/tabler/hugeicons/phosphor/remixicon 五个分支）→ 我们图标库的 `XIcon`；`@/registry/radix-nova/ui/button` → `@/components/ui/button`；`cn-font-heading` → `font-heading` |

CLI 对 `"use client"` 的处理**不是一条统一规则**——20 份里 15 份留着它、5 份没有（`button`/`skeleton`/
`textarea` 上游本来就没有，`tooltip`/`collapsible` 是被去掉的，而 `dialog` 留着）。如实记下这个不一致，
不要给下一个人一个并不存在的规律。

**两个 reasoning 文件是同一件事的两层，不是两份实现**：`elements/reasoning.aui.tsx`（styled 包装）从
`./reasoning` import `ReasoningRoot` / `ReasoningTrigger` / `ReasoningContent` / `ReasoningText` /
`ReasoningFade` / `reasoningVariants`，并用相对路径 re-export 类型。`elements/reasoning.tsx` 就是它的实现
模块，是被 `reasoning` 这一条的 `registryDependencies`（`reasoning -> elements-reasoning, markdown-text`）
拉进来的，**不是死代码**。04 要改 reasoning 行为时，改的是这两层里对应的那一层，别只改一个。
（订正一次：本说明的初稿写「`reasoning.tsx` 无人 import」——那是因为我只 grep 了 `@/components/...`
绝对路径形式，漏掉了 `./reasoning` 这个相对 import。已按实际更正。）

### 3. 依赖清单（新增）

**dependencies**：`@assistant-ui/react@0.15.19`、`@assistant-ui/react-ag-ui@0.0.59`、
`@assistant-ui/react-markdown@0.14.15`、`remark-gfm@4.0.1`、`@fontsource-variable/geist`、`radix-ui`、
`cn`、`shadcn`、`tw-animate-css`、`tw-shimmer`、`class-variance-authority`、`lucide-react`、`zustand`。
**devDependencies**：`tailwindcss@4.3.3`、`@tailwindcss/vite@4.3.3`。

其中 `@assistant-ui/react-markdown` / `remark-gfm`（`markdown-text` 用）、`tw-shimmer`（`tw-shimmer` 的
CSS import）、`zustand`（`@assistant-ui/react` 自身的依赖）是跟着 registry 展开进来的，不是我们主动选的。

两点值得点名：

- `shadcn` 与 `cn` 现在是**运行时依赖**，不是只在 CLI 期用。新版 shadcn 的形状就是这样：主题 CSS 在
  `@import "shadcn/tailwind.css"` 里，`cn` 被 `src/lib/utils.ts` 再导出，两者都要在构建时解析得到。
- `@fontsource-variable/geist` 是 nova 预置带进来的字体。它严格说不在票面那句「Tailwind／shadcn 侧的包」
  的枚举里，但它就是官方预置的产物；为了不手改生成出来的 CSS 而保留，记在这里备查。

### 4. 页面装配

`ui/src/app.tsx` 现在只有一件事：把 agent 交给适配器，把适配器交给 Thread。

```tsx
const agent = useMemo(() => new HttpAgent({ url: AGENT_URL }), []);   // AGENT_URL = "http://localhost:8080/"
const runtime = useAgUiRuntime({ agent });
// <AssistantRuntimeProvider runtime={runtime}> <TooltipProvider> <div className="h-dvh"> <Thread/> …
```

- **`useMemo` 而不是模块级单例**，这是票面点名的形状。依赖数组为空是承重的：重渲染换 agent 会把线程
  丢掉。模块级会看起来等价而并不等价——它活过 Fast Refresh，悄悄把线程带过一次编辑。
- **`TooltipProvider` 是必须的**，不是装饰。抄来的 `tooltip.tsx` 包的是 Radix 的 Provider，没有祖先
  Provider 时 Radix 的 `Tooltip.Root` 会抛；CLI 装完也专门提示了这一句。
- **`h-dvh` 的外层**：`ThreadPrimitive.Root` 自带 `h-full`，需要一个真有高度的父级。06/07 的面板要加在
  这一行上方而不能把高度抢走。
- 其余槽位一个没动：`showThinking` 走默认（true）、欢迎屏走官方判据、`Thread` 不传 `components`。

**四件东西随 CopilotKit 退场**（这是分票的代价，写在这里免得下一个读 git log 的人以为丢了）：
`ui/src/approval-gate.tsx` 与 `ui/src/reasoning-message.tsx` 两个文件删除；项目面板与会话面板原先是
`app.tsx` 里的两个组件、随这次重写一并撤出。它们都写在 CopilotKit 的 API 上（`useAgent` / `useInterrupt` /
`CopilotChatReasoningMessage`），留着既不能跑也是第二次实现。旧版本在 `cb3521c` 里，04–07 各取所需。
**当前页面只剩聊天。**

### 5. threadId 结论 —— 已写回 `spec.md` 决策 6

归属**不变**：主人仍是 **agent 对象**。03 没走 `adapters.threadList`（那是 06 的选择），所以拆成三问三答：

- **谁铸**：`HttpAgent` 的构造函数。实测两次 `new HttpAgent({url})` 得到两个不同 UUID。
- **谁读**：适配器只读、从不铸。`AgUiThreadRuntimeCore` 里两处都是 `this.agent.threadId || "main"`，
  随后把算出的值写回那次 run 的实例。`HttpAgent` 一定有 id，所以 `|| "main"` 在这个仓库里永不触发。
- **下一轮怎么带上**：`prepareRunAgentInput` 从 `this.threadId` + `this.messages` 组装 `RunAgentInput`，
  **两轮之间无需任何显式传递**。这就是「续同一线程」的全部机制。

**实测两条**：同一页面连发两轮 → 同一个日志文件从 **4 939 → 507 708 字节**，会话总数不变（第二轮
`input.threadId` 与第一轮逐字相同）；**刷新页面**（新 agent）→ 全新 id 与新文件，会话数 **5 → 6**。

### 6. 验收记录

真 Chromium（1440×900、light）对真 8080：

| 项 | 结果 |
|---|---|
| 空态 | 官方欢迎屏「How can I help you today?」，不是空白；刷新后重现 |
| 逐字增量 | 每 2s 采样可见文本长度：6757 → 7293 → 7853 → 8429 → 8710 → 8742，单调增长 |
| 运行中 / 空闲可分辨 | 运行中 composer 是 **Stop generating**，空闲是 **Send message**（空输入时 disabled） |
| 发送 | Enter 发送（一条 `/` 前缀都没有，`tools: []`，纯文本轮） |
| 取消 | 点 Stop generating → 运行停止、composer 复位、无报错 |
| 控制台 | **零报错、零页面错误**（全程只有 vite 连接与 React DevTools 的 dev 提示） |
| 网络 | `POST http://localhost:8080/` 200，`OPTIONS` 预检 204 —— 5173 直连、CORS 放行生效、无中间层 |
| 命令 | `tsc --noEmit` 0 error；`npm run build` 全绿（CSS 83.79 kB，JS 1 240.87 kB / gzip 352.76 kB）；`npm test` **11 passed (11)** |

截图（`.scratch/assistant-ui/evidence/`）：`t03-01-welcome.png`、`t03-02-two-turns-streamed.png`、
`t03-03-after-cancel.png`、`t03-04-fresh-thread.png`。（前缀用 `t03-` 而非 `03-`：那批 `03-project-bound.png`
是票 01 的第 3 项行为证据，同前缀会混。）

### 7. 本票造成的功能缺口（诚实记录）

| 功能 | 当前状态 | 谁接回 |
|---|---|---|
| 文本对话 | ✅ 可用 | — |
| 欢迎屏 / 自动滚动 / 取消 | ✅ 官方元素自带 | — |
| 工具调用卡 | ❌ 没有渲染器 → 工具调用看不见 | 04 |
| reasoning 渲染 | ⚠️ 官方默认：**流结束就折叠**，与仓库要的「仍展开」相反 | 04 |
| 审批门 | ❌ 完全没有 | 05 |
| 会话列表 / 恢复 / 新建 | ❌ 完全没有 | 06 |
| 项目目录面板 | ❌ 完全没有 | 07 |

**07 落地前 main 上的页面是薄的，这是分票的代价，不是丢失。**

### 8. 本票是否碰到 experimental 接口

**没有用到，但读包时先撞上了，结论对 05 有直接影响。**
`unstable_getPendingInterrupts()` 与 `unstable_submitInterruptResponses()` 在
`@assistant-ui/react-ag-ui@0.0.59` 里**已经被标 `@deprecated`** —— 类型注释写着「改用 `useAgUiInterrupts()` /
`useAgUiSubmitInterruptResponses()`，保留只为向后兼容，将在下一个大版本移除」。也就是说 05 要用的那两个
`unstable_*` 不是新口子，是行将删除的旧口子；升级时不是「静默失灵」而是「直接编译失败」。已写进 `spec.md`
已知风险，05 应优先用那对 hooks。`adapters.threadList` 本票没碰（06 的事）。

### 9. 其它如实记下的

- **CopilotKit 还剩三处引用，都留给 08，本票不动**（票面说依赖「只增加」，而 08 的验收才是「依赖、源码、
  打包产物三处都搜不到」）：
  1. `package.json` 里的 `@copilotkit/react-core` —— 已经是**没有消费者的死依赖**（`src/` 里零 import）。
     code-review 的 Standards 轴点了它，判定为 08 的范围。
  2. `test/suites/client.ts` 第 1 行注释提到 CopilotKit —— 02 落地时写的，那句话本身没错，但很快会过期。
  3. `src/app.tsx` 第 25 行我自己写的说明，讲这次换装配权，是**有意留的**历史注解。
- **模型侧偶发空返回，不是前端问题。** 第一轮用「请用两句话介绍你自己」问一个 system prompt 自称
  coding agent 的模型，日志里只有 `RUN_STARTED` → 6.6s → `RUN_FINISHED`，中间**零事件**、助手内容为空串、
  也没有 `RUN_ERROR`。换一句切题的提示即正常（历史成功日志有数百条 `REASONING_MESSAGE_CONTENT` +
  `TEXT_MESSAGE_CONTENT`）。下次有人在日志里看到 5 KB、助手内容为空，先想这个，别怀疑前端。
- **沙箱 PATH 不含 `/opt/homebrew/bin`**（`clojure` 在那儿），跑 `npm test` 要显式带上；这次验收用的
  `agent-browser` 在 nvm 的 bin 下，同样要带上。是环境不是代码。
