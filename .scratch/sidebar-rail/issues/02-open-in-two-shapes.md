# 02 — 展开这颗的两种 shape，与只在窄窗的让位

Status: done
Blocked by: 01

## 做什么

`ui/src/components/sidebar-toggle.tsx`：

- `SidebarOpenButton` 加 `shape?: "corner" | "rail"`（默认 `corner`）。
  - `corner`：今天那套浮标类，**加 `lg:hidden`**——宽屏折叠时列还在，浮标没有意义。
  - `rail`：`absolute inset-0 m-auto size-8 p-0 opacity-0 transition-opacity
    group-hover/brand:opacity-100 focus-visible:opacity-100`（母格由 01 提供 `group/brand` 与 `relative`）。
- 模块头那段「浮标是手机的」要改成真的：它只在窄窗画；宽屏的出口在 rail 顶格那一格。

`ui/src/components/sidebar.tsx`：

- `SidebarProps` 加 `onExpand: () => void`，两处用：rail 顶格那颗、以及 `addProjectNow` 里
  `PickerUnavailableError` 那一支（先 `onExpand()` 再 `setTypingPath(true)`）。

`ui/src/app.tsx`：

- `<Sidebar ... onExpand={() => setFolded(false)} />`。
- 顶栏让位：`folded && "ps-12"` → `folded && "ps-12 lg:ps-3"`，注释说明它只给浮标让位。
- 折起来时仍渲染 `<SidebarOpenButton/>`（宽屏由 `lg:hidden` 收掉），注释同步。

## 验收

- 宽窗折叠：浮标 `display: none`；顶栏 `padding-inline-start` 回到 12px。
- 窄窗折叠：浮标在 (8,8)、顶栏 44→48px 的清空仍在。
- rail 顶格 hover：展开那颗显形、可点、`aria-controls` = `SIDEBAR_ID`、`aria-expanded="false"`。
- 键盘 Tab 能走到它（`focus-visible` 让它显形）。

## 落地

`ui/src/components/sidebar-toggle.tsx`：`SidebarOpenButton` 加 `shape?: "corner" | "rail"`（默认 `corner`）；
`corner` 多一个 `lg:hidden`，`rail` 是 `absolute inset-0 m-auto size-8 p-0 opacity-0 transition-opacity
group-hover/brand:opacity-100 focus-visible:opacity-100`。模块头那段「浮标是手机的」重写成两种 shape。
`sidebar.tsx`：`SidebarProps` 加 `onExpand`，rail 顶格那颗与 `addProjectNow` 的
`PickerUnavailableError` 一支都用它（后者先展开再给表单）。
`app.tsx`：传 `onExpand`；顶栏 `folded && "ps-12"` → `folded && "ps-12 lg:ps-3"`；折起来时仍渲染
`<SidebarOpenButton/>`（宽窗由 `lg:hidden` 收掉），两处注释同步。
