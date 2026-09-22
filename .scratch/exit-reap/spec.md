# spec: 退出时把孩子带走 —— Ctrl+C / SIGTERM 都要把本进程起过的进程收干净

**一句话**：JVM 退出时（Ctrl+C、SIGTERM、正常结束）把**这个进程起过的每一个进程**——含它们的子孙——
先请后杀收掉，再退出。今天只有「有主的」那几棵树收得掉，**在飞的一次性命令**与**没有任何能力认领的
`start`** 会变成孤儿。

2026-09-22 立，一次交付。代码：`harness.infra.shell/reap!` + `ensure-exit-hook!`；用例：
`harness.infra.shell-test/everything-this-process-started-goes-with-it`。

## 问题

**「谁来决定一条命令结束了」有两套答案，退出只有一套。** 一次调用到点、或者持有者 `:close!`，
走的是 `kill-tree!`（先收子孙、再杀壳，请不动就动手）。而**进程自己结束**时没有任何调用者会跑：
JVM 只跑退出钩子。今天装钩子的有四处——`cap.jobs`、`cap.mcp`、`cap.claims`、`edge.record`——**各自只管
自己登记过的东西**。于是退出时在飞的那些进程不属于任何登记表：`shell/run` 上正等着的命令（`bash`
工具调用、`rg`、`git`、hook 引擎、env 探测）和 `shell/start` 起的进程（MCP 服务器之外的调用者），
还有 `edge.http` 那个**自建 ProcessBuilder** 的系统文件夹对话框。

**实测（2026-09-22，本机 macOS 15.7.3，openjdk@25）**：进程里同时有一个后台作业、一条在飞的
`shell/run`、一条长活的 `shell/start`，各自带一个 node 子进程；`kill -INT <jvm>` 之后：

| 起法 | 修复前 | 修复后 |
|---|---|---|
| 后台作业（`cap.jobs`，有自己的钩子） | 没了 | 没了 |
| 长活 `shell/start`（MCP 服务器的形状） | **壳与子进程都还在** | 没了 |
| 在飞 `shell/run`（`bash` 工具调用的形状） | **壳与子进程都还在** | 没了 |

SIGTERM 那次同样（`scripts/dev.mjs` 停后端用的就是它）：修复前在飞的那棵树还在，修复后三类全没。

**测量本身有个坑，写下来**：非交互 shell 里的 `&` 会把 SIGINT 设成 SIG_IGN，**JVM 启动时看到 SIG_IGN
就不装自己的 SIGINT 处理器**，于是 `kill -INT` 什么都不发生（第一次测量就是这么白跑的）。要复现
Ctrl+C，得先让 SIGINT 回到默认处置再 exec：

```sh
# /tmp/exec-reset-int.sh
exec perl -e '$SIG{INT}="DEFAULT"; $SIG{TERM}="DEFAULT"; exec @ARGV' "$@"
# 然后：/tmp/exec-reset-int.sh clojure -M:dev -m scratch-shutdown & kill -INT <jvm pid>
# （实验脚本：dev/scratch_shutdown.clj，起三类进程后阻塞）
```

## 决策

1. **扫的是 OS 的进程树，不是一张登记表。** 登记表要每个 spawn 点记得去登记，而本仓已经有反例：
   `edge.http` 的文件夹对话框自建 `ProcessBuilder`，任何表都没装过它。**这个 JVM 起过的一切都是这个
   JVM 的子孙**，退出那一刻问 OS 要这个集合，明天新加的 spawn 点当天就被同一行覆盖。

2. **每个子孙都直接发信号，不是让父进程转达。** 杀了壳不会带走它的孩子（`kill-tree!` 的正题），
   而 `.descendants` 是**任意深度**的整棵树，所以每一个都是「根」，各收各的信号。

3. **一个共享预算，2 秒，不是每棵树 2 秒。** `kill-tree!` 是每棵树的等待；退出钩子里二十条后台命令
   不能变成四十秒的 Ctrl+C——**钩子跑太久就是人退不出去**。做法：全 `destroy` → 共享 deadline 内各
   `waitFor` → 还活着的 `destroyForcibly`。绝不碰 JVM 自己（`reap!` 只在已经在退出时被调）。

   **而且扫到树空为止**（扫 → 请 → 等 → 再扫；某一轮扫不到东西就收工，总时长仍由那 2 秒封顶）。
   一次性的扫会从「正好在这时起来的进程」旁边走过去，而那个时刻是真的：JVM halt 之前 http 还在答请求，
   一次工具调用完全可能正在 spawn。多扫一轮是一行循环，换掉的是「孤儿正好卡在缝里」那一类。

4. **钩子在 spawn 之前装，CAS 只装一次**（与 `cap.jobs` / `cap.mcp` 同一个形状：`install-hook!` 是给
   测试数安装次数的门，`reset-exit-hook!` 是给测试把钩子当过一回的门）。装在一次性的 `run` 也装，
   所以 `rg`、`git`、hook、env 探测这些没人认领的起法从第一天就在里面。

5. **不抢别人手里的账。** 退出钩子是并发跑的，本钩子可能和 `cap.jobs` / `cap.mcp` 的钩子撞在同一棵树上：
   代价是给一个本来就要走的进程多一发 SIGTERM，**没有账会丢**——它们各自的注册表与记录文件仍由各自
   的钩子关掉，两边的收法都是「先请后杀」。

## 非目标

- **不是「等命令跑完」。** 「优雅」在这里的意思是先请（SIGTERM）后杀（SIGKILL），不是给在飞的命令
  一段宽限期——进程都要没了，等它跑完没有意义，而且长命令根本没有终点。
- **不把四个钩子收成一个协调器。** 一条「先让能力放手、再扫剩下的」有序告别是更好的形状，
  但那是四处改动 + 它们的用例，而今天并发的钩子不丢任何账（见决策 5）。记在这里，等有真需要时再做。
- **不追已经脱缰的 daemon。** 双 fork 之后被 init 领养的进程既不在 `.descendants` 里，也不在任何
  表里（`nohup ... &` 那种）。今天没有功能要求这个。
