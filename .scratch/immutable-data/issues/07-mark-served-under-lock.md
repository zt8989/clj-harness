# 07 — `mark-served!` 与它依据的那次读，在同一个会话锁里

**What to build:** 「已展示」这份账（`served`）不再可能在一次并发编辑之后被**并回**已经释放的锚点：
`sync!` 的那次读与 `mark-served!` 的那次写，中间的缝要合上。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

```clojure
;; hashline/serve.clj:107-115
(let [view (sync! thread-id path content)            ; 锁内（session -> path）
      page (reading/preview content (:anchors view) ..)]
  (store/mark-served! thread-id path (:shown page))  ; 锁外
  (assoc page :anchors (:anchors view)))
;; hashline/grep.clj:281-283 同一个形状
(result (render thread-id prepared ..))
(doseq [[abs anchors] (:shown result)]
  (store/mark-served! thread-id abs anchors))
```

`mark-served!` **自己**是干净的：读 `served` 与写并集在**一个** `db/with-transaction` 里
（`store.clj:268-279`），它的 docstring 也正是拿这一点防丢更新的。问题在那**两个调用方**：
`sync!` 读出 `:anchors` 之后、`mark-served!` 写下去之前，锁是空着的。夹在中间的一次并发编辑

- 在 `advance-on!`（`store.clj:220-231`）里做 `served ∩ 存活锚点` 的**修剪**——它按设计就是把
  已经释放的锚点从 `served` 里剔掉，
- 而紧接着的 `mark-served!` 又把它们**并回来**（它只并，永不剪）。

结果：这份账里出现已经**不再存在于 `:anchors`** 的名字，也就是「模型看过一个已经不存在的锚点」。
后果是有界的（所有权表 `hashline_ownership` 才是拒绝借锚点的依据，`served` 是「见过没有」那份
事实），但它是**错的**：`served` 与 `:anchors` 之间的那条不变量（前者是后者的子集）在并发下不成立，
而这条不变量正是 `:210-219` 那段推理的前提。

## 要改成什么

把两个调用方的**读 + 标**放进**同一个 `store/with-session-lock`**。

**只需要会话锁**，不必再套 path 锁：`served` 是 `(path, thread_id)` 一格，能改它的编辑属于**同一个
会话**，而同一个会话的编辑本来就走会话锁。`sync!` 自己会拿 session → path，锁可重入，套着没事
（顺序仍是 session 在外）。

```clojure
(store/with-session-lock
 thread-id
 (fn []
   (let [view (sync! thread-id path content)
         page (reading/preview content (:anchors view) ..)]
     (store/mark-served! thread-id path (:shown page))
     (assoc page :anchors (:anchors view)))))
```

`grep.clj` 那一处同样。两处各留一句 docstring：**为什么标之前必须先拿锁**（因为 `advance-on!` 剪、
`mark-served!` 并，两者之间不能有人插进来）。

## 验收

- [ ] `serve!` 与 `grep/perform!` 里，`sync!`/`render` 与 `mark-served!` 在同一个
      `with-session-lock` 里；两处都有一句 docstring 说清为什么
- [ ] **用例是确定的**（不许写成「并发跑跑看」）：把 `store/mark-served!` 用 `alter-var-root`
      换成一个替身，替身先断言**当前线程正持有本会话的 session 锁**再转调原函数；
      跑一次 `read` 与一次 `anchor_grep`，两次都必须断言成立。
      今天的实现断言为假 ⇒ 用例红；改完为真。锁对象经 `#'store/lock-for` 与
      `#'store/session-locks` 拿得到，`(.isHeldByCurrentThread ^ReentrantLock l)` 就是那条判据
- [ ] **不变量用例**：一轮里并发跑「同一个文件的 `read`」与「同文件的 `replace`」若干轮，
      跑完断言 `served` 里每一个锚点都在 `:anchors` 里（今天可能红——它作为第二条保险，
      不许取代上面那条确定的用例）
- [ ] 既有用例一字不改通过：`harness.cap.hashline.read-test`、`…grep-test`、`…replace-test`、
      `…store-test`（`mark-served!` 只并不剪、`advance!` 只剪不并这两条语义都不许动）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.read-test 'harness.cap.hashline.grep-test 'harness.cap.hashline.store-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.read-test 'harness.cap.hashline.grep-test 'harness.cap.hashline.store-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
