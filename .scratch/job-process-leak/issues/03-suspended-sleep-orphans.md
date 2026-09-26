# 03 — 跑完测试之后，机器上留着一堆**从没跑起来**的 `sleep`

**What to build:** 跑完任何一轮测试（单跑 `harness.infra.shell-test` / `harness.cap.jobs-test`，
或全量 `clojure -M:test -m harness.test-runner`）之后，这台机器上没有 `sleep.exe` 残留。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## 复现与实测（2026-09-25，这台机器）

`clojure -M:test -m harness.test-runner harness.infra.shell-test harness.cap.jobs-test` 跑一遍
（59 用例全绿），之后 `sleep.exe` 多出 **4 个**；全量那一轮（1273 用例）同样再多几个。它们是这样：

```
98184  [C:\Program Files\coreutils\bin\sleep.exe 30]
       ThreadCount=1   Win32_Thread.ThreadState=5 (Waiting)
       Win32_Thread.ThreadWaitReason=5 (Suspended)
       KernelModeTime/UserModeTime = 0/0
       parent = GONE
```

- **它们永远不会自己退出**：12:43 与 12:52 起的那些，到 13:06 还活着（`sleep 30` 早该走了），
  从早上累积到 30+ 个，每一个的父进程都已经不在。今天已经把它们全部杀掉（`taskkill /F`，
  12 个，杀完为 0）——**它们自己走不掉**，所以这是"留到重启"的那种垃圾。
- `Suspended` ＋ CPU 时间 0 的意思是：**这个进程被创建成挂起的，而且没有任何人唤醒过它**。
  所以这一票不是"杀得不够狠"，而是"**有一个进程从来没跑起来**"。

## 形状与决策

- **第一个要查的是名字，不是修法**：在这台机器上，**谁**把 `sleep` 解析成
  `C:\Program Files\coreutils\bin\sleep.exe`？当天试过的三个候选都不是省事的那个答案：
  harness 的 bash 里 `command -v sleep` 什么也没找到；pwsh 的 `sleep` 是 `Start-Sleep` 的别名；
  用 `shell: cmd` 起 `sleep 60` 的那次，连一个 `sleep.exe` 进程都没出现。**先把这条钉死**
  （跑一轮、在命令刚起的几百毫秒内抓一次进程表，看它的父进程是谁），再谈修。
- **不要先动 `infra.shell/kill-tree!`。** 今天验过：`job_kill` 收一棵 `sleep 45` 的树是干净的
  （shell 与 sleep 一起没）。所以这不是"收尾收不掉"，改它只会把一个好函数改坏。
- **`Suspended` 那一族最常见的来源是「创建者的 `CreateProcess` 走到一半被打断」**：Windows 先建
  挂起的初始线程、再 resume，中间那一刀落在谁身上，这个进程就永远挂着。若查下来是这条，那要问的
  就不是"怎么杀"，而是"**测试在命令刚起的瞬间杀的是什么**"（`kill-tree!` 的第一个动作是对壳
  `destroy`，而壳当时可能正站在 `CreateProcess` 里）。
- 这一票与 `spec.md` 里那两票同一个家族（一个进程的"结束"记错了地方），但**根因不同**，
  所以它是单独一票，不是那两票的补充。

## 验收

- [ ] 跑完 `harness.infra.shell-test` + `harness.cap.jobs-test`，以及全量那一轮之后，
      `Get-CimInstance Win32_Process | ? { $_.Name -eq 'sleep.exe' }` 是空的
- [ ] 票底写出**谁**创建了它、以及为什么没有 resume —— 一个名字，而不是"某个 shell"
- [ ] 根因在哪一层，那层就要有用例钉住（`infra.shell` 的 spawn/收尾缝，或 test-support 的夹具）
- [ ] 与本票相关的那一轮测试全绿（`harness.infra.shell-test`、`harness.cap.jobs-test`，全量另跑）
