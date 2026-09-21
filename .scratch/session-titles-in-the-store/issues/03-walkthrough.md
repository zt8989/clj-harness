# 03 — 套件、走查与收口

Status: done
Blocked by: 01, 02

## 做什么

- 前端套件：`suites/session-title.ts` 加 `titleOf` 一例；`suites/sidebar.tsx` 把原来「第一行是 id」
  那几条改成「有标题画标题 / 没有退回 id / id 在 `title` 属性里」，`EXPECTED_CASES` 跟着改。
- 走查（隔离家、真 Chromium，截图进 `evidence/`）：新建任务 ⇒ 行上是 id；发第一条 ⇒ 当场变标题、
  hover 是 id；刷新 ⇒ 列表来自 store，仍是标题；打开一个老会话 ⇒ 也有标题。
- 收口 `docs/architecture/client.md`（行的第一行 + 活的覆盖）、`CONTEXT.md`（会话标题那条：
  从「不落盘」改成「落 store 一列，客户端显示时裁切」）、服务端那份架构文档里列表字段。

## 验收

- 套件 / typecheck / build / 后端测试全绿；`node scripts/dev.mjs --scripted` 走查过。
- 截图在 `.scratch/session-titles-in-the-store/evidence/`。

## 落地

- 套件：`suites/session-title.ts` 加 `the-one-rule-both-copies-of-a-title-go-through`（空值 → null、
  空白折叠、码点裁切、**幂等**）；`suites/sidebar.tsx` 把「第一行是 id」那几条改成
  「有标题画标题 / 没有退回 id / id 在 tooltip / store 的 200 码点在行上裁成 60+… / 活的那份压过快照」；
  `EXPECTED_CASES` 57 → 60。
- 走查 `walkthrough.mjs`（真 Chromium、脚本家、隔离家）：新建任务是 id → 发第一句**不点刷新**行上就变
  → 第二句不改写 → **刷新页面还在**（这一格才是库那一列）。截图 `evidence/01`–`04`。
  另外直接读临时家的库确认：`sessions` 有 `title`，值是第一句而不是第二句。
- 文档：`docs/architecture/client.md`（两个来源 + 走查清单）、`home-and-storage.md`（schema 与那条被推翻的
  守卫）、`edge.md`（列表字段）、`CONTEXT.md`（会话标题那条从「不落盘」改成「落盘」）。
- 绿：后端整轮、`npm test` / `typecheck` / `build`、走查。
