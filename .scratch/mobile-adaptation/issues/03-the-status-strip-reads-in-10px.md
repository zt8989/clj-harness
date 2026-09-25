# 03 — composer 底部那条状态带读 10px，别在手机上折成两行

**What to build:** `ui/src/components/composer-stats.tsx` 那条带子（轮次 / 步数 / 速率 · token /
缓存命中）的字号从 `text-xs`（12px）降到 **10px**，让它在手机宽度下**一排读完、不折行**。

**Blocked by:** None — can start immediately.

**Status:** 已落地（2026-09-25，分支 `mobile-adaptation`）。

## 现状与证据

`ui/src/components/composer-stats.tsx:65`：

    className="text-muted-foreground flex items-center justify-between gap-4 px-1.5 pt-0.5 pb-1 text-xs tabular-nums"

两层各一组：左边 `TimerIcon` ＋轮次 / 步数 / 速率（`stats-turns` / `stats-steps` / `stats-rate`），
右边 `DatabaseIcon` ＋ token / 缓存（`stats-usage` / `stats-cached`），中间 `justify-between gap-4`。
`text-xs` 是 12px，两组在 360–430px 的屏幕上排不下、就折成了两行——而这条带子本来是一行一条读数的
地方，折行之后「哪一格属于哪一组」就散了。

仓库里 10px 已经是既有档：`settings-panel.tsx` 里 `data-slot="settings-providers-without-keys"`
那段提示就是 `text-[10px]`。所以这不是新数值。

## 形状与决策

1. **只改字号**：`text-xs` → `text-[10px]`（逐字量，不用自定义 token）。
2. **图标大概率要跟着小一号**：今天 `TimerIcon` / `DatabaseIcon` 都是 `size-3.5`（14px）。10px 的字配
   14px 的图标，行高由图标定，可能仍不省。走查时若还折行或顶高，把它们降到 `size-3`（12px）——
   以「一排读完」为准，别为了对齐数字去动 `gap` / `justify-between`。
3. **`tabular-nums` 保留**：数字会变，等宽数字是这条带子不抖的原因（`:62` 的注释）。
4. **不动别处**：输入框上方那条 `composer-context`（目录 / 分支）不在此票；`settings` 里 10px 的用法
   也不动。

## 验收

- [ ] 宽度 360px：`composer-stats` 是**一行**——轮次、缓存命中两组都在，不折行、不溢出、不裁切。
- [ ] 390×844 与 ≥640px 各看一眼：桌面宽度下与改动前只差字号，布局不塌。
- [ ] `data-slot="composer-stats"` / `stats-turns` / `stats-steps` / `stats-rate` / `stats-usage` /
      `stats-cached` 一个不少。
- [ ] `cd ui && npm test`、`npm run typecheck`、`npm run build` 全绿。
- [ ] 真浏览器走查：`node scripts/dev.mjs --scripted`，手机宽度截图进 `evidence/`（要能数出只有一行）。
- [ ] 本票只动 `ui/`，`clojure -M:test -m harness.test-runner` 的失败名单逐条不变。

## 落地

`ui/src/components/composer-stats.tsx`：带子 `text-xs` → `text-[10px]`。

实测（真浏览器，360 / 390px）：带子 `fontSize` 10px，两组 cell 同一 `top`（一行），不折行；宽窗
只差字号。截图 `evidence/03-04-composer-stats-360.png`、`03-04-composer-stats-390.png`。
