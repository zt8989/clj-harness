# 03 — `write` / `undo` 拿锁的顺序反过来：先 session，再 path

**What to build:** `cap.hashline.write/perform!` 与 `cap.hashline.undo/perform!` 也把
`store/with-session-lock` 当**外层**锁，`with-path-lock` 在里面——与 `replace` 一个形状。
两种拿法同时存在于同一条消息里的两个工具调用之间，就是**死锁**。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

规矩写得明明白白：

```clojure
;; kernel/hashline/store.clj:411-412
;; ALWAYS THE OUTER LOCK, before any `with-path-lock`. A fixed order is what keeps
;; two callers from deadlocking against each other.
```

`replace` 照做了（`replace.clj:555-563`：`with-session-lock` 包着 `with-path-lock`）。
`write` 与 `undo` 反着来：

```clojure
;; hashline/write.clj:112-123
(store/with-path-lock path                     ; 先拿 path
  (fn []
    (check-no-echo! thread-id path content)
    (spit f content ..)
    (store/forget-file! thread-id path)
    (store/clear-undo! path)
    (.. (auto-read-note thread-id path)))      ; :82-89 -> serve/read! -> sync!
;; hashline/serve.clj:76-80  sync! 里是
(store/with-session-lock thread-id             ; …再拿 session
  (fn [] (store/with-path-lock path ..)))
```

```clojure
;; hashline/undo.clj:109-148
(store/with-path-lock path                     ; 先拿 path
  (fn [] .. (undo-rows thread-id path)))       ; :45 -> serve/read! -> session 锁
```

**死锁是具体的，不是理论的**：一轮 run 里的工具调用**并发**跑（`loop.clj:113-125`），
所以「一条消息里 `write` 一个文件、同时 `read`/`replace` 同一个文件」是普通的一条消息。

- 线程 A（`write`）：拿着 `path(f)`，等 `session`
- 线程 B（`replace` 或 `read` `f`）：拿着 `session`，等 `path(f)`

环闭了，`ReentrantLock` 的可重入**只帮同一个线程**，所以没有救。后果是那一轮 run **永远不结束**：
`run-chan` 不关，客户端挂在那里，进程里两条线程永久占着。

## 要改成什么

**把 `with-session-lock` 加到最外层**，包住整个 `perform!` 的锁区，`with-path-lock` 留在原处
（session → path 的顺序就成立了）。**不要**用「把 auto-read 挪到 path 锁外面」这种改法：

- 它确实能解开死锁（读会自己按 session → path 拿），但 `undo` 会新开一个坏窗口：
  `restore!` 落完盘、锚点已按恢复后的内容算好，而 `undo-rows` 的读发生在锁外，
  这中间别人改一次文件，交回去的行就不再是刚恢复的那份。
- `write` 的 docstring 已经写了那条次序的理由（`:103-108`：「auto-read 在 release **之后**，
  因为正是那次 release 让读去铸一套新锚点而不是把刚作废的还回来」）——那是
  `store/forget-file!` 的次序，不是**锁**的次序，两件事别混。

加锁区变大**不**改变语义：`with-session-lock` 串的是**铸锚点**，而 `write`/`undo` 本来就在铸
（`forget-file!` / `restore!` / `advance!` 都在这条路上）。一句话说清为什么要外层锁，写进这两个
函数的 docstring：**锁的顺序是数据的一部分，反着拿就是死锁。**

## 验收

- [ ] `write/perform!` 与 `undo/perform!` 的 `with-session-lock` 在 `with-path-lock` **外面**，
      且 `store.clj:411` 那条规矩在这两个函数里各有一行指针
- [ ] **死锁回归用例**，必须**在有限时间里红**、不许挂住套件：
      同一个 `thread-id`、同一个文件上并发跑 `write` 与 `read`（各一条线程/`future`），
      两边都用 `(deref f 15000 ::timeout)`，断言两个都不是 `::timeout`；
      `undo` + `read` 再一条。今天两条都会走到 `::timeout` ⇒ 断言失败 ⇒ 用例红（**这就是要的效果**：
      红而不是挂）。用例本身要用 `timeout` 包住整轮，别把红写成挂
- [ ] 既有用例一字不改地通过：`harness.cap.hashline.write-test`、`…undo-test`、`…replace-test`、
      `…batch-test`、`…store-test`（锚点释放、撤销历史清空、auto-read 的三条语义都不许动）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.write-test 'harness.cap.hashline.undo-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.write-test 'harness.cap.hashline.undo-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
