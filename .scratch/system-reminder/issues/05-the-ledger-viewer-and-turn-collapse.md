# 票 05：轨迹视图改画账本，折轮带上 context

Blocked by: 04。

## 目标

`trajectory-view.tsx` / `trajectory-timeline.tsx` 按 dsh 的样子画**一条平铺的账本**：
粗分割线标 `Turn N` 与 `Between turns`，工具栏能 `Collapse turns` / `Expand turns`；
**一轮折上时，轮内的 `context` 与 `message` / `tool` 一起收进摘要行**——这就是牛总那句
「按轮次折叠的时候要把上下文注入一起折叠」。

## 照搬依据

dsh 的 `lib/client.js`：`sectionLabel(turn)` → `Turn N` / `Between turns`；`summarizeTurn(records)` →
`N steps · M tool calls`；`toolbar.collapseTurns` / `toolbar.expandTurns` / `collapseCalls` / `expandCalls`；
`system` 格标 `Initial System Prompt`，assistant 格标 `Message`。折叠摘要的 `collapsedSummaryKind` 是
`'turn' | 'assistant'`。

## 改哪里

1. `ui/src/components/trajectory-view.tsx`：
   - 列表遍历 `payload.cells`（不再是 `turns → items`），按 `:turn` 分组，`turn-start` / `turn-end` 画成
     轮头与轮尾的粗分割线，`:turn nil` 的格归 `Between turns` 区段；
   - `system` 格画在最上，不在任何轮的折叠里；点开仍是「提示词 / 工具表」两个 tab；
   - 工具栏加 `Collapse turns` / `Expand turns`（默认展开，与 dsh 同）；
   - 折上的一轮只留轮头 + 一行摘要；`context` 与其余格一样被收起来。摘要的 `N steps · M tool calls`
     走 `turn.steps` / `turn.calls` 的现有读数（或就地数），别新造一套。
2. `ui/src/components/trajectory-timeline.tsx`：时间线的道按 `:kind` 画；`turn-start`/`turn-end` 不进道
   （它们是边界）。
3. i18n（`zh` / `en` 两份 `trajectory.json`）：`turn.label`、`between.label`、`summary.turn`、`toolbar.collapseTurns`
   / `expandTurns` / `collapseCalls` / `expandCalls`、`system.initial`。
4. 搜索：`query` 的命中判断从 `JSON.stringify(item)` 改成 `JSON.stringify(cell)`；`context` 格照旧可搜。

## 判据

- 前端套件（`ui/test/suites/`）：给定一份手搓账本，折上一轮后该轮的 `context` 格**不在**可见行里；
  展开后回来；`system` 格与 `Between turns` 的格**不随任何轮折叠**。
- `ui && npm run typecheck && npm test && npm run build`。
- `node scripts/dev.mjs --scripted` 起服务、开浏览器：
  1. 打开轨迹 → 最上是 `Initial System Prompt`，下面 `Turn 1` 的分割线；
  2. 折上 `Turn 1` → 注入的 `context` 格跟着消失，只剩摘要行；展开回来；
  3. 有独立压缩那种记录时，它落在 `Between turns`。

## 不做

- 不做虚拟滚动 / 补页（本仓有自己的走查）。
- 不动会话栏（`turn-steps.tsx` 的 `A CARD IS NOT A STEP` 保持）。
