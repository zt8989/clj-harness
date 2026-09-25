# 03 — 记录在长也算「会话变了」：刷新回来的那一轮服务端要说话

**做什么**

一场正在被回答的会话，**服务端**要在这一轮还在写的时候就告诉看着它的读者「又长了一点」。

今天不是这样：门铃（`harness.kernel.session/watch!`）只在**会话表**变的时候响——动作的条目进表
（`append!`）、run 登记（`run-started!`）、终帧折叠回来（`settle!`）、行落盘（`land!` / `land-at!`）。
run 进行中写下的那些行**一次都不响**，而窗口画的正是那些行（有 run 在跑时窗口读**记录**，不是内存）。
于是刷新落回一场还在跑的会话：尾页把那一轮画到「刷新那一刻」，之后**一条帧都不来**，直到 `settle!`
一响，整轮一起出现——就是主人看到的那一格。

要做的是：**唯一那条写路径**（`harness.edge.http/log!`）每写一行就在会话上留一个标记
（`sessions/record-grew!`），**每 100ms 一次 tick**（`harness.kernel.session/growth-interval-ms`）把
这一段时间里的所有标记折成**一个 ring** 发给看着这场会话的连接。

**Blocked by:** None — 与票 01 一起落地（票 01 的合并语义要有帧可合才有意义）。

**Status:** 已落地（2026-09-25）。

- [x] run 进行中的每一行都让这场会话的 watcher 被 ring 到。`test/harness/edge/http_test.clj` 的
      `a-run-that-is-being-written-tells-the-window-watcher-the-record-grew`：run 停在工具缝上时，
      ring 的那一瞬记录里**已经有这个 run 的帧**（没有这一票就一条都没有）。
- [x] 一次 tick 里的所有标记折成一个 ring；没人在看的会话不响（读者下次连上拿尾页）。
      `test/harness/edge/sessions_test.clj` 的 `the-record-growing-rings-a-watcher-and-only-a-watched-one`。
- [x] `start!` 真把这个 tick 排上钟（`the-growth-doorbell-runs-on-the-clocks-own-tick`）；顺手修了那个
      「停过的钟再也起不来」的旧毛病（`stop-clock!` 把槽位清掉），否则同一个 JVM 里只要有人 `stop` 过一次，
      之后谁都再也听不到。
- [x] 代价写清楚了：ring 的代价是**读者**把整场会话重读一遍，标记是在 run 自己的帧循环里留的，所以
      **不能**按行 ring；`docs/architecture/edge.md` 的窗口一节记了这条。
- [x] 实测脚本 `dev/scratch_refresh_watch.clj`：run 跑着的时候回答的字节数一路涨（2 → 10 → 20 → 40 →
      55 → 70 → 90…），ring 数也一路涨（4 → 28）。没有这一票时同一时刻 ring 数**不动**、窗口答的还是旧条目。
