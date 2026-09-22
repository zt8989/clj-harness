# 04 — `grep` 的命中默认带上「前 1 后 3」

**What to build:** 不给上下文参数的 `grep` 命中回来时自带**前 1 行、后 3 行**（照 omp 的数字），
那几行和命中行一样是带锚点、可编辑的；想要今天那种光溜溜一行，给 `context_before 0` 与 `context_after 0`。

**Blocked by:** 03（同一文件、同一段描述、同一批用例——本仓对「动同一处」的规矩是排顺序；
工具名由 01 改成 `grep`）

**Status:** ready-for-agent

## 现场

**名字**：本票操作的工具有两张票先动它（01 改名——**已于 2026-09-22 落地**，03 正视正则），
下面的引文指向**今天这棵树**，
里面出现的仍是 `anchor_grep` 这个旧名——读它们时把它当 `grep`。

```clojure
;; cap/tools.clj:602-614（schema）—— 一个对称的 context，默认 0
;; cap/hashline/grep.clj:188-198（窗口算术）—— 同一个 ctx 往两侧展开，
             wanted = (range (max 0 (- (dec n) ctx)) (min total (+ n ctx)))
             {:text (rows) :shown (那些行的锚点)}
```

默认值住在 `def` 里、插值进描述，是本仓的既有做法：`grep/default-limit` = 100（`grep.clj:51-54`）、
`max-bytes` = 51200（`:46-49`）、`bash-default-timeout-ms` 同一种形状。

omp 是**不对称的两个数**，而且放在**设置**里：`grep.contextBefore = 1`、`grep.contextAfter = 3`
（`docs/tools/grep.md:145`、`:13`），命中印成 `*5:content`、上下文印成 ` 9:content`。

## 要改成什么

1. 今天的 `context` 参数退休，**两个参数**登场：`context_before`（默认 **1**）、`context_after`（默认 **3**），
   两个默认值**各是一个 `def`**并插值进描述——改默认值只改一处。
2. **已展示集合跟着走**：上下文行本来就在 `:shown` 里（`:198`），窗口变宽只是让它包含更多行；
   语义不变（能编辑的是**打印出来的**行）。
3. **一处明示的分歧**（spec 决策 5）：omp 把这两个数放在设置里（`grep.contextBefore/After`），
   我们放在**参数默认**里——本仓没有「按工具设置」那一层，为一个检索默认值新开两个配置键不划算。
   这句话写进 `grep` 的 ns docstring，免得下次有人当成漏抄。
4. `limit` 与 `max-bytes` 不动：一页里行数变多，字节预算仍按同一处收口。

## 验收

- [ ] 不给参数：命中行**前 1 后 3** 都在答案里，且那些行**可编辑**（拿上下文行的锚点做一次 `replace` 成功，
      不用再 read）
- [ ] `context_before 0` + `context_after 0`：退回今天的光行（既有用例的断言照着改）
- [ ] 两个参数各自的非法值各有一条指名拒绝（负数、非整数——与 `limit` 同一条校验）
- [ ] 描述里的两个默认值**由 `def` 插值**：经缝改掉 def 之后描述跟着变（一条用例证明，别只断言字面量）
- [ ] 跨文件命中的页眉/`[... 输出预算 ...]` 那两句不受影响（既有用例不改而通过）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.grep-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.grep-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致
- [ ] 落地那天：`.scratch/hashline-edit/spec.md` 加**加注的复议**——`anchor_grep` 的 `context` 参数与默认值
      那一段被推翻（旧话不动、划删除线）
