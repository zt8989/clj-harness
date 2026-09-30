# trajectory 那一栏走下行 socket（trajectory-on-the-downlink）

**状态：** 决议已定，**尚未落地**（本文 + `issues/` 三张票 + `docs/` 里那几节按目标状态改写，
都是这次交付；代码没动）。

## 起因

`GET /api/threads/<stem>/trajectory` 是 ADR 0004 之后**服务端最后一条长连接推送**，也是全仓
唯一一条不走下行 socket 的推：一条 NDJSON 响应，首行是头（`:threadId` / `:incomplete` /
`:behind`），其后一轮一行，`fold-trajectory` 折完一轮吐一轮；这场会话**被本进程持有、而且
本页的下行订阅在订阅它**时保持连接继续推，否则给完折好的那一份就结束。

它换来三条代价，每一条都记在案上：

1. **每个打开的轨迹栏各占一条 HTTP 长连接**——浏览器的同源连接池就那么几条，而这一页的下行
   之所以是一页一条 socket（ADR 0004），要免的就是这件事。
2. **「重连之后补一次存量」得靠一个外挂**（`docs/rules/panel-data.md` 记的那处例外，与
   `.scratch/memory-hygiene/` 票 02 同一件事）：服务端看不见一条普通流式响应的读者走没走
   （实测：没有 `:on-close`，`open?` 还是 true，写还「成功」），所以它的门铃只能**借**这一页
   的下行订阅当主人；socket 一断，服务端把门铃 prune 掉，流就哑了——`onDownlinkOpen`
   叫它重开，是这条路由独有的修补。
3. **头只随第一行发一次**，于是 `:incomplete` 会停在旧值，`trajectory-view.tsx` 只好把
   `isRunning` 写进 effect 依赖、靠**重开整条流**去刷新一个布尔。

## 决议

1. **`trajectory` 是下行 socket 的第五个家族。** 帧 `{:type "trajectory" :threadId <id> ..}`
   与 window / run / fact / task 同走一条 `events.mux`；订阅是**声明的一部分**——
   `declaredSet()` 里每场会话多带一个 `trajectory: true|false`。
2. **帧带增量，也带每一帧都重新说的头。** `:turns` 是「自上次以来**最终化**的轮 + 当前那一轮」
   （与今天的 NDJSON 一模一样，客户端照旧按 `:index` 就地替换）；`:incomplete` / `:behind`
   **每一帧都带**，所以第 3 条代价就地消失。**带 `:snapshot` 的帧是重开的快照**
   （订阅的第一次、以及任何一次重新声明——重连就是重新声明），客户端收到它**整份替换**而不是合并。
3. **一条连接一条门铃，覆盖它声明的每一族。** `mux-watch!` 建的那个 push 里，窗口那一半照旧，
   `trajectory` 那一半按声明的旗子做；折的游标（已发出多少轮）就在这个闭包手里——与今天那条
   路由的 `@sent` 是同一样东西。旗子翻面靠重新声明（见决策 5）。
4. **折法一个字不改**（`harness.edge.trajectory`）：同一份「模型每一轮看到了什么」，
   同一份 `:incomplete` 的判据，同一个 `trajectory/view-value` 的持有态。换的只有载体。
5. **删掉 `GET /api/threads/<stem>/trajectory`，连同 `mux/watching?`。** 没有了调用者；
   存量与推送都在 socket 上，读者要「补一次」就走重新声明那条既有的路。轨迹**没有游标**
   （不重放、不编号）——一帧就是「现在的折」，重连由开场快照补齐。
6. **读者走的时候要说一声。** 轨迹那一栏关掉时，若这场会话还有别的声明（窗口、事实、作业），
   客户端**重新声明这一条**（`trajectory: false`）而不是整条 `unsubscribe`：折子与推送都要停，
   但它不是这场会话最后一个声明者。这一半今天没有先例——别的家族断了就断了，不会留下东西：
   轨迹会（一份折 + 一个每 100ms 的 tick 里的重算）。

## 帧的形状

```json
{"type": "trajectory", "threadId": "…",
 "snapshot": true,            // 只在开场帧（订阅的第一次、每次重新声明）
 "incomplete": false,         // 每一帧
 "behind": 0,                 // 每一帧；没有欠账时不出现
 "turns": [ … ]               // 增量：最终化的轮 + 当前那一轮
}
```

- `:turns` 里一条轮的形状**就是** `trajectory/trajectory-answer` 的 `:turns`，一个字不变。
- 读不出折子时（这场会话没有日志、或本进程不持有它 —— 见「边界」）发**一帧**
  `{:type "trajectory" :threadId .. :error "<定位器自己那句话>"}`：读者当场知道为什么没有东西可画，
  而不是等一个永远不来的开场帧。客户端把它读成「没有记录」那一态（今天 404 之后画的就是它）。
- 这名字不与任何既有家族相撞：AG-UI 的词汇是大写，窗口的五种是小写类型名，事实是
  `turn/*` `model/*` `step/*`，作业是 `task`。

## 服务端在哪改（票 01）

- `harness.edge.http/mux-sessions`：`sessions` 那条 JSON 里多读一个 `:trajectory` 布尔。
- `harness.edge.http/mux-add!` → `mux-watch!`：多带那个旗子；`mux-watch!` 的 push 多一半，
  第一帧（或缺 `:trajectory` 状态时）带 `:snapshot`，之后带增量；折子取
  `(some-> (trajectory/view-value thread-id) trajectory/trajectory-answer)`。
- **`mux.clj` 不用动**：门铃本来就是「一条连接一条订阅」的那一个（`mux/subscribe!`），
  旗子由 `mux-watch!` 的闭包捕获，重新声明就是重新注册。`mux/watching?` 退休（票 03）。
- `POST /api/events.mux/subscribe` 的同一份 body 因此也带旗子（客户端那半边在票 02）。

## 客户端在哪改（票 02）

- `ui/src/lib/mux.ts`：`TrajectoryFrame`、`TRAJECTORY_FRAME_TYPE`、`familyOf` 多一支、
  `trajectorySubscriptions`（**SET**，同一场会话可能有两栏在看）、`subscribeTrajectory`，
  以及 `wantedThreads` / `stillWanted` / `declaredSet` / `deliver` 四处各加一格。
- `ui/src/lib/trajectory.ts`：`trajectoryFor`（fetch + 边收边画）换成「订阅 + 按 `:index` 合并」；
  `:snapshot` 帧整份替换。
- `ui/src/components/trajectory-view.tsx`：`isRunning` 从依赖里去掉（头每帧都来），
  `onDownlinkOpen` 那一处也去掉（重新声明就是修补）。

## 边界（不做的事）

- **不给轨迹加游标**：没有重放、没有编号，重连靠开场快照。
- **不动折法与 `:incomplete` 的判据**，不动窗口与游标的代数。
- **不为「本进程没持有这场会话」再造一条只读的路**：`mux-add!` 对**别的活进程持有**的会话
  本来就只答一帧 `end`（窗口那一半也拿不到），轨迹跟着它走。今天那条路由能只读地折一份记录，
  这是这次收窄掉的一格；真被咬到再说（那时要做的是「只读地折一份、不挂门铃」，不是复活一条流）。
- **不碰 `events.host`**：家级事实与一场会话的折不是一类。

## 代价

- **一条 socket 上多一族帧**：与 ADR 0004 决策 6（不许全量广播）一致——过滤是按连接的声明做的，
  没人声明轨迹的会话一条都不发。
- **一次重新声明 = 重发一次开场快照**：`open` 的 `onopen` 会把整份集合再声明一遍（今天也会，窗口
  因此重发一次空开场帧），所以一场长会话的折会在重连时被重发一次。可接受，而且是必要的：那正是
  「补一次存量」。
- **折子按需建**：`trajectory/view-value` 的第一次调用做一次记录流式 walk（今天那条路由也一样），
  之后读持有态。

## 验收

- 打开轨迹那一栏：帧从 `events.mux` 来，**没有** `/trajectory` 请求（浏览器网络面板）。
- 一轮 run 里新的一轮最终化：socket 上多一帧，视图跟着长；**头里的 `:incomplete` 跟着 run 落定翻面**，
  而**不**重开任何流。
- 断开重连：开场帧（`:snapshot`）把整份折重新给一遍。
- 关掉轨迹那一栏、会话还开着窗口：服务端**不再**为它折、也不再推（`trajectory: false` 的重新声明），
  而窗口那一半不断。
- 没有轨迹读者的会话：一条 `trajectory` 帧都不发。
- 别的活进程持有的会话：一帧 `end`（与窗口同一句拒绝）。
- 机器门：`trajectory_test.clj` 的四条路由用例改成 socket 用例、`mux_test.clj` 的 `watching?`
  三条删掉、`delegation_test.clj` 里那条同样搬过来；`ui && npm run typecheck / npm test / npm run build`；
  `node scripts/dev.mjs --scripted` 起服务、**自己开浏览器走一趟**（轨迹那一栏在 `对话` 之外，
  走查时点开它）。

## 落地的票

- `issues/01-trajectory-frame-on-the-downlink.md` —— 服务端的第五族（与它的单测）。
- `issues/02-the-pane-reads-the-downlink.md` —— 客户端改从 socket 读（与它的套件）。
- `issues/03-retire-the-trajectory-route.md` —— 删旧路由、`mux/watching?`、无主的 prune，
  搬走剩下的用例与指向它的注释。

## 与既有决定的关系

- **ADR 0004 不改**：它决定的是「下行是一条 WebSocket，按下行**类别**分」，而轨迹属于
  `events.mux` 这个类别（每场会话的帧）。它列的「run 的帧、子 agent 的帧、窗口条目」是例子，
  不是封闭清单（`fact`、`task` 两族就是这么长进来的）。
- `docs/rules/panel-data.md` 那一处「唯一的例外是服务端给不出的此刻」**不变**；轨迹从
  「例外」那一栏挪回正常的两半（存量 = 开场帧、推送 = 后续帧）——这次交付已经把那份文件改成了
  目标状态。
- `docs/architecture/edge.md` 的「门铃归连接所有」那一段、路由表里那一行、`client.md` 的轨迹一节、
  `architecture.md` 的 `edge.trajectory` 一行、`CONTEXT.md` 里「从管理边吐出去」那半句，
  同样已按目标状态改写。
