# 02 — 一轮的批计划按 `thread-id` 分家，并且在工具跑起来之前就登记好

**What to build:** `kernel.tools/turn-plan` 从**一个全局单槽**变成**按 `thread-id` 分家**，
并且 `register-turn!` 在工具线程被 spawn **之前**调用，让 `loop.clj:127` 那句注释变成真话。
`forget-turn!` 只清自己那一轮登记的条目。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

```clojure
;; kernel/tools.clj:546 —— 一个槽，被所有会话共用
(defonce ^:private turn-plan (atom {}))
;; :568-579  reset! 整个槽
(defn register-turn! [thread-id calls]
  (reset! turn-plan {:plan .. :counts ..}))
;; :604 读的是"某一次"登记的计数
(<= (long (get (:counts @turn-plan) name 0)) 1)
;; :585  清的是整个槽
(defn forget-turn! [] (reset! turn-plan {}))
```

```clojure
;; kernel/loop.clj:113-134 —— 线程先跑起来，之后才登记
(let [chs (mapv (fn [{:keys [id] :as call}]
                  (let [ch (async/chan 1)]
                    (async/thread (tools/run! call thread-id emit))   ; 工具已经跑起来了
                    ch))
                calls)
      done (atom {})]
  (tools/register-turn! thread-id calls))                           ; …这里才告诉缝
```

两件事实，各自都会咬人：

1. **一个槽被两个会话共用。** `edge.http` 的每个 run 是**自己的** `async/go`，两个会话同时在跑时，
   A 的 `register-turn!` 覆盖 B 的计划，先跑完的那个 `forget-turn!` 又把另一个的计划清空。
   后果不是「报错」而是**静默降级**：锚点批（一条消息里对同一个文件的多次编辑合成**一次**写入，
   `tools.clj:527-544`）退化成各改各的并发编辑——而这套批存在的理由正是防这个丢更新。
   另外 `sole-call-of-its-name?`（`:587-604`）会拿**别人的** `:counts` 回答，一个「我是本轮唯一
   同名调用」的判断可能在另一轮里答错。
2. **登记晚于开跑。** 工具体（`tools.clj:701` 用 `binding [*thread-id* ..]` 绑好上下文，body 里读
   `sole-call-of-its-name?` / `batch-role`）可能在 `register-turn!` 之前就查了计划，读到**空**或
   **上一轮**的值。注释说「THE SEAM IS TOLD ABOUT THE WHOLE TURN BEFORE ANY OF IT RUNS」，
   代码说的是另一回事。

## 要改成什么（两个决定，都定了）

**决定一：`turn-plan` 的形状是 `thread-id -> {:token .. :plan .. :counts ..}`。**
为什么是 `thread-id` 作键而不是别的：工具体拿到的是**解析后的参数**，没有 call id；它能问的问题
只能靠 `*thread-id*`（body 里已经绑好）与自己的名字。所以键必须是「body 拿得到的那个东西」。

**决定二：登记带一个 token，`forget-turn!` 只清 token 还属于自己的那条。**

```clojure
;; 形状（不是最终代码，是必须保住的性质）
(register-turn! thread-id calls)  ;; => token，并且 entry 里记下它
(forget-turn! thread-id token)    ;; 只有 (= token (get-in @turn-plan [thread-id :token])) 才 dissoc
```

不这么做的话，同一个 `thread-id` 上重叠的两轮里，先结束的那轮会把后一轮的计划抹掉。
**同一个 `thread-id` 同时有两轮 run** 这件事今天没有阻止（客户端可以同时发两个），
本票不发明「排队」：**登记晚的那轮在 `thread-id` 上胜出**，这一点写进 `turn-plan` 的注释，
作为**已知且接受**的边界。

**登记点的搬家**：`loop.clj` 里把 `(tools/register-turn!)` 挪到 `(mapv .. async/thread ..)`
**之前**，让注释与代码一致。`forget-turn!` 的位置不动（所有调用都答完之后）。

## 验收

- [ ] `turn-plan` 的键是 `thread-id`；`register-turn!` 的 `reset!` 不再覆盖别的 `thread-id` 的条目
- [ ] `register-turn!` 在 `loop.clj` 里出现在 spawn 工具线程的 `mapv` **之前**
- [ ] `forget-turn!` 只清自己那条：用一个别的 `thread-id` 的哨兵条目证明它还活着
- [ ] **两个会话各注册一轮**的用例：两边都调用同一个名字两次，各自的 `sole-call-of-its-name?` 答 `false`，
      且 `batch-role` 只认自己那轮的 call id（今天两条都会红）
- [ ] **登记早于开跑**的用例，且必须是**确定的**不是碰运气的：用公开的
      `(kernel.tools/install! {:name "slow-planner" :planner (fn [tid calls] (Thread/sleep 200) {})})`
      装一层会睡的 planner（跑完调它返回的 teardown），然后**一轮里调两次 `todo_write`**——
      那个 body 真的会读 `sole-call-of-its-name?`（`cap/tools.clj:721`）。断言**恰好一个成功、
      另一个被拒**。今天两个 body 都在 planner 睡着时读计划 ⇒ 读到空计数 ⇒ 两个都放行，用例红。
      **不许**用 `Thread/sleep` 去赌调度：planner 睡着时缝还在 `register-turn!` 里，
      工具线程一定已经开跑，这是确定的事实而不是概率
- [ ] 既有批用例一字不改地通过：`harness.cap.hashline.batch-test`（同名工具多文件成一次写、
      单文件多处替换成一次写）
- [ ] 定向跑：
      `timeout 180 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.kernel.tools-test 'harness.cap.hashline.batch-test) (let [r (clojure.test/run-tests 'harness.kernel.tools-test 'harness.cap.hashline.batch-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
