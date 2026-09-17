# 08 — 首次开库的判定要在写事务里

**What to build:** 「这个库是新家、要不要迁移、哪些步骤还没跑」这套判断与它引发的写入放进**同一个写
事务**。两个请求同时撞上「家是新的」时，两个都要拿到能用的连接，不许有一个以 `:open-failed` 失败。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

**库是第一个碰库的请求才开的**：`edge/http.clj` 里没有一个 `db/` 调用，所以
`infra/db.clj` 的 `ensure-connection!` 是在第一个碰库的路由线程上跑的——而 http-kit 一个请求一条线程，
所以「开机后头两个请求」正好是这段代码唯一的考试。

```clojure
;; infra/db.clj:843-862
(let [seen (inspect f)]                                ; 判定一：这是不是我们的库
  ..
  (let [created? (not= :ours (:state seen))            ; 判定二：要不要建
        outcome (connect-and-migrate! f steps created?)] ..))

;; infra/db.clj:733-742  判定三：哪些步骤还没跑 —— 读在写事务外面
(ddl! c steps-table)
(let [done (applied-steps c)                           ; 事务外读
      todo (remove #(contains? done (:name %)) steps)]
  (doseq [{:keys [name present? run]} todo]
    (in-transaction c (fn [] ..))))                    ; 每一步各自一个事务
```

三次判定都不在事务里，而写入在。于是两个并发请求都算出 `created? = true`、都读到空的 `done`，
都去跑同一步：抢到写锁的那个建表成功，**抢输的那个** `CREATE TABLE` 撞上「已存在」，
`damage?`（`:339-342`）把它正确地判成**不是**损坏，于是它作为 `:open-failed` 抛出去 ⇒ 那个请求 500。
坏库那条路还有一个同族的小口子：两个线程都看到 `:damaged` 就都去 `quarantine!`，
第二个在 `.exists` 与 `Files/move` 之间失去源文件时会以 `:quarantine-failed` 抛
（`db.clj:201-212`）——那本该是「别人已经搬走了」，不是失败。

## 要改成什么

**判定一是文件级的、留着不动**（`inspect` 必须先看头部才能知道这是什么文件）。改的是后两次：

1. `migrate-connection!` 里 `applied-steps` 的**读**与它引发的**写**放进**同一个**
   `BEGIN IMMEDIATE` 事务（步骤表 + 全部待跑步骤一起）。sqlite 只有一个写者，所以另一个线程要么
   等、要么进去之后看到 `done` 里已经有那些步骤——两条路都对。
   `PRAGMA application_id`（`:728-732`）**仍要**在 WAL 之前、单独一次事务，那段次序是 load-bearing
   的（namespace docstring 写了），别顺手并进去。
2. `quarantine!` 容忍「源文件已经不在了」：那是**别人已经搬走**，不是失败；`recovery-log` 里
   同一条记录不要记两遍（`:214-215` 现在是 `swap! conj`，无脑追加）。

## 验收

- [ ] `migrate-connection!` 里 `applied-steps` 的读与 `record-step!`/`run` 的写在同一事务里；
      `PRAGMA application_id` 的位置与事务边界一字不改
- [ ] `quarantine!` 在「源文件已被别人搬走」时**不抛**，且不为同一条记录追加第二次
- [ ] **首开并发用例（今天红）**：在一个全新的 home 上，N=8 条线程**同时**第一次碰库，重复 20 轮，
      断言 8 条全部拿到能用的连接、没有一条以 `:open-failed` / `:quarantine-failed` 失败。
      **实测要红**：跑一次改之前的代码确认它真的红；不够就加大轮次，**不许**留一条会偶发的用例
- [ ] **「第二次开库什么都不改」仍然成立**：既有的那条指纹用例（同一文件开两次，mtime 与字节都不动）
      原样通过——把读塞进写事务最容易踩的就是这里
- [ ] 既有用例一字不改通过：`harness.infra.db-test`（迁移链、按名字记账、坏库隔离、外来库拒绝）
- [ ] 定向跑：
      `timeout 300 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.infra.db-test) (let [r (clojure.test/run-tests 'harness.infra.db-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
