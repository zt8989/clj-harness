# 03 — 内层调用进审计流

**What to build:** 脚本里发出的每次工具调用都留下三行 `tools/*` 审计线，id 从外层那次调用派生。
今天三行只写给顶层调用：内层调用的相位事件**没有去处**（`run!` 收到的是 nil on-phase，
而 `report` 对 nil on-phase 什么都不做）。hook 那一半**已经看得见**内层调用（实测：一个
`:matcher "bash"` 的门禁拦住过 eval 里的 bash 并落了 `hook/PreToolUse` 行）——缺的只有审计线，
这一票是把两边补齐。

**Blocked by:** 01 — 有内层调用可谈记录，才谈得上记录它

**Status:** ready-for-agent

## 决定

- **缝把当前 run 的相位上报函数绑在一个动态 var 上**，位置与理由都跟 `*thread-id*` 一样
  （内层代码要寻址自己所属的那次 run）。`call!` 默认用它；显式传了 on-phase 的调用方以它自己为准，
  `loop` / `replay` / 既有测试**一字不改**。
- **id 由外层调用派生**：`<外层 toolCallId>/<n>`，`n` 在该次调用内递增。这样读日志的人一眼分得出
  内层与顶层，也能机械地把内层归到外层——而不是拿到一串看起来像顶层调用的 id 去猜。
- **内层事件照旧不上 wire**：相位事件本来就是审计行、不是帧（`harness.event` 的 docstring 写着这件事），
  所以 UI 不会因此多出卡片。**一个脚本的中间步骤不是七张卡片。**

## 已知代价（写在这里，免得下一个人重新发现）

一次循环二十个文件会落六十行审计线。接受它：审计的价值在时间线，而「脚本里的写操作在日志里查不到」
正是这一票要修的东西。真要压噪声，那是采样与归档的事（ObservationPack 那一族），不是这里偷偷只写一半。

## 验收

- [ ] 一次 eval 里调 N 个工具 → 日志里出现 3N 行内层 `tools/*`（pre / execute / post 各一行），
      id 全部派生自那次 eval 调用，且互不相同
- [ ] 内层行的 thread 与 runId 语义不变（仍由边写，仍是 `harness.http` 的 `log!`）
- [ ] **「审计三行不带 args」继续成立**：内层那些行不带参数，命令正文仍只在
      assistant message 的 `tool_calls[].function.arguments` 里（与 `eval` 的 code 同一条读法）
- [ ] 内层行**不上 wire**：一次带内层调用的 run 与不带内层调用的同形 run，AG-UI 帧序相同，UI 不多卡片
- [ ] 顶层调用不变：三行、id 就是 `toolCallId`，与基线逐字节相同
- [ ] 显式传 on-phase 的既有调用方（`loop`、`replay!`、缝级测试）行为不变
- [ ] 离线全量 `harness.test-runner` 全绿

## 测试卫生

hook 与审计都写在磁盘与共享进程状态上，测试同进程跑——**每个用例前后各擦一次**。
串味在这条路径上的表现是「上一次用例留下的 hook 批准/拦住了这一次的调用」，
`hooks_wired_test` 补 fixture 那次就是这么吃的亏。
