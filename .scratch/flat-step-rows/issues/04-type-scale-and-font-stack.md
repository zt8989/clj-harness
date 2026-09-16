# 04 — 字号与字体：步骤行 13px、正文 14px、系统字体栈

**What to build:** 对话区的字号收成两档——**正文 14px、步骤行（工具行与思考行）13px**，
参数与结果块 12px 不动；全站字体由装进来的 Geist 换成参考界面那一串系统栈。

从用户视角：中文在系统字体下渲染，不再先下 100+KB 的 webfont；助手回答是 14px，
一行行的工具/思考比正文再小一号，主次一眼看得出。

**Blocked by:** None — can start immediately（与 01–03 互不阻塞：行在不在、长什么样，都不影响字号；
**但 02/03 的行是这一票量 13px 的对象**，两边同时开工要合一次）

**Status:** ready-for-agent

## 验收

- [ ] `ui/src/styles.css` 的 `--font-sans` 换成
      `-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC", "Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Helvetica, Arial, sans-serif`
      （`@theme inline` 里那一行就地替换，不新造 token）。
- [ ] Geist 彻底删掉，三处一起：`@import "@fontsource-variable/geist"`、`package.json` 里的
      `@fontsource-variable/geist`、以及 `--font-sans` 里对它的引用。
      `grep -rn "geist\|Geist" ui/src ui/package.json ui/index.html ui/vite.config.js` **零命中**
      （`package-lock.json` 与 `node_modules` 不算：前者 `npm install` 后只少 Geist 那一段，
      后者是缓存，不进 git）。
- [ ] **正文 14px 从 `styles.css` 给**，不去改抄来的文件：那个 div 是 `thread.aui.tsx` 里的，
      它只有 `data-slot="aui_assistant-message-content"`、没有 `aui-*` class 可挂，所以
      `styles.css` 里加一条按 `data-slot` 命中的规则，并在旁边写明为什么落在样式表而不是文档流的那份文件里。
      `git diff --stat ui/src/components/assistant-ui/elements/` **空**。
- [ ] **步骤行 13px 写在使用处**：工具行与思考行各自 `text-[13px]`（替换今天的 `text-sm`），
      不新造 `text-step` 之类的 token——两个数字写在各自的组件里，理由写在注释里。
- [ ] **参数与结果块 12px 不动**（`text-xs` 照旧）；**思考的正文跟着正文 14px**
      （点开之后读的是一段话，不是载荷）。
- [ ] **不动的地方逐条量过**（真机，`getComputedStyle(...).fontSize`）：
      用户气泡仍是 16px、composer 仍是原来的字号、侧边栏与设置面板不变；`pre` / `code` 的
      `font-family` 仍是 mono 家族（换的是 `--font-sans`，不是 `--font-mono`）。
- [ ] 真机量到的数逐个对：工具行 **13px**、思考行 **13px**、正文 **14px**、
      参数块 **12px**、`getComputedStyle(document.body).fontFamily` 的**开头是 `-apple-system`**；
      `[...document.fonts].filter(font => /Geist/i.test(font.family)).length === 0`，
      且 `performance.getEntriesByType("resource")` 里没有 `geist` 的 `.woff2`。
      数字与截图写进 `spec.md` 的落地记录，截图
      `.scratch/flat-step-rows/evidence/t04-01-type-scale.png`、`t04-02-chinese-and-code.png`
      （一行中文 + 一个代码块，中文不出现方框、代码块仍是等宽）。
- [ ] `cd ui && npm install` 跑过（lock 跟上），随后 `cd ui && npm run typecheck` 0 error、
      `npm run build` 全绿（构建产物的 CSS 体积应比改动前明显小——少了一个 webfont 的 `@font-face`）、
      `npm test` **11/11 passed**。
