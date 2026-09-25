# 01 — 手机端的设置是二级（列表 → 一页），不是左右两列

**What to build:** 窄窗打开设置时，弹窗里**只有一屏**：先是一份四页的列表
（General / Models / MCP servers / Subagents），点其中一页，列表**换成**那一页的内容，页首留一颗返回键
回到列表。宽窗保持今天的两列——左边导航常驻、右边是选中那一页——**一个字都不改**。

**Blocked by:** None — can start immediately.

**Status:** 已落地（2026-09-25，分支 `mobile-adaptation`）。

## 现状与证据

`ui/src/components/settings-panel.tsx` 的弹窗从 `custom-providers/06` 起就是两列：

- `DialogContent`（`data-slot="settings-panel"`，`:1587` 的 `sm:max-w-3xl`）
- 里面一个 `flex h-[min(30rem,62vh)] gap-4` 的两列格（`:1598`）
- 左列是 `data-slot="settings-nav"`，`flex w-36 shrink-0 flex-col gap-0.5`（`:1599`）——固定 9rem
- 右列是 `min-w-0 flex-1 overflow-y-auto pr-1`（`:1618`），四页之一由 `PAGES`（`:1516`）与
  组件状态 `page`（`:1514`）决定

窄窗下这变成：9rem 的导航先吃掉一块，`gap-4` 再吃一块，`DialogContent` 自己的
`max-w-[calc(100%-2rem)]`（`ui/src/components/ui/dialog.tsx`）又只留 2rem 边距——右边那一页剩下
不到 260px。而那一页里的东西不是为这个宽度写的：`Row` 是 `grid-cols-[7.5rem_1fr]`（`:130`），
`Field`、厂商表单、`<code>` 里的绝对路径全挤在这一条缝里。

`custom-providers/issues/06-settings-nav.md` 当时写的是「两列在窄屏下不重叠」，只验到 ~900px；
**手机宽度（360–430px）既没验过，也从没为它设计过 shape。**

## 形状与决策

1. **断点用 `sm`，与这个弹窗自己已有的那个类同一个数。** `DialogContent` 在 `sm` 从
   `max-w-[calc(100%-2rem)]` 放开到 `sm:max-w-3xl`，那正是它从「一屏」变成「装得下两列」的宽度。
   两列版挂 `sm:`（窄窗不画），二级版反过来（宽窗不画）。**不新增断点数字**，同一个 `sm` 写在同一份文件里。

2. **二级版的第一层就是那份导航本身**，不是又一个菜单。`PAGES` 已经是唯一一张页表，第二张表会是
   第二个要维护的地方：第一层把 `PAGES` 渲染成整行的按钮，选中一页后同一块位置换成那一页的内容
   ＋一颗返回键。

3. **返回键照 `SubagentsPage` 今天那颗写**（`data-slot="settings-subagent-back"`，`ArrowLeftIcon`，
   `:1418` 一带）：一次导航的退出是「回上一步」，不是关弹窗——关弹窗是右上角那颗 `X`。

4. **进出一页不重发请求。** 面板只在 `open` 变化时 `load()`（`:1575` 的 `useEffect`），切页、钻进去、
   返回来都不该触发第二次 `GET /api/settings` 或 `GET /api/providers`；返回列表也**不丢**已读到的东西。

5. **窄窗每次打开都回到列表这一层**（关掉就清掉「选中的那一页」）。宽窗保持今天的行为：默认停在
   `general`，跨开合保留。

6. **文案**：返回键要一句可译的词——新加 `panel.back`（en `Back` / zh `返回`），写进
   `ui/src/locales/en/settings.json` 与 `ui/src/locales/zh/settings.json`。**两份同增、并在调用点逐字
   出现**，否则 `ui/test/suites/i18n.ts` 的两条（`both-catalogs-say-the-same-things`、
   `every-catalog-entry-is-named-by-something`）会红。页名沿用现有 `page.*`，**不新造**。

7. **不做**：不引路由 / URL（`page` 是组件状态，这条从 06 号票起就定了）；不给不存在的功能造页；
   不动宽窗的两列与 `data-slot`。

## 验收

- [ ] 宽度 < 640px（真机或 DevTools 的手机宽度）打开设置：
  - 首屏是四页的列表，**不并排画任何一页的内容**；
  - 点一页 → 列表消失、那一页占满、页首有返回键；点返回 → 回到列表；
  - 列表 ↔ 内容来回不重发 `GET /api/settings` / `GET /api/providers`；
  - 关掉再打开，停在列表这一层。
- [ ] 宽度 ≥ 640px：与改动前一致——左列导航常驻、右列是选中页、默认停在 General，
      `settings-nav`、`settings-nav-*`、`settings-page-*` 这些 `data-slot` 一个不少。
- [ ] 新加 `data-slot` 沿用现有命名（例如 `settings-nav-list`、`settings-page-back`）；宽窗那颗
      返回键不画，窄窗那份两列导航不画。
- [ ] en / zh 两份 `settings.json` 同步；`cd ui && npm test`（含 i18n 两套用例）全绿。
- [ ] `cd ui && npm run typecheck` 0 error、`npm run build` 全绿。
- [ ] 真浏览器走查：`node scripts/dev.mjs --scripted`，手机宽度与桌面宽度各走一趟，截图进 `evidence/`。
- [ ] 本票只动 `ui/`，`clojure -M:test -m harness.test-runner` 的失败名单逐条不变。

## 落地

`ui/src/components/settings-panel.tsx`：`SettingsPanel` 多一个 `drilled` 状态（窄窗在第几级），`nav`
改 `hidden ... sm:flex`；右列里新增 `settings-nav-list`（第一级，由同一张 `PAGES` 渲染）与
`settings-page-back`；页面内容包一层 `className={drilled ? "block" : "hidden sm:block"}`，宽窗照旧两列。
关闭弹窗时 `drilled` 复位。`panel.back` 进 en/zh 两份 `settings.json`。

实测（真浏览器 `node scripts/dev.mjs --scripted`）：390px 打开设置 → 列表占满右列（322px、四行），
`settings-nav` 不可见、无返回键；点 Models → 列表消失、返回键（“返回”）在、Models 页在；点返回 →
回列表。1280px：`settings-nav` 144px 常驻、列表不可见、无返回键、页面在。打开面板只发一次
`GET /api/settings` + 一次 `GET /api/providers`，进出页面不再发。截图 `evidence/01-settings-*.png`。
