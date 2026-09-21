# 01 — 一个项目（与任务那一块）最多画 5 行（前端）

Status: done

## 做什么

- `lib/sidebar-rows.ts`（新，零 import）：`ROWS_BEFORE_FOLD = 5` 与
  `foldRows(rows, isCurrent, wantsAll) -> {drawn, hidden, forced}`——前 5 行、折起来的行数、
  以及「展开是因为当前会话排在第 6 个之后」这一格。
- `components/sidebar.tsx`：
  - 任务块：`useState` 记「人点开了」，画 `drawn`；`hidden > 0 && !forced` 时在 `<ul>` 之后画折叠控件
    （`data-slot="sidebar-tasks-more"`）。
  - `ProjectSection`：同上（`data-slot="sidebar-sessions-more"`），状态跟着这个项目。
  - 折叠控件是一行按钮：左边一个 `size-3.5` 的空槽（文字与行标题同一个 x）、`aria-expanded`、
    箭头折起朝右 / 展开朝下，样式与「已归档」那颗同一套。
  - `forced` 时**不画**控件：能收起就等于把正在读的那一行藏起来。
- 词表：`session.showMore`（`还有 {{count}} 个` / `{{count}} more`，en 带 `_one`/`_other`、
  zh 只有 `_other`）与 `session.showLess`（`收起` / `Show less`）。

## 验收

- 一个项目有 6 条以上（未归档）时，最多画 5 行 + 一行折叠控件；点开画全、控件变 `收起`；再点收回 5 行。
- 任务那一块同一条规矩；「已归档」不限。
- 当前会话排在第 6 个之后时那一块画全、且没有折叠控件（把窗口刷新一遍更明显：它仍看得见自己那一行）。
- 每块各记各的状态；状态不落盘。
- `npm test` / `typecheck` / `build` 绿。

## 落地

- `lib/sidebar-rows.ts`（新，零 import）：`ROWS_BEFORE_FOLD = 5` 与
  `foldRows(rows, isCurrent, wantsAll) -> {drawn, hidden, foldable, forced}`。
  **多了一个票里没有的字段 `foldable`**，理由是实现时撞出来的：控件的条件是「这块有没有折叠」，
  不是「现在折了几个」——用 `hidden > 0` 的话，点开之后 `hidden` 变 0，控件自己消失，
  **就再也收不回去了**。所以「有没有折叠」与「折了几个」是两个问题，前者答 `foldable`、
  后者答 `hidden`（标签用）。
- `components/sidebar.tsx`：任务块 `tasksAll`、`ProjectSection` `allShown`，各记各的；
  两块都画 `drawn`，`foldable && !forced` 时画共用的 `RowFold`
  （`data-slot="sidebar-sessions-more"` / `"sidebar-tasks-more"`）。`forced` 时不画控件。
- `RowFold` 的标签落在**行标题的 x** 上：`ps-2`(8) + 槽位上的 chevron（`size-3.5` 14px）+
  `gap-1.5`(6) = 28，加上 nav 的 `px-2`(8) 就是 36——与行同一个算式，走查量到 `rows 36 vs control 36`。
- 词表：`session.showMore`（`还有 {{count}} 个` / `{{count}} more`，en `_one`/`_other`、
  zh `_other`）与 `session.showLess`（`收起` / `Show less`）。
