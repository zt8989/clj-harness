# spec: 测试跑完把现场还给机器 —— 临时树全收，隔离判据的输出里只剩真话

**一句话**：一轮测试造出来的**每一棵**临时树都有人收（正常结束收，被 Ctrl+C / SIGTERM 打断也收），
而且一轮**绿**跑的输出里不该出现 `ISOLATION FAILURE` —— 那行字留给真的出错。

2026-09-22 立，一次交付。代码：`harness.test-support`（登记表、`wipe-temp-dirs!`、退出钩子、
`track-temp-dir!`）与 `harness.test-runner`（run 自己那对目录的钩子）。用例：`harness.test-support-test`、
`harness.test-runner-test`。纪律：`docs/rules/testing.md`。

## 问题

### 一、19,512 个临时目录

**实测（2026-09-22，本机 macOS / openjdk@25）**：`java.io.tmpdir` 下堆着 **19,512 个 `clj-harness-*`
目录、409MB**，其中 **10,148 个是前一天造的**。一百多个调用点各自 `mkdtemp`，**没有一个说谁删**：
`cleanup!` 管的是 run 自己那对 root/home，用例造的树从来没人认领。

删在**发树的那个地方**，因为**调用点写下的删除，是下一个用例会忘的那一行** —— 忘的是普通的那次，
不是粗心的那次。于是 `temp-dir` 交出去的树进登记表，一轮结束时统一删。

| 状态 | 一轮全量之后剩下的 |
|---|---|
| 修复前 | 265 |
| 只在 `temp-dir` 里登记 | 9（**全是拼出来的兄弟目录**） |
| 加上兄弟目录登记 | **0** |

### 二、绿跑里的 `ISOLATION FAILURE`

一轮**全绿**的跑里固定打出 **6 行**大写 `ISOLATION FAILURE` / `ISOLATION NOTE`。来源是
`test/harness/test_runner_test.clj`：它驱动判据的三个分支，其中**五处是裸调** `isolation-verdict`，
判据的 stderr 就这么落到了跑这一轮的人自己的终端上。

**没有真的隔离失败**：每一行的路径都是夹具的 `/nowhere/the-developer/.clj-harness/harness.db`，而真判据比的是
`/Users/zhouteng/.clj-harness/harness.db`（那一轮头一行还写着 `left alone`），退出码 0。**问题是信号**：
真出错时打的那一行，和夹具自己在说话，在输出里长得一模一样。

## 决策

1. **谁发的树谁登记，一轮收一次。** `temp-dir` 把路径放进 `live-temp-dirs`；`run-suite!` 的 `finally`
   调 `wipe-temp-dirs!` 全删，并把数量说出来（`test scratch directories removed: N`）—— 收摊这件事
   看不见就等于没有。

2. **空参的 `wipe-temp-dirs!` 只属于一轮运行，不属于用例。** 整张名单是**先全部 `require` 完再开跑**
   的，所以 `(def root (support/temp-dir "project"))` 这种**加载期**树，在第一条用例跑的时候就已经
   在表里；用例里裸调一次，删掉的是**还没轮到的那些命名空间**的地基。**实测：4 failures + 55 errors**，
   每一条都是 `no such directory: …/clj-harness-project-…`，而且报在跟肇事用例毫无关系的命名空间里。
   用例想看 wipe 就交出自己的树：`(wipe-temp-dirs! [a b])`。

3. **被从外面停掉也要收：两个退出钩子。** 用例的树一个，run 自己那对一个。**主线程的 `finally` 不是
   JVM 有序关停的一员** —— 两行程序 `sleep` 在 `try` 里、SIGTERM 之后 `finally` 一行没打（实测）；
   一次跑到 14 秒被 SIGTERM 的套件，19 棵树全由钩子收走。钩子只装一次（compare-and-set），
   并且经由 `harness.infra.shell/install-hook!`，用例可以数「装了几次」而不必真的退出一个 JVM。

4. **run 自己那对目录不进登记表**（`isolate!` 用 `:track? false`）。它是整个 run 的环境，必须活过每一条
   用例，**结构上**落在任何 wipe 之外 —— 而不是靠「记得别调空参版」这种约定。谁想确认它真的在外面，
   问登记表（`tracked-temp-dirs`），不要用「wipe 一下看还在不在」来问：那样问，答案坏的时候证据也没了。

5. **拼出来的兄弟目录要显式登记。** `(str (temp-dir "git") "-detached")` 这类名字 `temp-dir` 没见过
   （而名字必须保持拼出来的样子，用例要断言它），所以加 `track-temp-dir!`，并**在造它的那个 helper 里**
   调：`cap.git-test/scratch-repo` 一处管 7 个，`edge.http-test/wipe-dir!` 一处管 2 个。

6. **判据的夹具自己接住 stderr。** `verdict+` 用一个 `StringWriter` 绑住 `*err*`，把「判定值」和
   「打出来的话」一起交回用例 —— 于是那些分支照样被断言（还多断言了「开了别的库」那条的措辞），
   而输出流上不再有夹具的声音。**判据：一轮绿跑的输出里不该出现 `ISOLATION` 这个词。**

## 非目标

- **不碰 `ui/`**：这次一行前端都没动，E2E 走查不是必跑项。
- **不是「把已经堆下的那些删掉」**：那是一次性清扫（已做）。这份 spec 管的是**明天不再堆**。
- **不碰别的会话正在用的临时目录**：删活着的 run 的树会把它搞挂（证过）。并发跑着的另一份 worktree
  用的是它自己那份代码，得它自己拿到这次改动。

## 测量本身的坑（写给下一次测这里的人）

**非交互 shell 里 `&` 起的 JVM 继承 `SIGINT = SIG_IGN`**，而 JVM 启动时看到忽略就不装自己的 SIGINT
处理器 —— 于是 `kill -INT` **什么都不发生**（我在这上面空转了两轮，还因此误伤过别的会话的进程）。
要复现 Ctrl+C，先把 SIGINT 恢复默认处置再 exec（见 `.scratch/exit-reap/spec.md` 里那段
`exec perl -e '$SIG{INT}="DEFAULT"…'`）；**测中断一律用 SIGTERM**，`scripts/dev.mjs` 停后端用的也是它。
