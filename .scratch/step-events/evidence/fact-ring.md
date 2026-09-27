# 事实环装得下多少步（票 04 那一格）

**量到的，不是算出来的**（2026-09-27；`scripts/example.json` 那一轮：两次请求、一个工具调用）：

一次这样的轮 = **10 条事实**，顺序逐条钉在
`ui/test/suites/turn.ts` 的 `a-reconnect-would-ask-for-the-gap-and-the-step-frames-are-in-it` 里：

```
turn/start
  step/start  model/start  model/end  step/end      ← 第一次请求（带 read 工具）
  step/start  model/start  model/end  step/end      ← 第二次请求（答案）
turn/end
```

也就是 **`turn/*` 两条 + 每次请求四条**（一步的两端，加调用的一对）。工具调用本身不进来：
`tools/*` 是记录行，不是事实。

**于是**：`harness.edge.mux/fact-buffer-size` = **1024 帧** ≈ **102 个这样的轮**。加步这一族之前，
同样一轮是 **6 条**（`turn/*` 2 + `model/*` 2×2），1024 ≈ 170 个轮——涨了近一半，余量仍是百倍级。

**为什么这个上界仍然成立**：一次断线缺口是**秒级**的（客户端立刻用手上的 `factSince` 重新声明，
而 `factSince` 就是它收到的最后一条事实那一行的行号——同一张用例表钉住这一点）。所以 1024 是
**内存上界**，不是对「离开一整轮的人」的承诺；那句话在 `harness.edge.mux` 里本来就是这么写的。

**没有改这个数**：读数没有给出要改它的理由。
