# 06 — raw 读取改成流式：走一遍、折一遍，不物化整份

**What to build:** `harness.edge.replay/read-lines` 今天 `(str/split-lines (slurp f))`——先把整份文件读成
一个 String，再切成 362,359 个 String 的 vector；`lines->records`（`(mapv read-row …)`）再把它变成
362,359 个 row map；`read-records` 又 `butlast`/`vec` 拼一遍。thread `bbcd4ae4-…` 实测：raw rows 常驻
≈ **307 MB**（文件本身 129.7 MB），而所有消费者只是**折**它——没有一处需要同时拿着 362,359 行。

改成：**一个流式行源 + 一次走的 fold**。一行一行读、一行一行 parse、折进结果就丢；整份文件任何时候
都不同时在内存里。

**Blocked by:** —

**Status:** ready-for-agent

## 要落地的判断

1. **行源**：`clojure.java.io/reader` 显式 UTF-8（本仓铁律：绝不信 JVM 默认 charset）上的 `line-seq`；
   不再 `slurp` + `split-lines`。空文件是空序列，不是错误。
2. **strict 规则原样保留**（这是记录读取的契约，一个字都不改）：
   - 中间一行坏了 ⇒ **硬失败**、点名行号（`read-row` 的 `:not-json` / `:old-contract` / …）；
   - **最后一行**可能是写者正在写的那半行 ⇒ **丢掉**（`read-records` 的规矩）。
   - 「丢最后一行」要**流式地做**：`butlast` 在 362k 的懒序列上是递归、会爆栈；`drop-last` 也要整份过
     一遍。用「先攒着这一行，读到下一行才把上一行交出去，EOF 时丢掉攒着的」这种**滞后一行**的写法。
3. **只留一条路，不要两套并存**：`lines->records` 返回**懒序列**，`read-records` 保持返回 vector、
   **建在流上**（不再单独 slurp）。折的调用者改走 fold，不再先要一个 vector：
   `entries`、`records->stats`、`records->context`、`records->trajectory`、`record-state`、
   `records->pressure`。`read-records`（vector）留给**真需要整个数组**的调用者（测试、`rebuild`、
   离线脚本）。
4. **`compaction-facts` / `prune-facts` 里的 `(vec records)` 是拦路虎**：它们用 `keep-indexed` 取
   `:seq`（行号）。改成在 seq 上直接 `keep-indexed`，`vec` 拿掉——否则一 `vec` 就把整份物化回来，
   前面的流全白做。
5. **别破坏 strict 那条路**：`harness.edge.http` 的 rebuild（`(vec (lines->records (read-lines …)))`）
   与 `record-entries` 的门是「文件应当完整」——语义不变，只是底层换成流。
6. **与 03 的分工**：03 让压力表**别每轮读**；06 让「读的那几次」**不再整份进内存**。互补，都不改
   记录的字节。

## 验收

- [ ] 在 thread `bbcd4ae4-…`（129.7 MB / 362,359 行）上折一遍（`entries`/`stats`/`trajectory` 任一），
      **峰值堆**是 O(折出来的对话)、不再是 O(文件)——给出前后数字
- [ ] `src/` 的折路径上不再有 `slurp` / `split-lines` / `(mapv read-row …)` 整份物化（按名字查）
- [ ] 中间坏行仍然硬失败且点名行号；**最后一行**坏/半仍然被丢（两条用例，与今天同断言）
- [ ] `read-records` 对同一份记录返回**逐字节相同**的 vector（与旧实现对同一 fixture 的回归）
- [ ] CJK 记录不受影响（UTF-8 显式，不靠默认 charset）——既有用例照旧绿
- [ ] `clojure -M:test -m harness.test-runner` 全绿

## Comments

2026-09-24 — 老板追加：「把读取 raw 改成流式」。与 03 是一对：03 去掉「每轮读」，06 去掉「读一次就
物化整份」——raw 常驻 307 MB 的实测见 spec 的实测表。

2026-09-24 — 落地（`.worktrees/model-surface-and-meter`）。`read-lines` → 显式 UTF-8 的懒行源（读完即关，
`closing-lines`）；`lines->records` → 懒（`map-indexed read-row`）；新增 `fold-records`（reader 用
`with-open` 关在自己里面，RF 收 `[line-index row]`）与 `fold-entries`；`read-records` 建在流上
（`(fold-records f [] (fn [acc [_ row]] (conj acc row)))`），保留「丢半行、中间坏行按行号硬失败」。
`entries` 与 `stats/records->stats` 改成**单趟折叠**（`entries-step` / `stats-step` + `entries-answer` /
`stats-answer`），`log-stats` 走 `fold-records`；`compaction-facts` / `prune-facts` 去掉 `(vec records)`。
`lines->records` 现在懒了，所以 `replay_test` 里两处「坏行要抛」的用例加了 `doall` 强制。

基准（126 MB 合成日志 / 231,289 行，采样线程读 `total-free`）：`-Xmx200m` 下旧的
`slurp + split-lines + mapv` OOM、新的 `read-records` 也 OOM，而 `(fold-entries f)` 通过，峰值增量
**36 MB**（`-Xmx512m` 时三者 438 / 307 / 110 MB）。`read-records` 仍 vector 化，所以「读一次就物化」
只对 **fold 路径**解决；用 `read-records` 的路由（rebuild / 窗口 / live 读）还是 O(文件)。
新增用例：`read-records` 丢半行 / 中间坏行 / 缺文件 / 空文件，`fold-records` 与 `read-records` 逐条相同
且带行号，`fold-entries` == `entries (read-records f)`，`log-stats` == `records->stats`。
全量 1219/13494，唯一红是预存在的 `claims_test` 用例。
