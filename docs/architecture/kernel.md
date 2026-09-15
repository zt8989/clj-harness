# 内核：event / loop / llm / tools

这四个命名空间是内核的全部。`event` 定义词汇，`llm` 说话，`tools` 干活，`loop` 把它们串起来。

## 事件：11 种，就这些

`harness.event` 是内核唯一的输出面。**AG-UI 的帧全部由 `harness.ag_ui` 从这些事件派生**，
内核自己不知道 AG-UI 存在。

| 事件 | 含义 |
|---|---|
| `:run/start` | 一轮 run 开始 |
| `:text/delta` | 助手文本的一个增量 |
| `:reasoning/delta` | 推理内容的一个增量 |
| `:tool/call` | 一次工具调用，参数是**累积完成的**文本（不是碎片） |
| `:tool/result` | 一次工具调用的结果 |
| `:tool/pre-execute` | 一次调用进入执行缝（**不上 wire**，只落审计行） |
| `:tool/execute` | 一次调用离开执行（同上） |
| `:tool/post-execute` | 一次调用的生命周期闭合（同上） |
| `:run/end` | 正常收尾 |
| `:run/interrupt` | **第二种终态**：有调用 park 等人，本次 run 到此为止 |
| `:run/error` | 出错收尾 |

`:run/end` 与 `:run/interrupt` **互斥**，一次 run 恰好发其一——客户端因此永远看得到一个终结。

## 循环：`harness.loop`

```
drive! :
  emit :run/start
  [有 resume 就先 replay! —— 把人的决定重放进缝]
  loop:
    llm/stream!      流式一轮（事件边流边发）
    tool_calls 非空 → 并发跑，收齐结果，追加 tool 消息，再来一轮
    tool_calls 为空 → 结束
  有 parked → emit :run/interrupt，否则 emit :run/end
  然后 emit :run/done（携带最终 history），通道关闭
```

几个不显然的地方：

- **同一轮的工具调用并发跑**，各自一个 `async/thread`；每个 `:tool/result` 在它自己的工具跑完时立刻发出
  （结果按**完成顺序**流动），但写进历史的 tool 消息按**调用顺序**——每个 `tool_call_id` 恰好被回答一次。
- **每条调用一个缓冲通道，且永不关闭**：`alts!!` 把「已关闭且为空」的通道当成就绪返回 nil，
  真关掉会让一次排空吞下一个幻影 nil，把真实结果晾在那儿。
- **parked 的调用不回答**：不发 `:tool/result`、不写 tool 消息。它还没被回答，
  它的工具消息落在 resume 那一轮。
- **`:run/done` 不是 wire 帧**，它是「本 run 追加了哪些消息」的返回面，边把它落成 `message` 行。
- **没有迭代上限**，这是设计。
- `replay!` 遇到本进程没 park 过的 interrupt **直接抛**——猜一个批准是这里最坏的失败模式。

## provider 层：`harness.llm`

一个 multimethod，按 `:protocol` 分派；生产上只有 `:openai-completions`，测试里有一个 `:fake` 脚本替身。

请求体只带 `{:model :messages :tools :stream true}`（有 reasoning-effort 就加 `reasoning_effort`）。
`:tools` 来自 `tools/specs`（**每个 LLM 请求都现取**——这条以后会变成 MCP 连接必须缓存的理由）。

两件被反复踩出来的事：

- **`reasoning_content` 必须原样留在历史里。** 带 `tools` 的请求，后续每一轮都要回传它，
  否则 DeepSeek 直接 400。所以 `loop` 把 assistant 消息**逐字**追加，从不重建。
  **推理字段两个拼法都认**：`llm/consume-sse` 同时读 `reasoning_content`（DeepSeek）与 `reasoning`
  （OpenRouter 代理），回来时按 OpenAI 形状写成 `reasoning_content`。
- **`line-seq` 是惰性的**，必须在 `with-open` 里强制求值，否则响应体泄漏、调用方死等一个没人读的流。

`:reasoning-effort` 只有某一档真的选了才出现（`reasoning_effort` 仅部分厂商需要，不给就是厂商默认）。

**`prompt.md` 的载体也在这里**（`prompt` / `reset-prompt!`），因为「冻结」这件事的理由就是 provider 的
前缀缓存——它是 provider 的约束，放在 provider 层。

## 工具表与执行缝：`harness.tools`

工具定义：

```clojure
{:description string :parameters JSON-Schema :required [kw..] :run (fn [args] string)}
;; 另有可选标记：:fence-paths（受围栏约束的文件工具）、:requires-approval（调用即 park）
```

**表与读表的缝住在一起**，因为它们是同一件事的两半：缝决定一次调用意味着什么，表说有什么可调。

### 六个内建

`read` / `write` / `edit`（三个带 `:fence-paths`）、`bash`、`eval`、`session-configure`（带 `:requires-approval`）。

`read` / `write` / `edit` 的相对路径经 `project/resolve-path` 重根到会话的项目目录，**回报的是已解析路径**。
`bash` 的 cwd 是绑定的目录；**命令内容永不判定**（这是明示接受的逃逸面）。
`eval` 在常驻的 `harness.user` 命名空间里执行，`def` 跨调用保留。
`session-configure` 改本会话的 provider/model/reasoning-effort，**先解析后写**——改不动的配置不会被写进会话。

### 会话 overlay：两条正交轴

```
thread-id → {:added {name tool}   ; presence：本会话贡献的定义
             :disabled #{name}}   ; availability：本会话关掉的，定义不动
```

- **关闭不是隐藏**：被关的工具**仍在工具表里**（模型仍看得见），调用被缝以 `:disabled` 拒掉。
  藏起来会让「没有这个工具」和「这个工具关着」变成同一个观察，而前者是谎话。
- **没有 `:removed`**：任何东西都不许从工具表里消失。
- 关掉是**策略开关，不是安全边界**——关掉 `write` 不阻止 `bash` 写文件。
- 全部进程内、按 thread、重启即失。

### 执行缝：一次调用，三相，三个出口

```clojure
(tools/run! call thread-id on-phase)
;; call 是 PROVIDER 形状：{:id .. :type "function" :function {:name .. :arguments json-string}}
;; 永不抛、永不返回 nil —— 工具失败是给模型的信息，不是 run 的失败
```

`on-phase` 收到三相事件（pre / execute / post）。**生命周期总是闭合**：连没通过 pre 的调用
（未知工具、被关、缺参数）也有自己的 `:tool/post-execute`。

判定次序（`cond`，先匹配者胜）：

| 序 | 判定 | 出口 | pre-execute 的 outcome |
|---|---|---|---|
| 1 | 本会话关掉了它 | 阻断（硬拒） | `:disabled` |
| 2 | 缺必填参数 | 阻断 | `:missing-args` |
| 3 | 审批规则命中 | 悬置**或**按已有决定穿越 | `:needs-approval` / `:approved` / `:vetoed` |
| 4 | `PreToolUse` 门禁 | 放行 / 阻断 | `:pass` / `:hook-blocked` |
| — | 工具不在表里 | 阻断 | `:unknown-tool` |

**为什么门禁最后**：前三条是 harness 自己的判断；一个根本跑不起来的调用拿去问用户的规则，
既浪费一次 spawn，也把模型读到的理由搅浑。

### 悬置：先问规则，再问人

一条必须悬置的调用问两次：

1. **已经有人答了吗？**（`take-decision!` 取 resume 带回来的裁决，取过即标记已消费——
   重放同一个 interrupt 不能把调用执行两次。）
2. 没有就问 **`PermissionRequest`**：让一条**规则**回答本该打断人的事。
   它的效力与人的答案相同（`approve` 就执行，`deny` 就用它的理由回答这次调用）；
   **声明了但没给答案，就照旧 park 等人**。

所以一个没声明任何 hook 的会话，行为与 hook 存在之前逐字节相同。

悬置的**原因**算一次、随 parked 记录走（`:tool-declares` / `:session-asks` / `:out-of-bounds`），
因为人（和读日志的人）需要知道这是工具自己声明的、本会话要求的、还是撞了项目围栏。

审批状态全在进程内存（`parked-registry`：interrupt-id → 记录），重启即失；
拿一个本进程没 park 过的 interruptId 来 resume 会被**明确拒绝**，不猜。

**不做超时，也不做跨进程持久化**：人一直不响应，这个 thread 就一直待决——这是可接受的语义，
不是缺陷（interrupt 也不填 `expiresAt`，延续本仓「不写 sleep、不重试」的纪律）。
两个开启悬置的来源都不删：工具自带 `:requires-approval` 与 `session-require-approval!`，
它们是「给这个工具装一条悬置型判定」的两种来源，与 `hooks.edn` 里写的门禁同一族。
**默认全放行**：没有任何工具被标记时，帧序列与这套能力存在之前逐字节相同。

**wire 上的形状**（客户端那一侧）：

```
内核 :run/interrupt
  → ag_ui 映射为 RUN_FINISHED + outcome{type:"interrupt",
      interrupts:[{id, reason:"tool-approval", message, toolCallId}]}
  → 客户端从 outcome.interrupts 落 pendingInterrupts
  → 下一次 run 的 RunAgentInput.resume:[{interruptId, status, payload?}]
      status "resolved" ⇒ 批准   "cancelled" ⇒ 否决   （同一个 POST 端点，不做第二个）
```

interrupt 的键是**严格校验**的（AG-UI 的 zod 多一个键就失败），所以内核只往外带四件事实；
给**人**看的那句话是 `message`，不是 `reason`。一次 run 里有多条开着的 interrupt 时，
客户端必须一次把它们**全部** resume——「只回答一部分」会被运行时按名拒绝。

`*thread-id*` 在工具体外面被绑定，所以工具内部的代码（`eval` 尤其是）能问到自己属于哪个会话。
