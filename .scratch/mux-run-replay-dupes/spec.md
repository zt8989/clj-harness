# spec: 复用原来的 tab ⇒ run 的帧被重发了一遍 ⇒ AG-UI 校验器把整场 run 打死

**一句话**：`runSince` 报的是**画出来**的游标（`lib/coalesce.ts` 交付时才推进），而它要答的是
**手里有**到哪儿（`harness.edge.mux/run-frames-after`：`since` 是"I hold"）。于是任何一次重新声明
（开着任务视图、订阅 facts、窗口修复、重连……）都会让服务端把**已经收到、只是还没画**的那批帧
再发一遍；`@ag-ui/client` 的 `VerifyEvents` 不按 `:seq` 去重，第二个 `*_END` 就是致命的。

2026-09-30 对活着的进程（pid 6116，`clojure.main -m harness.edge.http --port 8080`）实测复现。

## 症状

用户报的那条，落在侧栏那一行上（`openErrors[threadId]`，即"这一场 run 失败"）：

```
Cannot send 'TEXT_MESSAGE_END' event: No active text message found with ID
'2e3cf147-613f-4eb9-adeb-038bc46a0256-m20'. A 'TEXT_MESSAGE_START' event must be sent first.
```

- run `2e3cf147-…`，thread `62f30024-…`，17:19:01.597 起跑（记录里在，且**至今没有 terminal**：
  客户端 17:19:50 断了，服务端那场 run 还在跑）。
- 记录里 `-m20` 的 START 在 17:19:42.796、END 在 17:19:50.726 —— **服务端发了**，客户端没认。
- 同一个错误家族在复现里落在 `TOOL_CALL_END` 上（见下）。

## 机制

两边的契约不一致：

| 一侧 | 说的 | 做的 |
|---|---|---|
| `harness.edge.mux/run-frames-after` | `since` = "I hold nothing / 我手里有到 n" | `n > since` 的帧全发 |
| `ui/src/lib/mux.ts` 的 `runCursors` | 注释写"the last run frame this page saw" | **在 `deliver` 里推**（`lib/coalesce.ts`："THE CURSOR MOVES WHEN A FRAME IS DELIVERED, NOT WHEN IT ARRIVES"） |

于是：帧收到 → 压在 `createBatch` 里等下一帧 rAF → 这时客户端声明 `runSince = 上一批画完的位置`
→ 服务端 `mux-add!` → `mux-replay-run!` 把那批**已经收下的帧原样再发一遍**。
`HOLD_LIMIT = 200`（`lib/coalesce.ts`）× 实测 48–52 帧/秒 ⇒ 隐藏的 tab 一次能压住 ~4 秒。

**为什么"复用原来的 tab"特别容易撞上**：后台 tab 不画，rAF 不来 ⇒ 那 4 秒的窗口一直在。
一场 20 次模型调用的 run 有 20 个 `*_END`，只要有一次重新声明落在某个 END 还压在 batch 里的时候，
第二个 END 就到了。用户那场正好是 `-m20`。

**为什么死了就是整场死**：`@ag-ui/client` 的 `VerifyEvents` 只看"这个 id 现在开着没有"，
不认 `:seq`；它抛出的错误沿 `onRunFailed` 出来（页面写 `console.error("Agent execution failed: …")`、
`hostFailed` 把这一场的 host 摘掉），而服务端那场 run 照跑。

## 证据（实测）

**1. 声明里的游标确实会倒回去，而且就是"交付游标"**：把 `harness.edge.http/mux-add!` 用
`alter-var-root` 裹一层记下每次声明的 `(since, runSince, factSince, threadId, token)`，然后在浏览器里
点一次「任务视图」（= `subscribeTasks` → `declareThread`）：

```
[+3038ms  token=65ec0651 thread=63e4582d  runSince=1202  ]   ← 上一场 run 跑完的位置
[+8026ms  token=65ec0651 thread=63e4582d  runSince=191   ]   ← 本场 run 画到这里（新 run 从 1 重编号）
[+10862ms token=65ec0651 thread=63e4582d  runSince=191   ]
```

第二次那条 `runSince=191` 会触发 `mux-replay-run!`，把 192 起的帧全发回去。

**2. 客户端确实把同一条消息收了两三遍**（在页面上下文里裹 `WebSocket`，按 thread 记账：同
`messageId`、同 `:seq`）：

```
57b58885-…-r3 : 194 REASONING_START, 195 MESSAGE_START, 196–197 CONTENT, 198 MESSAGE_END, 199 REASONING_END   ×3
57b58885-…-m4 : 200 TEXT_MESSAGE_START, 201 TEXT_MESSAGE_END                                                  ×3
57b58885-…-m7 : 212 TEXT_MESSAGE_START, 213 TEXT_MESSAGE_END                                                  ×3
57b58885-…-r9 : 218…250（START/CONTENT/END 一整套）                                                            ×2
```

**3. 于是 run 当场被打死**（页面的 `window.__wb.errors`，`console.error`）：

```
Agent execution failed: Error: Cannot send 'TOOL_CALL_END' event: No active tool call found with ID
'call_00_M3Ecf5JPzbEClzgV8CRN6651'. A 'TOOL_CALL_START' event must be sent first.
```

**4. 复现步骤**（100%，不必等运气）：

1. 打开 http://127.0.0.1:8080/，发一条会用好几次模型的活（例：`请依次执行 6 次 bash：ls /tmp | wc -l`）。
2. 页面里 `window.requestAnimationFrame = () => 0;` —— 这就是"隐藏的 tab"，帧只压在 batch 里。
3. run 还在跑时，点「任务视图」开开关关几次（任何一次 `declareThread` 都行）。
4. ~10 秒内 `console.error` 就会报 `*_END` 找不到 START，侧栏那一行也会挂上同一句。

对照组：不压 rAF（帧按时交付、游标跟得上）时，同样的点击不报错 —— 缺的那味就是"压住的帧"。

## 两条修法

**A（建议，改客户端）：`runSince` 按"收到"推，不按"画完"推。**
`lib/mux.ts` 里把 `runCursors` 的推进从 `deliver` 挪到 `ws.onmessage`（`batch.push` 之前），
`RUN_STARTED` 归零保留；`lib/coalesce.ts` 头里那段理由要改（"游标跑在交付前面会让重连跳过它"——
不会：压着的帧本来就在本进程的内存里，重连跳过的只是**线上重发的那一份**）。
按此语义，"重新声明"要回来的就只剩真正漏掉的帧 ⇒ 重发消失。

**B（防御性，可与 A 并做）：客户端按 `:seq` 去重。**
每个 thread 记"收到过的最大 `:seq`"，`RUN_STARTED` 归零（用 `runId` 区分新 run 与重发的那一个），
≤ 水位线的 run 帧直接丢。这正是 `lib/follow.ts` 面板已经在做的事（它的 `boundary`），
放在 `lib/mux.ts` 一处，run 家族所有读者都受益。代价是线上仍会白跑一趟（最多 200 帧/次）。

**C（服务端，最小改动）：重发只在"这条连接第一次订阅这个会话"时做。**
`harness.edge.mux/subscribe!` 已经答"是不是新订阅"（`mux.clj`，"Answers true when the conversation
was NOT already subscribed"），而 `mux-add!` 现在不看它、每次声明都 `mux-replay-run!`。
一条连接一直在听 ⇒ 根本没有缺口，不需要补。但 `facts` 那半（`facts-after`）同病，要考虑一起改。

建议 A（把契约说对，最小、最省流量）+ B 里的水位线（让重发永远不再是致命的）。

## 相关文件

- `ui/src/lib/mux.ts`（`runCursors` / `declareThread` / `deliver`）
- `ui/src/lib/coalesce.ts`（`HOLD_LIMIT`、游标为什么在交付时推的那段注释）
- `ui/src/lib/agent.ts`（驱动 run 的那条 SSE）
- `src/harness/edge/mux.clj`（`record-run!` / `run-frames-after`）、`src/harness/edge/http.clj`
  （`mux-broadcast!` / `mux-replay-run!` / `mux-add!`）
- 校验器：`ui/node_modules/@ag-ui/client`（`VerifyEvents`）
