# 01 — 品牌行：logo + 产品名 + 收起那颗搬进来

Status: done

## 做什么

`ui/src/components/sidebar.tsx` 的 `<aside>` 里，在今天的 `<header data-slot="sidebar-header">`
**上面**加一行 `<div data-slot="sidebar-brand">`：内联 SVG 记号 + 产品名 `clj-harness`，
末位是**从 header 搬过来**的 `<SidebarCollapseButton/>`（`ms-auto` 顶到行尾）。
header 里那一颗随之删掉，剩下 `New task` / 加项目 / 刷新三件。

记号与产品名抽成新模块 `ui/src/components/app-brand.tsx`（`export const AppBrand`）：

- 记号是**内联 SVG**（`viewBox="0 0 16 16"`、`size-4`、`stroke="currentColor"`、
  `aria-hidden="true"`、`data-slot="brand-mark"`）：两条相向的弧（Lisp 的括号）夹一个实心圆点，
  读作 `( • )`——这门语言把内核括在括号里。
- 产品名是一个 `<span data-slot="brand-name">`，字面量 `clj-harness`，**不进词表**（spec 决策六：
  专有名词，两语同名；进了 `shell.json` 就得让 i18n 套件的同值条目过审）。
- 品牌行**不是链接**：这个页面没有「首页」可去，一个点了不动的 `<a>` 比一个 `<span>` 更坏。

## 不要做什么

- **不动 `SidebarOpenButton`**：浮标继续由页面画（spec 决策二）。
- **不动 `aria-controls` / `aria-expanded` 那对契约**：`sidebar-toggle.tsx` 的注释与两处
  `aria-*` 一个字不改，这一票只搬位置。
- **不加 `border-b`**：demo 的品牌行有下边框，这个侧边栏通篇没有一条横线，加上它会成为唯一一条。
  抄结构，不抄边框。

## 验收

- `data-slot="sidebar-brand"` 在 `data-slot="sidebar-header"` **之前**（DOM 顺序 = 视觉顺序）。
- `SidebarCollapseButton` 只出现一次，且它的源码位置在 brand 行那一块文本里。
- 品牌行在 `<aside>` 内 ⇒ 折起来时与整个侧边栏一起 `hidden`（spec 决策二/走查都要这一条）。
- `cd ui && npm run typecheck` 0 error；`npm test` 里 `sidebar` 套件那例绿（见 04 的
  `EXPECTED_CASES` 说明）。

## 落地（2026-09-21）

`ui/src/components/app-brand.tsx`（新）、`ui/src/components/sidebar.tsx`（品牌行 + header 里那颗删掉）、
`ui/src/components/sidebar-toggle.tsx`（收起那顆加 `ms-auto`，模块头那段「在 header 里」的话改写成品牌行末位）、
`ui/src/lib/session-title.ts` 提供 `PRODUCT_NAME`。走查量到：记号 x=10、名 x=34、收起那顆 x=245、同一行 y；
`New task` 整行在 y=48 之下。
