# 01 — 工具调用平铺：去掉「1 tool call」那一行

**What to build:** 助手消息里不再有那层组壳。一次 run 做的 N 个工具调用就是**相邻的 N 行**，
每行前面没有「N tool call」这个数字，也没有可折叠的组。

从用户视角：一段「想一下 → read → 再想 → anchor_grep → 再想 → bash → 回答」的回合，
页面上从上到下就是这些步骤本身；`1 tool call` 这种既不是步骤、也不是内容的行消失。
**它今天恒为「1」**——一次工具调用在线上就是一条独立的助手消息
（`.scratch/assistant-ui/issues/04` 第 8 节实测），而上游的 `groupPartByType` 连只有一个 part 的
run 也会分组，所以页面上是「每个调用前面多一行」，不是「把 4 个调用收成 1 行」。理由与代价见
`spec.md` 决策 1 与决策 9。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

## 验收

- [ ] `THREAD_COMPONENTS.ToolGroup` 换成一个**只渲染 children 的透传**（`group` 收了不用），
      并在那一处写明：组节点还在树上（我们不为这件事去改抄来的 `thread.aui.tsx` 里那个 `groupBy`
      数组），但这一层不画任何东西。
- [ ] `ToolCallsGroup` 连同它为 `defaultOpen` 一个 prop 而写的注释一起删掉；
      `ToolGroupRoot` / `ToolGroupTrigger` / `ToolGroupContent` 三个导入从 `message-parts.tsx` 里
      删干净：`grep -n "ToolGroupRoot\|ToolGroupTrigger\|ToolGroupContent\|ToolCallsGroup"
      ui/src/components/message-parts.tsx` **零命中**。
- [ ] `tool-group.aui.tsx` 这份**抄来的**文件**不删**（`thread.aui.tsx` 仍导入它里那三个件），
      也不改：`git diff --stat` 里 `ui/src/components/assistant-ui/elements/` 一项都没有。
- [ ] 组的 `gap-1`（4px）随组一起去掉：相邻两行由各自的 `py-1.5` 隔开。
      真机量相邻两个 `[data-slot="tool-call-trigger"]` 的间距为 12px（`getBoundingClientRect` 相减），
      并截图留档 `.scratch/flat-step-rows/evidence/t01-01-rows-flat.png`。
- [ ] 真机（脚本化后端，一个回合 3 个工具调用）：`document.querySelectorAll('[data-slot="tool-group-trigger"]')`
      长度 **0**，`[data-slot="tool-call-trigger"]` 长度 **3**，且三行的文字各自是 `read` / `anchor_grep` /
      `bash`。
- [ ] 每行仍能点开参数与结果、再点收起（`data-state` 在 `closed` / `open` 之间来回）——
      本票只删壳，不动行的可折叠性。
- [ ] `cd ui && npm run typecheck` 0 error；`cd ui && npm run build` 全绿；
      `cd ui && npm test` **11/11 passed**（本票不碰协议与运行时；驱动里那个 `EXPECTED_CASES` 一个数都不改）。
