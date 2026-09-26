# 04 — 走查、证据与收口

Status: done
Blocked by: 01, 02, 03

## 做什么

`node scripts/dev.mjs --scripted` 起真浏览器（隔离家、OS 分配端口、跑完收摊），
按 spec 的「走查」一节逐格走，截图落 `.scratch/brand-header/evidence/`。脚本给一条普通回答即可
（第一句话由走查自己在 composer 里打——**标题的源头就是用户自己打的字**）。

要走的格（spec 有逐条期望）：

1. 1280×800：品牌行 = 记号 + `clj-harness` + 收起那颗同行；`New task` 行不再有收起那颗，
   且它在品牌行**下面**。
2. 打一句并回车 ⇒ 顶栏与 `document.title` 当场换成这句话。
3. 新建任务 ⇒ 回退词（不是上一场的标题，也不是 id）。
4. 点回第一场 ⇒ 换回那句话；`location.href` 没变。
5. 收起 ⇒ 品牌行连 logo 一起 `display: none`；标题不压浮标（`x ≥ 40`）。
6. 切中文 ⇒ 回退词那场变 `新会话 · clj-harness`；有第一句话那场不变。
7. 390×844：抽屉里品牌行在顶、收起那颗在行尾；点会话行抽屉收起、标题照样换。

控制台除 favicon 404 与「还没跑过的会话」那条 `/api/threads/<id>/stats` 404 外应当安静。

## 收口

- `ui/test/ui.test.ts` 的 `EXPECTED_CASES` 52 → **55**（02 的两例 + `sidebar` 一例），
  并在那段流水账里加一行写清这三例是什么、为什么。
- `ui/test/suites/sidebar.tsx` 加一例：`AppBrand` 渲染出产品名与 `aria-hidden` 的记号；
  收起那颗**在哪一行**按源码文本读（`sidebar.tsx?raw` —— 它在这个 run 里渲染不了，
  理由与旁边那条 `SIDEBAR_ID` 的读法相同）。
- `ui/index.html` 的静态 `<title>` 上加一行注释：首次绘制之前是它，之后归 `lib/session-title.ts`
  （`lib/i18n.ts` 的 `<html lang>` 是同一个形状）。
- spec.md 的「走查」一节补上实测结果（哪几格和预期不同就如实写），`readme` 不动（它只有四节）。
- 三关全绿：`npm test`、`npm run typecheck`、`npm run build`。
- 后端一行没动 ⇒ 后端套件不必重跑；真要跑也只是 `clojure -M:test -m harness.test-runner`。

## 落地（2026-09-21）

七格全对上，证据 9 张在 `evidence/`，`EXPECTED_CASES` 52 → 55，spec 的「走查实测」一节记了量到的数。
`ui/test/suites/sidebar.tsx` 加的一例（`the-brand-row-says-what-this-is`）里，收起那顆在哪一行按源码文本读
（`sidebar.tsx?raw`），与旁边那条 `SIDEBAR_ID` 同一个理由。
三关：`npm test` 55 passed、`npm run typecheck` 0 error、`npm run build` 绿（CSS 98.10 kB / JS 1 443.69 kB，gzip 410.91 kB）。
后端一行没动，因此没跑后端套件。
