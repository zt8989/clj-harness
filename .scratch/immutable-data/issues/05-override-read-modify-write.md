# 05 — 会话覆盖的读-改-写要在一个原子操作里

**What to build:** 「读本会话现在的选择 → 折上这次改动 → 写回去 → 记审计行」变成**一次**原子操作，
并且记进审计行的 `before → after` 一定是**真的发生过**的一次转变。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

两处调用方都是同一个形状，只差在宿主线程（一个在 http-kit 的请求线程上，一个在工具线程上）：

```clojure
;; edge/http.clj:1138-1147
(let [before (providers/override-for thread-id)              ; 读
      answer (try {:ok (providers/resolve-override (merge before change))} ..)]
  ..
  (let [after (providers/set-override! thread-id (merge before change))]   ; 改-写
    (log! thread-id nil "provider/session-changed" {:before before :after after ..})))

;; cap/tools.clj:294-303
(let [before   (providers/override-for thread-id)
      resolved (providers/resolve-override (merge before change))
      after    (providers/set-override! thread-id (merge before change))]
  (providers/record-provider-change! thread-id before after "session-configure" ..))
```

`providers/set-override!`（`providers.clj:910-935`）自己是一次干净的 `swap!`，但**判定与写入分在
两个原子操作里**。两个并发改动（HTTP 端点 + `session-configure` 工具，或者同一个模型连点两次模型选择器）
都读到同一个 `before`，后写的那个吃掉前一个 ⇒ **丢更新**；而且两条 `provider/session-changed`
审计行都宣称一个**从未存在过**的 `before → after`。日志是给人事后读的，它会读出一个假的时间线。

`:1124-1129` 的清空分支还有第二个小毛病：先清后记，`log!` 抛了的话改动已经落地而没有审计行。

## 要改成什么

`providers` 出一个原子的入口，形状是 CAS 重试（`compare-and-set!` 比的是整个 map 的同一性，
所以读者手里那个 `before` 正好当期望值用）：

```clojure
(defn swap-override!
  "把 CHANGE 折进 THREAD-ID 的会话覆盖，返回 [before after]（都是规范化后的选择，nil = 没有）。
  CHANGE 为 nil 是清空。解析不过的改动**一个字节都不写**：RESOLVE 在写之前跑，抛了就整条退出。
  审计行要记的 before/after 就是这里返回的一对 —— 它们是真实的相邻状态，不是调用方自己的两份拷贝。"
  [thread-id change]
  (loop []
    (let [m      @session-overrides
          before (get m thread-id)
          sel    (when change (selection (merge before change)))]
      (when sel (resolve-override sel))            ; 抛 ⇒ 什么都没写
      (if (compare-and-set! session-overrides m (if sel (assoc m thread-id sel) (dissoc m thread-id)))
        [before sel]
        (recur)))))
```

**RESOLVE 不能塞进 `swap!` 的函数里**：那个函数会因竞争被重跑，而它会抛。CAS 这个形状把
「先证明这次改动服务得起来」和「原子写入」都保住了，两处调用方都改成用它，并用它返回的那一对
去记审计行。`set-override!` 留着（`providers.clj` 自己的用例在读它），但请求路径不许再用
「读一次 + 写一次」。

**留下不修的**：状态写入与审计行写入是两次 I/O（一个内存 swap、一个文件 append），
两者之间的窗口无法消掉。**不许**为此把 `log!` 塞进 swap 里；只要保证 `before/after` 这对值是
真实转变即可。

## 验收

- [ ] `providers` 有一个原子的入口返回 `[before after]`；`edge/http.clj` 与 `cap/tools.clj`
      两处都不再有「先 `override-for` 再 `set-override!`」的写法
- [ ] 解析失败仍然**不落地**：一个不存在的 model / provider 被拒后，`override-for` 答的还是改之前那个值
- [ ] **丢更新用例（今天红）**：8 条线程各改一个**不同的**旋钮（同 `thread-id`），每条重复 50 轮，
      断言最终的选择里 8 个改动**全在**。今天会红；若 50 轮不足以稳定红，加大轮次——
      **不许**留一条会偶发的用例
- [ ] **审计行用例**：每次调用返回的 `after` 恰好是 `before` 折上自己那次 change（规范化后），
      且最后返回的 `after` 等于最终状态；清空分支同样返回一对真实的 `[before nil]`
- [ ] 既有用例一字不改通过：`harness.cap.providers-test`、`harness.edge.http-test` 里
      `/api/model` 与 `session-configure` 那两组
- [ ] 定向跑：
      `timeout 300 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.providers-test 'harness.edge.http-test) (let [r (clojure.test/run-tests 'harness.cap.providers-test 'harness.edge.http-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
