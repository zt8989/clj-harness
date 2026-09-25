# 04 — 状态带先占住它那一行：数字没到不塌，到了也不顶

**What to build:** `ui/src/components/composer-stats.tsx` 那条带子的高度**从 composer 一出现就固定占
住**。数字（轮次 / 步数 / 速率 · token / 缓存命中）没到、或这一场根本没跑过时，它是一行**空高**；数字
到了，就地把这一行填满。**每帧都在 DOM 里**——所以 composer 的整体高度不因它而变，不再出现「先空、
后冒出来、整个 composer 往上顶一下」。

**Blocked by:** None — can start immediately.（与 03 票同批看，见决策 6。）

**Status:** 已落地（2026-09-25，分支 `mobile-adaptation`）。

## 现状与证据

`ui/src/components/composer-stats.tsx` 的 `ComposerStats`（`:47`）在 `cells === null` 时
`return null`（`:57`）——**整条带子不画**。`statsCells` 在两种情形给 null（`ui/src/lib/format.ts:174`）：

- `payload === null`（`:175`）：初次 `GET /api/threads/<stem>/stats` 还没回来，或这一场的记录读不到；
- `payload.turns === 0 && payload.steps === undefined`（`:176`）：这一场根本没跑过。

于是：刚打开、或刚发完第一条时，`ComposerStats` 什么都不画；数字回来，**整条冒出来**，`composer-frame`
（`bg-muted/40`，`composer-chrome.tsx` 的 `ComposerFrame`）就长高一行 → 停在底部的整个 composer 被向上
顶一下。数字还会分几次到齐（先轮次，后面 `rate` / `total` / `cached` 才陆续有），那是同一行内的增删，
只要高度固定就不动。

`ui/src/lib/format.ts:165` 那段注释（"the strip is not drawn at all for an empty session … is
furniture"）正是这次要**推翻的旧决定**：当时把「空会话不画占位」看得比「布局不动」重，现在反过来。

## 形状与决策

1. **给带子一个固定高度，不是 `min-height`。** 一个显式的 `h-*`（一说 `h-5`，20px，由走查定），内容
   在里头排；空的时候它就是这 20px 的空白。**固定高度**而非 `min-`，是为了连 03 票改字号也不带动
   composer。

2. **`cells === null` 不再 `return null`。** 容器照画，两个分组（`TimerIcon…` / `DatabaseIcon…`，
   `:67` 起）只在 `cells !== null` 时画。`data-slot="composer-stats"` 因此在有无数字两种状态下**都在
   DOM 里**，量高度时抓得到。

3. **不造数字。** 空档里不写 `0 tok`、不写占位符——那就是 `lib/format.ts` 说的 invented number。
   空就是空。

4. **注释同步改**：`lib/format.ts:165` 一带那段（"not drawn at all … furniture"）与 `statsCells` 的
   头注释，改成「带子先占住它那一行，空就是不画格子」。别让注释留在原地与代码说反话。

5. **只这一条带子。** `ContextRing` 是动作行里的一个行内图标，出现 / 消失不改行高，不在本票；
   `composer-context`（输入框上方那条目录 / 分支）也不在。

6. **与 03 票一起落。** 03 把字号降到 10px，本票把高度钉死；两票同批落，才会得到「高度从头到尾不变」。
   若 03 先落、本票后落也能各自成立，只是中间那一版还会动一次。

## 验收

- [ ] 打开一场**从没跑过**的会话：composer 下方有带子那一行的高度，且 `data-slot="composer-stats"`
      在 DOM 里；带子里没有任何格子，也没有 `0` 或占位符。
- [ ] 发第一条、这一轮跑完、数字回来：`composer-frame` 的高度**不变**——走查里量
      `getBoundingClientRect().height`，前后相等。
- [ ] 数字分几次到齐（先轮次、后 token / 缓存）时，带子高度不变、composer 不移动。
- [ ] 有数字后，`stats-turns` / `stats-steps` / `stats-rate` / `stats-usage` / `stats-cached` 照旧
      按各自有没有出现。
- [ ] `cd ui && npm test`、`npm run typecheck`、`npm run build` 全绿。
- [ ] 真浏览器走查：`node scripts/dev.mjs --scripted`，从「空会话」到「数字到齐」全程量 composer 高度
      不变，截图 / 数值进 `evidence/`。
- [ ] 本票只动 `ui/`，`clojure -M:test -m harness.test-runner` 的失败名单逐条不变。

## 落地

`ui/src/components/composer-stats.tsx`：`cells === null` 不再 `return null`；容器固定 `h-5`，数字到了
往这一行里画。`ui/src/lib/format.ts` 里「空会话不画带子 …furniture」那段注释改成「带子占住这一行」。

实测（真浏览器，390 / 360px）：新会话（`stats` 404）下 `data-slot="composer-stats"` 已在 DOM、高
**20px**；发一条、数字到齐后仍是 **20px**——带子从头到尾占同一行。从空会话到有会话时
`composer-frame` 会少 6px，那是**输入框上方那条 `composer-context` 按设计折掉**（`!started`），与本票
无关。截图 `evidence/03-04-composer-stats-360.png`、`03-04-composer-stats-390.png`。
