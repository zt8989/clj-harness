# 08 — CopilotKit 出局与文档收口

**What to build:** CopilotKit 从这个仓库里彻底消失：页面上没有它、依赖里没有它、产物里没有它的样式表。
同时把文档改到与新工具链一致，并记下这个特征取代了 `cljs-ui` 的哪几条决策。

这一票是扇入点：只有 04–06 都换完了，CopilotKit 才真的无人引用。删得掉，是前面那几票都真的换掉了的证据。

CLJS 一侧已经在 02 清干净了，本票只复核，不重做。Tailwind 与 shadcn 是本特征**有意引入**的，不是要清掉
的东西——本票要确认的是它们接线成文、抄来的组件可追溯。

**Blocked by:** 04, 05, 06（**07 已于 2026-09-15 置 `wontfix`**，被 `.scratch/project-sidebar` 取代。
它原本是本票扇入的一路——"项目目录面板的 CopilotKit 旧实现"——那份旧实现现在由 `project-sidebar` 04
连同顶部会话横排一并清掉，所以本票不再等它，扇入面小了一路。若 `project-sidebar` 先落地，本票的
"`ui/src` 下不再出现 CopilotKit"一条自然会连它一起扫过。）

**Status:** done（2026-09-15）

**落地说明**

- [x] `ui/src` 下不再出现 CopilotKit：三处历史注释（`app.tsx` / `message-parts.tsx` / `test/suites/client.ts`）
      改写为不点名包名的表述；旧装配、旧审批门、旧 reasoning 的 TS 胶水早在 03 已删，无残留、无尸体
- [x] 依赖清单移除 `@copilotkit/react-core`（`npm install` 随之清掉 408 个传递包）；`index.html` 里的
      CopilotKit 样式导入在 03 重写时已不存在，复核确认
- [x] 产物证明：`dist/` 下 grep `copilotkit`（大小写不敏感）与 `copilot` 均 0 命中。bundle hash 与 06 收拢后
      完全相同（`index-DlXUdPw-.js`）恰好是旁证——03 起页面就无引用，Vite 从未打包它，「还在打包只是没人用」
      从一开始就不成立
- [x] CLJS 复核清零：`ui/` 下 0 个 `.cljs`、无 `shadow-cljs.edn`、无 `cljs-test/`、`package.json` 无
      `shadow-cljs` 与 `helix`（02 号票的成果，本票只复核）
- [x] 样式侧成文：README 新增「样式体系（Tailwind v4 + shadcn）」一节——`src/styles.css` 是样式入口
      （CSS-first，无 tailwind.config）、`@tailwindcss/vite` 接进 `vite.config.js`、`components.json` 的
      registry 指向与别名约定、抄来的组件清单（elements 11 份 + ui 基件 7 份 + hooks 2 份）与对账基准
      （重装后 diff；本地差异只走 `THREAD_COMPONENTS` prop 与自建面板两个注入点）
- [x] `npm run build` 全绿：`tsc --noEmit` 0 error + Vite 打包成功
- [x] `npm test` 全绿：11 tests，四组用例仍驱动真 `@ag-ui/client`，审批链路端到端通过
- [x] README 前端章节与新工具链一致：开篇表述、架构条目、前置（Java 21/shadow-cljs 条目删除）、
      启动与构建、5173 CORS 契约成文（README + `vite.config.js` `strictPort: true` 双处保留）、
      会话/审批/项目三节的 UI 侧描述全部指向新组件路径
- [x] `cljs-ui/spec.md` 顶部记下作废记录：决策 1/2/3/5 作废、决策 8 已过期、
      「刻意不引样式体系」立场反转及理由；决策 6（5173 契约）仍然有效并注明
- [x] 本特征 `spec.md`：状态段补齐每票证据与未覆盖面；决策 6 的 03/06 实测结论已在其小节内；
      07 的去向已在状态/非目标/验收主线三处成文
- [x] 遗留风险点名：审批门（稳定 hooks，升级破法是编译失败而非静默失灵）、`adapters.threadList`
      （experimental + 双包返回值类型不一致）——均带「会话面板随 project-sidebar 搬侧边栏后，
      threadList 这条风险随之退役」的注明
