# 08 — CopilotKit 出局与文档收口

**What to build:** CopilotKit 从这个仓库里彻底消失：页面上没有它、依赖里没有它、产物里没有它的样式表。
同时把文档改到与新工具链一致，并记下这个特征取代了 `cljs-ui` 的哪几条决策。

这一票是扇入点：只有 04–07 都换完了，CopilotKit 才真的无人引用。删得掉，是前面四票都真的换掉了的证据。

CLJS 一侧已经在 02 清干净了，本票只复核，不重做。Tailwind 与 shadcn 是本特征**有意引入**的，不是要清掉
的东西——本票要确认的是它们接线成文、抄来的组件可追溯。

**Blocked by:** 04, 05, 06, 07

**Status:** ready-for-agent

- [ ] `ui/src` 下不再出现 CopilotKit；只服务它的那几处胶水（旧页面装配、旧审批门、旧 reasoning 组件
      的 TS 版本）删除，不留双份、不留注释掉的尸体
- [ ] 依赖清单里移除 CopilotKit 的包；`index.html` 里那行 CopilotKit 样式导入删除
- [ ] 打包产物里搜不到 CopilotKit 的样式前缀，也搜不到它的包名——证明不是「还在打包只是没人用」
- [ ] 复核 CLJS 侧确实清零：`ui/` 下没有 `.cljs`、没有 shadow-cljs 配置、没有 `cljs-test/`、
      依赖里没有 `shadow-cljs` 与 `helix`
- [ ] 样式侧成文：Tailwind 与 shadcn 的接线写进 README（`components.json` 的 registry 指向、样式入口
      文件、构建怎么接上），并列出抄进来的组件清单与各自被本地改过哪里
- [ ] `npm run build` 全绿：`tsc --noEmit` 0 error 加 Vite 打包成功
- [ ] `npm test` 全绿：四组用例仍驱动真 `@ag-ui/client`，审批那条链路仍端到端通过
- [ ] README 前端章节与新工具链一致：语言、依赖、启动、构建、5173 契约、样式体系、验收方式；开篇
      「验收用 CopilotKit v2 客户端」的表述改掉——它已经不再是真的
- [ ] `cljs-ui` 的 `spec.md` 里记下本特征作废了它的哪几条决策，并指向本特征：至少是决策 1
      （保留 CopilotKit v1.71）、决策 2（helix）、决策 3（shadow-cljs 桥）、决策 5（删掉 TS 版本）。
      同时注明 `cljs-ui` 那份「刻意不引样式体系」的立场也被本特征反转（本特征引入了 Tailwind + shadcn），
      并给出理由。顺带注明决策 8 提到的 `ui/*.mjs` 验证脚本今天已不在仓库里，那条决策本身已过期
- [ ] 决策 6（5173 是 CORS 契约不是偏好）在新特征里仍然有效，README 与代码都要留着这句话
- [ ] 本特征 `spec.md` 的「状态」补齐：每票验到了哪一步、留下哪些截图、哪些没覆盖；决策 6 补上 03 与 06
      的实测结论（threadId 到底归谁）
- [ ] 点名遗留风险：审批门压在实验性 `unstable_*` 接口上、会话面板压在 experimental 的
      `adapters.threadList` 上——升级 assistant-ui 时这两处是最可能的断点
