# 03 — 发送才建会话（前端）

Status: done
Blocked by: 02

## 做什么

- `sidebar.tsx`：`newTask` / 项目里 `newSession` 不再调 `startTask` / `bindThread`，也不刷新；
  只 mint id、记住「这个 id 属于哪个目录」（任务为 null）、`onShowFresh(id)`。
- 第一次发送那一刻把待绑定的项目绑上：页面在 host 上报「这个会话有第一句了」时，
  若它有 pending 项目则 `POST /api/project`（用现成的 `bindThread` 客户端函数）。
- 这次 run 结束后补一次刷新：侧栏看到「握着一个有标题、不在列表里、也不在跑」的会话就问一次库，
  每个 id 每次页面加载最多一次（用 ref 记，不可能成环）。
- 旧行为要拆干净：`startTask` 不再被侧栏调用（端点保留），`newTask` 那三条注释里关于
  「先建一行再显示」的理由要改写。

## 验收

- 点「新建任务」：列表里**不出现**任何行；库里也没有新行。
- 同一个 id 发送第一句之后：列表里出现这一行（标题 + 相对时间），且它在最上面。
- 项目里点「新建会话」再发送：它出现在**那个项目**下面（不是任务块），库里那行 `project_id` 正确。
- 发送过一次之后再刷新，行仍在、时间更新。

## 落地

- `sidebar.tsx`：`newTask()` 与 `newSession(project)` **只铸 id + `onShowFresh(id, dir|null)`**
  （项目会话把目录一起交给页面；任务交 null）。`startSessionIn` 删掉，`bindThread` / `startTask`
  在这个文件里不再有调用点，`newTaskError` 那份状态与它那条 `<p>` 也删掉——**它服务的注册动作没了**，
  留下一份永远为 null 的状态只是让人以为还有一条会失败的路径。
- `app.tsx`：`pendingBinds`（`useRef<Map<string,string>>`，**不是 state**：写它不该重渲染、
  读它的 `reportTitle` 也不该为此重渲染）+ `showFresh(id, projectDir|null)` 记下待绑定；
  `reportTitle`（host 上报「这个会话有第一句了」）里若这个 id 有 pending 目录就
  `POST /api/project` **一次**（取走即删），失败把服务端那句话落进 `openErrors[id]`——
  绘制在**那一行**上，与「历史读不出来」同一处。**绑是 upsert**，所以 run 先注册成任务也没关系。
- `lib/session-memory.ts`：记住的形状从「有没有日志」换成 `{threadId, lastSentAt}`；
  `app.tsx` 的恢复据此决定读法（有发送时间 → `sofar`，没有 → `none`）。
- 刷新那一格：侧栏新增一个 `useEffect`（`liveTitles` / `statuses` / 列表 ids 为输入），看到
  「有标题、不在列表里、也不在跑」的会话就 `refresh()` 一次；**每个 id 一次**（`asked` ref，
  所以成不了环），刷新现在是一次 SELECT，便宜。
- 走查（票 04 里那份 `walkthrough.mjs`）逐条量了这四条验收，并**另外直接问库**：点新建任务之后
  库里没有新行；第一句之后库里那行有 `firstUserText` 与 `lastSentAt`、`projectId` 为 null；
  项目里发送之后库里那行的 `projectId` 正是那个项目。
