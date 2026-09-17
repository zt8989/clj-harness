# 06 — 日志重配只许一个线程去做，appender 不许堆起来

**What to build:** 「root 变了没有」的判断与「摘掉旧的、装上新的」合成一次**单飞**的动作：
并发叫 `ensure!` / `configure!` 时，appender 永远恰好两个（console + file），一行日志不许被写两遍。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

```clojure
;; infra/logging.clj:70-72
(def ^:private configured-root (atom nil))
;; :163-174  —— 破坏性的一串：先全摘，再装两个，最后才记下新 root
(.detachAndStopAllAppenders root-logger)
(.setLevel root-logger Level/INFO)
(.addAppender root-logger (console-appender out))
(try (.addAppender root-logger (rolling-appender root)) ..)
(reset! configured-root root)
;; :182-186  —— 判定在锁外面，且判定与重配之间没有互斥
(let [root (str (home/root))]
  (when (not= root @configured-root)
    (configure! {:root root}))
  root)
```

`configure!` 里那句注释说得很清楚它怕什么（`:160-162`：「a reconfigure that stacked would double
every line in the file and on the console after every root change」），但**防它的机制只是
`detachAndStopAllAppenders` 在 `addAppender` 前面**——前提是**只有一个线程**在重配。
今天不是：两个线程同时看到 `@configured-root` 是旧值，就同时重配，`detach/detach/add/add` 交错，
于是两个 appender 各装两遍 ⇒ **每行日志写两次**，此后一直如此。
另外 `harness.infra.log` 的每一行都调 `ensure!`，写日志的线程（包括在工具线程上触发 hook 的那些）
与重配是同时在飞的。

`(locking out ..)`（`:103`）只锁住**一个 appender 写字节**那一段，锁不住摘/装这个序列——两件事，
别指望前者管后者。

## 要改成什么

**单飞**：一把进程内的专用 monitor，`configure!` 与 `ensure!` 都在它里面做「判断 + 重配」，
判断也在锁内重新读 `(home/root)`（否则锁外读到的那个值已经过期）。

**顺手把「搬 root 那一瞬间到达的那一行」的归属定下来，并写进 docstring。**
首选做法是**不摘了**：appender 的名字是稳定的（console 那个就叫 `"console"`，`:113`），
logback 的 `addAppender` 按名字替换旧的那一个——这样既不会堆、也**不漏**中间那一行。
若实测与预期不符（例如滚动的那个 appender 名字带日期，名字不稳定），就退回「先摘后装」，
并在 docstring 里写明：**搬 root 的窗口里到达的那一行会丢**。
判据是这份日志的性质：`logs/harness.infra.log` 是**诊断**（`home-and-storage.md:67`：没有任何会话读它），
诊断日志里**一个洞比一条重复更坏**。

## 验收

- [ ] `configure!` 与 `ensure!` 共用同一把 monitor，且 `ensure!` 的 `(home/root)` 读取与判定都在锁内
- [ ] **并发用例（今天红）**：N=8 条线程同时叫 `ensure!`（root 在调用前先挪一次，让它们都看到新值），
      跑完断言 root logger 上的 appender **恰好 2 个**。今天会红（数得出来 3、4 个）
- [ ] **重复行用例**：同上并发场景里每条线程各写一行日志，读回来的文件里**每句话恰好一次**
- [ ] docstring 写清了中间那一行会怎样（不漏 / 会丢，二选一，与实测一致）
- [ ] 既有用例一字不改通过：`harness.infra.log-test`（含「同一句话同时到了 console 与文件」那条）
- [ ] 定向跑：
      `timeout 180 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.infra.log-test) (let [r (clojure.test/run-tests 'harness.infra.log-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
