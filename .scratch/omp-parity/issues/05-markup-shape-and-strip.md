# 05 — 显示标记：`read` 的行带行号；抄回来的标记剥掉，不拒绝

**What to build:** 两件事，一处规则。①`read` 的行与 `grep` 的行**同形**（行号 + 锚点 + 正文），
一个模型从两边说出来的「第 42 行」是同一件事；②把带标记的行抄进 `write` 的 `content` 时，
标记被**剥掉**、正文照落，答案里说明剥了几行——**不再指名拒绝**。

**Blocked by:** `.scratch/write-no-content/01`（两者重写 `write.clj` 的 docstring 与 `write_test.clj`；
本仓对「动同一处」的规矩是排顺序）

**Status:** ready-for-agent

## 现场

```clojure
;; cap/hashline/reading.clj:178-193 —— read 的行：只有锚点和正文，没有行号
(def hash-sep "│")            ; U+2502，选型理由：anchor│ 要 TOKENIZE 成三个 token
(defn row [anchor line] (str anchor hash-sep (display-line line)))

;; cap/hashline/grep.clj:194-198 —— grep 的行：多一列行号
(format "%6d │ %s" (inc i) (reading/row-for path (nth lines i) (nth as i) (inc i)))

;; cap/hashline/write.clj —— 抄回来的标记是「拒绝」
;;   echo-line: 行首 ^([A-Za-z0-9]{4})│，且那 4 个字符必须是 THIS 文件 THIS 会话服务过的锚点
;;   check-no-echo!: 命中就抛 :anchor-echo，说清第几行、哪个锚点
;; cap/tools.clj:414-424（write 锚点脸描述）：
;;   "Content that begins a line with an anchor of this file followed by `│` is refused"
```

omp 两边都带行号（`docs/tools/read.md:125` 的 `41:def alpha():`、`docs/tools/grep.md:13` 的 `*5:content`），
而抄回来的标记是**剥掉 + 一句 note**（`docs/tools/write.md` 的 Outputs；`write.ts` 的 `stripWriteContent`：
`Note: auto-stripped hashline display prefixes from content before writing.`）。

## 要改成什么

1. **`read` 的行加行号列**，形状与 grep 完全一致；行号的格式化**只有一处**（read 与 grep 都调它，
   现在是 `grep.clj:194-198` 里那个 `%6d │`）。
2. 描述里补一句行号的用途：**拿来说话的，编辑仍然只认锚点**（`grep` 的描述已经有这句，`read` 没有）。
   页脚 `[Showing lines 1-N of M — …. Use offset=N+1 to continue.]` 的算术不变。
3. **抄回来的标记改成剥离**（spec 决策 7）：判据是**本会话为该文件服务过的锚点**，
   **不是**按形状剥——我们的标记是「四个字母数字 + `│`」，`2024│Q1` 这种正文会撞上同一个形状，
   按形状剥就是静默改写正文（omp 的 `[path#TAG]` 是整行 header、`123:` 是行首数字，它按形状剥是安全的）。
4. **识别要覆盖两种形状**：新形状（`(可选前导行号列) + 4 个锚点字符 + │`）与旧形状（`a3f9│alpha`，没有行号列）
   ——模型手里的**旧输出**还会被抄回来。这条规则只有一处，与第 1 条同一次写完。
5. 那个函数不再 refuse：名字与 docstring 跟着改（`check-no-echo!` → 剥离那一支），
   ns docstring 里 `THE REFUSAL OF AN ECHO IS WRITE'S OWN SHAPE CONSTRAINT` 那一段换成
   「**剥掉，而且剥得窄**」+ 上面第 3 条的理由。**剥离之后答案里带一句 note**（说了几行、第一行是第几行）：
   「你给的东西被改过」必须可见（omp 也有那句 note）。
6. `write` 的锚点脸描述里那句话改成「抄回来的标记会被剥掉，并在答案里说明」。

## 验收

- [ ] `read` 的一行与 `grep` 的一行**同形**（一条正则能从两边取出 [行号, 锚点, 正文]）
- [ ] 把 `read` 的一整行抄进 `write.content` → 落盘的是**剥干净的正文**，答案里有那句 note，**没有 error**
- [ ] 旧形状（`a3f9│alpha`，无行号列）照样被剥
- [ ] `abcd│text`（本会话**没**服务过这个锚点）一个字不动——今天那条「不拒绝正文」的控制用例换断言继续存在
- [ ] `N │ anchor│` 与 `anchor│` 混在一份 `content` 里 → 两种都被剥
- [ ] `a-refused-write-changes-nothing-at-all` 的对象（回显拒绝）没有了：**退役它**（它的断言在剥离之后
      不成立——write 会成功、归属与撤销按 write 的规矩动），并在票面留下的记录里说明它保护的性质现在由
      围栏 park 那条用例承担（park 的 write 什么都不改）
- [ ] `grep -rn "echo" src/harness/cap/hashline/write.clj` 没有「拒绝」语义的残留（名字与 docstring 都改过）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.write-test 'harness.cap.hashline.read-test 'harness.cap.hashline.grep-test 'harness.kernel.tools-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.write-test 'harness.cap.hashline.read-test 'harness.cap.hashline.grep-test 'harness.kernel.tools-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致（核一遍 UI 不解析 `read` 的行；预期不用改，若解析则跟着改）
- [ ] 落地那天：`.scratch/write-no-content/spec.md` 的「`check-no-echo!` 与 echo 拒绝一个字不动」
      加**加注的复议**；`.scratch/hashline-edit/spec.md` 的两处也加注（`read` 行的形状、write 的回显那段）
