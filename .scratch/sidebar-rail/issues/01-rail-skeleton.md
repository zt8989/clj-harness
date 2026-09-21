# 01 — rail 的骨架

Status: done

## 做什么

`ui/src/components/sidebar.tsx`：

- `<aside>` 的类改成按 `folded` 分两支：折叠支 `hidden lg:flex lg:w-12`（窄窗仍然整个消失），
  两个分支共用 `bg-background h-full shrink-0 flex-col border-e`。
- 品牌格：折叠时 `group/brand relative flex size-12 items-center justify-center`，
  里面是 `<AppBrand compact />`（外面套一层负责 hover 淡出的 span）与 `<SidebarOpenButton shape="rail" />`；
  没折叠时是今天那行（`h-12 px-2.5` + 完整 `AppBrand` + 收起那颗）。`border-b` 两支都要。
- `header`：折叠时 `flex flex-col items-center`，三颗按钮各 `my-2 size-8 p-0`（即 48px 一格），
  文字换成 `sr-only`；没折叠时是今天那行。
- `sidebar-scroll`：折叠时加 `hidden`（**仍然渲染**，理由见 spec）。
- 错误段落与手输路径那个表单：折叠时 `hidden`。
- `footer`：折叠时 `flex flex-col items-center border-t`，设置那颗 `my-2 size-8 p-0` + `sr-only`。

`ui/src/components/app-brand.tsx`：加 `compact?: boolean`（默认 `false`），为真时**只画记号**。

## 验收

- 宽窗折叠：rail 48px、四颗图标 + logo、列表不见、没有产品名、没有浮标。
- 窄窗折叠：整列 `display: none`，与今天一模一样。
- 展开时：一个像素都没变（品牌行、三个控件那一行、列表、设置）。
- `cd ui && npm run typecheck`、`npm test`、`npm run build` 三关绿。

## 落地

`ui/src/components/sidebar.tsx`：`<aside>` 的类按 `folded` 分两支（折叠支 `bg-background hidden h-full
w-12 shrink-0 flex-col border-e lg:flex`）；品牌格折叠时 `group/brand relative flex size-12 ...
justify-center border-b`，里面是套了一层淡出 span 的 `<AppBrand compact />` 与 `<SidebarOpenButton
shape="rail" />`；`header` 折叠时 `flex-col items-center`、三颗各 `my-2 size-8 shrink-0 p-0`（新建那颗
文字换 `sr-only` + `title`）；`sidebar-scroll`、两条错误句、手输路径表单折叠时 `cn(..., folded && "hidden")`；
`footer` 折叠时 `flex-col items-center border-t` + 设置那颗 `my-2 size-8 p-0`。
`ui/src/components/app-brand.tsx` 加 `compact?: boolean`（默认 false，为真时不画 `brand-name`）。
`footer` 补 `mt-auto`（第二版）：列表在 rail 里是 `hidden`，`flex-1` 的生长项就没了，
不加它设置那颗会浮到刷新下面（展开时这条完全惰性，见 spec 那一节）。
量到的数见 spec 的走查实测。
