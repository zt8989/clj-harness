# 01 — 前台的时限：一次调用最多等 120 秒，到点连子孙一起收

**What to build:** 一条挂住的 `bash` 命令不再挂住整个 run。调用可以带一个 `timeout`（毫秒，默认 120000），
到点命令被**连同它起的子孙**一起停掉，它在此之前打出来的东西原样回到模型手里，末尾多一行说它停在哪一刻。
没到点就正常结束的命令，答案与今天**逐字节一样**。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 为什么是这套形状

`infra.shell/run` 早就写好了这件事的一半——stdin、`:dir`、`:timeout-ms`、到点把**已经读到的输出**
还回来（它的注释写着：「一个打了原因之后才挂住的 hook，仍然应该是可读的」）。四个调用者
（rg / env / git / hook 引擎）都走它，**只有 `bash` 工具没走**：`t-bash` 走的是 `shell/shell`，
`clojure.java.shell/sh` 的一层皮，没有超时。

两处缺口：

1. **到点杀不干净。** `run` 只 `destroyForcibly` 它自己 spawn 的那个 shell。命令真正的进程是壳的**子孙**
   （`npm test` = bash 起 npx、npx 起 node），杀了壳等于留下孤儿——本仓实测过两个 16 小时没人收的 JVM。
   `kill-tree!` 已经写好（先收子孙、再请壳停、最后 `destroyForcibly` 剩下的），但它今天只给
   `shell/start` 的 `:close!` 用。
2. **`shell/shell` 存在的唯一理由是没有超时。** bash 改走 `run` 之后它在生产里一个调用者都没有了；
   留着它就是「跑命令的第二条路」——超时、stdin、输出收口各写两遍，日后只会修一处。

## 要做的事

- **`bash` 多一个参数**：`timeout`，`integer`，`minimum 1`，**毫秒**，默认 **120000**。
  描述里要写清三件事：单位是毫秒、默认是 120000（**插值**，别在手写第二个数）、到点会怎样；
  以及「写一个很大的数意味着这次的 run 真的会等那么久」（不封顶是**决定**，见 spec 决策 4）。
- **默认值只有一个来源**：一个 `def`，描述串里插值。先例是 `cap.hashline.grep/default-limit`
  在 `anchor_grep` 的描述里被插进去。
- **`t-bash` 改走 `shell/run`**，把 `:dir` 与解析出来的 `:timeout-ms` 传下去。答案形状与今天一致
  （stdout + stderr 拼起来、空则 `(no output)`、非零退出补 `[exit N]`），**到点**则补
  `[timed out after <n>ms]`（同一行格式、同一位置）。
  **`:error` 仍是 false**：挂住被停掉是事实，不是 run 的失败——与 `[exit N]` 同一条纪律。
- **`run` 到点收整棵树**：`kill-tree!` 成为 `run` 也走的那条路，**先收子孙再杀壳**。
  它今天在 `infra.shell` 里是私有的、只服务于 `start`，本票让它同时服务于 `run`。
- **`shell/shell` 退休**：删掉它，`edge.http_test` 里两个用它的小助手改用 `run`。
  （删的理由写进提交信息：它的 docstring「给只想要答案、不想要进程的调用者」已经没有这样的调用者了。）
- **一处行为收紧要写明**：`run` 会把子进程的 stdin 写空并**关掉**；`shell/sh` 不给 `:in` 时那条管道是
  开着的（读 stdin 的命令在那边会一直等）。这是收紧，不是回归——本仓没有一个工具给命令喂 stdin。

## 不要做的事

- 不给 `timeout` 封顶（spec 决策 4），不做「按工具配超时」，不做重试，不动命令内容的判定。
- **不碰** `cap.mcp` / hook 引擎自己的超时：它们走的是 `run` 或 `start`，本票只是让 `run` 的那条
  收尾路变得干净——顺带对它们也有好处，但**不改它们的默认值**。
- 不碰 AG-UI、不进 sqlite、不加审计行、不改轨迹。

## 验收

- [ ] 描述里的默认值与代码里的 `def` 是**同一个数**：`grep -rn "120000" src/harness` 只在一处出现
      （描述串是插值出来的）
- [ ] 不给 `timeout` 时缝上收到的是 120000：一条用例把 `shell/run` 换成替身（`with-redefs`，
      现状 `infra.env_test` 就是这么盯缝的），断言它收到的 `:timeout-ms` 是默认值；
      给了数就给那个数。**不在测试里等两分钟**——默认值由这条证明，机制由下一条证明
- [ ] 到点真的停：`bash {command: "echo hi; sleep 300", timeout: 1000}` 的答案里**有** `hi`、
      **有**那行 timed out，而且这次调用**测出来的墙钟时间**远小于 300 秒（断言 < 10 秒）
- [ ] **子孙也停了**：命令自己起一个后台进程并把它自己的 pid 写进临时文件
      （`sleep 300 & echo $! > <tmp>/pid; wait` 这种形状），调用返回后那个 pid **不在了**
      （对 pid 发信号 0，失败即通过）。这条是本票的核心，不是附赠
- [ ] 没到点的命令**行为不变**：非零退出的命令照旧补 `[exit N]`、空输出照旧是 `(no output)`、
      stderr 与 stdout 照旧拼在一起；没有任何 `[timed out` 出现
- [ ] `shell/shell` 在 `src/` 里搜不到了（`grep -rn "shell/sh\|infra.shell :as shell" src | grep -v "shell/run\|shell/start"` 空）；
      `grep -rn "shell/shell" src test ui` 只剩 0 处
- [ ] **收掉自己的进程**：本票的用例跑完不留进程（临时文件里那些 pid 全部不存在）；
      整套跑完 `pgrep -fl "harness.test-runner"` 只剩正在跑的这一条
- [ ] `timeout 1500 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
      （基线：810 tests / 11065 assertions / 0 failures）
