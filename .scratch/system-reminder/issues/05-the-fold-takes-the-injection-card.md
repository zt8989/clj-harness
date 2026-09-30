# 票 05：折轮时把注入卡一起折进去

Blocked by: 无。

## 现状

`ui/src/components/turn-steps.tsx` 的注释把现状写死了：

> A CARD IS NOT A STEP … `thread.aui.tsx` draws a card part even in a folded step or head.

所以一轮折上之后，注入卡仍留在屏幕上（除非它是那一轮的「结论」）。牛总要它**跟着轮一起折**。

## 改哪里

1. `ui/src/lib/turns.ts`：
   - `turnBounds`（或新增一个读法）把**属于本轮的注入卡**认进轮的边界：一张 `injected-context`
     的 card-only 消息，若紧贴 `first` 之前、或在 `first..last` 之间，就算这一轮的一部分。
   - 判据是**结构性**的（`lib/card-parts.ts` 的 `isCardPart` / `isCardOnly` 已有），不引第三套名字。
2. `ui/src/components/turn-steps.tsx`：
   - `useStepFold` 之外，加一个「这张卡该不该画」的读法：轮折着且卡属于本轮且不是结论 ⇒ 不画。
   - 摘要行的步数**不变**（`turnCounts` 只数 assistant 消息；卡不是步）。
3. `ui/src/components/assistant-ui/thread.aui.tsx`：卡片 part 的绘制条件接上上面那个读法。
   **压缩卡（`compacted-context`）保持现状**——它是折叠边界，`.scratch/compaction-frames` 刻意让它折了也画。

## 判据

- `ui/test/suites/turns.ts`：给一个用户消息 + 若干 assistant 消息 + 一张夹在中间的注入卡：
  折着时卡被隐藏、结论仍画；展开后卡回来；步数不变。
- 压缩卡在同一组用例里**仍画**（把这条反向断言写在旁边，防止顺手改掉）。
- `ui && npm run typecheck && npm test && npm run build`。
- `node scripts/dev.mjs --scripted`：一轮里制造一条注入（`/name` 或等一条后台作业结束），
  轮折上后注入卡消失、展开又有。

## 不做

- 不动压缩卡。
- 不动「运行中的轮不折」的规矩。
