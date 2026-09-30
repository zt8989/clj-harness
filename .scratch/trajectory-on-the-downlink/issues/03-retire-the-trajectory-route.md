# 03 — 退休：旧路由、`mux/watching?`、无主的 prune，以及所有还指着它们的注释

**What to build:** 票 01+02 之后 `GET /api/threads/<stem>/trajectory` 没有调用者了，把它连同它
那一整套脚手架删掉，让「一条会话的流」在服务端**只剩下行这一条**（ADR 0004 的方向走到底）。
这一票是纯删除 + 搬迁，不新加行为。

**Blocked by:** 01, 02

**Status:** ready-for-agent

## 删什么

- `harness.edge.http/trajectory-get` 整个函数，`dispatch` 里 `[:get "trajectory"]` 那一支，
  以及 `thread-verbs` 里的 `"trajectory"`——那张 docstring 里「窗口的两个动词（`feed` 和 `page`）」
  那句话顺手改对（`feed` 早就不在了），动词集里的 `feed` 说法也是。
- `harness.edge.mux/watching?` 整个函数：它唯一的调用者是那条路由。它的三条用例
  （`mux_test.clj`「nobody yet / true / the other socket is still reading」）一起删。
- `harness.edge.mux/unsubscribe!` 与 `detach!` 里的 `sessions/prune-watches!` 调用：
  它们存在的理由是「轨迹流那条门铃的主人是一条 mux 订阅」，而不带主人在册的门铃现在没有了
  （`subscribe!` 登记的那一个主人是**连接自己**，`unwatch!` 就会收它）。**动手前先证一遍**：
  `rg 'sessions/watch!' src/` 应只剩 `mux/subscribe!` 一处；证不出来就别删，把理由写进注释。
- `harness.edge.http/mux-watch!` 里为轨迹留的 `trajectory?` 分叉之外的过渡代码（如果 01 留了
  「两条路都在」的开关，这里收掉）。
- `harness.edge.trajectory/fold-trajectory`：它的 docstring 说「THE ROUTE DOES NOT COME THROUGH
  HERE ANY MORE」——现在那条 route 一句话都不剩了，改成「没有读者走这里；它是内存里的孪生」。
  `records->trajectory` 的 docstring 里 `(GET /api/threads/<stem>/trajectory) for the payload`
  换成第五族的帧。

## 搬什么

- `test/harness/edge/trajectory_test.clj` 里那条路由的用例：
  - `the-endpoint-folds-what-the-run-wrote` → 走假 channel + 开场帧（`:snapshot`），断言还在；
  - `the-view-is-kept-and-a-second-ask-does-not-read-the-record` → 断言拆成
    「第二次开场帧读的是持有态」：把记录移走，重新声明一次，答案逐字相同；
  - `a-stream-nobody-is-subscribed-to-ends-and-holds-nothing`、
    `a-subscribed-stream-s-doorbell-dies-with-the-subscription` → **删**（说的是那条流自己的规矩）；
  - `the-endpoint-says-not-here-for-a-session-that-has-no-log` → 断一帧 `:error`，句子还是定位器那句。
  - 本地助手 `get-trajectory` / `with-subscription` 用完就删。
- `test/harness/edge/delegation_test.clj` 的 `trajectory-of`（一条子 agent 的轨迹读回来）：
  改成从这个连接的开场帧里拿，或直接调 `harness.edge.trajectory` 的读侧——**要保住那条断言**
  （「子 agent 的轨迹是它自己的」），它证的是 `run-subagent!` 写进子会话自己的记录。
- 还指着旧路由的注释：`harness.edge.sessions/watch!` 的 docstring（那段「服务端看不见一条普通
  流式响应的读者走没走」的实测要**留**——它是「主人」这个机制存在的理由——但「所以 trajectory
  那条流把订阅当主人」那半句换成「所以没有门铃可以挂在一条普通流式响应上」）、
  `ui/src/lib/mux.ts` 的 `openListeners` docstring、`ui/src/lib/trajectory.ts` 的文件头。

## 验收

- [ ] 仓里 `rg '"/trajectory"|trajectory-get|mux/watching\?|watching\?' src test` 一条都不剩
- [ ] `clojure -M:test -m harness.test-runner harness.edge.trajectory-test harness.edge.mux-test
      harness.edge.delegation-test harness.edge.stats-test harness.edge.sessions-test` 全绿
- [ ] 整轮后端 `clojure -M:test -m harness.test-runner` 绿（0 退出码）
- [ ] `cd ui && npm run typecheck && npm test` 绿
