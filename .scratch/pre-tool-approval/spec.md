# spec: pre-tool-approval（执行前人工审批 + 否决）

在工具执行的 pre 相引入 Human-in-the-loop：被标记的调用在真正执行前暂停、交出决定权给人，人可以批准（照常执行）或否决（不执行，把否决当作工具结果回灌给模型）。这是 tools-lifecycle spec「非目标」里明确排除、本次推翻的那一条（pre-hook veto / needs-approval 审批暂停）。

## 参考（2026-09-13 已核对客户端源码，非臆测）

- **AG-UI 原生 interrupt 契约**（`@ag-ui/client@0.0.59`）：
  - `RUN_FINISHED.outcome` 是严格判别联合：`{type:"success"}` 或 `{type:"interrupt", interrupts:[...]}`；interrupt 对象允许的键只有 `id`（必填 string）、`reason`（必填 string）、`message?`、`toolCallId?`、`responseSchema?`、`expiresAt?`、`metadata?`——**多一个键即 zod 校验失败**。
  - 客户端从 `outcome.interrupts` 落 `agent.pendingInterrupts`；下一次 run 的 `RunAgentInput.resume` 是 `[{interruptId, status:"resolved"|"cancelled", payload?, metadata?}]`，且 `onInitialize` 要求**所有**未决 interrupt 都被 resume 覆盖，否则抛错。
- **CopilotKit v1.71 的 `useInterrupt`**（`@copilotkit/react-core/v2` 公开导出）：`render({interrupt, interrupts, resolve, cancel})`，`resolve(payload)` 以 `status:"resolved"` 重发、`cancel()` 以 `status:"cancelled"` 重发（都经 `copilotkit.runAgent` 自动带 resume）；`renderInChat` 默认 true，卡片自动渲染进 `<CopilotChat>`。
- **本仓既有契约**（不动）：执行缝 `tools/run!` 是唯一的工具执行点，且已带 `thread-id` 与 on-phase；`"工具失败是给模型的信息，不是 run 的失败"`；内核只产事件、字节↔字符串显式 UTF-8；jsonl 只 append 永不读。

## 决策

- **暂停机制走协议原生 interrupt，不自造帧。** 内核新增终态事件 `:run/interrupt`（第 11 种，与 `:run/end` 互斥：一次 run 只发其一）。ag_ui 把它映射为 `RUN_FINISHED` + `outcome{type:"interrupt", interrupts:[…]}`；帧类型仍是 `RUN_FINISHED`，SSE 的终止语义与消费端零改动。
- **决定回传走 resume，同一个 POST 端点。** `status:"cancelled"` ⇒ 否决；`status:"resolved"` ⇒ 批准（`payload` 原样带给工具/审计）。不做第二个端点。
- **审批是显式开启的能力，默认全放行。** 两条开启路径，取并集：工具定义带 `:requires-approval true`（base 注册或会话 overlay 均可），或会话级集合 `session-require-approval!`（落在 memory 的自省面）。现有 55 tests / 249 assertions 的脚本里没有审批工具，行为逐字节不变。
- **否决只作用于该次调用。** 工具不执行，向模型回灌一条工具结果（内容说明人工否决 + resume payload 里的理由），run 继续跑下一轮；wire 上就是一个普通 `TOOL_CALL_RESULT`。不中止 run，不抛错。
- **生命周期三相语义扩展，不新增相。** pre-execute 的 outcome 增加 `:needs-approval`（park：本相结束即闭合本次穿越——"缝看过了、交给人类了"）、以及决定穿越的 `:approved` / `:vetoed`。jsonl 沿用 `tools/pre-execute` 行（outcome 字段承载），**零新增 kind**。同一 toolCallId 会出现两次穿越的记录，按时间序读得通。
- **待决状态落 `harness.memory`（自省面），进程内、按 interruptId 键控。** 记录 `{thread-id, tool-call, name, args, run-id}` 与最终决定。agent 可以经 eval 看到"自己正等一个人点头"；决定由边写入，eval 侧没有伪造入口。重启即失——客户端拿着未知 interruptId 来 resume 会被明确拒绝，不做持久化。
- **内核结构不动。** 审批判定在 `tools/run!` 缝内完成（它已经有 thread-id 和 on-phase）；`loop` 只负责：一轮里有 parked 调用 ⇒ 发 `:run/interrupt` 收尾。**resume run 由 loop 的重放前导段处理**：对每个已决调用再走一次缝（批准即真执行、否决即回灌），追加 tool 消息，然后照常进 LLM 轮。
- **UI 用 `useInterrupt`，不手搓 overlay。** 项目无 shadcn/MUI/Chakra/Ant/Mantine，所以走 `renderInChat` 的聊天内卡片，用原生按钮；批准 `resolve({decision:"approved"})`、否决 `cancel()`；并照搬 CopilotKit 文档的 unmount 中止 run 的 ref 模式（HITL 元素卸载会让 run 挂死）。
- **不做过期。** interrupt 不填 `expiresAt`（延续本仓"不写 Thread/sleep、重试、超时"的纪律）。人工一直不响应 ⇒ 该 thread 一直待决，这是可接受的语义，不是缺陷。

## 非目标

- 不做待决状态的持久化（跨进程重启恢复审批）；不做过期/超时自动放行或自动否决。
- 不做工具级 allowed/disallowed 白名单中间件（`@ag-ui/client` 的 `FilterToolCallsMiddleware` 已覆盖该诉求）。
- 不做审批的"修改参数后放行"（只批/否，payload 只作为理由与审计，不回写调用参数）。
- 不做审批历史面板等 UI 界面工作，UI 只做当次审批卡片。
- 不做苹果派（ApplePi）的 approval 之外的审批形态（多级审批、配额审批）。

## 验收主线

离线全量 `harness.test-runner` 只增不减（基线 55 tests / 249 assertions），且：

1. scripted run 命中审批工具 ⇒ 工具**未执行**、无 `:tool/result`、run 以 `:run/interrupt` 收尾，转换出的帧是唯一一条带 `outcome.interrupts` 的 `RUN_FINISHED`；
2. 同 thread 的 resume run（`resolved`）⇒ 工具真实执行、结果与 tool 消息按 call 序进 history；
3. resume（`cancelled`）⇒ 工具未执行、回灌否决消息、run 继续；
4. 未开启审批的工具：帧序列与现状逐字节等价（非目标守住）；
5. 真实 HTTP 集成：`ui/verify-approval.mjs` 对活服务跑通批准与否决两条路径，全部帧过 `EventSchemas.safeParse`。

## 状态

- 01（内核：审批标记 + 缝 park + `:run/interrupt`）：已落地（8151a38）
- 02（内核：resume 重放前导）：已落地（531ad93）
- 03（协议与边：RUN_FINISHED outcome + resume 解码 + jsonl + 真 HTTP 集成）：已落地（bbb27f0）
- 04（前端：`useInterrupt` 审批卡 + verify-approval.mjs）：已落地

四张票全部完成并删除，验收主线逐条跑通。

## 已验证到什么程度

**离线全量**：`clojure -M:test -m harness.test-runner` → **70 tests / 348 assertions，0 failures 0 errors**（基线 55/249 ⇒ +15 tests / +99 assertions），三连跑稳定。

逐条对验收主线：

1. **park 与终态**（`harness.approval-test`）：标记工具命中 ⇒ 无 `:tool/result`、无 tool 消息、`tools/pre-execute` 以 `needs-approval` 闭合，run 以 `:run/interrupt` 收尾；`ag_ui_test` 断言转换出的帧是 `RUN_FINISHED` 且带 `outcome{type:"interrupt"}`，且 `EventSchemas.safeParse` 通过（内核 emit 前实测 `interrupt frame valid: true`）。
2. **批准路径**：同 thread 的 resume run 重放已决调用 ⇒ 工具真实执行、结果与 tool 消息按 call 序进 history；`a-decision-cannot-be-spent-twice` 守住"决定只消费一次"。
3. **否决路径**：工具未执行，回灌 `vetoed by human: …` + resume payload 里的理由，run 继续到正常终态。
4. **未开启审批零扰动**：`unmarked-tools-are-untouched` 覆盖；改动只在 `tools/run!` 的判定分支与 `loop` 的 parked 收尾，帧序列对未标记工具逐字节等价。
5. **真实 HTTP + 真模型**：`ui/verify-approval.mjs` 对活服务（8080、真 provider）跑出 **RESULT: PASS**，13 条断言全 ok —— 会话经 eval 标记 ⇒ 第一次 write park（`reason:"tool-approval"`、带 `toolCallId`、`pendingInterrupts.length === 1`、文件未写、调用未答）⇒ `resolved` 重发 ⇒ 文件写出、`TOOL_CALL_RESULT` 回流为 call 的答案、run 正常收尾 ⇒ 第二次 write 再次 park ⇒ `cancelled` ⇒ 文件未写、模型收到含理由的否决消息、run 照常收尾。`http_test` 另有真实 HTTP 的 park+resume 与"未知 interrupt 被拒绝"两条。

**客户端契约按源码核对（非臆测）**：`@ag-ui/client@0.0.59` 的 `defaultApplyEvents` 在 `RUN_FINISHED` 分支先算 `a = t.outcome?.type === "interrupt" ? {event, outcome:"interrupt", interrupts} : {event, outcome:"success", result}`，订阅者收到 `{...a, messages, state, agent, input}`，随后 `agent.pendingInterrupts = a.outcome === "interrupt" ? a.interrupts.map(…) : []`；`onInitialize` 对未被 resume 覆盖的未决 interrupt 抛 `AGUIError`。0.0.59 > 0.0.57，故 `BackwardCompatibility_0_0_57` 中间件未挂载，`interrupts` 原样透传、`pendingInterrupts` 确实被填充。

**已知弱点与未覆盖**：

- verify 脚本的合规性依赖真模型：弱模型会把"调用某工具"的轮次答成纯 reasoning（无文本、无工具调用）。脚本对每个合规依赖轮做最多 3 次重试并如实打印 note —— 本次第 4 步就用到了重试。park 之后的全部断言与模型无关，那半段才是这份脚本真正证明的东西。
- 未覆盖：真机上多 interrupt 同时 park 的 UI 表现（离线有覆盖）；过期（非目标）；跨进程重启后 resume（非目标，且被 `resume-decisions` 明确拒绝）。
- 环境注记：本机沙箱会把服务进程对 workspace 之外的写入重定向，`~/.lisp-harness/logs/*.jsonl` 在宿主机不可见。真机验证因此只走 wire、不读 jsonl；jsonl 的审批行（`tools/pre-execute` 的 `needs-approval`、`approval/decided`）由 `http_test` 在进程内覆盖。
