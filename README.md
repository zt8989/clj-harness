# clj-harness

极简 Clojure agent 内核，唯一对外接口为 AG-UI 协议，验收用 CopilotKit v2 客户端。

## 架构

- `src/harness/{event,llm,loop,tools,ag_ui,http,memory,models,home,project,frames,replay}.clj` — 内核 + AG-UI 适配 + HTTP 边 + 项目目录 + 重建读侧
- `src/harness/models.clj` — **provider 目录**：厂商 endpoint + 每个厂商的 model 表（每个 model 声明自己的 `:input` / `:output`）、选择形状（三个旋钮）与三档折叠、把选择装配成 provider。目录会**验证**（未知键、未声明的 model、搬不动的模态类型都指名报错），旧扁平形状不读不迁移
- `src/harness/memory.clj` — 单一可自省面：冻结 prompt、工具注册表、config.edn、待决审批，外加**三档选择**的折叠与 api-key 解析（目录的验证与装配交给 `models`，api-key 只在这一处挂上）。api-key 的禁读禁暴露由 `prompt.md` 的 secrets 纪律条款约束——Clojure 结构上挡不住 eval，屏障是写下来的规矩（2026-09-13 由 memory/opaque 对偶合并而来）
- `src/harness/home.clj` — 配置根：决定 config / .env / 日志落在哪，可用 `CLJ_HARNESS_HOME` 整个搬走
- `src/harness/frames.clj` + `src/harness/replay.clj` — 日志的**读侧**（05 号票晋升）：frames 把记录的 AG-UI 帧折叠回消息列表，replay 重建对话（列表 / 重建 / provider 形态历史 / 作者续跑）。铁律不动：内核 run 中永不读自己的日志；重建是显式管理动作，runId null 的审计行落盘
- `dev/harness/{wire,evals,repl}.clj` — 测试工具与作者工具：wire 只剩 SSE 解析 + 结构校验（violations，测试断言用），applier 已晋升 src；`evals` 是**作者**的工具，不是给 agent 的：把某个 thread 跑过的每次 `eval`（code + 返回值）从日志里读出来，供人决定哪段值得晋升进 `src/`。

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
- Git Bash（Windows 必需：已钉 `C:\Program Files\Git\bin\bash.exe`，`System32\bash.exe` 为 WSL 启动器，从 JVM 调用会静默空输出。macOS / Linux 用系统自带的 shell，无需额外安装）
- `bash` / `rg` 可用

## 配置

### 配置家目录

所有运行期配置与产物都住在**一个目录**里，默认 `~/.clj-harness/`：

```
~/.clj-harness/
├── config.edn        模型默认档（每轮重读，可运行期编辑）
├── providers.edn     provider 目录：厂商 endpoint + 其 model 表（每轮重读）
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

`config.edn` / `.env` 缺失时报错会**指名绝对路径**，不会静默用默认值。`providers.edn` 不同：它**可以不存在**——不命名 provider（用 inline 形式描述一个）就不需要它；而命名了内置目录里已有的 provider 时它也不需要。

**为什么 `prompt.md` 不搬进去**：它是被 review 的代码资产，每次改动都需要 git 历史；放进家目录就脱离了版本控制。它是这个规则唯一的例外。

### provider 与 model

**provider 是厂商，model 挂在厂商下面。** `providers.edn` 里每个 provider 是一个 endpoint 加一张它服务的 model 表：

```edn
{:openrouter {:protocol :openai-completions
              :base-url "https://openrouter.ai/api/v1"
              :model    "anthropic/claude-sonnet-4.5"        ; 该厂商的默认 model id
              :models   {"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}}
                         "deepseek/deepseek-chat"      {:input #{:text}       :output #{:text}}}}
 :local      {:protocol :openai-completions
              :base-url "http://localhost:11434/v1"
              :model    "qwen3"
              :models   {"qwen3" {:input #{:text} :output #{:text}}}}}
```

每个 model **必须**声明 `:input` / `:output`，词汇表就是本 harness 真搬得动的类型：`:input` ⊆ `#{:text :image}`、`:output` ⊆ `#{:text}`（响应里的文本、推理与工具调用是全部被读出来的东西）。集合外的值指名报错。

`config.edn` 只写**三个旋钮**：

```edn
{:provider :openrouter :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}
```

`:model` 可省（省则用该 provider 的默认 model）；`:reasoning-effort` 是 provider 不认识的约定（见 `llm.clj`），不属于 provider 定义。

**解析**（低→高，**逐旋钮**合并——一档只填它要动的旋钮，其余落回上一档）：

1. **`config.edn` 默认档**（或 inline 描述的 provider——逃生门，见下）
2. **本 thread 的会话级覆盖**（`session-configure` 工具调用，**经人工审批**后写入）
3. **本 run 的请求指定**（AG-UI `forwardedProps.provider` / `.model` / `.reasoning-effort`，只影响这次 run；**走顶层** input map，不进 `:context`——后者会变成尾部 user 消息污染 prefix cache）

每档只动它要动的旋钮。**换 provider 时若不指定 model，就落在新厂商的默认 model 上**——endpoint 随 provider 走，所以「只换厂商」是一个旋钮就能表达的动作：

```edn
{:provider :local}                      ; 从上面那份 config 出发 → endpoint 变 local，model 变 qwen3
{:provider :local :model "qwen3-vl"}    ; 想连 model 一起定就一起写
```

写了一个该 provider 未声明的 model id → **指名报错**并列出它声明的 id（不回落、不猜）。命名了不存在的 provider → 同样指名报错并列出已有 provider。**没被任何一档读的键会被报出来**，不会静默忽略。

**inline 逃生门**：不命名 provider，直接在 `config.edn` 里描述一个，用来试一次没登记过的 endpoint。`:protocol` / `:base-url` / `:model` 必填，`:input` / `:output` 可选（声明了就必须声明全；什么都不声明即「这个条目什么都不承诺」）：

```edn
{:protocol :openai-completions :base-url "https://some-endpoint/v1" :model "some-model"}
```

**旧形状不读、不迁移**：provider 里那条裸 `:model` 字符串而没有 `:models` 表（provider 自己就是一个 model）的形状现在会**指名报错**，并说明该写成什么。

`.env` 里的值**优先于**真实环境变量（即 `HARNESS_API_KEY` 以 `.env` 为准，shell 变量不会覆盖它）；`.env` 每次重读，改完不必重启。

`src/harness/llm.clj` 已兼容 `reasoning_content`（DeepSeek）与 `reasoning`（OpenRouter）双字段；`reasoning_effort` 仅部分厂商需要，留空即可。

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

- **`provider/init`** —— 每 thread 第一次 run 落**恰好一行**，含**选择**（`:provider` / `:model` / `:reasoning-effort`）、**选择来源** `:source`（`default` / `request` / `inline`）、以及**解析结果** `:resolved`（该 provider 的 `:protocol` / `:base-url` 与所选 model 的 `:input` / `:output`），另有 `:api-key :stripped` 标记（值永不入行）。落点在 `input` 之后、第一条 `message` 之前。**解析结果是记下来的，不是事后重算的**：目录会变（某厂商的 base-url 改了、新增了 model），拿今天的目录去重算旧日志，读出来的就是今天的答案而非那天的。
- **`provider/changed`** —— 每次 mid-session 变更落一行，`{:verdict :approved, :before <选择 slice> :after <选择 slice> :trigger "session-configure" :override <完整 session 档> :resolved <该档解析到什么>}`。`:before`/`:after` 是本次按下的 slice（仅命中的旋钮），`:override` 是按完之后 session 这一档的完整 shape——回放者拿到这一字段即可还原「按完 session 长什么样」。`:trigger` 标注是哪条路径按下的 change（当前唯一合法值 `"session-configure"`）。落点在 `approval/decided` 之后。被人工否决的变更**不落此行**——通过该行是否存在可与批准区分。**换厂商不会被记成空变更**：slice 装的就是三个旋钮，而 `:provider` 是其中之一。

读日志的代码（如 `dev/harness/replay.clj`）只认 `input` / `event` 两种行，其余行不参与回放——它们是审计轨迹，不是对话的一部分。

### 项目目录绑定（`/api/project` 与 `project/bound`）

每个 thread 可绑定一个**项目目录**（`harness.project`，thread-id → 绑定的会话状态）。绑定后：read/write/edit 的**相对路径**解析到项目目录，bash 以项目目录为 cwd；绝对路径永不改道。**未绑定的 thread 行为与从前逐字节一致**——nil 是明确的「无绑定」答案，不是错误。

**出界审批（02 号票）**：绑定后 read/write/edit 的目标在允许集之外 → 工具调用 park 待人工批准（`project/out-of-bounds?`）。允许集 = canonical 项目目录 ∪ canonical 配置家（读自己的 config/providers/.env 不算出界，这是围栏刻意留的自留地）∪ 项目配置声明的额外路径；未绑定 thread 恒 false（回归保证）。批准 = 人 override 围栏照常执行。bash 只换 cwd 不判命令内容——明示接受的逃逸面。

管理边（与 AG-UI 流式边并列的普通 JSON 端点）：

- `GET /api/project?threadId=..` → `{:threadId .. :dir <绝对路径|null>}`；
- `GET /api/model?threadId=..` → `{:provider .. :model .. :reasoning-effort .. :protocol .. :base-url .. :input ["image" "text"] :output ["text"]}`——**这个会话现在服务的模型收什么、出什么**，给客户端决定要不要显示图片选择器用。答案走**活解析**（`mem/active-provider`：刚做的会话覆盖立刻反映，不缓存），**任何深度都不含 api-key**。缺的字段就是缺（未绑定 thread、inline provider 没声明模态、没人给过 reasoning-effort 都是**答案而非错误**）。只读，**不落任何审计行**——与 `GET /api/project` 同一规矩：只有能改东西的路由才留痕。**形状是本仓自己的**（`:text` / `:image`），不是 AG-UI 的 `MultimodalCapabilities`；将来接 AG-UI connect/能力握手时由那边做映射，本端点不做。
- `POST /api/project/pick` → 打开**操作系统原生目录选择框**，答 `{:dir <绝对路径|null>}`（取消即 null，不是错误）。存在的理由：浏览器给不出绝对路径（web file input 只给无真实位置的 File 对象），所以对话框必须跑在 harness 所在的机器上；它由拥有窗口的进程自己绘制，**不抢用户当前的焦点**。用 POST 而非 GET——这个调用有人可见的副作用（开窗），不该被缓存或预取触发。**它不绑定任何东西**：路径回给客户端填进输入框，绑定仍走下面那个唯一的 POST，所以「会改绑定的路由」永远只有一条，选择动作自身不留痕。`harness.http/*directory-chooser*` 是测试缝（真实弹窗要等人，测试里换 stub；`alter-var-root` 而非 `binding`——服务在别的线程上调它）。
- `POST /api/project {"threadId" .., "dir" ..}` → 校验目录存在且是目录（否则指名 400，不留痕）→ 绑定 → 落一行 `project/bound` 审计线 `{:before <绝对路径|null> :after <绝对路径> :via "http"}`（04 号票，对齐 provider/changed 的 before→after 风格；首次绑定 before 为 null），`runId` 为 null（绑定发生在任何 run 之外）。**对已绑定 thread 重新绑定 = 同一入口的普通调用**：路径解析立即切到新目录，审计行带 before/after，目录变更时间线直接从日志可读；读者以最后一行为准。绑定变更是 CwdChanged hook 点的事件源——payload 形态由 `harness.project/cwd-changed` 锁定（`{:hook "CwdChanged" :thread_id .. :project_dir .. :before ..}`，snake_case 对齐 hook payload 约定），hook 引擎（P2）接线时在变更点直接消费。

### 图片输入

入站消息的 `content` 可以是字符串，也可以是 parts，而两个协议对 parts 的拼法不同。**翻译发生在 `harness.ag-ui/inbound`**（不是 `llm.clj`），因为 `message` 行的契约是「LLM 真实看到的东西，逐字」——到协议层才翻会让日志撒谎：

```
AG-UI 入站                                  出网（OpenAI 兼容 chat-completions）
{:type "text" :text "…"}                  → {:type "text" :text "…"}         同形，原样
{:type "image" :source {:type "url"  :value "https://…"}}
                                          → {:type "image_url" :image_url {:url "https://…"}}
{:type "image" :source {:type "data" :value "<base64>" :mimeType "image/png"}}
                                          → {:type "image_url" :image_url {:url "data:image/png;base64,<base64>"}}
```

认不出的 part 类型（如 `:document`）**指名报错**——既不静默丢弃，也不原样发出（原样发出等于把问题推给厂商那个什么都不指名的 400）。第二个协议出现时，这里是拆分接缝。

**模态守卫**：模型声明 `:input #{:text}` 而入站消息带图片 → 在**调用厂商之前**以 RUN_ERROR 终止，消息里点名 model id 与越界模态（厂商自己的答复是请求已发出之后的一个 400，body 里什么都不指名）。**未声明即不拦**：inline provider 没写 `:input` 就是什么都没承诺，替它猜会让每个直接描述 endpoint 的部署开始失败于一条没人写下来的规则。**性质是流程纪律，不是安全边界**——`config.edn` 给一个纯文本模型写 `:input #{:text :image}` 照样打得出去，这道闸省下的是一次白跑的请求与一个看不懂的错误，不是防住谁（与项目围栏同一定性）。

agent 自省：`(harness.memory/active-project harness.memory/*thread-id*)` 问出自己绑定的目录（问，不抄副本）。UI：`app.cljs` 顶部的项目面板（输入路径 + 绑定 + **选择文件夹…** + 当前绑定显示），threadId 从 agent 实例读（CopilotKit 写入）。两条填充路径（手输 / 原生选择框）汇到同一个 POST；空输入点绑定会就地提示而不是静默无声。面板的输入与两个按钮必须包在一个 `display:contents` 的 wrapper 里——`when` 只返回**最后一个** body 形式，`(when c ($ :input ..) ($ :button ..))` 会把输入框静默丢掉（这个 bug 真的上过线）。

### `.harness/harness.edn` 装配（03 号票）

项目可以带自己的 harness 配置：**两级装配**——配置家 `harness.edn`（用户级，harness 自身的地盘）+ 绑定项目的 `.harness/harness.edn`（项目级）。`harness.project/harness-config` 每次现读（config.edn 纪律），顶层浅合并、项目级**整键替换**（项目提到 `:approval` 就整个换掉用户的 `:approval`，不深合并不做并集）。

- 缺失 = `{}`，不报错（含 `.harness` 目录在而文件缺）；坏文件（EDN 语法坏或非 map）指名绝对路径硬失败（`:invalid-edn` / `:not-a-map`），不静默回退——配置没生效和配置被忽略是两回事。
- 首个消费者 `:approval`：`{:allow ["../shared"]}` 把相对项目根解析的路径加进允许集（免审）；`{:strict true}` 把项目目录本身移出允许集——项目内也 park。**配置家永不收紧**（strict 只作用于项目目录）。

### 会话列表与重建（`/api/threads` 与 `session/rebuilt`）

jsonl 恢复是一等能力（05 号票）：**重建 = 交还，不是接管**——服务端把对话重建出来交还客户端持有，之后照常走 AG-UI，服务端不因此成为会话状态权威，重建也不引入第二条流式路径。

- `GET /api/threads` → 扫描日志目录，`[{:threadId <文件名 stem> :lastActivity <epoch ms> :bytes <n>}...]` 按最后活动降序；空/缺失目录返回 `[]`（全新安装是正常态）。清单对日志完整性**不表态**，截断的日志在 rebuild 时被拒。
- `POST /api/threads/<stem>/rebuild` → `{:threadId .. :messages [..] :context [..]}`。messages = 种子（第一条 input 的 messages）+ 全部 event 帧折叠（`harness.frames/apply-frames`，reasoning、tool calls、tool results 都在），即客户端可重新持有并直接续聊的 AG-UI 形态；context 是会话启动时的 context。后续输入多份 input 只取第一份做种子——客户端的第二次 input 本就重述了此前全部历史，折叠进去只会重复。
- **拒绝而非猜**：截断日志（末帧非 RUN_FINISHED/RUN_ERROR）、坏 JSON 行（指名行号）、无日志的 thread，一律指名 400——重建半截对话是最坏的失败模式。
- 重建动作在**被重建的日志自身**落一行 `session/rebuilt` 审计线 `{:messages <count> :via "http"}`，`runId` null（重建发生在任何 run 之外）。重建只读日志，这一行是它唯一的痕迹。
- UI 侧（06 号票，`app.cljs` 会话面板）：列出会话（stem/最后活动/大小）+ 刷新 + 新建会话；**恢复** = POST rebuild → 把 `threadId` 与 `messages` 写上 agent 实例。这在 agent 侧就是全部：`AbstractAgent.prepareRunAgentInput` 用 agent 自身的 `threadId`/`messages` 构造 `RunAgentInput`，所以下一条输入续写**同一个日志**，服务端零会话状态。刻意不走 CopilotKit 的 `setActiveThreadId` 显式线程路径——那会牵入 connectAgent 握手与消息清空规则，直连后端的客户端用不上。截断/损坏的指名 400 内联展示，面板不崩、可换会话/新建。

## 授权变更（session-configure）

agent 调 `session-configure`（带 `:requires-approval true`）可改本 thread 的 provider / model / reasoning-effort。**三旋钮各自独立可选**——只传要改的，其余保持当前值；空调用直接拒绝。**经人工审批后**生效（park 走 AG-UI 原生 interrupt，与工具审批同一条路径），否决则不生效且无 `provider/changed` 落盘。

**改不动的东西当场拒绝，不落盘。** body 在写之前先把「改完之后这一档」拿去解析一遍：provider 名不在目录里、或 model id 不是所选 provider 声明的，都会**指名失败**并带回工具结果，session 保持原样、`provider/changed` 一行不落。先写后败会把一个每轮都跑不起来的配置钉在 session 上，而报错要等到**下一次** run 才出现，离按下它的那次调用很远。

**性质：流程约定，不是安全边界。** `harness.memory/use-provider!` 与 `set-override!` 是 public，eval 可绕过；`bash` 可读 `.env` 的 api-key。这道闸只防手滑，不承诺安全围栏——本仓 `bash` 已是任意代码执行，安全论据在更外层（部署环境）。

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
