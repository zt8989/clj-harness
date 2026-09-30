# spec: 记录窗口的那盏 10Hz 时钟 —— 一次实测、一条已落的票、一条未落的

**一句话**：`harness-session-sweeper` 每 100ms 为**每一个正在写的会话**把**整份记录**重读重解析一遍；
一份 23.7MB / 53732 行的记录单次要 **254–321ms**，而节拍是 100ms ⇒ 这个线程被钉死在 100%，且它和
每 5 秒的 `sweep!`（空闲会话回收）**共用一条线程**。已按"把折从上次停下的地方继续"修掉一半；
投影那盏 2 秒的盲钟仍未改成监听。

2026-09-30 对着活着的进程（pid 235，`clojure.main -m harness.edge.http --port 8080`）实测。
方法与读数都是实测，不是推算。

## 读数

**谁在烧 CPU**（进程活了 6250s，累计 CPU 1980s ≈ 31.7% 常驻）：

| 线程 | 累计 CPU | 占进程 CPU |
|---|---|---|
| `harness-projection` | 460s | 23% |
| `harness-session-sweeper` | 430s | 22% |
| GC workers（8 条合计） | 222s | 11% |
| G1 Conc（2 条合计） | 226s | 11% |

**JFR（90 秒，process 空闲、只有我这一场会话在流式输出）**：1429 个执行采样里 **693 个（48.5%）**
落在 `harness-session-sweeper` 上，热点是 `clojure.data.json/slow_read_string`（8.5%）、
`RT.first/next/seq`、`readLine`。

**调用栈（三次线程转储一致）**：

```
session.clj:1378  10Hz 时钟（growth-interval-ms = 100）
└ ring-growth! → ring! (session.clj:389)
  └ mux/subscribe! 的 watch (mux.clj:116)
    └ http/mux-watch! 的 push! (http.clj:5178)
      └ window-page → record-entries (http.clj:4737)
        └ replay/read-records   ← 整份文件
          └ json/read-str       ← 每一行
```

**单次代价（进程内直接计时，随文件线性）**：653KB/676 行 ≈ 10–15ms；1.4MB ≈ 60ms；
1.88MB ≈ 81ms；3899 行 ≈ 207ms；**23.7MB/53732 行 ≈ 254–321ms**。10Hz 节拍下后者需要
**2.6–3.2 个核**——一个线程给不出，`scheduleAtFixedRate` 只会背靠背连跑。

**内存那半：没有泄漏。** 五分钟窗口：老年代稳定 55%（cap 98–138MB，无上升趋势）、RSS 在
84MB↔566MB 之间被 GC 反复收回、线程数 72–114 无单调增长；`jcmd GC.heap_info` 事后 used 332MB /
committed 1056MB / `-Xmx` 4GB。**唯一常驻的垃圾来源就是上面这个循环**（空闲进程每 ~1.2–2 秒一次
Young GC，正是它）。

**分配榜不可信**：JFR 的 `allocation-by-class` 报 `ReentrantLock 87.6%`、`allocation-by-thread`
报 sweeper 89.4%，但那条样本 `weight = 859.7 GB`、父帧链不成立——采样伪影，本 spec 不拿它当结论。

**磁盘那半（真·无限增长）**：`harness.db` 620MB，其中 `hashline_ownership` 一张表 **1,987,215 行**
/ 110 个 thread_id / 1446 个 path，没有任何按时间的清理；`projects/` 记录树 1.9GB。

## 票 01（**已落地**，提交 `record-window：窗口的 10Hz 推送改成按字节续折`）

**做了什么**：`replay/rows-after` 按字节偏移读"上次之后追加的部分"（偏移只越过换行，所以半行的线
留给下一趟、不丢，也不被误读）；`replay/entries-fold` / `entries-of-fold` 把 entries 的折**从上次
停下的那一行继续**。`window-page` 用一条 per-thread 的游标（`harness.edge.http/record-entries-resumed`）
换掉原来的 `read-records` 全量读。

**为什么这是可证明等价的**：`entries` 就是 `(reduce entries-step (entries-init) (map-indexed vector records))`，
`entries-answer` 是纯的（flush 尾组但不改状态）。同一批 `[行号 行]`、同一个 reduce，分两段与整份
逐条相同——不是"差不多"，是同一个函数。`replay-test` 有两用例钉住：分两段折 == 整份折，以及
半行不算一行、补全后下一趟读到。

**游标随 run 存亡**：run 结束即 `dissoc`。所以代价上界是"同时跑着的会话数"（`max-running` = 8），
而留住的是一份该会话的 entries（内存换 CPU，账写在这里）。

**验收**：`harness.edge.replay-test` 40 tests / 224 assertions 绿；`harness.edge.http-test`
121 tests / 1330 assertions 绿（窗口路线行为未变）。

## 票 02（未落）：投影只做监听，不要再盲钟

**现状**：`harness.edge.projection/start!` 用 `interval-ms = 2000` 的 `scheduleAtFixedRate` 跑
`project!`，而 `project!` 每一轮都要 `listed-sessions`（一条查询）+ **对 263 个会话逐个 `log-for` +
`stat`**。实测一轮 `projection/lag` 约 **100–127ms / 2s ≈ 5% 一个核**，永远在跑，且与"有没有新字节"
无关。昨天那一轮已经修掉大头（`memory-hygiene` 票 04：每会话两条连接 → 一轮一条查询，
3914ms → 现在这个数）。

**要做**：把触发从"盲钟"换成"监听"——`harness.infra.stream/listen-every!` 已经有一个进程级监听
（`session.clj:1399`，namespace load 时装上，race-free），它每次写一行都拿到 `{:thread-id .. :row ..}`。
监听器只**标记脏会话**，再合并成一轮只投影**脏的那几个**（`read-session!` 已经是按 byte offset 增量
的）。要注意的：

- 别每写一行就跑一次事务（一次流式回答几千行）——合并（一个短的 debounce 或一个"下一拍"）。
- 保留 `project!`（整轮）与 `rebuild!`：测试与"重算"都靠它们，`projection_test.clj` 直接调
  `project!`，不驱动时钟。
- 按 ADR 0008 的口径改 `docs/architecture/home-and-storage.md` 与那条现状注记。

**验收**：空闲且无人写作时的投影 CPU 归零（当前 ~5% 常驻）；有写入时只投影写过的那个会话；
`harness.edge.projection-test` 保持绿。

## 另一处（顺手指出的，不属本族）

`harness.cap.hashline.store` 的 `hashline_ownership` 只为读而增：每 `read` 一次就为返回的每一行
插一行锚点归属，只在该 (thread, path) 被重读时删。200 万行/620MB 就是这么来的。**没有按时间的
清理**。会话被删时会一起清掉（见 `.scratch/session-lifecycle/`），但"活着的会话攒下的那些"还没有
人管——要新开一票。
