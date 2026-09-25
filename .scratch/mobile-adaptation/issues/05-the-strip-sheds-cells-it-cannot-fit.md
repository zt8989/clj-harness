# 05 — 底部那条带子：文案改短，挤不下时按序让出格子

**What to build:** `ui/src/components/composer-stats.tsx` 那条带子**不再靠折行消化宽度**：它固定在
一行里，行放不下时**按序让出整格**——先 `tok/s`，再会话总 token，最后轮数；**模型调用数与缓存共享
留着**（那是瞄一眼手机要看的两件事）。同时把中文那两格改短：`{{count}} 次模型调用` → `{{count}} 次
调用`、`{{value}}% 命中缓存` → `{{value}}% 缓存`。

**Blocked by:** None — can start immediately.

**Status:** 已落地（2026-09-25；分支 `stats-strip-fits`，已并入 `main`）。

## 现状与证据

报的现象：**手机底部，次数少的时候放得下，次数多之后就放不下了。**

代码位置 `ui/src/components/composer-stats.tsx:71`（03 票的 10px 落地之后）：

    className="text-muted-foreground flex h-5 items-center justify-between gap-4 px-1.5 pt-0.5 pb-1 text-[10px] tabular-nums"

03 票把字号降到 10px，让 360–430px 的屏**排在了一行**——那是拿**小数字**量的：一场刚起步的会话，
`1 轮 · 2 次调用 · 280 tok/s` 与 `1k tok` 在 390px 上合起来只占 209px（可用 331px）。数字一长就不同了：
同一份记录有 1026 次调用、123.8M token 时，五格在 360px（可用 301px）上**要 24px 高的内容**，
而这一行固定 20px——文字被允许折行，折出来的第二行就从 composer 的圆角盒子里**溢出去**，压在对话上
（两次读数都在 `evidence/05-strip-sheds-cells.md`）。

`h-5`（20px，内边距后 14px）是 04 票定的：**带子从第一笔就占住那一行**，数字迟到不许把 composer 顶动。
所以「放不下」在这里只能有一种解：行保持一行，**少画几格**。

## 形状与决策

1. **行拒绝折行**（`whitespace-nowrap` + `overflow-hidden`），于是「放不下」成了一个可测的事实：
   `scrollWidth > clientWidth`。让出格子之后重新量，直到放得下或无人可让。
2. **让出顺序是一张表**（`GIVE_UP`）：`rate` → `total` → `turns`。速度最先走（它是**新近**的读数，
   不是这场会话的成本），然后是总量，最后是轮数；**模型调用数与缓存共享永不让**。
3. **只在行宽变化时重新考虑**（`ResizeObserver`）：手机转向、右栏开合、侧栏收起——那是唯一能让格子
   回得来的事。内容变长走的是**每次渲染后**都量一次（数字是取回来的，行自身的宽度不会因为格子变长而动）。
   两条合起来才不会来回抖：让出一格正是行放得下的原因，若「放得下就补回来」，就会补了又让、无限循环。
4. **文案同时改短**，让每一格都更省：中文那两格去掉了「模型」与「命中」。**英文不动**——`2 steps` /
   `91% cached` 本来就是这个短度，两边仍然各说各的语言。`CONTEXT.md` 的词条（中文正文一律写「模型调用」）
   要跟上这一格：**条子上那格**写「调用」，正文里其它地方照旧。
5. **不改在哪儿**：五格的**数据**一个字节不动（`statsCells` / `lib/format.ts` / 服务端），`h-5` 不动，
   两组各自的图标与 `gap` 不动，`tabular-nums` 不动。

## 验收

- [x] 360px 窄屏：`composer-stats` 仍是**一行**，`scrollWidth ≤ clientWidth`，不折行、不溢出。
- [x] 挤不下时 `stats-rate`（必要时连 `stats-usage` / `stats-turns`）从 DOM 里让位，**不让半格**；
      `stats-steps` / `stats-cached` 一直在。
- [x] 行变宽回来（390 → 宽窗）：让出去的格子回来。
- [x] 中文带子读作 `1 轮 · 2 次调用 · 242 tok/s` / `2k tok · 91% 缓存`。
- [x] `cd ui && npm test`、`npm run typecheck`、`npm run build` 全绿。
- [x] 真浏览器走查：`node scripts/dev.mjs --scripted` + Playwright，1280 / 390 / 360 / 300 / 200px 五次
      视口变化，读数与截图进 `evidence/05-strip-sheds-cells.md`。
- [x] 让掉的格子**不在 DOM 里**（不是 `display: none`）：`data-slot="stats-rate"` 等读不到——读这些
      锚点的自动化要按「可能不在」写。
- [x] 本票没有后端改动。后端一轮在这台机器上**两次都撞到 1800s 的整轮上限**（`EXIT=2`：1096 个用例
      跑完、8 个命名空间没起跑），红的是超时型的用例——`kernel.tools-test` 抢 `child.pid` 抢输、
      `cap.mcp-test` 那条「挂住就该超时」；两次红的条数还不一样（1 条 / 3 条 + 1 个 error）——
      与这一票无关。**没有**在改动前的树上单独调一次基线来比。

## 落地

`ui/src/components/composer-stats.tsx`：行加 `whitespace-nowrap overflow-hidden`；新增 `GIVE_UP` 表与
`useShedCells`（ResizeObserver + 每次渲染后量一次），两格的文案读取改成「让出去的不画」；左侧的
秒表图标跟着轮数一起走，分隔符由 `Group` 按**画出来的那几格**生成。
`ui/src/locales/zh/format.json`：`stats.steps_other` / `stats.cached` 改短。
`ui/test/suites/stats.ts`、`CONTEXT.md`、`docs/architecture/client.md`：跟着改（两格的期望值与词条）。
证据脚本：`evidence/long-session.mjs`（往临时家目录那份 jsonl 追加 1024 对 `model/start` · `model/end`，
让一场会话在**真记录**上变成「1026 次调用 · 123.8M token · 85% 缓存」）。

实测（真浏览器）：360px 上 `clientWidth 301 · scrollWidth 301`，`stats-rate` 让位；390px / 1280px
五格都在且不溢出；300px 再让掉 `stats-usage`；200px 让掉轮数（秒表跟着走）；回到 390px 全部回来。
同一份内容用换掉的那两条 CSS 量：固定 20px 的行里装着 24px 的内容。

实测见 `evidence/05-strip-sheds-cells.md`。
