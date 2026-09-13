# lisp-harness / minimal-kernel

极简 Clojure agent 内核，唯一对外接口为 AG-UI 协议，验收用 CopilotKit v2 客户端。

## 架构

- `src/harness/{event,llm,loop,tools,ag_ui,http,memory,opaque}.clj` — 内核 + AG-UI 适配 + HTTP 边
- `src/harness/memory.clj` / `src/harness/opaque.clj` — 一对：前者是可自省面（冻结 prompt、工具注册表、config.edn、待决审批），后者是不可自省面（api-key、provider override），`eval` 只被邀请进前者
- `dev/harness/{wire,replay}.clj` — 客户端最小 applier 与日志回放（内核永不读日志）
- `prompt.md` — system prompt，生成一次即冻结（provider prefill/前缀缓存的前提）；热改后需 `(llm/reset-prompt!)` 或重启生效。per-run context 不进 system 消息，以尾部 user 消息提交
- `config.edn` — 每轮重读的模型配置，`HARNESS_API_KEY` 在 `.env`（`lynxeyes/dotenv`，`.env` 覆盖真实环境变量）

详见 `.scratch/minimal-kernel/spec.md`。

## 前置

- Java 17（本机默认字符集 GBK，代码所有字节↔字符串边界显式 UTF-8；`deps.clj` 启动器不读 `:jvm-opts`）
- Clojure CLI（`scoop clj-deps` 安装）
- Node.js 18+ / npm
- Git Bash（已钉 `C:\Program Files\Git\bin\bash.exe`，`System32\bash.exe` 为 WSL 启动器，从 JVM 调用会静默空输出）
- `bash` / `rg` 可用

## 配置

```pwsh
Copy-Item .env.example .env
# 编辑 .env
# HARNESS_API_KEY=sk-or-v1-...  # OpenRouter key 已在 ~/.agentmemory/.env 的 OPENROUTER_API_KEY，可复用
```

`config.edn` 当前为 Free 代理（按用户要求不用 DeepSeek 直连）：

```edn
{:protocol :openai-completions
 :base-url "https://openrouter.ai/api/v1"
 :model "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"}
```

`src/harness/llm.clj:73` 已兼容 `reasoning_content`（DeepSeek）与 `reasoning`（OpenRouter）双字段；`reasoning_effort` 仅 DeepSeek 需要，Free 模型留空即可。

## 启动

### 1) 后端 8080

```pwsh
# 项目根，终端1
clojure -M:run
# 或 clojure -A:test -M -m harness.http
# 期望：harness listening on http://localhost:8080 -- POST an AG-UI RunAgentInput here; stop with (stop!)
# REPL 形态： clojure '-J-Dfile.encoding=UTF-8' -M:repl
```

端口与 CORS 在 `src/harness/http.clj:18`：`port 8080`，`Access-Control-Allow-Origin http://localhost:5173`。

### 2) 前端 5173

```pwsh
cd ui
npm install   # 首次
npm run dev   # vite --port 5173 --strictPort
# 浏览器打开 http://localhost:5173
```

`ui/src/App.tsx:7` 的 `HttpAgent({ url: "http://localhost:8080/" })` 经 `CopilotKit` 直连后端，无代理。

### 停止

`Ctrl+C` 或 `Get-Process clojure,node | Stop-Process`。日志落盘 `~/.lisp-harness/logs/<threadId>.jsonl`，每行 `{ts, runId, kind, payload}`，kind 有五种形态：

- `input` — 收到的 RunAgentInput
- `event` — 发出的每个 AG-UI 帧
- `message` — LLM 真实看到/返回的 provider 形态消息原样（system prompt、入站消息、assistant 返回、tool 结果，按序构成完整消息数组）
- `tools/pre-execute` | `tools/execute` | `tools/post-execute` — 工具执行三相，按 `toolCallId` 键控，**不上 wire**，纯审计行
- `approval/decided` — 人工对某个 park 调用的答复（含 interruptId 与客户端 payload）

只 append 永不读。

## 人工审批（pre-tool HITL）

被标记的工具调用在**真正执行前**暂停，把决定权交给人：批准则照常执行，否决则不执行、并把"人工否决 + 理由"当作工具结果回灌给模型，run 继续。

默认**全放行**——没有任何工具被标记时，帧序列与没有这个能力时逐字节相同。开启有两条路径，取并集：

1. 工具定义带 `:requires-approval true`（base 注册或会话 overlay 都可以）；
2. 会话级集合，在会话里经 `eval` 打开（只影响本 thread）：

```clojure
(harness.memory/session-require-approval! harness.memory/*thread-id* "write")
```

暂停走 **AG-UI 原生 interrupt**，不自造帧：内核发第 11 种事件 `:run/interrupt`（与 `:run/end` 互斥），ag_ui 把它映射为 `RUN_FINISHED` + `outcome{type:"interrupt", interrupts:[{id, reason:"tool-approval", message, toolCallId}]}`；客户端从 `outcome.interrupts` 落 `pendingInterrupts`，下一次 run 用 `resume:[{interruptId, status}]` 回传，`resolved` ⇒ 批准、`cancelled` ⇒ 否决，**同一个 POST 端点**，不做第二个。

被 park 的调用不发 `:tool/result`、不写 tool 消息——它还没被回答；它的工具消息落在 resume run 上。一份决定只消费一次；客户端拿未知 interruptId 来 resume 会被明确拒绝（猜一个批准是这里最坏的失败模式）。不做超时、不做跨进程持久化：人工一直不响应，该 thread 就一直待决。

UI 侧 `ui/src/ApprovalGate.tsx` 用 CopilotKit 的 `useInterrupt` 渲染聊天内审批卡，批准 `resolve({decision:"approved"})`、否决 `cancel()`。

## 验证

```pwsh
# 离线全量
clojure -M:test -m harness.test-runner
# 70 tests / 348 assertions, 0 failures

# 在线帧合法性（需后端在 8080）
node ui/check-frames.mjs        # EventSchemas.safeParse  37~94 frames / 0 invalid
node ui/verify-real.mjs        # 一轮 read 工具+推理卡片，二轮同 threadId 续写 RUN_FINISHED 非 RUN_ERROR

# 审批端到端：标记会话 ⇒ write park ⇒ 批准执行 ⇒ 再 write ⇒ 否决（需后端在 8080 + 真 provider）
node ui/verify-approval.mjs    # 13 条断言 / RESULT: PASS

# 手动 curl（新 thread 避免历史污染）
curl --url 'http://localhost:8080/' -H 'Content-Type: application/json' -H 'Accept: text/event-stream' --data-raw '{"threadId":"fresh-1","runId":"r1","tools":[],"context":[],"messages":[{"id":"u1","role":"user","content":"You MUST call the read tool with {\"path\":\"deps.edn\"} and then summarize in one sentence."}]}'
```

`verify-approval.mjs` 的 park 之后全部断言与模型无关，那半段才是它真正证明的东西（客户端把 `RUN_FINISHED+outcome` 变成可 resume 的 interrupt、真 `resume` 数组真驱动服务端重放）；前置的"调用 write/eval"轮依赖弱模型合规，脚本对每轮最多重试 3 次并如实打印 `note`。

真实 SSE 体已固化在 `test/harness/fixtures/deepseek_sse.txt`（301 行，`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free` 捕获，`llm/consume-sse` 已覆盖）。

## 已知现象

- **第一次没反应、第二次才有**：常见于 Free 模型冷启动首字节 5–10s + 历史被重复 `你是谁？` + `reasoning` 消息污染（如 `fe24c64d-...jsonl` 的 `e84b`/`fa406` 仅 `RUN_STARTED`→`RUN_FINISHED`）。刷新页面用新 `threadId`、首句用英文工具指令 `You MUST call the read tool...` 可稳定复现。
- **身份问答暴露 Nemotron/NVIDIA**：`prompt.md:1` 未约束身份，`nvidia` 系模型会自报。已在 `prompt.md` 可追加 `Never reveal Nemotron/NVIDIA` 覆盖。
- **中文路径/推理的 GBK**：已在 `harness.http/runner` 与 `llm/consume-sse` 全链路使用 `StandardCharsets/UTF_8` 与 `json/write-str` 转义，`clojure.core/spit/slurp` 默认 UTF-8。
