# 02 — 单行、缩进槽、相对时间（前端）

Status: done
Blocked by: 01

## 做什么

- `lib/projects.ts`：`SessionSummary` 换成 `{threadId, archived, running, lastSentAt, firstUserText}`。
- `lib/relative-time.ts`（新）：`relativeTime(ms, now)` 的阶梯——`刚刚 / N 分钟 / N 小时 / N 天 /
  超过 7 天给日期`；词来自调用方的词表（与 `sessionTitle` 同一个形状：模块零 import、纯函数）。
  两个语言加键（`session.justNow` / `minutesAgo` / `hoursAgo` / `daysAgo` + 复数形态）。
- `thread-list.aui.tsx`：单行——左边**缩进槽**（跑起来时里面是 spinner，不跑时是空的，标题起点不动）、
  中间标题（`truncate`）、右端相对时间；`title` 属性放 `thread-id` 与完整绝对时间两行；
  去掉 `CURRENT` 字样（当前会话只用底色）；parked 词与归档那个项目名留在右端、标题截断元素之外。
- `sidebar.tsx`：会话行缩进到项目行图标右侧，缩进宽度 = spinner 宽度 + 间距（量出来的数写进注释）。
- 词表：`session.current` 与 `session.noLog` 删掉（两个语言）；`session.neverRun` 只留给
  `lastSentAt` 为 NULL 的行。`formatBytes` 若再无调用点则删。

## 验收

- 行的第一行有：标题（或 id 回退）、右端相对时间；`bytes` 不再出现在任何行上。
- 跑起来时 spinner 出现在缩进槽里，标题的 x 不移动（walkthrough 量）。
- 老会话（回填过）显示相对时间；`lastSentAt` 为 NULL 的行显示 `还没跑过`。
- `npm test` / `typecheck` / `build` 绿。

## 落地

- `lib/relative-time.ts`（新，零 import）：`relativeAge(sentAt, now)` 答一个**结构**
  （`{kind:"justNow"} | {kind:"minutes"|"hours"|"days" count} | {kind:"date"}`），词由调用方从词表里取
  ——阶梯是 60 秒 / 60 分 / 24 小时 / **7 天**（`RECENT_MAX`），超过给日期；未来时间也算 `justNow`
  （时钟偏移不画出「-3 分钟」）。**与票里的名字不同**：票写的是 `relativeTime(ms, now)` 返回字符串，
  实现返回结构 —— 因为「超过 7 天给日期」要 `toLocaleDateString`，而那是渲染的事，纯函数不该碰。
- `lib/projects.ts`：`SessionSummary = {threadId, archived, running, lastSentAt: number|null, firstUserText}`。
- `thread-list.aui.tsx`：单行 `[槽][标题 …][右端时间]`；槽是**永远画出来**的
  `data-slot="thread-list-item-slot"`（`size-3.5`），跑起来时里面是 `Loader2Icon`
  （`data-slot="thread-list-item-running"`），不跑时是空的——**标题的 x 两种状态相同**；
  `title` 属性是两行（thread-id + 绝对时间）；`CURRENT` 那段与 `thread-list-item-meta` 整块删掉。
- `sidebar.tsx`：归档块与移除后的落点都按 `lastSentAt` 排（nil → `Number.MAX_VALUE`）；
  行上的 `running` = `session.running || statuses[id].running`（**进程内的那份覆盖库那份**）。
  **缩进不在这个文件里**：它在行的类名里（`ps-2` 8 + 槽 14 + `gap-1.5` 6 = 项目名起点 36），
  与项目行的 `px-1.5` 6 + 图标 16 + `gap-1.5` 6 对齐——数写在两份文件的注释里，走查量。
- 词表：删 `session.current` 与 `session.noLog`，加 `session.justNow` / `minutesAgo` / `hoursAgo` /
  `daysAgo`（en 带 `_one`/`_other`，zh 只有 `_other`）；`session.neverRun` 只留给 NULL 那一行；
  `sidebar.refreshTitle` 从「从磁盘和库重新读取列表」改成**「从库里重新读取列表」**
  （主人那句「刷新按钮显示从磁盘和库加载」读成「列表不该再从磁盘加载」）。
- `formatBytes` **留着**：`file.tsx` / `context-card.tsx` / `suites/stats.ts` 还在用（票里说「若无调用点
  则删」，grep 的答案是有）。
- 套件：`suites/sidebar.tsx` 换成 store 形状的行（`a-row-is-one-line-and-it-says-the-name-and-how-long-ago`
  / `the-right-end-counts-back-from-now` / `the-indent-slot-is-always-drawn-and-the-spinner-goes-in-it` /
  `a-row-nothing-was-ever-sent-to-says-which-absence-it-is`）；新增 `suites/relative-time.ts`（3 例）；
  `EXPECTED_CASES` 60 → **65**。`npm test` 65 例、`typecheck`、`build` 全绿。
