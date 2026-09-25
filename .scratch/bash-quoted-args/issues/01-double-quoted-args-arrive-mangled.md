# 01 — `bash` 把含空格的**双引号**参数拆坏，并把同一行后面的命令一起吞掉

**What to build:** 一条 `bash` 调用里写 `echo ONE "TWO THREE" FOUR`，命令要**原样**到达 shell：打印
`ONE TWO THREE FOUR`，同一行后面的命令（`; echo END` 之类）照常跑完。**参数里的空格不是 shell 的分词
信号，除非 caller 自己写了引号——而现在引号被谁吃掉了。**

这不是「quote 写法的建议」问题，是**字节没有原样到达**：一台机器上写对的命令，得到的是另一条命令的
结果，而模型只看到「结果不对」，看不到「你的命令被改过」。它同时也是这一场里反复出现的
`unexpected EOF while looking for matching '` 与「管道后面的东西全没了」的同一个根。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## 复现（三条，都是当着这台机器跑出来的）

1. 本机 `bash` 工具，含双引号的那一行会**变形并且吞掉行尾**——后面几条命令一个字都没跑：

   `echo ONE "TWO THREE" FOUR; echo "---"; printf '[%s]' ONE "TWO THREE" FOUR; echo; echo ONE 'TWO THREE' FOUR`
   → 只印出 `ONE TWO`，剩下的（三个分隔符、两次 `printf`、单引号那次）**全部没有了**。

2. 同一条命令换成单引号就完好：`echo ONE 'TWO THREE' FOUR; echo END-MARKER-1; printf '[%s]' a b c; echo; echo END-MARKER-2`
   → `ONE TWO THREE FOUR` / `END-MARKER-1` / `[a][b][c]` / `END-MARKER-2`，一条不缺。

3. 浏览器走查时另一个进程独立撞到同一件事，且这次是 shell 自己报的语法错:
   `yes "spill padding line for the record demo" | head -400`
   → `spill: -c: line 1: syntax error: unexpected end of file`（引号只开不闭的样子），改用无引号的
   `seq 1 2000` 才成功。

## 形状与决策

- **先查「谁在吃引号」**，不要先改命令的拼法。第一个诊断动作是把执行缝真正交给进程的 **argv 向量**打出来
  （`harness.infra.shell` 起进程那一处）：如果 argv 里那条命令已经不是 caller 送来的字节，问题就在这一层；
  如果 argv 是对的、而 shell 仍解析错，那才轮到「这台机器自己的 shell 怎么拼命令行」（`:kind` 那一支）。
- **两个 shell 都要过**：`bash` 与「本机自己的」（Windows 上 `cmd`/`pwsh` 那一支；`bash-shell-choice` 那套
  `:kind` 解析）。同一个 caller 的字节，在两边都该原样到达。
- **不许用「把命令重新拼一遍」来修**：`cap.jobs/command-tokens` 那种字符级扫描是给**报告**用的（尽力而为，
  自己就写着会漏），拿它当拼装器等于把「我们猜的命令」送进 shell——那正是这个 bug 的形状。
- **不改语义**：单引号、反引号、`$(…)`、重定向（`> /tmp/x`，`output-redirect` 只报告不拦）与 `job` 那条
  路照旧；`stdin` 与 `workdir` 照旧。

## 验收

- [ ] 用例（执行缝那一层，`harness.kernel.tools-test` 或 `harness.infra.shell-test`）：`printf '[%s]' ONE
      "TWO THREE" FOUR` 的 stdout 是 `[ONE][TWO THREE][FOUR]`，**且同一行后面的命令跑完了**（末尾标记在）
- [ ] 用例：stderr 里不再出现 `unexpected end of file` / `unmatched` 这一族（把第 3 条复现当反例钉住）
- [ ] 用例：记录里存的那条命令**逐字**等于 caller 送的字节（`[exit N]` 之前那几行之外，`job` 的 `:command`
      也是原文——通知要靠它认出是哪个作业）
- [ ] 用例：单引号、重定向、`|` 管道、`stdin`（`cat` 读得到送进去的字节）四样各一条，证明没有连带改坏
- [ ] 两个 `:kind` 各跑一遍（本机有的都跑；没有的那种要指名跳过，不许静默略过）
- [ ] `clojure -M:test -m harness.test-runner` 全量（或受影响的那几个命名空间）绿
- [ ] 走查证据：真浏览器里发一条含双引号的命令，答案里的 argv 是完整的四个 token
