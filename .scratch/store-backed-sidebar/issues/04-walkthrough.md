# 04 — 套件、走查与收口

Status: done
Blocked by: 01, 02, 03

## 做什么

- 前端套件：`suites/sidebar.tsx` 改行形状（单行、缩进槽、右端时间、没有 `CURRENT`、没有 bytes、
  NULL 时间显示 `还没跑过`）；新增 `suites/relative-time.ts`（阶梯的边界：59 秒/60 秒、59 分/60 分、
  23 小时/24 小时、6 天/7 天/更早）；`EXPECTED_CASES` 跟着改；`suites/i18n.ts` 的键表随词表变动。
- 后端套件：`http_test`（字段、排序、不再读盘）、`db_test`（迁移与回填）、`project_test`
  （`remember-send!`）。
- 走查（真 Chromium、脚本家、隔离家）：新建任务列表**不出现**行 → 发送后才出现且在最上面 →
  项目里的新建会话出现在该项目下 → 缩进与 spinner 槽量出来（spinner 的 x、标题的 x、不跑时标题的 x
  与跑时相同）→ 相对时间显示、tooltip 里是绝对时间 + thread-id。截图进 `evidence/`。
- 收口文档：`docs/architecture/client.md`（行形状、缩进槽、相对时间、新建语义）、`edge.md`
  （列表字段与「只读库」）、`home-and-storage.md`（新列与回填）、`CONTEXT.md`（会话/项目条目里
  与「左侧全部来自库」有关的那句）。

## 验收

- 套件 / typecheck / build / 后端全量全绿；走查过；截图在 `.scratch/store-backed-sidebar/evidence/`。

## 落地

- 套件：`suites/sidebar.tsx`（行形状、缩进槽、右端相对时间、没有 `CURRENT`、没有第二行、
  NULL 那行说 `session.neverRun`）、`suites/relative-time.ts`（3 例：秒/分/时的边界、7 天那一条线、
  日期形态）、`suites/restore.ts`（bytes → lastSentAt）、`suites/i18n.ts` 的键表跟着变；
  `EXPECTED_CASES` 65。后端：`infra/db_test` / `cap/project_test` / `edge/http_test`（票 01、05 的名单）。
- 走查 `.scratch/store-backed-sidebar/walkthrough.mjs`（真 Chromium、脚本家、**隔离家**、
  `--ui-port 5219`）：7 张截图 + 30 格断言，逐条对着验收量——
  ① 没有一行带体积、刷新键的 tooltip 说「从库里重新读取列表」；
  ② 点「新建任务」**列表一行业都不出现**，且**直接问 `/api/projects`** 库里也没有新行，
  而页面确实在一场有 id 的新会话上；
  ③ 第一句之后那一行出现（**中间没有点过刷新**：侧栏自己补的那一次）、**在最上面**、
  右端是「刚刚」、tooltip 是 id + 绝对时间，库里那行 `firstUserText`/`lastSentAt` 对得上、`projectId` 为 null；
  ④ 量缩进：槽 14px、`gap-1.5` 6px、跑起来时 spinner 落在槽里而**标题的 x 36 → 36 不动**；
  ⑤ 项目里点「新建会话」同样不出现行、库里也没有；发送之后行落在**那个项目**下（不在任务块）、
  库里那行 `projectId` 正是那个项目，且**项目名下沿与行标题 x 相等**（36 = 36，图标在 14）；
  ⑥ 按刷新键：两边都还在、时间还在。
  走查可以重复跑（每一格按 session id 认，不数行数）——第二遍起 store 里已经有上一遍的会话。
- 文档：`docs/architecture/client.md`（只读库的列表、单行与缩进槽的数、相对时间、新建语义、
  走查清单）、`edge.md`（`/api/projects` 的字段与「不再 stat 任何日志」、`/api/project` 的锁）、
  `home-and-storage.md`（新增「一个会话一份文件」一节、`last_sent_at` 那一列与回填、库/文件边界）、
  `CONTEXT.md`（任务由发送造、会话标题、**上次发送时间**那一条、修掉「日志落在 `logs/`」这句过时的话）。
- **走查逮到一个真缺陷，另开了票 05**（见那份）：绑定落在 run 中途时，写手与搬文件抢同一个重命名。
