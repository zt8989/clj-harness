# 进程漏了：命令跑完了，起它的那个进程还在

一场真实的现场（2026-09-25）：`taskkill` 杀不掉一堆进程，其中 12 个是 `harness.e2e-server` JVM
（8:33 到 12:17 之间，几乎每一次 UI 测试留一个，各自占着一个端口），另有一整个 12:17:43 起的后端
测试（它的会话 12:18:16 之后就没再写过字，它自己一直打到 12:20:55 之后）。两件事是一个形状：
**一个进程的「结束」被记在了一个比进程本身短的地方。**

两票（`issues/01`、`issues/02`）都落地了，按本仓库的规矩票面删掉，记录在这里。

## 1. UI 测试手里的 pid 不是它以为的那个进程

`ui/test/support/harness.ts` 的 `stop()` 只 `proc.kill("SIGTERM")`。Windows 上那是
TerminateProcess、作用**一个 pid**，而 `spawn` 交回来的是 LAUNCHER（`clojure` → `deps.exe` →
JVM）：JVM 是**孙进程**，既不在 kill 的目标里，也不跑自己的 shutdown hook ——
`infra.shell/reap!` 那套「进程自己退出时收掉自己起的一切」在这条路上**永远不会执行**。

最小复现（本机，形状与那段 spawn 一字不差）：node 起 `spawn('clojure', ['…/leak_probe.clj'])`，
22 秒后 `proc.kill('SIGTERM')` → launcher 报 `signalCode=SIGTERM`（Node 认为杀掉了），
脚本打印的 JVM pid **还活着**，一直活到用手 `taskkill /F` 点名。

**修法**

- `stopTree`（`scripts/proc.mjs`）：仓库已有的、唯一一只杀树的手（Windows `taskkill /T /F`，
  POSIX 进程组）。测试里不重写第二份。
- 判据从「launcher 退出了」换成**端口不再答应**（`waitForGone` 用一次 TCP connect 问）：launcher
  的 `exit` 事件在 Windows 上恰好是最容易满足的那条，它就是这个 bug 的藏身处。
- `SIGINT` / `SIGTERM` 各装一次「杀树 + 重新抛出信号」的处理器：Ctrl+C 走不到 `afterAll`，而重新
  抛出是为了不把 Node 自己的默认行为吞掉（杀不掉 Ctrl+C 的测试套比留下一个 server 更糟）。
  `stop()` 把这两个处理器摘掉。
- `ui/tsconfig.json` 开 `allowJs`，让 TS 直接读那个 `.mjs`，而不是在它旁边再写一份会漂移的声明。

**证据**：修前，这一天 12 个 JVM 带着 `--user-home …\clj-harness-ui-test-*\user-home` 活着；
修后连跑两次 `cd ui && npm test`（146 用例全绿，各约 50s），每次跑完
`CommandLine -match 'harness\.e2e-server'` 的进程数是 **0**，机器上 java 只剩 harness 自己；
`cd ui && npm run typecheck` 干净。

## 2. 会话被放下，它起的作业进程还站着

`harness.kernel.session` 的 `sweep!`（空闲回收）与 `drop!`（明说的门）交出 claim、通知窗口，
**一个字都没提作业**。而作业的表在 `harness.cap.jobs` 里是**进程本地、按会话**的：claim 一交回，
这个对话就可以被**另一个进程**接过去，那边 `job_kill` 只认自己的表 —— 从那一刻起，那些命令
**没有任何一条路能停**，除了杀掉整个 harness。

**修法**

- 新增 `:stop-jobs!` seam（与 `:claim`、`:unflushed?` 同一层：机制在 kernel，答案在 cap）。
  `harness.edge.sessions` 的适配表里装 `jobs/stop-session!`；`sweep!` 与 `drop!` 都敲一下。
  `kernel.session` **没有** require `cap.jobs` —— 这是缝存在的原因，也是这条 diff 里最该保住的一格。
- `jobs/stop-session!`：停掉该会话**还在跑**的每一条命令，**记录、entry、id 全部留下**
  （`shutdown!` 对同一对东西的判断：进程的结束带走的是 JOBS，从来不是记录）。
- 新增第三种发起者 `:by :put-away`：**不打** `:stopped-by :user`（没有人按过任何东西），
  也不claim `:told?` —— 因此 ending 仍**欠着一个 notice**，会话在这个进程里重新出生时，
  下一次模型调用会被告知它的后台命令被停了。

**证据**：`clojure -M:test -m harness.test-runner harness.edge.sessions-test harness.cap.jobs-test`
→ 72 用例 / 358 断言 / **0 红**。两条新用例各钉一半：kernel 那一半是「两扇门都敲了、只敲被放下的
那一个」；cap 那一半跑真进程（`child-command` 写 pid 文件）——命令与它起的子进程都没了、
记录还在且以 `[stopped]` 收尾、第二个 `job_output`/`job_kill` 仍能回答、notice 里没有 `by=user`、
**隔壁会话的进程还活着**。另跑 `harness.edge.record-test harness.edge.http-test`
→ 117 用例 / 1250 断言 / 0 红（那条 `ISOLATION NOTE` 是活着的 harness 自己在写 `harness.db`，
按 `docs/rules/testing.md` 不算失败）。

## 没修的，以及为什么

- **一棵树的 kill 本身没坏，这一轮不要去动它。** 实测：`job_kill` 一条命令收掉
  `bash → clojure.exe → deps.exe → java` 四层；`taskkill /F /T` 从壳上收整棵树也是全灭。所以
  **不要**重写 `infra.shell/kill-tree!`，也不要引 Windows Job Object：链子活着的时候它是对的，
  而「中间那一环自己退出、JVM 被重新挂到死 pid 上」今天只见过一次（那是一只手杀了一半留下的
  现场），没有复现出来，不该按它改代码。
- **Ctrl+C 那一格在这台机器上无法验证。** Windows 上从外面给一个进程送 SIGINT 要控制台
  （`taskkill` 不带 `/F` 送的是 WM_CLOSE，`Stop-Process` 是 TerminateProcess，两者都不跑处理器），
  所以信号处理器写了，但没有当着这台机器跑出证据 —— 谁有交互控制台，值得补一次。
- **`%TEMP%` 里 79 个 `clj-harness-ui-test-*` 目录**是修前那些跑法的遗留：JVM 活着，`rmSync`
  抢不到文件句柄，代码走了它自己的 warning 分支。修后每一次跑完自己那个目录都会没；老的那堆没人删。
- **跑完测试留下的 `sleep.exe`**（当天 36 个）**单独开了一票**：`issues/03-suspended-sleep-orphans.md`。
  修前先钉根因，因为实测到的形状否掉了两种猜测：它们每一个都是**挂起**的
  （`Win32_Thread.ThreadWaitReason=5 (Suspended)`、CPU 时间 0/0）、父进程全 GONE、而且
  **永远不会自己走**（`sleep 30` 从 12:52 活到 13:06）。也就是说那是一个**从来没跑起来**的进程，
  而不是一次没收干净的收尾。那 12 个今天已经用手杀干净（杀完为 0）。
- **没提交。** 这一轮的改动留在工作区里：`src/harness/{cap/jobs,edge/sessions,kernel/session}.clj`、
  `test/harness/{cap/jobs_test,edge/sessions_test}.clj`、`ui/test/support/harness.ts`、
  `ui/tsconfig.json`。

## 顺带看到、没有动的

`cap.jobs/status-of` 与 `writer-alive?` 都只问「**写记录的那个进程**还在不在」——那是 harness
自己，不是命令。所以一个会话的记录会永远显示 `[running]`，而一条早就跑完的命令与一条还在跑的
命令，从记录一侧看不出区别。这不在这一轮的两票里，记在这里。
