# spec: minimal-kernel

用 Clojure 从零写的极简 agent 内核。唯一对外接口是 **AG-UI 协议**，验收用现成的
CopilotKit v2 客户端。

## 目标与定位

这是**探索载体**，不是日用工具。要验证两个命题：

1. Lisp 能把一个 agent 内核压到多小
2. **agent 能不能在运行时改自己的内核**（命题 1 之后的真命题）

pi（Mario Zechner 的 `badlogic/pi-mono`）在这里的角色是**极简的度量尺**，不是要抄的
包结构。架构分层沿用 applepi 已验证的 core 划分。

## 明确不做

| 不做 | 为什么 |
|---|---|
| 权限门禁 / 审批 / 沙箱 | 单机探索载体，工具可写任意文件、跑任意 shell。这是 pi 的默认 |
| trace | REPL 就是 trace |
| 上下文压缩 | 客户端持有历史；等真撞上上限再说 |
| plugin / extension / slash command | 不是探索命题的一部分 |
| `session` 状态权威 | 权威历史是 AG-UI 客户端（它每轮全量回传） |
| frontend tools（`input.tools`） | 会把 loop 从"跑到没有 tool call"变成"可能中途停下"，是对内核形状的实质改动 |
| `state` / JSON Patch 同步 | 没有状态类能力需要同步 |
| 上下文恢复（SSE `Last-Event-ID`） | 协议明确不使用。断线即换 `runId` 重跑 |

## 已定的契约

### 内核

- **消息形状 = provider 原始形状**（`tool_calls` / `reasoning_content` / snake_case），
  无中间表示。`tools` 与 `loop` 都活在这个世界里
- 内核产 **7 种事件**：`:run/start` `:text/delta` `:reasoning/delta` `:tool/call`
  `:tool/result` `:run/end` `:run/error`
- 工具**串行**执行；`:tool/call` 的 `args` **拼完再发**，不做增量参数，因此没有
  "断在 JSON 中间"的状态机
- 终止条件 = assistant 消息不含 `tool_calls`；**不设迭代上限**（跟随 pi，认账死循环烧钱）
- **绝不重建 assistant 消息**，把 provider 返回的原始 message 原样 append
- **不写 `Thread/sleep`、不写重试、不写超时配置**。坏了再加

### 工具（5 个）

`read` / `write` / `edit` / `bash` / `eval`。

- `edit` 要求 `old_string` 精确且唯一：不匹配报错，出现多次也报错，不做静默部分替换
- `bash` 走 **Git Bash**，显式钉住路径
- `eval` **in-process**，常驻 `harness.user`（`def` 跨调用保留），同时捕获 `*out*` 与
  返回值，`pr-str` 截断 8000，**不设超时**

### AG-UI 面

- 每次 run 收完整 `messages`；服务端不持有权威历史
- 最小合规流：`RUN_STARTED` → 消息/工具事件 → `RUN_FINISHED`。不发 `STEP_*` /
  `STATE_*` / `MESSAGES_SNAPSHOT` / `RAW` / `CUSTOM` / `SUBAGENT_*`
- 终止 = **关 body，无 `[DONE]` 哨兵**
- **每轮 assistant 都发一个 `TEXT_MESSAGE`**（内容可空），tool calls 挂它的
  `parentMessageId`
- **绝不发 `*_CHUNK`**
- 忽略 `input.tools` / `state` / `forwardedProps` / `resume`；`context` 拼进
  system prompt 尾部
- system prompt 载体是磁盘上的 `prompt.md`，**每个 run 重新读**（agent 能用 `write`
  改它）；首条是 system 则替换，否则前插

### reasoning 链路

出站：`:reasoning/delta` → `REASONING_START` → `REASONING_MESSAGE_START/CONTENT*/END`
→ `REASONING_END`，独立 `messageId`。

入站/折返：AG-UI 把 reasoning 存成**独立消息**，provider 要的是 assistant 消息上的
**字段**。所以回灌时把 `role:"reasoning"` 折给**紧随其后的第一条 assistant**（邻接规则）。

**这不是美化，是硬要求**——见下。

### 错误语义

分界线：**传输/LLM 层的失败 → `RUN_ERROR` 终止；模型输出与工具执行层的失败 → 回灌**。

| 场景 | 处理 |
|---|---|
| HTTP 非 200、SSE 中断 | `RUN_ERROR`，run 终止 |
| `arguments` 非法 JSON | 不终止，`error: true` 回灌 |
| 工具抛异常（bash 非零退出、edit 匹配不上） | 不终止，回灌 |
| `eval` 编译/运行出错 | 不终止，回灌 |

理由：工具错误对模型是**可恢复信息**，不是 run 的失败。

### 配置与日志

- `config.edn`（提交）存 `:protocol` / `:base-url` / `:model` / `:reasoning-effort`，
  **每轮重读**，可热改
- 密钥在 `.env`（gitignore）+ `.env.example`（提交），经 dotenv 库读取。
  遵循**该库的优先级**：`.env` 压过真实环境变量
- 日志 JSONL，一线程一文件 `~/.lisp-harness/logs/<threadId>.jsonl`，每行
  `{ts, runId, kind: "input"|"event", payload}`。入参原文和每一帧都存。
  **只 append，永不读**

## 后半程才发现的硬约束

这些是读规范读不出来、必须真跑才暴露的。写在这里是因为它们**决定实现形状**。

### DeepSeek V4

- **思考模式是调用参数不是模型名**：`thinking` + `reasoning_effort`，**默认开启**，
  effort 默认 `high`
- **请求带 `tools` 时，后续每一轮都必须完整回传历史里的 `reasoning_content`**，
  包括那些没有发起 tool call 的轮次；漏传 **HTTP 400**。不带 `tools` 时多传会被忽略
- 因为默认模型就是思考模式开启，**这不是边缘场景而是默认路径**
- 推论：只存 `content` 的框架在这里必炸；`loop` 必须原样 append provider 消息

### AG-UI shipped schema

shipped 版（`@ag-ui/core` 0.0.59 一代）的 zod 判别联合比文档严格：

- `REASONING_START` 与 `REASONING_END` **需要 `messageId`**
- `REASONING_MESSAGE_START` 的 `role` 必须是**字面量 `"reasoning"`**
- 客户端的 applier 确实不看这个 `role`（它硬编码），但 **zod 在 applier 之前先校验**，
  所以漏了就是硬失败
- 必须能忽略未知事件类型（前向兼容）
- **不要按 1.0 draft 写**：`protocolVersion` 在 shipped 版不存在，`THINKING_*` 已废弃

### reasoning 折返必须成立

**每一轮 assistant 都必须有一条紧随 reasoning 的消息**。否则那条 reasoning 没有可
折返的 assistant 消息，下一轮就丢 `reasoning_content` → 400。

可达场景：`finish_reason: "length"` 且 `content` 为空（max_tokens 被思维链吃满）。
所以适配器在关闭 reasoning 组之后**总是**补一个 assistant 消息，所有分支统一成立。

### http-kit

- `as-channel` **不使用外层 ring map 的 headers**。响应头必须跟**第一个 `send!`** 走
- 关闭必须跟**终止帧**走（`close-after-send?`）。单独调 `hk/close` 会把整个响应丢掉：
  http-kit 对小块写入做缓冲，close 走另一条路径时未 flush 的部分就没了。
  症状是"日志里有完整正确的帧，客户端收到 0 帧"
- `send!` 对 HTTP 的 `close-after-send?` **默认为 true**，中间每个 chunk 都要显式传 false
- `:on-open` 里不能阻塞，用 `future`
- 响应体发 **UTF-8 byte 数组**，不发 String
- `emitter` 和 `converter` 都**每次 run 只建一次**（每事件新建会让 messageId 重启、
  `START` 帧重复）

### 入站消息规范化

- 丢 `role:"activity"`（规范强制，永不回传）
- assistant 白名单重建为 `{role, content, tool_calls, reasoning_content}`；
  tool 为 `{role, content, tool_call_id}`
- `toolCalls`→`tool_calls`、`toolCallId`→`tool_call_id`
- 丢 AG-UI 专有字段 `id` / `encryptedValue` / `subagentRunId` / `metadata` / `activityType`
- `user` / `system` 的 **content 原样透传**（可能是多模态 part 数组，白名单会把它压平）

### 本机环境（Windows）

- PATH 上的 `bash` 是 **`System32\bash.exe` 也就是 WSL 启动器**，从 JVM 里跑是**另一个
  文件系统**且会静默返回空输出。必须显式钉住 Git Bash
- JVM 默认字符集是 **GBK**，且本机的 deps.clj 启动器**不读 `:jvm-opts`**。
  所以代码不能依赖 JVM 默认字符集——所有字节↔字符串边界显式 UTF-8。
  好在 `clojure.core/spit`/`slurp` 默认就是 UTF-8，且 `json/write-str` 会把非 ASCII
  转义成 `\uXXXX`，线上是纯 ASCII
- GitHub 与 Playwright 的 CDN 都不可达（所以无头浏览器截图这条路不通）
- `dotenv/env` 让 `.env` 压过真实环境变量（拿 `USERPROFILE` 对照实测过）

## 实测预算

| 模块 | 实测净行数 | 预算 |
|---|---|---|
| core（`event` + `llm` + `loop` + `tools`） | **261** | 500 |
| `ag-ui` | **133** | 200 |
| `http` | **95** | 100 |

> 2026-09-12 用 `python` 去空去注释计数（`strip` + `startswith ";"`）复核：`llm.clj` 因兼容 OpenRouter 的 `reasoning` 字段（`reasoning_content`/`reasoning` 二选一，见 `src/harness/llm.clj:73`）从 111→114 净行，`core` 258→261、`ag-ui` 132→133，均在预算内。

对照：pi 的锚点是 4 个工具、system prompt + 工具定义 < 1000 token。我们的锚点是
5 个工具、prompt + 工具定义 ≤ 1200 token（`prompt.md` 53 词/330 字符，5 工具 specs 约 350 token）。

## 交付物

```
src/harness/{event,llm,tools,loop,ag_ui,http}.clj
dev/harness/{wire,replay}.clj  replay 的读侧 + wire 的 AG-UI 结构契约（复用于 tests）
dev/harness/repl.clj          起服务后落进 REPL（日常开发形态）
ui/                           CopilotKit v2 验收应用（Vite，4 个源文件）
ui/verify.mjs                 用真 @ag-ui/client 驱动并断言
ui/check-frames.mjs           把每一帧喂给 @ag-ui/core 的 schema 校验
ui/verify-real.mjs            针对 free 模型真实跑一轮+第二轮（非 deepseek 专用 400 用例的等价验证）
test/harness/                 44 tests / 167 assertions（其中离线 44/167 仍零网络；真机另计）
test/harness/fixtures/deepseek_sse.txt  已由手写 3-chunk 合成体替换为 OpenRouter 上 `nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free` 的真实 SSE 捕获（301 行，含 `reasoning`/`tool_calls`，`llm/consume-sse` 对两种字段均兼容）
```

## 已验证到什么程度

**可校验的部分全部通过**：

- `clojure -A:test -M -m harness.test-runner` → 44 tests / 167 assertions，零失败（含离线 HTTP 真起服务）
- HTTP 集成测试：真起服务、真 POST、真读 SSE 体，断言头/CORS/帧序列合法性/
  中文 reasoning 完整往返/`read` 工具真的跑了/JSONL 落地 + `replay/history` 读回同文件
- `test/harness/fixtures/deepseek_sse.txt` 已为真实捕获，`harness.llm-test/parses-a-streaming-body` 对其仍全绿
- 逐帧 schema 校验：`ui/check-frames.mjs` → 94 帧 / 0 违规（`EventSchemas.safeParse`）
- 真 `@ag-ui/client` 驱动：`ui/verify-real.mjs` 一轮含 `read` 工具 + 推理卡片可折叠，二轮同 `threadId` 续写 `RUN_FINISHED` 非 `RUN_ERROR`（等价于深究的“第二句话不 400”——此处 free 模型不强制 `reasoning_content` 回传，但 `loop` 原样 append 与 `ag-ui/inbound` 折返链路已在 `harness.loop-test`/`replay_test` 覆盖，且真机二轮已证不中断）
- 真实模型的端到端（OpenRouter free 代理 DeepSeek 形状）：`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free` 经 `https://openrouter.ai/api/v1`，`.env` 持有 `HARNESS_API_KEY`（`~/.agentmemory/.env` 的 `OPENROUTER_API_KEY` 复用），`config.edn` 已切 `base-url`/`model`，一轮工具调用+二轮续写均 `200` 且 `wire/violations` 为空 → ticket 01 的 5 项已齐
- 从日志 replay 续上 → ticket 02 已合入 `37ff979`，`dev/harness/replay.clj` + 3 seam 测试全绿

**尚未验证**：

- **agent 改自己的内核** → ticket 03，也就是这个项目的原始命题
