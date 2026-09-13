# clj-harness

极简 Clojure agent 内核，唯一对外接口为 AG-UI 协议，验收用 CopilotKit v2 客户端。

## 架构

- `src/harness/{event,llm,loop,tools,ag_ui,http,memory,opaque,home}.clj` — 内核 + AG-UI 适配 + HTTP 边
- `src/harness/memory.clj` / `src/harness/opaque.clj` — 一对：前者是可自省面（冻结 prompt、工具注册表、config.edn、待决审批），后者是不可自省面（api-key、provider override），`eval` 只被邀请进前者
- `src/harness/home.clj` — 配置根：决定 config / .env / 日志落在哪，可用 `CLJ_HARNESS_HOME` 整个搬走
- `dev/harness/{wire,replay,evals}.clj` — 客户端最小 applier、日志回放、eval 提取器（内核永不读日志）。`evals` 是**作者**的工具，不是给 agent 的：把某个 thread 跑过的每次 `eval`（code + 返回值）从日志里读出来，供人决定哪段值得晋升进 `src/`。

### 会话级自我扩展与晋升路径

agent 可以在运行期通过 `eval` 给**本会话**长出新的工具（`session-register!`），也可以把已有工具**关掉/打开**（`session-disable!` / `session-enable!`，关闭后工具仍在工具表里、只是调用被拒）。

这些能力**仅本会话生效，进程重启即失**。要把某段值得留的东西固化下来，走人工晋升：读日志 → 判断哪段值得留 → 抄进 `src/harness` 的正式工具表 → git 提交。**绝不自动重放历史 eval**——那等于把日志变成可执行输入，确定性与安全一起崩。

```pwsh
clojure -M:evals <thread-id> [log-dir]   # 列出该 thread 每次 eval 的 code 与返回值
```

写工具表时注意：`eval` 调用的 code 在 assistant message 的 `tool_calls[].function.arguments`（JSON **字符串**，需二次解码取 `:code`）；返回值在对应 `tool_call_id` 的 tool message 的 `:content`。审计三行 `tools/*` **不带 args**，别去那里找 code。
- `ui/src/harness/ui/{main,app,approval_gate}.cljs` — ClojureScript 客户端（helix + React 19 + CopilotKit v2）；`ui/vite-plugin-cljs.mjs` 把 shadow-cljs 编出的 ESM 交给 Vite 打包
- `prompt.md` — system prompt，生成一次即冻结（provider prefill/前缀缓存的前提）；热改后需 `(llm/reset-prompt!)` 或重启生效。per-run context 不进 system 消息，以尾部 user 消息提交。**它留在仓库里**，是唯一一个不进家目录的配置（见下）

详见 `.scratch/minimal-kernel/spec.md`。

## 前置

- Java 17（本机默认字符集 GBK，代码所有字节↔字符串边界显式 UTF-8；`deps.clj` 启动器不读 `:jvm-opts`）
- **OpenJDK 21**（`scoop install openjdk21`）——**只给 `ui/` 构建用**：shadow-cljs 3.x 编译要 Java 21，而全局默认仍是 17。构建时给子进程单独换 PATH，`scoop reset openjdk17` 保证全局不被改：
  ```pwsh
  cd ui
  $env:PATH = "$HOME\scoop\apps\openjdk21\current\bin;$env:PATH"; npm run dev
  ```
- Clojure CLI（`scoop clj-deps` 安装）
- Node.js 18+ / npm
- Git Bash（已钉 `C:\Program Files\Git\bin\bash.exe`，`System32\bash.exe` 为 WSL 启动器，从 JVM 调用会静默空输出）
- `bash` / `rg` 可用

## 配置

### 配置家目录

所有运行期配置与产物都住在**一个目录**里，默认 `~/.clj-harness/`：

```
~/.clj-harness/
├── config.edn        模型默认档（每轮重读，可运行期编辑）
├── providers.edn     具名 provider 注册表（每轮重读）
├── .env              HARNESS_API_KEY
└── logs/*.jsonl      会话日志
```

想换位置就设 `CLJ_HARNESS_HOME`——这是唯一的旋钮，测试也用它把自己的读写隔离到临时目录：

```pwsh
$env:CLJ_HARNESS_HOME = "D:\harness-config"
clojure -M:run
```

首次使用先建目录：

```pwsh
New-Item -ItemType Directory -Force ~/.clj-harness
Copy-Item config.edn.example ~/.clj-harness/config.edn
Copy-Item providers.edn.example ~/.clj-harness/providers.edn
Copy-Item .env.example ~/.clj-harness/.env
# 编辑 ~/.clj-harness/.env 填入 HARNESS_API_KEY
```

`config.edn` / `providers.edn` / `.env` 缺失时报错会**指名绝对路径**，不会静默用默认值。

**为什么 `prompt.md` 不搬进去**：它是被 review 的代码资产，每次改动都需要 git 历史；放进家目录就脱离了版本控制。它是这个规则唯一的例外。

`config.edn` 当前为 Free 代理（按用户要求不用 DeepSeek 直连）——它降级为**默认档**，指向注册表里的 `:cheap`：

```edn
{:provider :cheap :reasoning-effort "low"}
```

具名注册表在 `providers.edn`：

```edn
{:cheap {:protocol :openai-completions
         :base-url "https://openrouter.ai/api/v1"
         :model "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"}
 :smart {:protocol :openai-completions
         :base-url "https://openrouter.ai/api/v1"
         :model "anthropic/claude-sonnet-4.5"
         :reasoning-effort "high"}}
```

**解析优先级**（低→高，后者覆盖前者，**逐字段**合并——一档只填它要改的字段，其余落回上一档）：

1. **`providers.edn` 具名项**（或 `config.edn` 里 `{:provider {...}}` 的 inline map——逃生门）
2. **`config.edn` 默认档的字段覆盖**（如 `{:provider :cheap :reasoning-effort "low"}` 把 `reasoning-effort` 单独调低）
3. **本 thread 的会话级覆盖**（`session-configure` 工具调用，**经人工审批**后写入；详见下）
4. **本 run 的请求指定**（AG-UI `forwardedProps.provider` / `.model` / `.reasoning-effort`，只影响这次 run；**走顶层** input map，不进 `:context`——后者会变成尾部 user 消息污染 prefix cache）

每档**只填它要改的字段**：`{:model "anthropic/claude-sonnet-4.5"}` 只覆盖 model，protocol/base-url/reasoning-effort 仍取上一档。这让三档各管各的，不需要为每种组合造新条目。

`.env` 里的值**优先于**真实环境变量（即 `HARNESS_API_KEY` 以 `.env` 为准，shell 变量不会覆盖它）；`.env` 每次重读，改完不必重启。

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
npm run dev   # 拉起 shadow-cljs watch 并起 Vite 于 5173
# 浏览器打开 http://localhost:5173
```

构建要 Java 21（见"前置"），所以实际命令是：

```pwsh
cd ui
$env:PATH = "$HOME\scoop\apps\openjdk21\current\bin;$env:PATH"; npm run dev
# 生产构建：$env:PATH = "..."; npm run build   → dist/
```

`ui/src/harness/ui/app.cljs` 的 `HttpAgent({ url: "http://localhost:8080/" })` 经 `CopilotKit` 直连后端，无代理。CLJS 改动由 shadow-cljs watch 自动重编译，Vite 感知到 `ui/cljs-out/` 变化即整页重载；首次编译约 25s，dev server 会等它落地再放行第一屏。

### 停止

`Ctrl+C` 或 `Get-Process clojure,node | Stop-Process`。日志落盘 `~/.clj-harness/logs/<threadId>.jsonl`，每行 `{ts, runId, kind, payload}`，kind 有五种形态：

- `input` — 收到的 RunAgentInput
- `event` — 发出的每个 AG-UI 帧
- `message` — LLM 真实看到/返回的 provider 形态消息原样（system prompt、入站消息、assistant 返回、tool 结果，按序构成完整消息数组）
- `tools/pre-execute` | `tools/execute` | `tools/post-execute` — 工具执行三相，按 `toolCallId` 键控，**不上 wire**，纯审计行。pre-execute 的 `outcome` ∈ `pass` / `unknown-tool` / `disabled` / `missing-args` / `needs-approval` / `approved` / `vetoed`；`disabled` 是会话开关（工具仍可见、调用被拒），先于审批检查
- `approval/decided` — 人工对某个 park 调用的答复（含 interruptId 与客户端 payload）

只 append 永不读。

### Provider 时间线（`provider/init` 与 `provider/changed`）

会话的 provider 历史落成两种新行——**不是**每 run 一行快照，时间线 init + changes 已能完整重建：

- **`provider/init`** —— 每 thread 第一次 run 落**恰好一行**，含 `:protocol` / `:base-url` / `:model` / `:reasoning-effort` 四字段 + `:source`（`default` / `request` / `inline`），以及 `:api-key :stripped` 标记（值永不入行）。落点在 `input` 之后、第一条 `message` 之前。
- **`provider/changed`** —— 每次 mid-session 变更落一行，`{:verdict :approved, :before <slice> :after <slice> :trigger "session-configure" :override <完整 session override>}`。`:before`/`:after` 是本次按下的 slice（仅命中的字段），`:override` 是按完之后 session 这一档的完整 shape——回放者拿到这一字段即可还原「按完 session 长什么样」，不必再向 opaque 询问。`:trigger` 标注是哪条路径按下的 change（当前唯一合法值 `"session-configure"`）。落点在 `approval/decided` 之后。被人工否决的变更**不落此行**——通过该行是否存在可与批准区分。

读日志的代码（如 `dev/harness/replay.clj`）只认 `input` / `event` 两种行，两种新行不参与回放——它们是审计轨迹，不是对话的一部分。

## 授权变更（session-configure）

agent 调 `session-configure`（带 `:requires-approval true`）可改本 thread 的 provider / model / reasoning-effort。**三字段各自独立可选**——只传要改的，其余保持当前值；空调用直接拒绝。**经人工审批后**生效（park 走 AG-UI 原生 interrupt，与工具审批同一条路径），否决则不生效且无 `provider/changed` 落盘。

**性质：流程约定，不是安全边界。** `harness.opaque/use-provider!` 与 `set-override!` 是 public，eval 可绕过；`bash` 可读 `.env` 的 api-key。这道闸只防手滑，不承诺安全围栏——本仓 `bash` 已是任意代码执行，安全论据在更外层（部署环境）。

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

UI 侧 `ui/src/harness/ui/approval_gate.cljs` 用 CopilotKit 的 `useInterrupt` 渲染聊天内审批卡（`ApprovalGate` 挂在 `app.cljs` 里 `CopilotChat` 之前）；批准 `resolve({decision:"approved"})`、否决 `cancel()`。卡片自行按 `reason === "tool-approval"` 认领属于它的 interrupt，工具名与参数从客户端自己的 `toolCalls` 里读，不让服务端回显。

## 验证

```pwsh
# 离线全量
clojure -M:test -m harness.test-runner
# 103 tests / 478 assertions, 0 failures

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
