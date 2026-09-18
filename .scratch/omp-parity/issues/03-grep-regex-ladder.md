# 03 — `grep` 的危险正则：不再拒绝，改走引擎阶梯

**What to build:** 一个 rg 默认引擎编译不了的正则（lookahead、反向引用之类）**照样能搜**：先按原样跑，
编译不了就加 `--pcre2` 再跑，还不行就按**字面**搜一遍并在答案里说明；今天那条「会挂的形状一律拒绝」
（`:unsafe-regex`）连同它的出路 `literal: true` 一起退场。

**Blocked by:** 01（它先把工具名从 `anchor_grep` 改成 `grep`；两者动同一张脸——描述、schema、同一批用例）

**Status:** ready-for-agent

## 现场

**名字**：本票的阻塞票 01 把工具名从 `anchor_grep` 改成 `grep`。下面的行号引用与引文指向**今天这棵树**，
里面出现的仍是旧名——读它们时把名字换成 `grep` 即可，行号不变。

```clojure
;; cap/hashline/grep.clj:30-36（ns docstring）
  REGEXES THAT CAN HANG ARE REFUSED. ... So the shapes that do it are refused
  up front, with `literal: true` named as the way out
;; :58-76  catastrophic? —— 反向引用 \1..\9、量词化的组、{NNN,}、量词化的选择分支
;; :88-95  :unsafe-regex 拒绝
;; :127    literal -> --fixed-strings
```

对称的另一半在 `infra/rg.clj:73-105`：`run` 把 0/1 当答案、127 当「没装 rg」、超时当 `:search-timeout`、
其余一律 `:rg-failed`（带 stderr）——**所以今天「正则编译不了」到达调用方时是一条通用失败**。
`rg/timeout-ms` 今天是 **10000**（`infra/rg.clj:31-35`，理由写的是「upstream 的数字」）。

omp 不做这个判别：Rust regex 先试 → 换 PCRE2（lookaround / 反向引用）→ **两个都拒就按字面搜**
（`docs/tools/grep.md:9-11` 与 Notes），保护交给 30 秒超时（`SEARCH_GREP_TIMEOUT_MS = 30_000`），
而且它**没有** `literal` 参数。

`glob` 走的是 `--files` + `--glob`（`cap/glob.clj:63-71`），一个正则都没有——这一点决定了阶梯落在哪里。

## 要改成什么

1. **三段阶梯，落在 `cap.hashline.grep`**（只有这里构造 `--regexp`）：原样 → 编译失败时 `--pcre2`
   → 再失败时 `-F` 按字面。**不进 `infra/rg.clj`**：那是 `glob` 共用的执行器，`--files` 不该凭空继承
   一条它用不上的性质。
2. **只对「正则编译不了」这一类失败重试**：判据是 `:rg-failed` 的 stderr（`regex parse error` 之类）。
   一个不存在的路径、一个坏掉的 glob 不许被当成语法问题重试三遍。
3. 删掉 `catastrophic?` 与 `:unsafe-regex`；`literal: true` 仍然按字面搜，但它的定位从「被拒时的出路」
   改成「我指的是文本」（**相对 omp 的一处小超集**，spec 决策 4 写明）。
4. **按字面搜的那一轮要在答案里说一句**（omp 是静默回退；我们让「这次的命中为什么长得不一样」可见——
   一句话，换掉一个谜）。
5. `rg/timeout-ms` **10000 → 30000**，理由写进那个 def 的 docstring：阶梯把**回溯引擎（PCRE2）**引进来之后，
   超时是唯一的保护，而 30 秒是 omp 为同一件事选的数。这是跟着阶梯一起动的数字，不是单独抄来的。

## 验收

- [ ] 一个默认引擎不支持的形状（lookahead）→ **有命中**（走 `--pcre2`）；反向引用同理
- [ ] 两个引擎都拒的形状 → **按字面有命中**，且答案里有那句说明（不是错误）
- [ ] 立票当天被拒的四类形状逐条打一遍：没有一条再得到 `:unsafe-regex`
- [ ] **非语法类失败不被重试**：不存在的 `path` 仍一次性得到它原来的错误（用例断言 stderr/`exit` 不变，
      而不是「试了三次」）
- [ ] `literal: true` 仍按字面搜（既有用例留一条）；描述里那句「被拒时的出路」改成「我指的是文本」
- [ ] `rg/timeout-ms` 是 30000，且它是**唯一**一处（描述里若提到数字，从 def 插值）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.grep-test 'harness.cap.glob-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.grep-test 'harness.cap.glob-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致
- [ ] 落地那天：`.scratch/hashline-edit/spec.md` 加**加注的复议**——「REGEXES THAT CAN HANG ARE REFUSED」
      那一段被推翻（旧话不动、划删除线）
