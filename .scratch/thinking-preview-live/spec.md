# spec: 一个回合里的每一行思考都要说出自己的首行

**一句话**：跑起来的一回合，adapter 把整轮的东西堆在**一条** assistant 消息里（思考、答案正文、
工具调用都在同一个 `parts`），`thoughtAt` 却按**整条消息**判断「有没有工具调用」——于是一出现
工具调用，这一轮里**每一行思考都被读成空**。修法：这一行自己的 part 区间
（`GroupPart.indices[0]`）交给 `thoughtAt`，**当前消息按 part 逐个读**（工具调用 part 结束一个
想法，正文不结束），后面的消息照旧整条读。

**主人原话**：*「除了第一次发送的时候思考中是跑马灯，后续思考中都显示空」*。

2026-09-23 立。分支 `fix/thinking-preview-live-empty`，工作树
`.worktrees/thinking-preview-live-empty`，从 `main` @ `785f889` 切出。这是
`.scratch/thinking-row-tail`（思考行：不自己展开、流式那段在行上拖出滚动、一个想法一行）的补丁。

## 问题

- **现场**：`.scratch/thinking-row-tail` 上线后的真会话。第一行思考（正文还没到、只有 reasoning
  时）显示滚动窗口；一旦工具调用进了这条消息，屏幕上**每一行 `思考` 后面都是空的**（截图为证）。
- **根因**在 `ui/src/lib/reasoning-preview.ts` 的 `thoughtAt`：它用 `isStep(message)` 问**整条
  消息**里有没有 tool-call。运行时（`@assistant-ui/react-ag-ui` 的 `RunAggregator`）在一次 run
  里**只开一条** assistant 消息（`emitUpdate` → `updateAssistantMessage`），把每个 round 的
  reasoning / text / tool-call 都 append 进同一条 `parts`。于是「消息里有工具调用」恒真，前向
  行走第一步就 `break`，`parts` 恒空，`preview` 恒为 `""`。
- **为什么第一行还看得见**：只有 reasoning、还没有正文/工具调用时，那条消息**不含** step，行走
  照常；而且 reasoning part 是消息最后一个 part，`toMessagePartStatus` 给它 `running`，于是尾窗
  （跑马灯）画了出来。工具调用一到，全灭。

## 决策

1. **行的身份是 part 区间，不是整条消息。** `ReasoningGroup` 拿到的 `GroupPart` 已经带着
   `indices`（这一段 reasoning parts 在消息里的下标）。`ReasoningBlock` 把 `group.indices[0]`
   传给 `thoughtAt(messages, index, from)`。
2. **当前消息按 part 读，别的消息照旧整条读。** 前向从 `from` 开始扫：`tool-call` /
   `standalone-tool-call` part 结束一个想法，`text` 不结束；扫完这条消息再往后整条消息走。后向
   同理：当前消息里 `from` 之前的 part 先扫（step 先到 ⇒ 新想法；reasoning 先到 ⇒ 续行）。跨
   消息仍用 `isStep(message)`——那里运行时已经按 round 拆过。
3. **`running` 的判据不改**：仍是「这一段 reasoning 的 status 是 running」。live 消息里只有最后
   一个 part 会拿到 `running`，所以正在写的那一行滚，停下来的回到首行——和之前一致。

## 判据与实测

- `ui/test/suites/reasoning-row.ts` 新增一例 `a-live-run-keeps-a-whole-turn-in-one-message`：
  一条消息 `[思考1, 正文, tool-call, 思考2]`，`thoughtAt(live, 0, 0)` 只收思考1、
  `thoughtAt(live, 0, 3)` 只收思考2；正文夹在两段 reasoning 之间时仍是**一个**想法
  （后一段 `drawn=false`）。
- **真浏览器**（`.scratch/thinking-preview-live/`，`check.mjs`）：三段「思考 + 工具调用」的脚本，
  三行 `思考` 各自说出首行；**回退两处源码后重跑，三行 subject 全空**（复现），再装回全非空。
- `cd ui && npm test` **113 绿**（`EXPECTED_CASES` 112 → 113）；`npm run typecheck` 绿；
  `.scratch/thinking-row-tail/walkthrough.mjs` **24 条 GREEN**（尾窗行为未回归）。
