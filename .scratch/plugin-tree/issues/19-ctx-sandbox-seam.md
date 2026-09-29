# 19 — `ctx.sandbox` 接缝

**What to build:** dsh 有一条**沙箱缝**：消费者的做法是**在 spawn 之前把 argv 包一层**，于是把 fs 与
subprocess 两个 provider 一起指到远程沙箱，`bash`、PTY、LSP 就跟着搬过去了，不用各自 fork 一份实现。
本仓今天**没有**任何隔离——`bash` 就是在机器上跑。这一票新立这条缝加一个**默认实现（不隔离，本机跑）**，
并让 subprocess 的消费者经它包 argv。

**Blocked by:** 14 — `ctx.subprocess` / `ctx.shell` 接缝

**Status:** ready-for-agent

## 验收

- [ ] 缝的三个角色各有名字；**默认实现是「不隔离」**，并且它明说自己不隔离（不假装有边界）
- [ ] 消费者（`bash`、`job`、hook 命令、`cap.git`、`infra.rg`）在 spawn 前**都**经过它包 argv——
      有一条断言钉着「没有第二条绕过它的 spawn 路径」
- [ ] 换一个实现（例如在临时目录里跑、或包一层受限命令）不动任何消费者——接缝成立的反证
- [ ] 围栏（票 13 的 `ctx.fs` 策略）与沙箱的分工写清：围栏管**哪些路径**，沙箱管**进程能碰什么**
- [ ] 「本仓今天没有真沙箱」这件事写进 docstring 与 `docs/architecture.md`，不许被读成已有隔离
- [ ] 审批那条「人一直不响应就一直待决」不受影响
