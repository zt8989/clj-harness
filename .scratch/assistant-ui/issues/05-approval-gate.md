# 05 — 审批门：人在回路的那张卡

**What to build:** 一次运行把工具调用挂起等批准时，聊天流里出现审批卡：工具名、参数、服务端给的说明、
批准与否决两个按钮、底部一句说明「批准则照常执行；否决则工具不执行，模型会收到一条被人工否决的工具
结果并继续」。点批准，工具真的执行、run 恢复；点否决，工具不执行，模型据此改变行为并且本轮照常继续。

**服务端语义一字不改。** 线上形状是已核对过的：park 的调用由服务端编成
`{id, reason: "tool-approval", message, toolCallId}` 放进 `RUN_FINISHED` 的 `outcome.interrupts`，
客户端下一次 run 用 `resume: [{interruptId, status}]` 回传，服务端把 `resolved` 映射成批准、`cancelled`
映射成否决。这一票动的只有「人这一端怎么把决定交出去」。

运行时的口子在 `AgUiAssistantRuntime` 上，两个方法都带 `unstable_` 前缀：

- `runtime.unstable_getPendingInterrupts()` —— 取最近一条助手消息上未处理中断的快照
- `runtime.unstable_submitInterruptResponses(responses)` —— 逐条提交 `{interruptId, status, payload}`
  并恢复运行

中断本身落在助手消息的 `metadata.custom.agui.interrupts` 上，该消息被标成 `requires-action`（原因
`interrupt`）——这就是卡片该出现的时机，不要去猜别的信号。

**有一个具体的失败形态必须处理。** 文档写明：未解决的**客户端工具调用**会被 `autoCancelPendingToolCalls`
自动取消，但**待处理中断绝不会被自动取消**——中断开着的时候再发消息，运行时会抛错。所以界面不能在中断
未决时假装自己能正常发送。要么在 composer 上拦住，要么把抛错变成一句人话，二选一，但必须选一个，
且要有验证。

**压下实验性 API 的风险**：中断读取与提交都带 `unstable_` 前缀。依赖它是本票的现实，但必须点名——升级会
破，且破的方式是审批静默失灵，属于最坏的失败模式。结论写进 `spec.md` 已知风险，并写下升级时的检查方式。

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] 服务端 park 的调用在聊天流里出现审批卡：工具名、参数、服务端 `message`、批准 / 否决两个按钮、底部说明
- [ ] 卡片只认领 `reason === "tool-approval"` 的中断；别的中断一概不碰，也不假装自己处理了
- [ ] 工具名与参数**从客户端自己的消息里读**（按中断的 `toolCallId` 去找对应的工具调用），不去解析服务端
      回显的 `message`——服务端的 `message` 里确实含工具名与参数，正因如此才更要读懂它是给人看的一句话，
      不是给程序读的字段。参数逐字比对
- [ ] 批准：该中断以 `status: "resolved"` 提交并带上与现状一致的 payload，工具真的执行，run 恢复，卡片消失
- [ ] 否决：以 `status: "cancelled"` 提交，工具不执行，模型收到被人工否决的工具结果并继续本轮；**否决只
      作用于这一次调用**，模型若再发起一次调用，再次 park、再次出卡
- [ ] 有未决中断时界面不伪装成空闲。中断开着时发送消息会抛错——界面要么拦住这个动作，要么把这个抛错
      转成明确提示，且两条路都验过
- [ ] 卡片所在的界面若在 park 期间被卸载，run 被取消——不留下一个永远等不到答复的挂起线程。运行时给了
      `onCancel` 回调，用它，不要自己在别处偷偷 abort
- [ ] `spec.md` 已知风险里记下：两个 `unstable_*` 方法的具体名字、升级时节点的检查方式（哪一步、看什么
      现象、怎么判），以及「审批静默失灵」这个失败形态长什么样
- [ ] 真 Chromium 验收：批准与否决两条路各走一次；否决后模型确实改变了行为，截图留档
- [ ] 服务端一侧不改一行：`resume` 的解析与重放逻辑保持原样，本票不碰后端
