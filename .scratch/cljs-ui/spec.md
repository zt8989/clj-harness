# spec: cljs-ui

> **被取代（2026-09-15）。** 本特征已被 `.scratch/assistant-ui`（ui 换语言 + 换库）整体取代，本文件留作记录。
> 对应关系：
>
> - **决策 1（保留 CopilotKit v1.71）作废**——CopilotKit 已从仓库彻底出局：页面装配换 `@assistant-ui/react-ag-ui`
>   的 `useAgUiRuntime`，验收客户端不再是它，依赖与产物里都搜不到（assistant-ui 08 号票）。
> - **决策 2（helix）作废**——前端回到 TypeScript + React，无 helix。
> - **决策 3（shadow-cljs 桥，含自写 `vite-plugin-cljs`）作废**——仓库里没有第二个编译器了：Vite 单工具链
>   （`@vitejs/plugin-react` + `@tailwindcss/vite`），构建不再需要 Java 21。
> - **决策 5（删掉 TS 版本）被反向执行**——assistant-ui 特征 01/02 把语言搬回 TypeScript，CLJS 整体离场；
>   本特征「只留一份实现」的原则保留，只是留下的那份是 TS。
> - **「刻意不引样式体系」的立场被反转**——本特征引入 Tailwind v4 + shadcn（assistant-ui 决策 3）。理由：
>   assistant-ui 官方组件按 Tailwind 工具类写成，抄源码路线没有样式体系就无法落地；接线与对账基准见
>   README「样式体系」一节。
> - **决策 8（`ui/*.mjs` 验证脚本不动）本身已过期**——那批脚本今天已不在仓库里，其覆盖面由 `ui/test/suites/`
>   的四组 TypeScript 套件接走（assistant-ui 02 号票）。
> - **仍然有效的一条**：决策 6（dev server 钉 5173 是 CORS 契约不是偏好）在新特征里原样保留，README 与
>   `vite.config.js`（`strictPort: true`）都还写着这句话。

把 `ui/` 的胶水层从 TypeScript 译成 ClojureScript，让这个仓库里不再有 TypeScript。

## 背景与定位

后端本来就是纯 Clojure。`ui/` 是仓库里唯一非 Lisp 的部分，但它自己只有 **169 行 TSX**：

| 文件 | 行数 | 干什么 |
|---|---|---|
| `ui/src/main.tsx` | 13 | 挂 React root |
| `ui/src/App.tsx` | 21 | CopilotKit provider + `HttpAgent` + `CopilotChat` |
| `ui/src/ApprovalGate.tsx` | 135 | `useInterrupt` 审批卡 |

其余全是 `node_modules`。所以这次不是"把一个大前端改成 CLJS"，而是**把这 169 行胶水用 ClojureScript 重写一遍**。

## 决策

1. **保留 React 19 + CopilotKit v1.71**，经 JS interop 调用。CopilotKit 仍是**外部验收客户端**，它的独立性不变——变的只是我们这一侧胶水的语言。
2. **前端框架用 helix 0.2.2**：React 的薄包装，hooks 与现有 React 心智一一对应，审批卡的 `render` 回调可以直接返回 helix 元素。
3. **shadow-cljs 编 ClojureScript，Vite 继续打 npm 包。** 两者由自写插件 `ui/vite-plugin-cljs.mjs` 桥接：dev 时插件拉起 `shadow-cljs watch`，build 时先跑 `release`，再把产物当 ESM 交给 Vite。分工的理由见下条。
4. **npm 的打包交给 Vite，而不是 shadow-cljs。** 实测 shadow-cljs 的 npm 解析器啃不动 CopilotKit 的依赖链（三处硬伤，见"落地修正"），而 Vite 今天就是好的。这条推翻了初版决策"删掉 Vite"。
5. **删掉 TS 版本**，`ui/` 只留一份 CLJS 实现，不留双份避免漂移。
6. **dev server 必须钉在 5173**：后端 CORS 只放行 `http://localhost:5173`（`src/harness/http.clj:18`），端口不是偏好而是契约。
7. **CSS 由 Vite 打包，且在 `index.html` 里显式点名** `@copilotkit/react-core/v2/styles.css`。不能依赖 CopilotKit 模块自身的副作用导入（见"落地修正"）。
8. **`ui/*.mjs` 验证脚本不动**：它们是 Node 驱动真 `@ag-ui/client` 的**外部验收**，既不是 UI 胶水也不是 TypeScript。删了就等于把验收换成了自验。
9. **命名空间 `harness.ui.{main,app,approval-gate}`**，落在 `ui/src/harness/ui/`，与后端 `harness.*` 的命名习惯对齐。
10. **`:target :esm` + `:js-options {:js-provider :import}`**：CLJS 编成 ESM，npm 的裸导入原样保留给 Vite 解析。`:js-provider` 必须写在 `:js-options` 里，写在 build 顶层不生效。

## 落地修正（初版决策的偏差，均已实证）

1. **放弃"删 Vite + vendor 拷 CSS"（初版决策 3/6）。** 三处硬伤串起来堵死了单工具链：shadow-cljs 3.x 要 Java 21（本机 scoop 只有 17，为此装了 21 并让构建单独换 PATH）；即便换成 2.x（最后的 2.28.23），它的手写 JS 解析器也读不了 `@copilotkit/core` 的 ES2022 私有字段（`this.#connectViaDelegate`）和 `@ag-ui/client` 的反引号 `require`；升到 3.5.1 后又在 `@upsetjs/venn.js` 的坏 exports 映射上翻车——该包 `exports["."].require` 指向一个**根本不存在**的 `./build/index.js`（Vite 走 `import` 条件所以一直没事），而 mermaid 本身 82MB / 130 个 chunk，本就该交给会做代码分割的打包器。结论：**让 Vite 继续干它擅长的事**。
2. **官方 `shadow-cljs-vite-plugin@0.0.9` 在 Windows 上不可用，两处独立缺陷且都不可配置。** ① `spawn("shadow-cljs", …)` 不带 shell：Windows 的 CLI 是 `node_modules/.bin/shadow-cljs.cmd`，libuv 不做 PATHEXT 解析、Node 又拒绝对 `.cmd` 无 shell 启动（EINVAL），实测 `spawn` 直接 ENOENT（连同样身为 `.cmd` 垫片的 `vite` 也 ENOENT，而 npm 用 shell 所以能跑）；② `generateStaticModule` 把 `path.resolve()` 的结果原样拼进 JS 字符串字面量 `export * from "C:\…\cljs-out\main.js"`，反斜杠被当作转义序列吃掉。改为自写约 40 行插件（拆成 watch / release / virtual 三个对象）。
3. **CSS 不能依赖模块内的副作用导入。** `@copilotkit/react-core` 的 `package.json` 确实声明了 `sideEffects: ["**/*.css"]`，其 v2 入口也确实 `import "./index.css"`，但实测产物里搜不到任何 `.copilotKitChat{`——这条副作用导入在生产管线里会丢。在 `index.html` 显式 import 一行即可，Vite 仍负责打包与 hash 命名，不需要 vendor 拷贝。
4. **一个自找的坑：不要给 `cljs-out` 配 `server.watch.ignored`。** 初版插件为了"不让 Vite 干扰 shadow-cljs 自己的 HMR"而忽略了输出目录，结果 Vite 的 module graph 只靠 watcher 事件失效，忽略之后**每个文件的首次 transform 变成永久**——浏览器永远拿到页面首次加载时的那个构建，改动看起来完全没生效（实测服务端返回 4211 字节的旧内容，磁盘上只有 1433 字节）。现在不忽略，Vite 感知到 `cljs-out` 变化即整页重载。
5. **放弃"不做浏览器端自动化验收"（初版非目标）。** agent-browser 在本机可用（`~/.workbuddy/binaries/node/.../agent-browser`，需绕过沙箱并直接调其 `bin/agent-browser.js`——它的 shell 垫片依赖 `sed`/`uname`，本机 bash 没有）。UI 验收因此**真的在浏览器里做了**，见"已验证"。

## 非目标

- 不重写内核——JVM 侧 `src/harness/*.clj` 一行不动
- 不抽 `.cljc` 共享层（内核与浏览器客户端暂不共用源码）
- 不改 AG-UI 协议行为、不改后端任何帧
- 不引入 reagent / re-frame / 路由 / 状态库——`ui/` 只有 169 行，加框架是反向优化

## 验收主线

1. `npm run build` 全绿：shadow-cljs release 编译 0 warning + Vite 打包成功，产出 `dist/`（含被 HTML 引用的 CopilotKit 样式表）
2. `npm run dev` 起在 **5173**；CopilotChat 渲染出聊天框，无控制台报错；npm 裸导入由 Vite 预打包接管
3. 审批链路行为不变：浏览器里标记会话 ⇒ write park ⇒ **helix 渲染的审批卡**出现 ⇒ 点批准则执行 / 点否决则回灌
4. `node verify-approval.mjs` 保持 13/13 PASS（它一行没改）——证明这次重构**没有碰协议**
5. 仓库里不再有 `.tsx` / `.ts` 源文件（`node_modules` 除外），`vite.config.ts` 与 `tsconfig.json` 已删
6. README 的前端章节、验证命令说明与新工具链一致

## 状态

三票均已落地（提交见 git log）：

- 01（工具链 + `main`/`app`）：已落地。自写 `ui/vite-plugin-cljs.mjs`、`ui/shadow-cljs.edn`、`ui/vite.config.js`、`ui/{index.html}`、`ui/src/harness/ui/{main,app}.cljs`
- 02（`approval-gate` 命名空间）：已落地。`ui/src/harness/ui/approval_gate.cljs`
- 03（删 TS 残留 + `.gitignore` + 收口）：已落地

## 已验证到什么程度

**离线/构建**：`npm run build` 全绿——shadow-cljs release（133 files，CLJS **0 warning**）+ Vite 打包，产物含被 HTML 引用的 `index-*.css`（90,201 字节，内容确为 CopilotKit v2 的 Tailwind v4 + `cpk:` 前缀样式表）。`npm run dev` 在 5173 起来，日志确认 `[cljs] shadow-cljs watch` 真的拉起、`Build completed`、npm 裸导入被 Vite 改写为 `/node_modules/.vite/deps/…`。

**浏览器（agent-browser + 真 Chromium，light 主题）**：

1. 页面渲染成功：`#root` 有子树，聊天框完整（"How can I help you today?" + 输入框 + 免责声明 + CopilotKit 徽章），样式正常，控制台**零报错**（此前 `Missing required prop` ×8 与 `<CopilotKitInternal>` 抛错均已消失）。截图：`ui-dev.png`。
2. 一轮真实对话贯通：发消息 ⇒ 后端 8080 ⇒ 流式返回"Thought for a few seconds" + "OK"。截图：`ui-conversation.png`。
3. **审批卡**：标记会话 ⇒ 让模型调 `write` ⇒ 运行 park，CLJS 渲染的卡片出现，内容完整（标题「需要你批准这次工具调用」、工具名 `write`、参数 `{"path":"/tmp/ui-approval-check.txt","content":"approved"}`、服务端 message、批准 / 否决两个按钮、底部说明）。
4. **批准路径**：点批准 ⇒ 卡片消失、run 恢复，模型立刻开始新一轮推理。
5. **否决路径**：点否决 ⇒ 模型收到 `"vetoed by human: the call was not executed."` 并据此改变行为（原文可见 "Possibly the tool call didn't go through due to some policy. … Let's try again."），随后**再次调用 write 并再次 park**——否决确实只作用于该次调用，run 照常继续。

**协议层未受影响**：`ui/*.mjs` 一行未改；`node verify-approval.mjs` 仍是 13/13 PASS。

**已知未覆盖**：`npm run build` 之后没有再用浏览器验一次 `dist/` 产物（dev 链路已验）；审批流程的浏览器验收依赖弱 Free 模型配合调工具，脚本里带重试（模型偶尔整轮只出 reasoning、不调工具）。
