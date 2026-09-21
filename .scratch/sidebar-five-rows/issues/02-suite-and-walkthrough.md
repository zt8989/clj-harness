# 02 — 套件、走查与收口

Status: done
Blocked by: 01

## 做什么

- 新套件 `test/suites/sidebar-rows.ts`：`foldRows` 的边界——4 / 5 行没有折叠、6 行画 5 藏 1、
  当前行在第 5 与第 6 的差别、`wantsAll` 与 `forced` 同时为真、计数是藏起来的行数。
  `test/ui.test.ts` 的 `SUITES` 与 `EXPECTED_CASES` 跟着改。
- 走查 `.scratch/sidebar-five-rows/walkthrough.mjs`（真 Chromium、脚本家、隔离家）：
  一个项目里造 6 条以上会话 ⇒ 只画 5 行 + `还有 N 个`；点开画全（`收起`）；再点回 5 行；
  选中第 6 条之后刷新 ⇒ 那一块仍展开、当前那一行看得见、没有折叠控件；
  任务那一块同一条规矩（造 6 条任务）；`已归档`不限。截图进 `evidence/`。
  量：前 5 行的 x 与折叠控件文字的 x（应与行标题同一个 x）。
- 收口 `docs/architecture/client.md`（侧栏那一段：上限、谁把它展开、状态不落盘）。

## 验收

- 套件 / typecheck / build / 后端全量全绿；走查过；截图在 `.scratch/sidebar-five-rows/evidence/`。

## 落地

- 新套件 `test/suites/sidebar-rows.ts`（4 例）：折叠本身与它报的数（4/5 不折、6 折 1、
  40 行折 35）、**正在读的那一行永远不会是被折掉的那一行**（第 5 与第 6 是分界）、
  手动展开与「当前会话压过它」（两者同时为真时 `forced` 仍然为真——这是决定这个函数形状的那一格）、
  以及没折时不复制数组（`drawn` 是同一个引用）。`EXPECTED_CASES` 65 → **69**，
  `SUITES` 加 `sidebarRowsSuite`。`npm test` 69 例、`typecheck`、`build` 绿。
- 走查 `.scratch/sidebar-five-rows/walkthrough.mjs`（真 Chromium、脚本家、隔离家、
  `--ui-port 5219`）：**每一格都按 session id 与库里的次序比**（不数字数），所以可以重复跑。
  先真发 12 条消息（项目里 6 条、任务 6 条）把两块都顶过 5 行，然后：
  ① 项目只画 5 行、**正好是库里最近的 5 条**、第 6 条（最老的那条）被折起来、控件说 `还有 1 个`、
  `aria-expanded=false`；② 控件标签的 x 与行标题的 x 相同（36 = 36）；
  ③ 点开画全 6 条、控件变 `收起`；④ 再点，回到 5 行、计数回来（**控件没有在用过一次之后消失**）；
  ⑤ 打开后点最老那条 → 刷新页面 → 那一块仍画全 6 条、它仍是当前行、**没有控件**；
  ⑥ 任务那一条块同样五个一行、同样点开/收起；⑦ 任务里的最老那条被选中后刷新，整块仍然画全、无控件；
  ⑧ 把项目那 6 条全部归档 → 归档块 6 条全画、**没有折叠控件**。
  截图 `evidence/01`–`06`。**第二遍（store 里已有上一遍的 12 条任务）也全绿**：计数是从库里算的
  （`还有 7 个`），不是写死的。
- 文档：`docs/architecture/client.md` 的侧栏那一节加上这一条（上限、谁把它展开、状态不落盘、
  归档块为什么不限、上限是画的事不是读的事），走查清单里也加了这一格。
