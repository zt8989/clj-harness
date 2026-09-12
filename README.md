# lisp-harness / minimal-kernel

极简 Clojure agent 内核，唯一对外接口为 AG-UI 协议，验收用 CopilotKit v2 客户端。

## 架构

- `src/harness/{event,llm,loop,tools,ag_ui,http}.clj` — 内核 + AG-UI 适配 + HTTP 边
- `dev/harness/{wire,replay}.clj` — 客户端最小 applier 与日志回放（内核永不读日志）
- `prompt.md` — 每轮重读的 system prompt，agent 可用 `write` 热改
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

`Ctrl+C` 或 `Get-Process clojure,node | Stop-Process`。日志落盘 `~/.lisp-harness/logs/<threadId>.jsonl`，每行 `{ts, runId, kind:"input"|"event"|"message", payload}`：`input` 是收到的 RunAgentInput，`event` 是发出的每个 AG-UI 帧，`message` 是提交给 LLM 的 provider 形态消息原样（本次 run 组装出的 system prompt 与入站消息）。只 append 永不读。

## 验证

```pwsh
# 离线全量
clojure -A:test -M -m harness.test-runner
# 44 tests / 167 assertions

# 在线帧合法性（需后端在 8080）
node ui/check-frames.mjs        # EventSchemas.safeParse  37~94 frames / 0 invalid
node ui/verify-real.mjs        # 一轮 read 工具+推理卡片，二轮同 threadId 续写 RUN_FINISHED 非 RUN_ERROR

# 手动 curl（新 thread 避免历史污染）
curl --url 'http://localhost:8080/' -H 'Content-Type: application/json' -H 'Accept: text/event-stream' --data-raw '{"threadId":"fresh-1","runId":"r1","tools":[],"context":[],"messages":[{"id":"u1","role":"user","content":"You MUST call the read tool with {\"path\":\"deps.edn\"} and then summarize in one sentence."}]}'
```

真实 SSE 体已固化在 `test/harness/fixtures/deepseek_sse.txt`（301 行，`nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free` 捕获，`llm/consume-sse` 已覆盖）。

## 已知现象

- **第一次没反应、第二次才有**：常见于 Free 模型冷启动首字节 5–10s + 历史被重复 `你是谁？` + `reasoning` 消息污染（如 `fe24c64d-...jsonl` 的 `e84b`/`fa406` 仅 `RUN_STARTED`→`RUN_FINISHED`）。刷新页面用新 `threadId`、首句用英文工具指令 `You MUST call the read tool...` 可稳定复现。
- **身份问答暴露 Nemotron/NVIDIA**：`prompt.md:1` 未约束身份，`nvidia` 系模型会自报。已在 `prompt.md` 可追加 `Never reveal Nemotron/NVIDIA` 覆盖。
- **中文路径/推理的 GBK**：已在 `harness.http/runner` 与 `llm/consume-sse` 全链路使用 `StandardCharsets/UTF_8` 与 `json/write-str` 转义，`clojure.core/spit/slurp` 默认 UTF-8。
