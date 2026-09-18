# 02 — `replace` 的反向区间：指名拒绝，不再替你交换

**What to build:** `remove_from` 落在 `remove_to` **之后**的一次 `replace` 得到一句指名拒绝——两个锚点都点到、
说清正确顺序、**一个字节都不写**——而不是今天这样把两个参数悄悄换过来再照常落盘。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

```clojure
;; cap/hashline/edit.clj:346-357
;; Reversed anchors are SWAPPED, not refused: the model said which two lines
;; it meant, and writing them back in the other order is unambiguous. The
;; comparison is by POSITION IN THIS FILE'S ANCHOR ARRAY -- the only ordering
;; that means anything about lines. Comparing them as pool values would be
;; comparing two anchors that were chosen to be unrelated.
(let [[i j swapped?] (if (> i j)
                       (do (swap! warnings conj
                                  (str "remove_from and remove_to were reversed;"
                                       " swapped them."))
                           [j i true])
                       [i j false])]
  (assoc (range-map st i j) :swapped? swapped?))
```

三件事今天同时成立，票面把它们摆齐：

1. **那句话模型看得见**：警告会被渲染成答案开头的 `Note: …`（`replace.clj:616-617`）——所以这不是静默修，
   是「修了并说了一声」。
2. **` :swapped?` 是惰性数据**：唯一的写处在 `:357`，`grep -rn "swapped?" src/` 另一个命中就是这条 `let`，
   **没有任何读者**。
3. **没有用例盯它**：立票当天 `grep -rn "reversed\|swapped" test/harness/cap/hashline/` 无输出——
   这条行为既没被承诺，也没被防住。

omp 把这一类判给拒绝：它的区间 `inclusive, must be ordered`（`docs/tools/edit.md:132`），
`Reversed or overlapping ranges` 明列在 **Common failures** 里（`:144`）。
**只关于 `replace`**：`insert` 收的是 `anchor` + `direction`，没有区间可逆；但区间算术住在 `edit.clj`，
所以 `edit-merge` 那个 `edit` 载荷（`PUT a..b`）落地时会**自动继承**这条拒绝。

## 要改成什么

1. `edit.clj` 里那段：`i > j` 时**抛** `:reversed-range`，话里有两个锚点与正确顺序
   （照本仓指名拒绝的写法：说清「谁落在谁后面」、把两个参数对调就好）。
2. 删掉 `:swapped?`（唯一的写处连同它一起走）与那句警告。
3. `replace` 的描述（`tools.clj:469-494`）加一句：逆序的区间会被拒绝，不会替你交换——
   用过旧行为的人需要一个能读到的说明。
4. **重叠不动**：`replace.clj:472-501` 的 `:batch-overlap` 是另一件事（同一条消息里两笔编辑抢同一行），
   一个字不改。

## 验收

- [ ] 新增用例：反向区间 → `:error true`、话里**两个锚点都在**、指向正确顺序、文件一字未动、
      归属与撤销记录都没动（`a-refused-write-changes-nothing-at-all` 是同类形状，照它写）
- [ ] 正向区间与单行区间（只给 `remove_from`）行为**逐字不变**：`harness.cap.hashline.replace-test` /
      `insert-test` / `batch-test` 的既有用例不改而通过
- [ ] `grep -rn "swapped" src/` 无输出（那条警告与 `:swapped?` 都不在了）
- [ ] 描述里那句拒绝有用例（`replace-test` 的描述断言加一条）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.replace-test 'harness.cap.hashline.insert-test 'harness.cap.hashline.batch-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.replace-test 'harness.cap.hashline.insert-test 'harness.cap.hashline.batch-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致（本票不碰 `ui/`）
- [ ] 落地那天：往 `.scratch/edit-merge/` 的 `05-guards-and-refusals` 加注一笔——「逆序」那条已由本票兑现，
      它的票面**不改**（`.scratch/` 是历史）
