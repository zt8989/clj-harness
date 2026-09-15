# clj-harness

一个 Clojure 写的 agent 内核，唯一对外接口是 **AG-UI**；前端是 TypeScript + React + assistant-ui，
经 `@ag-ui/client` 直连后端（无中间层、无代理）。会话历史由**客户端持有**，服务端每轮现收现算，
jsonl 只是记录。工具、hook、审批、项目目录、provider 解析都长在这个骨架上。

**本文只讲怎么装、怎么配、怎么起。** 它是什么、内部怎么转、接口有哪些，看——
[`docs/architecture.md`](docs/architecture.md)（现状：模块地图、一次请求的完整路径、状态存在哪、接口清单）。

三处文档的分工写在这里，省得下次又要猜：**README 是入口**，`docs/architecture/` 是**现状**，
`.scratch/<feature>/` 是**历史**（各特征的 spec 与票面，记的是当时的决策，**不作现状读**）。

## 前置

- **Java 17**。本机默认字符集是 GBK，代码所有字节 ↔ 字符串边界显式 UTF-8（`deps.clj` 启动器不读 `:jvm-opts`）
- **Clojure CLI**（`scoop clj-deps` 安装）
- **Node.js 18+ / npm**（前端与 UI 测试）
- **Git Bash**（仅 Windows 必需：已钉 `C:\Program Files\Git\bin\bash.exe`；`System32\bash.exe` 是 WSL
  启动器，从 JVM 调用会静默空输出。macOS / Linux 用系统自带的 shell，无需额外安装）
- `bash` / `rg` 可用

## 配置

### 配置家目录

运行期配置与产物都住在**一个目录**里，默认 `~/.clj-harness/`：

```
~/.clj-harness/
├── config.edn        模型默认档：三个旋钮（每轮重读，可运行期编辑）
├── providers.edn     provider 目录：厂商 endpoint + 它的 model 表（每轮重读，可以不存在）
├── harness.edn       用户级 harness 配置（可选；围栏的 allow / strict 在这）
├── hooks.edn         hook 声明（可选；不存在 = 这个点没人监听）
├── .env              HARNESS_API_KEY（优先于真实环境变量）
├── harness.db        sqlite：项目 / 会话归属 / 归档（home 的元数据层）
└── projects/<项目>/*.jsonl   会话日志，按项目分目录
```

想换位置就设 `CLJ_HARNESS_HOME`——这是唯一的旋钮，测试也用它把读写隔离到临时目录：

```pwsh
$env:CLJ_HARNESS_HOME = "D:\harness-config"
clojure -M:run
```

首次使用先建目录并放三份配置：

```pwsh
New-Item -ItemType Directory -Force ~/.clj-harness
Copy-Item config.edn.example ~/.clj-harness/config.edn
Copy-Item providers.edn.example ~/.clj-harness/providers.edn
Copy-Item .env.example ~/.clj-harness/.env
# 编辑 ~/.clj-harness/.env 填入 HARNESS_API_KEY
```

**`prompt.md` 是唯一的例外**：它留在仓库里，不进家目录——那是被 review 的代码资产，每次改动都需要
git 历史。它首调读入即**冻结**（provider 前缀缓存的前提），热改要 `(harness.llm/reset-prompt!)` 或重启。

**缺失与损坏是两回事**：`config.edn` 缺失会**指名绝对路径**报错（不静默用默认值）；
`providers.edn` / `hooks.edn` / `harness.edn` / `.env` 可以不存在——前者 = 那个配置什么都没说，
`.env` 不在则 key 落回真实环境变量 `HARNESS_API_KEY`。而**存在却写坏**（EDN 语法坏 / 不是 map /
键拼错）一律指名绝对路径硬失败：一份被静默忽略的配置，与一份什么都没说的配置，从外部看没有区别。

### provider 与 model

**provider 是厂商，model 挂在厂商下面。** 会话由**三个旋钮**描述，写在 `config.edn`：

```edn
{:provider :openrouter :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}
```

`:model` 可省（省则用该厂商的默认 model）；`:reasoning-effort` 是 provider 不认识的约定。解析低 → 高、
**逐旋钮**合并：`config.edn` 默认档 → 本会话覆盖（`session-configure` 工具，经人工审批）→ 本次 run 的请求。
**换厂商而不指定 model，就落在新厂商的默认 model 上。**

`providers.edn` 里每个厂商是 endpoint + 一张 model 表：

```edn
{:openrouter {:protocol :openai-completions
              :base-url "https://openrouter.ai/api/v1"
              :model    "anthropic/claude-sonnet-4.5"        ; 该厂商的默认 model id
              :models   {"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}
                                                        :context-window 1000000
                                                        :max-output-tokens 64000}
                         "deepseek/deepseek-v4-pro"    {:input #{:text} :output #{:text}}}}}
```

每个 model **必须**声明 `:input` / `:output`（词汇表就是本 harness 真搬得动的类型：`:input` ⊆
`#{:text :image}`，`:output` ⊆ `#{:text}`）；两个数字可选。**没登记过的 endpoint 走 inline 逃生门**——
不命名 provider，直接描述一个：

```edn
{:protocol :openai-completions :base-url "https://some-endpoint/v1" :model "some-model"}
```

**形状与校验的细节**（哪些键必需、哪些值会指名报错、两个数字为什么是「报告用」不是「执行用」、
旧扁平形状为什么不读不迁移）见 [`docs/architecture/providers.md`](docs/architecture/providers.md)。

### hook 与项目级配置（可选）

`hooks.edn` 声明某个 hook 点上要跑的命令（`hook 点 → [{:matcher :command :timeout}]`），
契约是：payload 走 stdin JSON，**退出码 0 放行 / 2 阻断（stderr 回喂模型）**，超时与崩溃都不炸 run。
**不声明任何 hook 时整条路径是 no-op。**

绑定到一个项目目录后，项目可以带自己的 `.harness/`（`harness.edn` / `hooks.edn`），
项目级**整键/逐点替换**用户级。两者的完整语义见
[`docs/architecture/hooks.md`](docs/architecture/hooks.md) 与
[`docs/architecture/projects.md`](docs/architecture/projects.md)。

## 启动

### 1) 后端 :8080

```pwsh
clojure -M:run          # 项目根
# 或 clojure -A:test -M -m harness.http
# 期望：harness listening on http://localhost:8080 -- POST an AG-UI RunAgentInput here; stop with (stop!)
# REPL 形态：clojure '-J-Dfile.encoding=UTF-8' -M:repl
```

### 2) 前端 :5173

```pwsh
cd ui
npm install      # 首次
npm run dev      # Vite 起在 5173，浏览器打开 http://localhost:5173
npm run build    # tsc --noEmit + vite build → dist/（不需要 Java）
```

**5173 是 CORS 契约不是偏好**：后端只放行 `http://localhost:5173`，`ui/vite.config.js` 里
`server.port: 5173, strictPort: true` 把这句话钉死——换端口不是改一处配置，是同时改两处契约。

### 停止

`Ctrl+C`，或 `Get-Process clojure,node | Stop-Process`。

### 验证

```pwsh
# 内核（Clojure）：离线全量
clojure -M:test -m harness.test-runner
# 276 tests / 1533 assertions，全绿（基线随分支变，报数时带上分支与提交）

# UI（TypeScript）：端到端全量。自带后端，不需要 8080、不需要 api-key、不需要模型
cd ui && npm test
# 11 tests，含 4 组：帧 schema / 真 @ag-ui/client 驱动 / 二轮续写 / 审批 park→approve→veto
```

UI 套件驱动**真后端**（真 HTTP、真 `@ag-ui/client`），只是 provider 是脚本替身；
测什么由**脚本文件**决定，生产 HTTP 边因此一个测试专用路由都不长。细节见
[`docs/architecture/client.md`](docs/architecture/client.md)。
