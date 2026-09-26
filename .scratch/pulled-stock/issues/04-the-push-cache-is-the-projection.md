# 04 — 推送缓存就是投影：内存里不再攒增量

**做什么**

`harness.edge.mux` 今天为每场会话在内存里留两份**原始增量**：

| atom | 装什么 | 上限 | 谁写 |
|---|---|---|---|
| `runs` | 当前这条 run 的 AG-UI 帧，逐条编号 | 4096 | `http.clj/mux-broadcast!` → `mux/record-run!` |
| `facts` | `turn/*` / `model/*` 事实，各带记录行号 | 1024 | `http.clj/family-send!` → `mux/record-fact!` |

它们是「推一个没人听到就没了」的补偿：断线的读者声明 `runSince` / `factSince`，把缺的那一截要回去。

**存量既然是拉的，这份补偿就不该存在。** 断档重连改**拉一次投影**（票 01 的第一次拉 / 尾页对齐）——
投影就是会话当前的样子，比一串增量更准，也不会比记录旧。

**Blocked by:** 01, 02

**Status:** ready-for-agent

- [ ] 重连不再重放 run 帧 / 事实：服务端 `run-frames-after` / `facts-after` 与 `mux-replay-run!` 撤掉。
- [ ] `mux/runs` 与 `mux/facts` 两个 atom 连同 `record-run!` / `record-fact!` 一起退场
      （`rg 'run-frames-after|facts-after'` 与 `rg 'defonce .*runs'` 得到零）。
- [ ] 客户端不再维护 / 声明 `runSince` / `factSince`（`ui/src/lib/mux.ts`）；重连时**只**重声明它与
      窗口的 `since` / `generation`，然后按票 01 的第一次拉对齐。
- [ ] 一条真浏览器走查：一场 run 中途拉断 socket，重连之后**画面不漏**——因为它拉的是投影，不是增量。
- [ ] 说清楚代价：断线期间那一截的**中间过程**（逐帧的推理 delta）不再被重放；它们照旧在记录里，
      要看的人走「点开那一轮」的第二次拉。
