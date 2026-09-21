# 03 — 走查、套件与收口

Status: done
Blocked by: 01, 02

## 做什么

- `ui/test/suites/sidebar.tsx` 加一例：`AppBrand compact` 只渲染记号；完整形态记号 + 产品名都在。
  改 `the-brand-row-says-what-this-is` 里那两条按源码读的断言（`<AppBrand />` → 允许两支）。
- 走查三态并截图（宽窗展开 / 宽窗 rail / 窄窗浮标），把量到的数写进 spec 的走查表。
- 收口：`npm test` / `typecheck` / `build`，spec 补「走查实测」，报告。

## 验收

- 三态截图在 `.scratch/sidebar-rail/evidence/`。
- 套件、typecheck、build 绿。

## 落地

`ui/test/suites/sidebar.tsx` 加一例 `the-rail-is-the-mark-alone-with-nothing-to-read`（`AppBrand compact`
只出记号、完整形态两样都在；`sidebar.tsx` 源码里 rail 那支是 `<AppBrand compact />` + `shape="rail"`，
列表那段是 `cn(... folded && "hidden")` 且 `sidebar-scroll` 全文件只有一处）；`the-brand-row-says-what-this-is`
的两条断言放宽到两支并存。`ui/test/ui.test.ts`：`EXPECTED_CASES` 55 → **56**。
`docs/architecture/client.md`：模块地图里 `sidebar.tsx` / `sidebar-toggle.tsx` 两段、折叠那条与走查清单。
走查五张截图在 `evidence/`，量到的数在 spec 的走查实测表。
