# 02 — 侧边栏照它写，id 进 tooltip，活的那份压过快照

Status: done
Blocked by: 01

## 做什么

- `lib/projects.ts`：`SessionSummary` 加 `title: string | null`。
- `lib/session-title.ts`：抽出 `titleOf(said)`（去空白 + 60 码点 + `…`，空则 null），
  `sessionTitle` 改成用它；`firstUserText` 不动。
- `thread-list.aui.tsx`：第一行 = `titleOf(said ?? session.title) ?? threadId`，正常字体（不是 mono）；
  `data-slot="thread-list-item-title"`；`title={threadId}`（hover 可见 id）；
  新入参 `said`（这个页面自己握着的那份，没有就是 null）。
- `app.tsx`：`SessionStatusReporter` 读 `firstUserText(s.thread.messages)` 并上报（与 `running` 同一条路），
  页面按 id 存一份 `said`，卸载时丢掉；传给 `Sidebar`。
- `sidebar.tsx`：`SidebarProps.said`，行上 `said={said[threadId]}`（归档区那几行也一样）。

## 验收

- 有标题的行显示标题、hover 的 tooltip 是完整 thread id；没有标题的行显示 id。
- 刚发第一条消息，侧栏那一行**当场**变标题（不等下一次列表）。
- 打开一个 store 里没有标题的老会话，行上也显示它的标题（历史重建后第一句就在内存里）。
- `npm test` / `typecheck` / `build` 绿。

## 落地

- `lib/projects.ts`：`SessionSummary.firstUserText`（库里那份，原文，服务端最多 200 码点）。
- `lib/session-title.ts`：抽出 `titleOf`（空白折叠 + 按码点 60 + `…`，空则 null）——**两个来源同一条规矩**；
  `firstUserText` 改成用它；文件头那段「不落盘是重点」改写成两个来源与反转的来龙去脉。
- `thread-list.aui.tsx`：第一行 = `liveTitle ?? titleOf(session.firstUserText) ?? threadId`，
  正常字体（不是 mono），`data-slot="thread-list-item-title"`；id 留在 trigger 的 `title` 属性（hover 可见）。
- `app.tsx`：`SessionStatusReporter` 多读一次 `firstUserText(s.thread.messages)` 并上报（与 `running`
  同一条路的第二个 effect）；页面 `liveTitles` 登记表，卸载时丢；传给 `Sidebar`。
- `sidebar.tsx`：`liveTitles` 与 `statuses` 并排传下去，三处行渲染（任务 / 项目 / 归档）都读它。
- 绿：`npm test` 60 用例、`npm run typecheck`、`npm run build`。
