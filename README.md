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
├── harness.edn       用户级 harness 配置（可选；编辑模式、围栏的 allow / strict、技能根、指令文件都在这）
├── hooks.edn         hook 声明（可选；不存在 = 这个点没人监听）
├── .env              HARNESS_API_KEY（优先于真实环境变量）
├── harness.db        sqlite：项目 / 会话归属 / 归档 / **文件锚点**（home 的元数据层）
└── projects/<项目>/*.jsonl   会话日志，按项目分目录
```

**这不是唯一的 floor。** 技能与指令读的是**宿主自己的约定位置**——`~/AGENTS.md` 与 `~/.agents/skills/`——
它们在 **OS 家目录**下，**不跟随 `CLJ_HARNESS_HOME`**：搬家搬的是 harness 的配置，不是这台机器的家目录。
把配置家目录挪到别处不该让另一批技能凭空消失（见「技能与指令」）。

**库装状态，文件装记录。** `harness.db` 里只有会被**改写**的东西：项目、会话归属、归档标记，以及按
锚点编辑的行锚点（`hashline_snapshots` / `hashline_ownership` / `hashline_sessions` 三张表，外加
`hashline_undo` 存最近一次撤销）。**锚点不在某个目录里**，它与项目、会话共用同一个库、同一条迁移链。
日志与配置都不进库——**库里没有消息表**，也没有日志的全文索引或大小镜像，那些读的时候现问文件。判别
标准是「能不能被改写」，不是「改得勤不勤」：

- `config.edn` / `providers.edn` / `harness.edn` / `hooks.edn` **仍是文件、仍是现读**，改完不用重启
  （「设置」那一版生效配置每次打开都重读，就是这条纪律看得见的地方）。
- **旧的 `~/.clj-harness/logs/` 不导入、也不迁移**：那个平铺目录下的会话在本产品里一律不可见（文件名
  不含项目身份，自动归属只能猜）。字节一个都不动，要接着用就手动挪进 `projects/<workspace>/`。

想换位置就设 `CLJ_HARNESS_HOME`——这是唯一的旋钮，测试也用它把读写隔离到临时目录：

```pwsh
$env:CLJ_HARNESS_HOME = "D:\harness-config"
clojure -M:run
```

首次使用先建目录并放三份配置（第四份可选）：

```pwsh
New-Item -ItemType Directory -Force ~/.clj-harness
Copy-Item config.edn.example ~/.clj-harness/config.edn
Copy-Item providers.edn.example ~/.clj-harness/providers.edn
Copy-Item harness.edn.example ~/.clj-harness/harness.edn   # 可选：不复制就是全默认（含按锚点编辑）
Copy-Item .env.example ~/.clj-harness/.env
# 编辑 ~/.clj-harness/.env 填入 HARNESS_API_KEY
```

`harness.edn.example` 把 `:editing` 与 `:approval` 的每个键都写在**它的默认值**上并逐条注释，所以它同时
是参考手册——只想改一个旋钮就照抄那一行（`:editing` 是**逐键**合成的，见下）。

**`prompt.md` 是唯一的例外**：它留在仓库里，不进家目录——那是被 review 的代码资产，每次改动都需要
git 历史。它首调读入即**冻结**（provider 前缀缓存的前提），热改要 `(harness.kernel.llm/reset-prompt!)` 或重启。
**冻结的是它这份文本，不是整条 system 消息**——见下面「system 消息：冻结的开头 + hook 追加的文本」。

**缺失与损坏是两回事**：`config.edn` 缺失会**指名绝对路径**报错（不静默用默认值）；
`providers.edn` / `hooks.edn` / `harness.edn` / `.env` 可以不存在——前者 = 那个配置什么都没说，
`.env` 不在则 key 落回真实环境变量 `HARNESS_API_KEY`。而**存在却写坏**（EDN 语法坏 / 不是 map /
键拼错）一律指名绝对路径硬失败：一份被静默忽略的配置，与一份什么都没说的配置，从外部看没有区别。

### system 消息：冻结的开头 + hook 追加的文本

**一条 system 消息，开头冻结，其余现算。** `prompt.md` 只留**与任何会话无关的话**——身份、secrets
纪律、「其余自己读」——那是**承诺**。**本会话的事实**由 `SystemPrompt` 点上的 hook 在每次 run
组装时追加：

```
[system  prompt.md 的冻结开头                    ]
[追加    <tools>…</tools>       ] ← 内建（内核注册的行）
[追加    <project>…</project>   ] ← 内建
[追加    <provider>…</provider> ] ← 内建
[追加    作者写什么就是什么      ] ← hooks.edn 里 / 本会话声明的 hook，按声明顺序
[user    开场块：AGENTS.md / 技能清单 / 技能正文 —— 一字不动]
```

内建三块报的都是**活的事实**：本会话**实际被服务**的工具集合（含会话注册的、以及以后 MCP 桥进来的，
并另起一行说明哪些被本会话关掉了——**关闭不是隐藏**）、绑在哪个目录（绝对路径 + 围栏的自由路径集合，
含 `:approval {:strict true}` 与 `:approval {:allow ..}` 两种变化；未绑定就明说没绑定）、
生效的 vendor / model / 思考档（**永不含 api-key**；答不出就一个块都不出）。

**事实不动则文本逐字节不动**，所以 provider 的前缀缓存照旧命中；事实动了（换目录、关掉一条 hook、
改 provider 档）就付**一次冷前缀**——宁可冷一次，也不让 system 消息说一件已经不成立的事。
这也是为什么 `prompt.md` 里那句工具枚举退场了：它从写下的那一刻就在过时，而 `<tools>` 不会。

**客户端一个字节都收不到这些追加的文本**：它不进任何 AG-UI 帧，只进 jsonl 的 `message` 行（还有一行
`hook/SystemPrompt` 审计行记录这次组装匹配了几条）。

### 技能与指令（一场会话开场拿到什么）

会话开场时，模型除了冻结的 system 消息开头，还会拿到两样东西，**都从约定目录现读**：

| | 默认位置 | 变成什么 |
| --- | --- | --- |
| 指令 | `<OS 家目录>/AGENTS.md`；绑定时再加 `<项目>/AGENTS.md` | 每个文件**一条 user 消息**，`<instructions path="…">…</instructions>` 包裹 |
| 技能清单 | `<OS 家目录>/.agents/skills/*/SKILL.md`；绑定时再加 `<项目>/.agents/skills/*/SKILL.md` | **一条 user 消息**，`<skills>` 包裹，每技能一行 |
| 技能正文 | 同上 | **加载**后出现：模型调 `skill`，或**人打 `/name `**；`<skill name="…">` 包裹的 **user 消息**，紧跟「要求加载」的那条消息之后 |

**前端一个字都不出现**：这些消息**从不产生任何 AG-UI 帧**，客户端永远收不到它们——界面上只有一张普通的
`skill` 工具卡；人打 `/name ` 加载时连那张卡都没有，因为那条消息本来就是他自己打的字。它们照旧写进 jsonl
的 `message` 行（模型看到了什么，日志就有什么）。

**「只加载，不创作」也说清了两种加载**：模型调 `skill`，或人在输入框里打 `/name `。打错了不是失败——
注入位换成一句点名说明（收到什么名字、能加载哪些）。**`disable-model-invocation: true` 的技能只有人能加载**：
文件说这条不该由模型决定，所以清单里没有、工具也拒绝，而人打 `/name` 就是人在决定。

两个键都写在 `harness.edn`（用户级 `~/.clj-harness/harness.edn`，项目级 `<项目>/.harness/harness.edn`）：

```edn
{:instructions {:files ["AGENTS.md"]}          ; 只要项目那一份
 :skills       {:roots ["/abs/skills" ".agents/skills"]}}
```

两键都**整表替换**默认值（不是追加），所以「只要项目那份」是写 `["AGENTS.md"]` 而不是别的；
相对路径按工具路径的规矩解析（相对项目根）。改配置不需要重启——每次调用现读。

**缺文件 / 空文件不注入那一条**，属于日常（多数项目没有 AGENTS.md，占位文件什么都没说）。**反过来，
AGENTS.md 在但读不出来（权限 / 非 UTF-8）是点名失败，run 不开始**：它是对这场会话的显式配置，与
`config.edn` 同一族，静默跳过等于按没人写过的规矩跑。技能里坏掉一条则是**诊断而不是失败**——那份技能
不进清单，但自省里看得见原因、被调用时得到同一句话；菜单上坏掉一项不该拖垮一场会话。

只加载，不创作：不写技能、不装技能、不做授权。**内部怎么转、为什么这样设计、代价是什么**，见
[`docs/architecture/skills-and-instructions.md`](docs/architecture/skills-and-instructions.md)。

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

**想知道此刻实际在用什么**：侧边栏底部「设置」打开一版**只读**的生效配置——三个旋钮各是**哪一档**
选的、家目录的绝对路径是哪条规则给的、home 里哪几份文件在、有没有 api-key（**只有有没有与来源，
值永不出现在响应里**）。它每次调用都重读配置文件，所以改完 `config.edn` 按「Re-read」就是新值，
不用重启。

**形状与校验的细节**（哪些键必需、哪些值会指名报错、两个数字为什么是「报告用」不是「执行用」、
旧扁平形状为什么不读不迁移）见 [`docs/architecture/providers.md`](docs/architecture/providers.md)。

### hook 与项目级配置（可选）

一个 hook 是某个 hook 点上的一行声明，**说它跑什么、且只说一样**：`:command`（非空字符串，经 shell
spawn）或 `:run`（可调用的函数，在进程内跑）。契约是：payload 走 stdin JSON（`:run` 拿到同一批键的
map，值保类型），**退出码 0 放行 / 2 阻断（stderr 回喂模型）**，超时与崩溃都不炸 run。

**声明有三个来源**，先后由来源档位定：内核自己注册的（`:built-in`，最前）→ 配置家 / 项目的 `hooks.edn`
（`:config`，按文件顺序）→ 本会话 `eval` 加的（`:session`，按加入顺序）。**`:run` 只有后两个来源能写，
`hooks.edn` 里写它被指名拒绝**——文件里放不了函数。

**`SystemPrompt` 点上 stdout 是内容本身**：匹配到的声明**全部跑、全部追加**（不是「第一条获胜」），
文本原样进 system 消息的追加部分。这一点的退出 2 表示**这次 run 不开始**，而不是少一段规矩。

**不声明任何 hook 时整条路径是 no-op**——内建的那三条行是唯一常驻的，把它们也关掉，那一点就一行都不写。

绑定到一个项目目录后，项目可以带自己的 `.harness/`（`harness.edn` / `hooks.edn`），
项目级**整键/逐点替换**用户级。两者的完整语义见
[`docs/architecture/hooks.md`](docs/architecture/hooks.md) 与
[`docs/architecture/projects.md`](docs/architecture/projects.md)。

### 文件编辑的两种实现（`harness.edn` 的 `:editing`）

`read` / `replace` / `insert` / `anchor_grep` / `undo_last_replace`（**默认**）与 `read` / `edit`（原版）
是两套编辑实现，一次只会有一套装在本会话的工具表里。切换就是 `harness.edn` 里一行：

```clojure
{:editing {:mode :hashline}}    ; 默认：按锚点编辑
{:editing {:mode :str-replace}} ; 原版：edit 按 old_string 替换
```

两种模式都是一等公民，各有完整用例；`edit` 的行为一个字没变，只是不再默认在场。`:editing` 是
`harness.edn` 里**唯一逐键合成**的块（其余顶层键是整键替换），所以项目级只写 `{:auto-read false}`
不会把用户级的 `:mode` 一起抹掉；全部七个键与它们的默认值都在 `harness.edn.example` 里。自省：
`(harness.cap.editing/editing-mode harness.kernel.tools/*thread-id*)`。

两套各自是什么、为什么默认换了、锚点存在哪，见
[`docs/architecture/kernel.md`](docs/architecture/kernel.md) 与
[`docs/architecture/home-and-storage.md`](docs/architecture/home-and-storage.md)。

## 启动

### 1) 后端 :8080

```pwsh
clojure -M:run          # 项目根
# 或 clojure -A:test -M -m harness.edge.http
# 期望：harness listening on http://localhost:8080 -- POST an AG-UI RunAgentInput here; stop with (stop!)
# REPL 形态：clojure '-J-Dfile.encoding=UTF-8' -M:repl
```

### 2) 前端 :5173

侧边栏是三个段位：「新建任务」与「设置」钉在上下，**中间的项目区是唯一滚动的东西**（窗口拉高拉矮
都不出现整页滚动）。一个项目 = 一个目录，显示成它最后一个文件夹名；悬停出「更多」，里面有
**移除项目**——那只是解绑，`projects/` 下的日志一个字节不动，重新添加同一目录会话就都回来。
项目下面是它的会话，每行可以**归档**（归档 = 行上的一个布尔，日志同样不动，进「已归档」分组）。

**新建任务必须先有项目**：一个项目都没有时，它说的是「先添加一个项目」并给出入口。会话的记录落在
`~/.clj-harness/projects/<workspace>/<threadId>.jsonl`。

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
# 607 tests / 9774 assertions，全绿，exit 0（基线随分支变，报数时带上分支与提交）
# 断言数被锚点表的 rank/select 往返与去重用例拉高（各自数千条），不是用例变多了

# UI（TypeScript）：端到端全量。自带后端，不需要 8080、不需要 api-key、不需要模型
cd ui && npm test
# 11 tests，含 4 组：帧 schema / 真 @ag-ui/client 驱动 / 二轮续写 / 审批 park→approve→veto
```

UI 套件驱动**真后端**（真 HTTP、真 `@ag-ui/client`），只是 provider 是脚本替身；
测什么由**脚本文件**决定，生产 HTTP 边因此一个测试专用路由都不长。细节见
[`docs/architecture/client.md`](docs/architecture/client.md)。
