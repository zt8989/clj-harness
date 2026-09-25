# 02 — 下行帧按帧边界合流：渲染次数从「每个 token 一次」降到「每帧一次」

**做什么**

在 `ui/src/lib/mux.ts` 的 `ws.onmessage` 与订阅者之间加一层合流：同一帧到达的帧，按**原顺序**
一次交付。运行时那边每收到一个 AG-UI 事件就走一次 `notifyUpdate → setVersion`（`@assistant-ui/react-ag-ui`
的 `useAgUiRuntime.js`），那是**一次 React 更新**；事件来自 socket 回调、各占一个 task，React 只在一个
task 内合批，所以渲染次数 = token 数（好的厂商一秒几十到上百个）。窗口那一半一模一样：每帧一次
`commit` → `setView` + `runtime.thread.import(整场)`（`app.tsx` 的 `useWindowFeed`）。

**做法**

1. **合流器是纯模块**（收帧、吐批次，时钟/调度器注入），`lib/mux.ts` 只是它的调用者。没有 `requestAnimationFrame`
   的环境（套件）注入一个立即或可控的调度器。
2. **终端帧立即交付**：`RUN_FINISHED` / `RUN_ERROR` / `RUN_CANCELLED`，以及窗口的 `end`。合流最多让画面
   晚一帧（~16ms），不能让「跑完了」这种话晚一帧以上。
3. **游标必须在「交付」时前进，不能在「收帧」时前进。** `runCursors` 是重连时要的 `runSince`
   （`mux.ts` 的 `declaredSet`）：现在就提前步子，合流之后就成了「记了没送达的帧」——socket 恰好在
   flush 之前掉线，那些帧**永远不会再来**。窗口那一半天生安全（`since` 取的是已提交窗口的 `cursor`），
   run 那一半要显式挪到交付处。
4. **socket 关闭先 flush 再 `onClosed`**：`onClosed` 触发的修复（`align()`）必须基于一个已经交付到的位置。
5. **批准的卡不受影响**：它们不是终端帧，但同一帧内必须出现——这就是第 1 点「一次交付」的意思，
   不要在批内再分优先级。

**量过了，这一票现在是头一个要做的**（`spec.md` 的「量了」）：票 01 修好的那一块只占一次作答的
百分之几（行自己 2.2 / 4.9 秒，而整跑 35–78 秒、全程 60 帧/秒）；页面**每个 delta 花掉整整一帧**
（≈13 ms），那笔钱最可能就在这一票的合流点（以及票 04 的订阅）上 —— 而且它随**会话有多长**涨，
不是随思考有多长。所以这一票要量之前，夹具得先换成一场长会话。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] 纯模块的套件（`ui/test/suites/`，照 `mux.ts` 现有那条的写法）：顺序保持；一次 tick 只交付一次；
      空 tick 不交付；终端帧立即交付；`close()` 之前未交付的先交付；同一线程的帧不跨 tick 乱序。
- [ ] 一条按次数判的用例：往合流器里连送 100 帧 ⇒ 交付 **1** 次、100 帧顺序不变。
- [ ] **`runSince` 停在最后一条已交付的帧上**（不是最后一条收到的）：用例直接断言 `declaredSet()` 里的
      `runSince`，并且在「收到但未交付」时重连 ⇒ 那些帧会被重新要回来。
- [ ] 浏览器：照 `spec.md`「量了」那一节的方法学量 before/after —— 夹具换成**一场长会话**
      （像 `.scratch/thinking-row-tail/scratch-continuation.mjs` 那样种一份真记录进隔离家）+ 一段
      流式思考，指标是**每个 delta 的提交次数**与 `long-animation-frame` 的 blocking 时长。
      爆发式（不限速）的**墙钟时间不能当指标**：同一版两次跑出 34 s 与 78 s。数字进 `evidence/`。
- [ ] 窗口那条路同样变便宜：观看另一进程在跑的会话时，`import` 的次数是「每帧一次」，不是「每帧 N 次」。
- [ ] 停下来那一刻的观感不变：跑完不再「还在跑」，停下来的卡（中断、工具批准）照旧当场出现
      —— 一次手点走查，截图进 `evidence/`。
