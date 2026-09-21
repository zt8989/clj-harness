# 01 — 揭示不该被禁用打败，圈该归刷新

Status: done

## 症状（主人 2026-09-21 报的）

点「添加项目」⇒ 刷新按钮开始转 + 会话列表里每行的归档按钮**全部展示**。

## 复现（真 Chromium 1280×800，真后端，`page.route` 把 `POST /api/sessions` 拖 5s 造忙窗口）

| 量什么 | 空闲 | 忙 | 恢复 |
|---|---|---|---|
| 行上归档 `opacity` | 0 | **0.5** | 0 |
| 项目「更多」`opacity` | 0 | **0.5** | 0 |
| 刷新图标 `animate-spin` | 否 | **是** | 否 |

## 做了什么

- 新增 `ui/src/lib/reveal.ts`（`REVEAL_ON_HOVER`），两个调用点（`thread-list.aui.tsx` 的
  `ThreadListItemAction`、`sidebar.tsx` 项目行的「更多」）都从它取；串里补 `disabled:opacity-0`
  与 `group-hover:disabled:opacity-50`。
- `sidebar.tsx`：新增 `refreshing`，`refresh()` 的 `try/finally` 里开关，刷新图标只读它。
- 套件：`a-disabled-row-action-stays-out-of-the-way`（`EXPECTED_CASES` 56 → 57）。
- 截图与量到的数在 `evidence/` 与 spec 的验收表里。
- `docs/architecture/client.md`：`lib/` 模块地图加 `reveal.ts` 一行（含为什么必须带
  `disabled:opacity-0`）。

## 验收

- 修复后的同一张表：忙时归档 `0`（`disabled=true`）、忙时 hover 那一行 `0.5`、项目「更多」`0`、
  刷新图标在忙窗口里**不转**；把 `GET /api/projects` 拖 3s 后点刷新，图标**转 3s**。
  真后端与隔离家各测一遍。
- `npm test`（57）、`npm run typecheck`、`npm run build` 绿；`node scripts/dev.mjs --scripted` 走查过。
