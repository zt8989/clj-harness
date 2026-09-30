# 03 — `interrupt`：stop 升级成命令，`POST …/cancel` 退役

**What to build:** 打断从「一条路由」变成「队列里最高优先级的那一条」，而且入队即生效。

- **命令**：`{:type "interrupt"}`。edge 收到它做**两件事**：
  1. **入队**（它因此有序、可寻址）；
  2. **立刻 ring** 那场 run 的 stop 开关——`sessions/cancel!`（`harness.kernel.stop` 的 flag+doorbell）。
     一个 park 在慢调用/慢厂商上的 run **当场**醒，不等下一个边界。**这就是「打断最高优先级」的落点**：
     它不进队列也起作用。
- **`harness.kernel.stop` 一个字不改**：flag+doorbell 仍是「怎么停」的机制（一条命令能唤醒一个 park 住的
  循环，一条队列条目不能）。变的只是**谁触发**它。
- **`POST /api/threads/<stem>/cancel` 退役**：设置里那句 `stop!` 的说明、`ui/src/lib/agent.ts` 的停止路径、
  composer 的停止按钮全部改走 `commands`。**没有 run 时拒**（409，沿用 `cancel-post` 今天那句原文：
  「这个进程没有那场会话的 run 可停」）。
- 结局不变：循环照旧给每个未答调用 `frames/cut-off-result`、run 走到 `:run/stopped` 的终止帧——
  这一票不碰停下来的那半，只换**入口**。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `{:type "interrupt"}` 随一次 run 请求进来：那场 run 的 stop 开关被 ring、run 走到 `:run/stopped`。
- [ ] **parked run 当场醒**：run 停在一个慢调用上（测试的 fake provider/tool 慢），一条 interrupt 命令
      让它在**下一个边界之前**结束（一条用例；这是本票最要紧的一条）。
- [ ] 没有 run 时一条 interrupt 被拒（409），句子与今天 `cancel-post` 同义。
- [ ] `POST …/cancel` **不在路由表里了**；旧路径 405/404，不是静静地还能用。
- [ ] `ui/src/lib/agent.ts` 的停止走 `commands`；composer 的停止按钮跟着改；端到端能停一场真 run。
- [ ] 记录不变：终止帧照旧写、`code: "stopped"` 照旧是客户端认的那一个（`lib/agent.ts` 那条判据不动）。
- [ ] 一条用例断言「停下来的那半」没有变（cut-off 结果、终止帧与今天逐字相同）。

**本票的界线**：`steer` 不做（票 05）。
