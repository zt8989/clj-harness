# 证据 —— bash 的时限与后台作业

`bash-lifetime.clj.sh` 是**可重跑**的（从仓库根跑：`zsh .scratch/bash-lifetime/evidence/bash-lifetime.clj.sh`），
`bash-lifetime.txt` 是它这一次的输出。两边都是**真的进程、真的答案**：脚本走的是
`harness.kernel.tools/run!` 这条缝（与模型调用同一条），命令是脚本自己起的，子进程的 pid 由**命令自己**
写进一个临时文件——不是脚本替它编的。

## 三件事各证明了什么

1. **前台的时限连子孙一起收**。给了 2000ms 的时限，而命令要 `wait` 一个 `sleep 60`：
   答案在 **2032ms** 回来（不是 60 秒），结尾那行是 `[timed out after 2000ms — the command was stopped]`，
   而命令自己起的那个 pid **不在了**（在同一进程里用 `ProcessHandle` 问的）。
   这一条是本特征的核心：`run` 原来只杀它持有的那个 shell，`npm test` 那样的真进程会活下来。
2. **后台起 / 读 / 停**。`bash_background` 立刻答 `job j1 started`；第一次读拿到已经打出来的两行 +
   `[running]`；第二次读拿到第三行 + `[exit 0]`（**读回来的只有新行**，游标是会话的）。
   另一条作业被 `bash_kill` 停掉：答案 `[stopped]`、它的子进程同时消失，之后再读同一个句柄得到的是
   **指名拒绝**并列出本会话还活着的那条（`j1`）——「停」就是「忘掉」。
3. **JVM 退出不留作业进程**。一个**单独的子 JVM** 起一条作业就退出，父进程（外面）用 `ps` 查它写下的
   pid：**没了**。这一条是套件做不到的（测试里不 fork JVM：两个 JVM 对一个配置家会挂十三分钟，
   `cap.mcp-test` 记着那次发现），所以它只能这样手工核一遍——而这次核的是 `System/exit` 那条路，
   不是 SIGKILL。

## 没做的事（写在这里，免得被读成做过）

- **没跑活厂商**：本特征一个字节都不碰 provider、不碰记录、不碰 AG-UI，所以没有厂商可跑。
- **没在 Windows 上跑过**。`:program` / `:shell` 两种 argv 形状的 Windows 那一支是**纯函数**断言
  （`shell-test/the-two-long-lived-shapes-differ-exactly-on-windows`），在这台 mac 上永远走不到
  那一支的实现路径——这是有意的设计（可断言优先），但它不等于「在 Windows 上验证过」。
- **没跑 UI**：本特征 UI 一个字没改，`subjectOf` 的兜底（第一个字符串参数）恰好对
  `bash_background {command}` 就是那条命令、对 `bash_output {job}` 就是那个句柄；`npm test` 与
  `npm run build` 照旧过（数字写在 spec 的「落地记录」里）。
- **默认值 120000 没有在真机上等满两分钟**：那一条由**替身**证明（`shell/run` 收到的是 120000），
  机制由上面第 1 条证明（显式的短时限）。两条合起来才是「默认 120 秒且到时真的停」，
  而**任何一条单独**都不足以说明它。
