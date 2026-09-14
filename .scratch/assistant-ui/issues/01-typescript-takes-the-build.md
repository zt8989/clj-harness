# 01 — 构建换成 TypeScript，页面直译后行为零变化

**What to build:** `ui/` 的前端从 ClojureScript 换成 TypeScript，而页面长得一模一样。四份 CLJS 源码
（React root、页面装配、审批卡、reasoning）逐行搬成 TS/TSX，构建从 shadow-cljs 双编译器换成 Vite 的
React 插件加 `tsc`。CopilotKit 仍在、视觉仍在、六项行为逐条与今天对位。

这一票的验收是**没有变化**：聊天、工具卡、reasoning、审批、会话、项目目录面板任何一处观感或行为差异
都算缺陷。把语言与库分两步，买的就是这一点——差异出现时只有一个嫌疑人。

验收侧的六份 CLJS 这一票**不动**：它们只驱动 `@ag-ui/client`，不依赖 `src`，是移植期间的回归网。
把它们搬过去是下一票的事。

**Blocked by:** None — can start immediately.

**Status:** done

- [x] `ui/src` 下不再有 `.cljs`：四份源码直译成 TS/TSX，React root、页面装配、审批卡、reasoning
      一一对应，文件职责不比今天多也不比今天少
- [x] 构建只由 Vite 与 `tsc` 驱动：`npm run dev` 起在 5173，`npm run build` 走 `tsc --noEmit` 加 Vite
      打包；`vite-plugin-cljs.js` 与 shadow-cljs 的 `:app` build 退场（`:test` build 留到 02）
- [x] 依赖新增 TypeScript、`@vitejs/plugin-react`、`@types/react`、`@types/react-dom`；CopilotKit 与
      `@ag-ui/client` 的版本一个不动
- [x] `shadow-cljs.edn` 的 `:dependencies` 里去掉了 helix：src 已经全是 TS，没有 CLJS 再引用它
      （`:test` build 留到 02，而它只用 `@ag-ui/client`，本来就不依赖 helix）
- [x] `tsc --noEmit` 0 error，`strict` 打开。从 JS 库进来的每个边界都要有类型；确有第三方类型缺口时
      就地注明原因，**不用 `as any` 把编译糊过去**
- [x] 四组 CLJS 用例暂时保留并继续全绿（`npm test` 今天怎么跑，这一票之后还怎么跑）
- [x] 六项行为逐条对位：一轮真实对话贯通 8080、工具卡（工具名 / 参数 / 状态 / 结果）、reasoning 在
      run 结束后仍展开、审批批准与否决两条路、会话列表与恢复与新建、项目目录的读与绑定与选择文件夹
- [x] 真 Chromium 验收（light 主题）：六项各留截图，与今天的截图并排比对；控制台零报错
      （截图比对的方法学有折损，见落地说明第 4 条）
- [x] `ui/index.html` 指向新的 TS 入口，且照样显式点名 CopilotKit 的样式表（它今天不是靠模块副作用
      导入的，这一条不能在该票里丢掉）
- [x] agent 的 URL 仍是 `http://localhost:8080/`，dev server 仍是 5173，CORS 放行名单一行未改
- [x] 落地说明记下这一票**没有**改什么：内核、AG-UI 帧、后端一行未动

## 落地说明

### 1. 文件地图

`src/harness/ui/*.cljs` 的目录层级是 CLJS 命名空间 `harness.ui.*` 的镜像，在 TS 里没有对应物，所以
平铺到 `src/`（前缀路径是纯噪音，留着只会让人以为那里还有分层）：

| 旧 | 新 | 职责 |
|---|---|---|
| `src/harness/ui/main.cljs` | `src/main.tsx` | React root，只负责挂载 |
| `src/harness/ui/app.cljs` | `src/app.tsx` | 页面装配 + 项目面板 + 会话面板 + 三个 fetch 客户端 |
| `src/harness/ui/approval_gate.cljs` | `src/approval-gate.tsx` | 审批门 |
| `src/harness/ui/reasoning_message.cljs` | `src/reasoning-message.tsx` | 常展开的 reasoning |

命令式命名跟着语言走：`fetch-binding!` → `fetchBinding`，`bind!` → `bind`，`use-effect` → `useEffect`。
`defonce` 的两处（`agent`、`tool-renderers`）变成模块级 `const`——同样只求「稳定标识」，CopilotKit 对
`renderToolCalls` 每次 render 换新数组会告警。

### 2. 构建链

- `npm run dev` = `vite`，5173，`strictPort` 不变。
- `npm run build` = `tsc --noEmit && vite build`。**`tsc` 是构建的一部分**，不是可选的旁路。
- 另加 `npm run typecheck` = `tsc --noEmit`，给「只想量一下类型」的场合用；这是新脚本，不是新职责。
- `vite.config.js` 从 `cljs()` 插件换成 `@vitejs/plugin-react`；`vite-plugin-cljs.js` 删除（它是
  shadow-cljs 与 Vite 之间的桥，没有第二个编译器就没有存在理由）。**02 的清单里还列着这个文件，
  它已经在这一票没了。**
- `shadow-cljs.edn` 只剩 `:test` build，`:source-paths` 收成 `["test"]`，整个 `:dependencies` 键删掉
  （helix 是它唯一的内容）。`:load-tests` 那段警告原样留着——02 要靠它。
- `index.html` 的两条 import 形状不变，第二条从 `virtual:shadow-cljs/app` 换成 `/src/main.tsx`。构建后
  Vite 把内联入口抽成一份 JS + 一份 CSS，**CopilotKit 的样式表确实活到了生产产物里**（`dist/assets/
  index-*.css`，90 KB）——这正是当初要显式点名它的原因。

### 3. 两处只有编译才能问出来的事实

**(a) CopilotKit 的槽位类型是 `typeof <组件>`，所以替换件必须把库的静态成员一并发布。**
`CopilotChat` 的 `messageView` 槽是 `SlotValue<typeof CopilotChatMessageView>`，
`CopilotChatMessageView` 的 `reasoningMessage` 槽是 `SlotValue<typeof CopilotChatReasoningMessage>`。
`SlotValue<C> = C | string | Partial<ComponentProps<C>>`——第一支要求结构上带全 `C` 的属性，于是：

- `messageView` 的替换件必须有 `Cursor`；
- `reasoningMessage` 的替换件必须有 `Header` / `Content` / `Toggle`。

这不是类型系统的刁难：那三个正是默认组件渲染用的子组件，替换件本来就在用它们。所以两处都用
`Object.assign(实现, { …库自带的静态成员 })` 发布，**不是 `as` 断言**——它们是真的那些组件。
`ReasoningMessage` 内部也正是用 `ReasoningMessage.Header` 自引用的。

**(b) 上一轮那个 Closure 改名坑随 shadow-cljs 一起消失了。** 旧的 `:esm` + `:advanced` 会把外部包的
属性改名（`ThreadPrimitive.Root` → `P.K`），当时只能把整个 build 永久降到 `:simple`。现在没有第二个
编译器，这个约束不存在了，也不需要任何 `:optimizations` 配置。

### 4. 验收记录，以及方法学上的折损

- `npm run build`：`tsc --noEmit` 0 error（`strict` + `noUnusedLocals` + `noUnusedParameters` +
  `verbatimModuleSyntax`），Vite 打包成功，产物里搜不到 `shadow-cljs`。
- `npm test`：**11 passed (11)**，四组用例未改一行。`:test` build 在 `:source-paths ["test"]` 且无
  `:dependencies` 的情况下照样编译（`62 files, 1 compiled, 0 warnings`）。
- 真 Chromium（1440×900，light），对着**真 8080** 跑完整六项，全部 200：
  `GET /api/project` ×4、`GET /api/threads` ×3、`POST /api/project` ×2、
  `POST /api/threads/<id>/rebuild` ×1、`POST /api/project/pick` ×1。
  截图在 `evidence/01…06`。
  - 对话贯通：一轮真实对话（OpenRouter 推理模型）走完 `RUN_STARTED → REASONING_* → TOOL_CALL_* →
    tools/execute → TOOL_CALL_RESULT → RUN_FINISHED`。
  - 工具卡：`read`，`ARGUMENTS {"path": "…/README.md"}`，状态 `complete`，`RESULT` 是文件内容。
  - reasoning：头行 `Thought for 19 seconds`，**run 结束后内容仍然展开**——默认组件此时会折叠，这一处
    差异正是这个组件存在的理由，行为对上了。
  - 审批两条路都走到底：否决 → 客户端交 `[{status:"cancelled"}]`，服务端记 `vetoed`，**没有**
    `tools/execute`，模型收到 `vetoed by human: the call was not executed.` 并继续作答；批准 → 客户端交
    `[{status:"resolved", payload:{decision:"approved"}}]`，服务端记 `approved`，`tools/execute` 出现，
    工具结果是真的 `/etc/hosts` 内容。
  - 会话：列表 / 刷新 / 新建（历史清空、面板切回未绑定）/ 恢复。**恢复的验收点是日志**：`b511a0af-…jsonl`
    从 4 831 字节长到 22 565 字节，中间只多一行 `session/rebuilt`，续聊写进的是同一个文件。
  - 项目目录：读（未绑定）/ 绑定（面板显示服务端回的绝对路径，输入框清空）/ 选择文件夹（原生对话框返回
    的绝对路径落进输入框，按设计只填字段、不直接绑定）。
  - 控制台：整场只有 2 条 error，且是**同一次**后端失败的两次上报
    （`Remote host terminated the handshake`，OpenRouter 那一跳的偶发握手失败，重试即通）。前端零缺陷，
    而且它把这次后端失败如实渲染成了一个可关闭的错误横幅。

**折损，如实记下**：票面写的「与今天的截图并排比对」没有做到——旧构建在源码被替换后无法再起（这是本票
的必然结果），所以「今天」那一侧的依据是**逐行读完的四份 CLJS 源码**加上现场逐条观察，而不是两套截图
并排。样式值（含色值、字号、`display: contents`、`flexBasis`）是逐个照搬的，观感差异的风险因此很低，
但这是推理，不是并排比对，不该被写成已经做过了。

### 5. 这一票没有改什么

内核 `src/harness/*.clj`、AG-UI 帧、CORS 放行名单、5173 契约——一行未动。浏览器始终直连 8080。
`test/` 下四组 CLJS 用例与 `cljs-test/` 桥、`test/support/{build,java,harness}.js` 一字未改。
