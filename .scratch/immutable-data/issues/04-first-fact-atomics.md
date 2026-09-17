# 04 — 「本会话第一次」这类事实，一次原子操作判完

**What to build:** `edge.http` 的两个「首见」判断（`SessionStart` 触发过没有、`provider/init`
行写过没有）改成**一次**原子操作决定归属：谁拿到那一次，谁去跑副作用。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

```clojure
;; edge/http.clj:206-228
(defonce ^:private init-logged (atom #{}))
(defn- init-logged? [thread-id] (contains? @init-logged thread-id))
(defn- mark-init-logged! [thread-id] (swap! init-logged conj thread-id))
(defonce ^:private session-started (atom #{}))
(defn- session-started? [thread-id] (contains? @session-started thread-id))
(defn- mark-session-started! [thread-id] (swap! session-started conj thread-id))
```

调用处一次 deref、之间有副作用、再一次写：

```clojure
;; edge/http.clj:461-474
(when-not (session-started? thread-id)
  (hook/emit :session-start {:source "new"})      ; 副作用
  (mark-session-started! thread-id))
(when (and (nil? (providers/pinned-provider thread-id))
           (not (init-logged? thread-id)))
  (log! thread-id run-id "provider/init" ..)      ; 副作用
  (mark-init-logged! thread-id))
```

每个 `run-agent!` 是**自己的** `async/go`，同一个 `thread-id` 的两个 run 完全可以重叠。两个都看到
`false` 的时候，`SessionStart` 触发**两次**、`provider/init` 行写**两条**——而
`docs/architecture/edge.md:110` 写的是「每 thread 恰好一行」。这不是崩，是**账对不上**：
hook 引擎的审计行会多出来一条，读时间线的人会以为这个会话起了两次。

## 要改成什么

一个**判定即占位**的入口，副作用只在拿到 `true` 的那条线程上跑：

```clojure
;; 形状：一次 swap-vals!，前后一比就知道是不是我加进去的
(defn- claim-once! [^clojure.lang.Atom a thread-id]
  (let [[before _] (swap-vals! a conj thread-id)]
    (not (contains? before thread-id))))
```

调用处变成：

```clojure
(when (claim-once! session-started thread-id)
  (hook/emit :session-start {:source "new"}))
```

**两个事实各留一个 atom，不许合成一个**（`:218-224` 的理由是对的，别顺手合并）：被脚本 pin 服务的
会话**不写** `provider/init` 行，但它**确实**起了，两个事实本来就不同。

## 验收

- [ ] `edge.http` 里不再有「`contains?` 一下、干点事、再 `conj` 一下」的写法；
      两个事实各自只有**一个**判定即占位的入口
- [ ] **并发用例（确定的，不靠时序）**：N=16 条线程拿同一个 `thread-id` 同时叫那个入口，
      断言**恰好一条**拿到 `true`、其余全是 `false`。这条今天不可能过（今天没有这个入口），
      所以它是「新的原语是对的」的证明，而不是「旧代码会红」的证明
- [ ] **顺序用例（今天必须红）**：同一个 `thread-id` 的两条线程各跑一次 run（用现成的假 provider），
      断言 `SessionStart` 的审计行**恰好一行**、`provider/init` 行**恰好一行**。
      为了让今天真的红：两条线程用闩锁/promise 让它们都进到那段之前再一起走。
      若闩锁做不出来，改用**计数断言 + 多条线程**（比如 8 条同 `thread-id` 的 run），
      今天会稳定地多写；**不许**留一条会偶发的用例
- [ ] 两个事实仍是**各自一个 atom**，且 `:218-224` 那段理由原样保留
- [ ] 既有审批/会话用例一字不改通过：`harness.approval-test`、`harness.edge.http-test`
- [ ] 定向跑：
      `timeout 300 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.edge.http-test 'harness.approval-test) (let [r (clojure.test/run-tests 'harness.edge.http-test 'harness.approval-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
