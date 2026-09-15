# 05 — 审批门：人在回路的那张卡

**Status: done** — `ui/src/components/approval-gate.tsx`（新）、`ui/src/components/message-parts.tsx`（接入）、
`ui/src/app.tsx`（挂 provider + `isSendDisabled`）。服务端零改动。三关全绿，真 Chromium 双后端
（单调用批准/否决、park 期间发送探测、双调用批次各一遍）走完，截图见
`.scratch/assistant-ui/evidence/t05-01..06`。

## 代码落点

| 文件 | 放了什么 |
|---|---|
| `components/approval-gate.tsx` | `ApprovalBatchProvider`（批次决定表 + 补齐即提交 + hold 上报）、`ApprovalGate`（按 `toolCallId` 认领中断并画卡）、`APPROVAL_REASON` 常量 |
| `components/message-parts.tsx` | `ToolCallCard` 的 parked 判据：part status **或** 未决中断命中此 `toolCallId`，两者任一即 `needs-approval` 并挂审批卡 |
| `app.tsx` | `ApprovalBatchProvider` 挂在 Thread 外层；`useAgUiRuntime({ agent, isSendDisabled: ... })` 把 hold 接进 composer |

## 两个接缝，查实后选了一个

票面写的是 `unstable_getPendingInterrupts` / `unstable_submitInterruptResponses` 两个方法。装的这版
（`@assistant-ui/react-ag-ui` 0.x）**已经把这两个换成稳定名字的 hooks**：`useAgUiInterrupts()` 读
`RUN_FINISHED.outcome.interrupts` 的快照，`useAgUiSubmitInterruptResponses()` 校验「每个开着的 interrupt
都有应答」后一次提交并恢复。旧的 runtime 方法还在但带 `unstable_`，没有用。

另一条路也查实了：assistant-ui 有一等公民的审批投影（`ToolCallMessagePart.approval` +
`respondToApproval`），但它的 ag-ui 适配层 `projectAgUiToolApprovals` 只认
`reason === "tool_call"` 的中断，我们的服务端发的是 `"tool-approval"`——投影返回空 map，`approval`
永远不会出现在我们的 part 上，上游自己的 `ToolFallbackApproval` 在这张网上画不出任何东西。所以卡是
自己写的，只借上游的形状（同样的 Button 原子、提交中禁用、行内错误段）。

## 批次：一次 park 多个调用，必须攒齐再交

AG-UI 恢复一个 run 时要求 **每个开着的 interrupt 各有一条应答**，runtime 对缺条的提交按名字报错
（"missing responses for open interrupts: …"）。所以批次不是优化是约束：每张卡只记录自己的决定，
最后一张卡补齐时整批提交。`ApprovalBatchProvider` 挂在 Thread 外层就是因为组成批次的卡是兄弟节点、
没有共同归属；决定的 key 是 interrupt id，批次变化（被答完/被新一轮替换/换线程）时残留决定随之清掉。

## 实测抓到的两个深坑（读代码读不出来）

**① 一发多调用时，第一批 park 的调用落在已定稿的消息里，状态显示 Done。**
客户端 runtime 给每个服务端 `TEXT_MESSAGE` 开一条自己的助手消息：新的 text 一到，它把上一条消息
**定稿为 complete**，并清空自己的累计。interrupt 元数据只挂在**最后一条**消息上。于是一个 park 了
read + write 的回合，read 的 part 住在已定稿（complete）的消息里——按 part status 判断，这张卡显示
「Done」，也不挂审批卡；而 runtime 又拒绝部分提交，provider 永远等不到 read 的决定，
**批次死锁，composer 永远不再打开**。这是真浏览器里跑双调用批次跑出来的，不是推出来的。
修法：`ToolCallCard` 的 parked 判据并入中断缝——未决中断里有这个 `toolCallId`，卡就是
`needs-approval`，无论 part status 说什么。恢复运行后，read 的结果由 runtime 的跨运行结果补写
（`applyCrossRunToolResult`）送回旧消息里的那张卡，实测内容逐字对上。

**② park 期间发送消息的失败是无声的。** 票面预料到「会抛错」，实测比抛错更糟：composer 被清空、
消息不进记录、没有请求、没有日志、没有任何提示——输入的文字凭空消失。所以选了「拦住」：
`useAgUiRuntime` 的 `isSendDisabled` 由 provider 的 hold 状态驱动，gate 开着时 Send 键禁用、文字留在
框里。禁用是真的禁（`updateStore` 每次重读），实测 park 期间按 Enter 文字原地不动；gate 一关，
同一份输入立即可发。卡上另有一行小字说明这件事，让禁用读起来像规则而不是坏了。

## 卸载与时钟

票面要求的「park 期间卸载要取消 run」在运行时已有：detach 会 abort 活跃 run。而 park 状态本身
没有 run 可取消——`RUN_FINISHED` 已到，`abortController` 是 null，卸载什么也不会打断；服务端的
park 记录是进程内存（`parked-registry`），进程死了 park 也就没了。这里没有加任何自己的 abort：
运行时的 `onCancel` 是给「有 run 在飞」的场景的，趁 park 悄悄 abort 别的东西才是要避免的。
`expiresAt` 的过期检查在 `submitInterruptResponses` 里由 runtime 做（我们的服务端不发过期时间，
该分支不生效）。

## 验收记录

| 关 | 结果 |
|---|---|
| `tsc --noEmit` | 0 error |
| `npm run build` | 全绿，CSS 83.94 kB / JS 1248.59 kB（gzip 354.54 kB），比 04 多 3.6 kB，就是这张卡 |
| `npm test` | **11 passed**，四组用例一行未改 |

真 Chromium（脚本化后端 + 假 provider，真后端让出 8080，跑完恢复）：

- **批准路**：park 卡出现（工具卡仍折叠，审批卡在折叠头之下全宽），点 Approve → 文件落盘、卡翻
  `Done`、审批卡消失、run 恢复、composer 复原（`t05-01`）。
- **否决路**：点 Deny → 文件不存在、run 继续、模型收到 `vetoed by human: the call was not executed.`
  并改变行为（最终文本说文件没建）（`t05-03`、`t05-04`）。
- **hold 探测**：park 期间输入文字 → Send 禁用、文字保留；按 Enter 无效（`t05-02`）。修复前的行为
  （文字被无声吞掉）也实测过，作为对照。
- **批次路**：read + write 同回合并 park → **两张卡都显示 Needs approval**（修复的直接证据，
  `t05-05`）；批 read（只记录，批次仍停）、否 write（补齐提交）→ read 真执行、内容逐字、write 未执行、
  最终文本确认、composer 复原（`t05-06`）。
- 控制台除 vite 连接日志外零输出，页面错误零。

## 给 06 的接点

park 的中断只活在客户端内存里，页面一刷新就没了，而服务端的 park 记录还在进程里。06 的恢复路径
要么把 park 状态一起恢复，要么明确「刷新即弃决定」并保证重放时不重新 park 出一张无法回答的卡。
另外 `isSendDisabled` 已经是 hold 驱动的，06 若加线程切换，切走时 hold 要跟着断。

## 复议：code-review 抓到的问题逐条对过

两轴审查（Standards / Spec）之后逐条核实，改了一处，其余有实证结论：

- **（已改）中断判定谓词三处重复。** `reason === "tool-approval"` 的比较原来散在 provider 的过滤、
  审批卡的认领、工具卡的 parked 判据三处，reason 串漂移一处就三处全坏。收成
  `isApprovalInterrupt()` 一个出口，其余地方都问它。
- **payload 不是没人读。** Spec 轴怀疑否决不带 payload 会与后端预期不符。查实：`veto-message`
  **确实读 payload**——有则附 ` reason: <json>`。我们的否决不带（与被替换的旧 gate 逐字一致），
  所以模型看到的正是 `vetoed by human: the call was not executed.`，浏览器实测与此逐字相符；
  批准分支完全不读 payload。
- **`onCancel` 那条票面要求的核实过程补全。** `detachRuntime()` 的实现是 `this.cancel()` →
  `abortActiveRun()`：在飞 run 卸载即中止，这是上游行为，不是我们加的；park 态没有活跃 run
  （`abortController` 已是 null），无可中止。票面的意图「不留下永远等不到答复的悬挂」由
  「在飞即中止 + park 是进程内存、进程死则 park 消失」共同覆盖，未加自己的 abort。
- **「参数逐字比对」的核对方式是验收而非断言。** 卡上的 `argsText` 是客户端 part 自己的（按
  `toolCallId` 认领，不解析服务端 `message`），与线上 `TOOL_CALL_ARGS` 的 delta 逐字对过的证据在
  截图里（`t05-01`、`t05-05` 的参数块与脚本 JSON 逐字符一致），没有为此加运行时断言。
- **批次提交与卡上多出的两段说明判为保留。** 批次不是 scope creep：runtime 拒绝部分提交是硬约束，
  服务端一次 park 多个调用是真实路径（`approval_test.clj` 有用例）；hold 提示段支撑「不伪装空闲」
  这条票面要求；提交失败的行内错误与可重试支撑「提交缝断了不能静默」。
