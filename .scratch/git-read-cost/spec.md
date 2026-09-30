# spec: 一次 `/api/git` 读 = 约 1.6s，两条命令各付一次登录 profile

**来源**：`test-suite-performance` 的票 01 做完之后回答「还有可改进空间吗」时量出来的**产品账**
（2026-09-30）。票 01 修的是测试夹具里的同类花费；这一笔不在测试里，在页面要读的东西上。

## 症状（实测）

`dev/scratch_git_read_cost.clj`：对同一个刚建好的小仓库，`harness.cap.git/state` 六次——

```
1616ms / 1744ms / 1609ms / 1649ms / 1647ms / 1626ms
```

## 机制

`state` 是**两条** git 命令：

- `git status --porcelain=v2 --branch` —— 是不是仓库、在哪个分支、脏了几行；
- `git branch --format=%(refname)` —— 分支表（这个格式不带分支列表，所以是第二条）。

两条都经 `harness.infra.shell/run`，也就是 `bash -lc`。本机一次 spawn 约 **0.8s**，其中约七成是登录
profile；`-c` 只要 ~84ms，但仓库记录过它会**破坏超时子进程的回收**（见 `harness.infra.shell` 的注释），
所以 **`-lc` 不许动**——能动的只有「哪些命令需要 shell」。

## 谁付这笔钱

页面打开一个会话的目录时，分支条拿的就是这一次读（`/api/git` 的一次 GET）；切换分支另算（`switch!`）。
即 Windows 上开一个会话的分支条≈1.6s。

## 要定的（所以先 triage）

三条路，代价不同：

1. **短命的 git 读走一条不经 profile 的 spawn**（直接起 `git`，沿用 `shell/run` 的超时与杀树语义）——
   收益最大，但要在 `harness.infra.shell` 里开一条新路径，得说清它和 `-lc` 的边界；
2. **把两条读合成一条**——省一半，但要覆盖两类事实；
3. **判定接受这个价钱**（git 读本来就不频繁）——那就把它写进文档，别留着当惊喜。


**三条路各自的价钱（2026-09-30 实测，`dev/scratch_git_spawn_options.clj`）**：一次读 = 两条命令，所以
一条路省多少就是 spawn 省多少：

| 路 | 价钱 |
|---|---|
| `bash -lc` 起一次（今天） | **771ms** |
| 同一次，子环境加上 `WINELOADERNOEXEC=1` + `TERM=dumb` | **398ms**（`dev/scratch_which_env.clj` 拆过：`LANG` 无关；`WINELOADERNOEXEC` 省 ~180ms、`TERM` 省 ~240ms） |
| 直接起 `git`（不经 shell） | **61ms** |
| `state`（今天，两次 spawn） | **1584ms** |
| 两条命令放进同一次 `bash -lc`（第 2 条） | **356ms** |

也就是说第 1 条不是「好一点」，是**另一个量级**；第 2 条只到一半多一点。代价是 `harness.infra.shell`
要多一条路——那是选它时要认下的那一笔。
动的是**产品行为**，不是测试，所以这张先 triage，不直接派。

## 落地（2026-09-30）：走第 1 条——不经 shell 的 spawn

**新入口**：`harness.infra.shell/run-program` 收 `:argv`（`[program arg ...]`）直接起程序。`run` 里
「起进程并按一次性协议等」的那半抽成私有的 `await-program!`，两个入口共享同一套保证（stdin 写完即关、
两条管道各自抽干、到点连子孙一起杀、退出钩子先装）。`run-program` 不加 SHLVL 钉子（那是登录 shell 的
logout 文件用的），程序起不来时答 `{:exit 127 ...}` 而不是抛——那是一切 shell 对「命令不在」的答案，
也让「没装 git」继续是一个普通的非零答案，而不是 500。

**`harness.cap.git` 改用它**：`git` 助手从「拼一条单引号命令行交给 `bash -lc`」变成把参数当 argv 交给
程序。这个命名空间里的 `quoted` 随之消失，`require-posix!` 那一问也撤了——没有命令行可插值，也就不需要
POSIX shell（因此装机没有 Git Bash 的机器现在也能读分支，而不是按名字拒绝；`require-posix!` 本身留
着，`infra.rg` 还在用它拼命令行）。

**实测（同一个刚建好的小仓库，各六次）**：

| | 改动前 | 改动后 |
|---|---|---|
| `harness.cap.git/state`（一次 `/api/git` 读） | 1609–1744ms | **127–132ms**（12×） |
| `cap.git-test`（整只命名空间，全量里） | 45.8s | **18.5s** |

`cap.git-test` 顺带快了是因为它测的就是这个命名空间，它的 `state` / `switch!` 调用跟着一起不付 profile。

**一个量出来的 Windows 边**（`dev/scratch_argv_probe.clj`）：JVM 按 MSVCRT 规则写命令行，**原生程序**
（`node`、`java`、`git.exe`——MINGW）整着收到参数（空格也在）；**MSYS 程序**（Git Bash 及它旁边的
coreutils）会重新解析那一行，空格会拆、`'` 会吃。git 是前一种，分支名又不许有空格，所以对调用方无影响；
这条边写在 `run-program` 的注释里，别再假设 argv 对谁都逐字。

**用例**：`shell_test` 四条（argv 不被 shell 解读、`:dir`、缺程序 127、超时照杀）；`git_test` 一条
（带 `'` 的分支名——这正是 `quoted` 当年存在的原因——能被列出并切过去）。`harness.infra.shell-test`
33.3s / `harness.cap.git-test` 18.5s，全绿。**全量**：**11 fail + 4 err，与基线逐条相同**（无 git / shell /
http 的新红）；`edge.http-test` 114.0s，含那两条走 `/api/git` 的用例。
