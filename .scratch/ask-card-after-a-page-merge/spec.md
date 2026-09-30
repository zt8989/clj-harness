# spec: 悬置的卡片在「页面跟着跑」的那条路上丢了自己

**一句话**：一个 park 住的 `ask`，卡片在**整页刷新**后画得出来，在**页面自己跟着 run 一路跑到 park** 时画不出来——
侧栏写着「等你回应」，点进去什么都没有。

**2026-09-30 修**。现场两个会话：`b92dfd61`（DSH goal 那场）、`62f30024`（`<system-reminder>` 那场）。

## 问题

1. **上游只认最后一条 assistant。** `AgUiThreadRuntimeCore.getPendingInterrupts()` 是
   `getMessages().findLast((m) => m.role === "assistant")`，再读它的
   `metadata.custom.agui.interrupts`。最后一条不是那条，`useAgUiInterrupts()` 就是空的，
   `ElicitationGate` 直接 `return null`——一个字节都不画。同一份读数还管
   `submitInterruptResponses`（`no pending interrupts on this thread`），所以「另外画一张卡绕过去」
   是错的：卡能显示，一按发送就抛。
2. **服务端两个门都是对的。** `GET …/page` 与 `POST …/rebuild` 都折成
   `[…, reasoning(r59), assistant(m60, interrupts)]`，id 各自独立；`/api/elicitation?interruptId=…`
   200。**整页刷新**走整表导入，卡片立刻回来。
3. **坏在 live 的窗口合并。** `lib/window.ts` 的 `merged` 对已经在手里的 entry **原地更新**，
   对**新** entry 一律 `additions.push` 追加到末尾。出事那两个会话的日志里
   **`REASONING_*` 帧数为 0**（全库 8509 条，别的会话都有）——思考只由折叠产生的
   `kernel-message` 记录带着，页面在 live 期间**没有** `r59` 而**有** `m60`，于是后到的页把 `r59`
   加在了 `m60` **后面**。顺序一坏，卡就没了。

## 决策

1. **顺序在 `lib/thread-messages.ts` 里修回来**，不在 `merged` 里：`merged` 是窗口的算术，
   而这是一条关于 park 的规则。`parkStaysLast` 只搬它必须搬的——**park 之后只剩思考**——
   其余形状原样放行。
2. **思考前移到 park 之前**，与服务端折叠的顺序一致：引出这个问题的思考，读在问题前面。
   什么都不丢（`parkStaysLast` 只换位，不删）。
3. **不新增一条画卡通路的。** 上游的读写两半是配套的；绕过 `getPendingInterrupts()` 只会得到
   一张按不动的卡。

## 落地

- `ui/src/lib/thread-messages.ts`：`parkStaysLast`（+ `carriesAPark` / `isThinkingOnly` /
  `PARK_NAMESPACE`），在 `toThreadMessages` 里接在 `keepCardParts` 之后。
- `ui/test/suites/thread-messages.ts`：新用例
  `a-park-stays-the-last-assistant-message-when-the-thinking-arrived-after-it`（票数 188 → 189）。
  用例的**红**先验过：摘掉那一行调用，顺序回到 `[u1, m60, r59]`，用例失败。
- **机器门**：`npx tsc --noEmit` 干净；`npm test` 189 例，**187 过 2 红**，两条红在干净 `main` 上
  逐条同名（`elicitation > a-servers-question-parks-the-run-and-the-answer-finishes-it`、
  `subagents > the-endpoint-answers-in-the-shape-both-screens-read`）；`npm run build` 过。
  第一条与本次同族但不是本次引入（它走 `mcp__fake__ask`，指向 config 收归之后的 MCP 落点），
  留给既有红账。
- **浏览器走查**（`node scripts/dev.mjs --scripted`）：live、切走再切回、整页刷新三条路都是
  1 张卡 + 4 个字段；把卡提交掉，答案真的落成那次调用的工具结果、run 接着跑完。
- **这一版走查到不了的那一格**：复现需要「长会话（entry 数 > 单页窗口 50）+ 模型不流
  `REASONING_*` 帧」两个条件同时成立，脚本 provider 两条都不满足（它把思考走进帧、会话也只有一步），
  所以走查验的是**没有回归**，不是**那条重排真的被触发**。那一条由单测钉住。
