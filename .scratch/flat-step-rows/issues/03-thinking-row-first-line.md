# 03 — 思考行摊出想法首行

**What to build:** 思考那一行也把「这一步在说什么」摊在行上：
`[大脑图标] 思考 · <想法的第一行>`，形状与工具行逐字段相同（同一个 `py-1.5`、同一个字号、同一个
一点就开的 disclosure）。

从用户视角：模型在想什么，不点开也能读到一句；想知道后面写了什么再点开，那一下看到的是整段。

**Blocked by:** 02（「同一形状」是对着 02 那一行量的；本票只补行上的正文与它的取法）

**Status:** ready-for-agent

## 验收

- [ ] 行上的文字是 `思考 · <首行>`，标签用 `思考`（与参考界面一致，也是本特征唯一一个中文行标签；
      工具名照旧是 `read` 这样的原文）。展开仍是整段想法，点开点合都可。
- [ ] **首行怎么取**：从这一组 reasoning part 的正文里取**第一个非空行**（模型常常以换行开头，
      取第一行会取到空串），去掉首尾空白，**截到 120 字**加 `…`，再交给 CSS 单行省略兜底。
      本票把 120 这个数字与它的理由写在使用处。
- [ ] **预览的 selector 返回的是字符串，不是 parts 数组**：`useAuiState` 那一处返回算好的
      `string`（`useAuiState` 按值比较，字符串截满之后就不再变），**并且**在注释里写明这条理由——
      返回数组会让这一行在流式途中每个 token 重渲染一次。代码里能直接看出来是两种写法里的哪一种。
- [ ] **多段思考归一组**（同一个 `group-reasoning` 里可能有多个 reasoning part）时，
      预览取**第一段**的首行，不是把它们拼起来。
- [ ] **空想法**（part 的正文是空串或纯空白）时行上只有 `思考` 两个字：不写 `·`、不写空的摘要。
- [ ] **与工具行同形，逐字段量**：真机里同一轮的两个行
      `[data-slot="reasoning-trigger"]` 与 `[data-slot="tool-call-trigger"]` 的
      `fontSize` / `padding` / `getBoundingClientRect().x` / `height` **每个字段相同**
      （04 第 12.4 节量过一次，这次在 13px 之下再量一次并把数字写进本 spec 的落地记录）。
- [ ] 流式途中「还在想」的信号没丢：`reasoning-trigger-label` 的 class 仍带 `shimmer`，
      内容块仍带 `aria-busy`；run 结束后 shimmer 停止。
- [ ] 长思考**不把行撑高**：给一段 5000 字的思考，行的 `height` 仍是单行高度。
      截图 `.scratch/flat-step-rows/evidence/t03-01-thinking-row-preview.png`
      与 `t03-02-thinking-row-expanded.png`（点开之后是整段）。
- [ ] `data-slot` 名字照旧（`reasoning-trigger` / `reasoning-trigger-label` /
      `reasoning-trigger-chevron`…，chevron 按 02 的决定从静息行上去掉，slot 名不动）。
- [ ] 抄来的 12 份文件零改动（`git diff --stat ui/src/components/assistant-ui/elements/` 空）。
- [ ] `cd ui && npm run typecheck` 0 error；`cd ui && npm run build` 全绿；
      `cd ui && npm test` **11/11 passed**（`EXPECTED_CASES` 一个数都不改）。
