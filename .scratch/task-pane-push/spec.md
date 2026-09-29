# 右侧任务视图：首拉 + 推送 + 起止时间(task-pane-push)

**状态：** 票 01 落地(2026-09-29)。票文件在收尾时按仓库约定删除;决定、定位与验证都记在这里和 git 历史里。
把这段活从 Windows 工作区搬过来时,`.scratch/task-pane-push/issues/01-*.md` 已经不在(见下节);当时的交接brief
留在同目录 `handoff.md`,内容未改。

## 起因

主人的要求一句话:tasks 的右侧任务视图**先拉一次,之后靠推送更新**,并且每一行要说得出**起止时间**——
正是这次右栏里那条"已跑 36 秒"停在屏幕上不动的由来。

## 一、票 01 的形态(搬过来之前已经写完的部分)

- **推送的第四族帧**：`{:type "task" ..}`,整包载荷、无游标。理由写在 `ui/src/lib/mux.ts` 的 `TaskFrame` 上:一个
  窗口帧有尾页、一个 run 帧有 `runSince`、一条 fact 有记录的 `seq`,而"作业结束"写在**作业自己的记录**里、
  "委派结束"只是**本进程的一段记忆**——没有行号可编,也就没有东西可以回放,所以每次变化送整包,和 `events.host`
  给侧栏送 listing 是同一个理由。
- **服务端两个通知缝**：`harness.cap.jobs/set-change-hook!`+`announce!`、`harness.cap.subagents` 的同名缝,
  `harness.edge.http/task-body` / `task-send!` 把两个载荷装成一帧,发给**会话 socket**上正在听这个会话的连接。
- **前端**：`hooks/use-task-pane.ts` 里轮询没了——挂载、切会话、可见性回来时各读一次快照 + `subscribeTasks` 订阅;
  唯一的计时器是本地滴答(`TASK_PANE_TICK_MS`,只在**有东西在跑**时开,不发请求),因为它画的是"已经跑了多久",
  这个数字服务端不可能持续送。

## 二、收尾时修掉的那个 bug(这一票真正的结尾)

### 观测(浏览器走查,修复前)

作业行在作业**结束之后**仍然画着 `[running]` / `已跑 13 秒`,而且越走越大;同时该会话的
`GET /api/threads/<id>/jobs` 已经答 `[exit 0]` + `endedAt`——**服务端是对的,帧没到。**

服务端临时探针(`:task/push-probe`)把这件事钉死:

```
push-probe channels=1 thread-id=02d5c6b7-…   ← 作业开始:有 1 条连接在听,帧发出去了
push-probe channels=0 thread-id=02d5c6b7-…   ← 作业结束:0 条连接在听 ⇒ 这一帧没人收到
```

### 根因(三条,前两条是同一个错的两个方向)

在页面里给 `fetch` 和 `WebSocket` 挂上记录器复现后,declare 流量是这样的(一个刚 mint 出来的会话):

```
+1.0s  subscribe  [{threadId:"7e80a151", …}]      ← 窗口/运行订阅
+1.1s  subscribe  [{threadId:"7e80a151", …}]      ← 任务视图订阅同一条
+2.6s  unsubscribe ["7e80a151"]                   ← run 结束,它把整条线程的 watch 关掉了
```

1. **`wantedThreads()` 少两张表**(交接 brief 已指出)：它只收窗口和 run 的订阅,`factSubscriptions` 与
   `taskSubscriptions` 不在里面。声明不只是第一次握手——它是**被替换的 socket 重新说的那句话**
   (ADR 0003 决定 7)。新握手不点名这条会话,服务端就把它从 watch 里丢掉。
2. **拆除是"家族盲"的**(交接 brief 没指出,但它才是这次走查红掉的那一半)：`harness.edge.mux/subscribe!` 的
   watch 是**一条连接 + 一条会话一份**,四个家族共用。而 `subscribeMux` / `subscribeRun` 的收尾只问自己那张表,
   于是"run 结束"这个动作把**任务视图还在画的会话**一起关掉了。刚刚 mint 的会话只有 run 订阅、没有窗口订阅,
   所以每次都会踩到。
3. **无游标的帧没有别的补法**：即使 1 修好(重连会重新声明),socket **断着的那段时间**里发生的变化也不在任何
   一帧里。所以任务视图在 socket 重开时必须**再读一次快照**。

### 修法

- `wantedThreads()` 收齐四张表(窗口 / run / fact / task)。
- 新增 `stillWanted(threadId)`,四个家族的收尾都问它:**最后一个走的关灯**。`subscribeFacts` /
  `subscribeTasks` 的收尾以前根本不发 `unsubscribe`,现在也走同一条规则(否则服务端会一直往没人读的会话推)。
- 新增 `openListeners` 与 `subscribeTasks(threadId, onTask, onOpen)`:socket **重开**(第一次开不算,那是挂载那一次读
  已经在做的事)时把所有登记过的读者叫一遍;`use-task-pane` 交出去的就是它自己那个幂等的 `read`。
- 删掉诊断探针 `:task/push-probe`(`harness.edge.http/task-send!`)。服务端其余一行未改。

## 三、验证

- 后端：`clojure -M:test -m harness.test-runner harness.edge.http-test harness.cap.jobs-test harness.cap.subagents-test`
  —— 全绿(见下"机器"一节;`http-test` 在本机会很慢,单独跑得动)。
- 前端：`npm run typecheck` 绿;`npm test` 里 **新增** `mux` 用例 `a-thread-only-the-pane-follows-is-in-the-declaration`
  (用一支**永不连接**的 WebSocket 替身,所以它问的是"集合里有没有这条线程"这个值,不是一个连接;用例数量钉
  `EXPECTED_CASES` 166 → 167),并在原有 `a-fact-frame-is-not-a-run-frame` 里补了第四族的归属断言。
- 浏览器走查(MCP 驱动真浏览器;`walkthrough.mjs` 需要 playwright,这台 WSL 里没有装,见下):
  - 修复**前**,同一套流程:作业跑到 `已跑 13 秒` 仍不落定,`push-probe` channels 1 → 0。
  - 修复**后**,同一套流程:`<1s so far` → `3.1s so far` → **`took 8.0s`**,作业行带上 `[exit 0]`;两侧路由的请求数
    一动不动(4 次,与挂载时相同)——结束那一帧是**推**来的。
  - 握手替换:在页面里把 mux socket 关掉,1 秒后新 socket 的握手 URL 里
    `sessions=` **同时点名了当前会话与恢复出来的会话**;任务视图随后**再读了一对快照**(不是轮询)。
    `walkthrough.mjs` 末尾把这一段固化成四条断言。

### 机器差异(不是本改动的红)

本机 `cd ui && npm test` 跑满 167 条时会红几条,**基线同样红**:未改动的 `main`(173 条)在本机也是 4 条红——
3 条 `Test timed out in 120000ms`(JVM 起得慢/机器忙)+ 1 条被超时连累的 `mux`(前面超时的用例把订阅留在
了模块里)。所以这不是本票的红,证据是那条基线跑。真要在本机判绿,单独跑:
`npx vitest run test/ui.test.ts -t <用例名>`。

## 四、证据

`evidence/`

- `task-pane-running.png` / `task-pane-settled.png`：搬过来时就在的两张(作业进行中 / 落定)。
- `task-pane-pushed-row-settles.png`：修复后,推送把行放回落定态(本次真机走查截的)。

## 五、边界与没做的

- **`docs/rules/panel-data.md` 不存在**：交接 brief 两次引用它("与本仓 `docs/rules/panel-data.md`「断了怎么办」
  一致"、`use-task-pane.ts` 的注释也点名它),但仓库里只有 `concurrency.md` / `hotfix.md` / `testing.md`。这次没有
  凭空格造那份规矩:把"断了怎么办"的讨论留在了 `lib/mux.ts` 与 `hooks/use-task-pane.ts` 的注释里。
- **`walkthrough.mjs` 没在本机跑**：脚本 `loadChromium()` 要 playwright,这台 WSL 里既没有全局装、`ui/node_modules`
  里也没有;本机是 Windows 侧的 MCP 浏览器在驱动同一套流程(上面第三节的记录就是它)。脚本本身按同一套步骤扩了
  四条断言,谁在有 playwright 的机器上跑一次即可。
- **一条连接一份 watch 的语义**：本次没有把四个家族合并成"一条会话一张表"的结构(那会让"漏一张表"这类错**结构上
  不可能**),只按交接 brief 的路子把该问的都问全。结构上的那种改法值得单独一票。
