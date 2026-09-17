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
├── config.edn        **唯一一份配置**：:default（三个旋钮的默认档）+ :providers（厂商目录）（每轮重读）
├── harness.edn       用户级 harness 配置（可选；编辑模式、围栏的 allow / strict、技能根、指令文件都在这）
├── hooks.edn         hook 声明（可选；不存在 = 这个点没人监听）
├── mcp.edn           MCP 服务器声明（可选；不存在 = 一个服务器都没声明）
├── .env              密钥：一家厂商一把 `<ID>_API_KEY`（`acme-gateway` → `ACME_GATEWAY_API_KEY`），
│                     全局 `HARNESS_API_KEY` 兜底；优先于真实环境变量
├── harness.infra.db        sqlite：项目 / 会话归属 / 归档 / **文件锚点**（home 的元数据层）
└── projects/<项目>/*.jsonl   会话日志，按项目分目录
```

**这不是唯一的 floor。** 技能与指令读的是**宿主自己的约定位置**——`~/AGENTS.md` 与 `~/.agents/skills/`——
它们在 **OS 家目录**下，**不跟随 `CLJ_HARNESS_HOME`**：搬家搬的是 harness 的配置，不是这台机器的家目录。
把配置家目录挪到别处不该让另一批技能凭空消失（见「技能与指令」）。

**库装状态，文件装记录。** `harness.infra.db` 里只有会被**改写**的东西：项目、会话归属、归档标记，以及按
锚点编辑的行锚点（`hashline_snapshots` / `hashline_ownership` / `hashline_sessions` 三张表，外加
`hashline_undo` 存最近一次撤销）。**锚点不在某个目录里**，它与项目、会话共用同一个库、同一条迁移链。
日志与配置都不进库——**库里没有消息表**，也没有日志的全文索引或大小镜像，那些读的时候现问文件。判别
标准是「能不能被改写」，不是「改得勤不勤」：

- `config.edn` / `harness.edn` / `hooks.edn` / `mcp.edn` **仍是文件、仍是现读**，改完不用重启
  （「设置」那一版生效配置每次打开都重读，就是这条纪律看得见的地方）。
- **旧的 `~/.clj-harness/logs/` 不导入、也不迁移**：那个平铺目录下的会话在本产品里一律不可见（文件名
  不含项目身份，自动归属只能猜）。字节一个都不动，要接着用就手动挪进 `projects/<workspace>/`。

想换位置就设 `CLJ_HARNESS_HOME`——这是唯一的旋钮，测试也用它把读写隔离到临时目录：

```pwsh
$env:CLJ_HARNESS_HOME = "D:\harness-config"
clojure -M:run
```

首次使用只需要一份配置（其余可以不存在）：

```pwsh
New-Item -ItemType Directory -Force ~/.clj-harness
Copy-Item .env.example ~/.clj-harness/.env
# 编辑 ~/.clj-harness/.env 填入 HARNESS_API_KEY（或某家厂商自己的 <ID>_API_KEY）
```

**`config.edn` 不用自己造**：服务第一次在一个家目录里启动时会写下一份带注释的空配置，
之后在「设置」的 General 页里挑一家厂商就成型了（手动编辑当然也可以）。
`config.edn.example` 是那份文件的**注释版**——把 `:default` 的三个旋钮与 `:providers` 里的一家厂商
都写了出来，想要一份带完整说明的起点就照抄它。
`harness.edn.example` 同理，可复制可不复制（不复制就是全默认，含按锚点编辑）。

`harness.edn.example` 把 `:editing` 与 `:approval` 的每个键都写在**它的默认值**上并逐条注释，所以它同时
是参考手册——只想改一个旋钮就照抄那一行（`:editing` 是**逐键**合成的，见下）。

**`prompt.md` 是唯一的例外**：它留在仓库里，不进家目录——那是被 review 的代码资产，每次改动都需要
git 历史。它首调读入即**冻结**（provider 前缀缓存的前提），热改要 `(harness.kernel.llm/reset-prompt!)` 或重启。
**冻结的是它这份文本，不是整条 system 消息**——见下面「system 消息：冻结的开头 + hook 追加的文本」。

**缺失的文件与空的文件是同一件事：什么都没说。** `config.edn` 不存在也算——**服务启动时会替你写一份**
（一段说明两节是什么的注释 + 一个空 map），所以刚装好的家开箱就能用，而且有一份能直接编辑的文件。
`.env` 不在则 key 落回真实环境变量 `HARNESS_API_KEY`；`harness.edn` / `hooks.edn` / `mcp.edn` 同样可以不存在。
而**存在却写坏**（EDN 语法坏 / 不是 map / 顶层冒出第三节 / 键拼错）一律**指名绝对路径硬失败**：
一份被静默忽略的配置，与一份什么都没说的配置，从外部看没有区别。**旧形状会被自动搬过去**：`config.edn` 从前**就是**默认档（三个旋钮、或整个 provider 写在顶层），
服务启动时会把它整体挪进 `:default` 并打印一行（旧的那份留作 `config.edn.bak`），所以已经配好的家
不用手改；手编成旧形状的文件如果没经过启动就读，读侧的句子会告诉你挪到哪儿。

### system 消息：冻结的开头 + hook 追加的文本

**一条 system 消息，开头冻结，其余现算。** `prompt.md` 只留**与任何会话无关的话**——身份、secrets
纪律、「其余自己读」——那是**承诺**。**本会话的事实**由 `SystemPrompt` 点上的 hook 在每次 run
组装时追加：

```
[system  prompt.md 的冻结开头                    ]
[追加    <project>…</project>   ] ← 内建（内核注册的行）
[追加    <env>…</env>           ] ← 内建
[追加    作者写什么就是什么      ] ← hooks.edn 里 / 本会话声明的 hook，按声明顺序
[user    开场块：AGENTS.md / 技能清单 / 技能正文 —— 一字不动]
```

内建两块报的都是**活的事实**：绑在哪个目录（绝对路径 + 围栏的自由路径集合，含
`:approval {:strict true}` 与 `:approval {:allow ..}` 两种变化；未绑定就明说没绑定），
以及**这台机器长什么样**——平台、命令实际交给哪个 shell、这个 shell 里看得见哪些命令行增强工具
（`rg` / `fd` / `jq` / `git`，**有就说有，没有也说没有**：「写一条 `rg` 而机器上根本没有 rg」是这一块
要防的那个错）。机器的事实每进程算一次（探测走的是同一个 shell，不是 JVM 的环境变量），块本身仍每 run 现算。

**事实不动则文本逐字节不动**，所以 provider 的前缀缓存照旧命中；事实动了（换目录、关掉一条 hook）
就付**一次冷前缀**——宁可冷一次，也不让 system 消息说一件已经不成立的事。
（工具名册与生效的 vendor / model 曾经也各占一块，2026-09-16 的复议里退场：名册在 wire 的 `:tools`
数组里自描述，说第二遍是把同一件事说两遍。见 `.scratch/session-context/spec.md`。）

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

**名字不必背**：输入框里打 `/`，弹出一张技能表——名字、它属于**哪一层**（`System` = 机器上的
`~/.agents/skills`，`Project` = 绑定项目里那份）、一句描述；接着打就按名字与描述过滤。选中一行，
输入框里就是 `/名字 `，后面照旧由人接着写（选中**不发送**）。两条与模型那份清单**故意不同**：
`disable-model-invocation` 的技能**在**（人是决定者），**坏掉的技能也在**——带原因、但选不动，
因为「一个静默消失的技能」与「一个从没装过的技能」从外面看没有区别。同名的那份按**前面的根赢**，
所以默认顺序下机器上的那份赢，项目里同名的不会出现。

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
{:default {:provider :openrouter :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}}
```

`:model` 可省（省则用该厂商的默认 model）；`:reasoning-effort` 是 provider 不认识的约定。解析低 → 高、
**逐旋钮**合并：`config.edn` 的 `:default` → 本会话覆盖（`session-configure` 工具，经人工审批）→
本次 run 的请求。**换厂商而不指定 model，就落在新厂商的默认 model 上。**

`config.edn` 的另一节 `:providers` 里，每个厂商是 endpoint + 一张 model 表：

```edn
{:providers
 {:openrouter {:protocol :openai-completions
               :base-url "https://openrouter.ai/api/v1"
               :model    "anthropic/claude-sonnet-4.5"        ; 该厂商的默认 model id
               :models   {"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}
                                                         :context-window 1000000
                                                         :max-output-tokens 64000}
                          "deepseek/deepseek-v4-pro"    {:input #{:text} :output #{:text}}}}}}
```

每个 model **必须**声明 `:input` / `:output`（词汇表就是本 harness 真搬得动的类型：`:input` ⊆
`#{:text :image}`，`:output` ⊆ `#{:text}`）；两个数字可选。**没登记过的 endpoint 走 inline 逃生门**——
不命名 provider，直接描述一个：

```edn
{:protocol :openai-completions :base-url "https://some-endpoint/v1" :model "some-model"}
```

**加一家厂商不必手编文件**：侧边栏底部「设置」打开两页——**General**（在用什么，以及**默认档**那三个
控件：厂商、该厂商的一个 model、思考档）、**Models**（厂商列表与表单：新建 / 改写 / 删掉一条，
填了密钥就写进 `.env` 的一行）。
General 与 Models **会写** `config.edn`：先校验整份新配置再原子落盘，被拒时一个字节都不动，
改写前那份留在 `config.edn.bak`。密钥的值**永不出现在任何响应里**——Models 每一行报的是有没有密钥与派生出来的**凭据名**
（那就是你要编辑的那一行）。面板每次打开都重读文件，所以手改完按「Re-read」
就是新值，不用重启。

**形状与校验的细节**（哪些键必需、哪些值会指名报错、两个数字为什么是「报告用」不是「执行用」、
旧扁平形状为什么不读不迁移）见 [`docs/architecture/providers.md`](docs/architecture/providers.md)。

### MCP 服务器（可选）

`mcp.edn` 声明外部的 MCP 服务器，它们的工具以 `mcp__<server>__<tool>` 出现在这个会话的工具表里——
与内建工具**走同一个执行缝**，所以审批、`PreToolUse` 阻断、会话级关闭、三行审计全都一样：

```edn
{:servers {"workshop" {:command "node" :args ["/abs/path/server.js"]}
           "depot"    {:url "https://example.com/mcp"}}}
```

`{:command ..}`（本机子进程）与 `{:url ..}`（远端 HTTP）二选一。**项目级整表替换用户级的。**
`:env` 的值**永不入日志、永不进端点响应**（与 api-key 同一条纪律）。连不上、崩掉、挂死的服务器
**不拖死任何人**：它的工具缺席、原因被指名，其余服务器照常；下一次用它自己重连。

设置页的 **MCP servers** 一页是它的账本：状态、失败原因、工具清单、以及本会话的开关
（**关闭不是隐藏**——工具仍在表里，调用被拒；进程被收掉，`mcp.edn` 一个字不改）。

细节见 [`docs/architecture/mcp.md`](docs/architecture/mcp.md)。

### hook 与项目级配置（可选）

一个 hook 是某个 hook 点上的一行声明，**说它跑什么、且只说一样**：`:command`（非空字符串，经 shell
spawn）或 `:run`（可调用的函数，在进程内跑）。契约是：payload 走 stdin JSON（`:run` 拿到同一批键的
map，值保类型），**退出码 0 放行 / 2 阻断（stderr 回喂模型）**，超时与崩溃都不炸 run。

**声明有三个来源**，先后由来源档位定：内核自己注册的（`:built-in`，最前）→ 配置家 / 项目的 `hooks.edn`
（`:config`，按文件顺序）→ 本会话 `eval` 加的（`:session`，按加入顺序）。**`:run` 只有后两个来源能写，
`hooks.edn` 里写它被指名拒绝**——文件里放不了函数。

**`SystemPrompt` 点上 stdout 是内容本身**：匹配到的声明**全部跑、全部追加**（不是「第一条获胜」），
文本原样进 system 消息的追加部分。这一点的退出 2 表示**这次 run 不开始**，而不是少一段规矩。

**不声明任何 hook 时整条路径是 no-op**——内建的那两条行是唯一常驻的，把它们也关掉，那一点就一行都不写。

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

### 另外四只手：找文件、记清单、上网（`glob` / `todo_write` / `web_fetch` / `web_search`）

这四个**不属于任何编辑模式**——两种模式都服务它们，因为它们是关于路径、关于这次运行、关于网的，
与「按什么寻址一行」无关：

| 工具 | 一句话 | 为什么是这个形状 |
|---|---|---|
| `glob` | 按**名字**找文件：一列能直接交给 `read` 的绝对路径，按路径排序 | 答案是 `rg` 两次列举的**交集**，因为 `rg --glob` 的优先级**高于** `.gitignore`——直接交给它，`**/*` 会把 `node_modules` 整棵树列出来。交集说的是「在**这个树里**按模式找」 |
| `todo_write` | 记本会话的任务清单：一次送**完整**清单，空数组即清空 | 清单**进库**（`todos`，一行一个会话）：它每次被整份改写，而「能被改写的」正是这个库收状态、不收记录的那条判据。一条消息里只许写一次——两次整份替换之间不存在合并，所以那样的消息两条都**不落盘** |
| `web_fetch` | 取一个 http(s) URL 的**正文**（`<script>`/`<style>` 丢掉、块级标签换行、实体解开） | 它是**有损的文本抽取器，不是渲染器**：JS 渲染的页面会如实回一句「没有可读正文」，而不是假装那一页是空的 |
| `web_search` | 搜索并拿回标题 / URL / 摘要 | 三个厂商：**Brave**（`BRAVE_API_KEY`）、**Exa**（`EXA_API_KEY`）、**Tavily**（`TAVILY_API_KEY`）。键在配置家的 `.env` 或环境里找（与 provider 的键走**同一个** `harness.infra.home/env-value`），**哪个有就用哪个，顺序就是上面这个**。全都没有就**指名拒绝**，别的功能一概不受影响 |

**出网的两个都不带审批——这是决定，不是疏忽。** `bash` 今天就能 `curl` 任何地址且不带审批，
所以给它们挂个 park 是**装样子**：「关闭不是禁止」这句在这里同样成立，一个看起来像护栏、
一步就能绕过去的东西比没有护栏更坏。要这道坎的会话自己装规则：

```clojure
(harness.kernel.tools/session-require-approval! harness.kernel.tools/*thread-id* "web_fetch")
```

### 命令等多久，以及不等人那种跑法（`timeout` / `bash_background` / `bash_output` / `bash_kill`）

`bash` 的调用是**有上限**的：`timeout`（毫秒，**默认 120000**）到点就把命令**连它起的子孙一起**停掉，
把它在此之前打出来的东西原样还回来，末尾补一行 `[timed out after 120000ms — the command was stopped]`。
这不是错误（`[exit N]` 也不是），模型据此换个更窄的做法。

为什么「连子孙」是重点：`bash` 跑的是 `<shell> -lc "…"`，**我们手里那个进程是壳**，而人真正指的是它的
孩子——`npm test` 就是 bash 起 npx、npx 起 node。只杀壳等于留下一个没人认领的进程（本仓真出现过两个
跑了 16 小时的测试 JVM）。同一个收尾函数两个 spawn 都走，所以 `run` 的超时、`start` 的关闭、
后台作业的停止，收的都是整棵树。

**要跑得比一次调用久的东西，用后台**：

| 工具 | 一句话 |
|---|---|
| `bash_background` | 起一条命令，立刻返回一个**句柄**（`j1`）。命令继续跑，输出**不在**这个答案里 |
| `bash_output` | 读**上次读之后**的新行 + 一行状态（`[running]` / `[exit N]`）。**不阻塞**——`(no new output)` 是「此刻没有新东西」，不是「它结束了」 |
| `bash_kill` | 停掉整棵树并**忘掉**这条作业，顺带把它还没被读走的输出带回来 |

三件事都是**决定**，不是省略：

- **没有超时的是作业**。后台执行的全部意思就是没人等它；`timeout` 是「等多久」，两者不是一个旋钮。
- **没有人通知你它结束了**。没有推送通道，所以工具描述里写明了「你得自己来问」。
- **它只活在进程里**。按会话分家、JVM 退出时收掉、不落盘、不进库、也不随一轮 run 结束而死——
  随 run 死就等于后台执行没用。输出只留最近 500 行（丢了会报数），因为这是给模型读的尾巴，
  不是一份日志（要日志就让命令自己重定向到文件，再用 `read` 去看）。

## 启动

### 1) 一条命令起两个（推荐）

```bash
node scripts/dev.mjs             # 后端交给 OS 挑端口，前端代理到它，浏览器开 http://localhost:5173
node scripts/dev.mjs --port 8080 # 钉死端口（老地址，需要时）
node scripts/dev.mjs --scripted  # 脚本厂商替身：不要 api-key、不要模型、家目录临时、跑完即删
node scripts/dev.mjs --ui-port 5199  # 前端换端口
```

**脚本在 `scripts/` 下**：`dev.mjs` 起服务、`test.mjs` 跑测试、`proc.mjs` 是两者共用的跨平台子进程
动作。**是 Node 脚本不是 shell 脚本**，三个平台同一个文件：进程组 / `taskkill`、信号处理、临时目录
三处各自分叉，`process.platform` 一看就知道走了哪条。跑 `node scripts/dev.mjs`（POSIX 上
`./scripts/dev.mjs` 也行，它带 shebang）。脚本从自身位置往上找仓库根，所以在哪一级敲都不影响。
仓库里**只有这一处**告诉前端后端在哪，而它不在源码里：脚本让后端**在 0 号端口上绑**（OS 分配），
把后端**自己报出来的**那个端口交给 `HARNESS_BACKEND_URL`，`ui/vite.config.js` 拿它当代理目标。
所以没有任何源文件知道端口号，也不会有「8080 被上次忘了关的会话占着」这件事。
端口是**读回来的不是猜的**：先探一个空闲端口再交给后端，是跟整台机器赛跑。

**真实模式用的是你自己的 `~/.clj-harness`**（你的配置、你的厂商、你的密钥）；
`--scripted` **绝不用**——它拿一对临时家目录（config root 与 OS home，两者平级不嵌套，
见 `AGENTS.md`），退出时连目录一起删。`CLJ_HARNESS_HOME=... node scripts/dev.mjs` 也能用：脚本不覆盖
这个变量，所以想让真实模式落在别处，就在前面给它。

### 2) 分开起

```bash
clojure -M:run --port 0   # 后端；不带 --port 就是 8080，0 是「随 OS 挑」
# 期望：harness listening on http://localhost:<真正绑到的那个端口> -- POST an AG-UI RunAgentInput to /api/agent
# REPL 形态：clojure '-J-Dfile.encoding=UTF-8' -M:repl
```

```bash
cd ui
npm install      # 首次
HARNESS_BACKEND_URL=http://127.0.0.1:<上面那个端口> npm run dev
npm run build    # tsc --noEmit + vite build → dist/（不需要 Java）
```

### 3) 前端侧的形状（为什么是代理）

**页面只跟自己的 origin 说话。** 整个后端在**一个前缀**下——run 端点是 `POST /api/agent`，
其余都是 `/api/<什么>`——所以 `vite.config.js` 只需要**一条** `/api` 前缀规则转给后端。
`src/lib/threads.ts` 因此导出两个地址：`API_BASE`（管理调用挂的地方）与 `AGENT_URL`
（`HttpAgent` 构造时用的那一个端点，=`${API_BASE}agent`）。两件事因此成立：浏览器
**一个跨域请求都不发**（没有 preflight，也没有一份要跟着端口改的 CORS 白名单），而构建产物里
**不带我们的地址**，换到任何部署自己的反代后面都一样。要直连后端（不走代理）就
`VITE_AGENT_URL=http://…:8080`，那正是后端那条 CORS 放行存在的理由。

### 4) 会话里的东西

侧边栏是三个段位：「新建任务」与「设置」钉在上下，**中间的项目区是唯一滚动的东西**（窗口拉高拉矮
都不出现整页滚动）。一个项目 = 一个目录，显示成它最后一个文件夹名；悬停出「更多」，里面有
**移除项目**——那只是解绑，`projects/` 下的日志一个字节不动，重新添加同一目录会话就都回来。
项目下面是它的会话，每行可以**归档**（归档 = 行上的一个布尔，日志同样不动，进「已归档」分组）。

**新建任务必须先有项目**：一个项目都没有时，它说的是「先添加一个项目」并给出入口。会话的记录落在
`~/.clj-harness/projects/<workspace>/<threadId>.jsonl`。

**5173 还在，但它不再是 CORS 契约。** 走后端那条放行的那半边（`VITE_AGENT_URL` 指绝对地址）
才需要它；走代理时浏览器跟 5173 同源，白名单与它无关。`strictPort` 留着是因为第二个 dev server
悄悄落到 5174 比启动失败更让人意外。

### 会话旁边还有一个「轨迹」

线程栏上方可以在 `Conversation` 与 `Trajectory` 之间切。对话页签回答「我们聊了什么」；
轨迹回答**另一个问题**：**模型每一轮到底看到了什么**——它自己那份 system 消息的字节、
拼在它旁边的指令文件与技能清单、模型中途要来的技能正文、每次工具调用的参数与结果，
以及**照发出**的那张工具表；上方一条 `input` / `model` / `tools` 的时间轴把这些按时间摊开。

默认只有那份列表：**点某一行，右侧才展开那一条**（再点一次收回），因为记录里没有「这一轮的提示词」
这种东西，只有二十条里的某一条。**上面条带里的每一段也能点**，效果与点那一行一样——
它俩本来就是同一件东西的两次画法。点开 `system` 那一行，面板里有 `system prompt` 与 `tools` **两个页签**：
一个是那份提示词，另一个是它照发出的工具表——一行一个工具，折着看名字与描述的第一行，
展开看完整描述与定义 JSON。

它**读的是记录**（`~/.clj-harness/logs/...` 那份 jsonl），不是客户端手里那份对话——因为上面这些东西
客户端一个都没有，AG-UI 帧里也没有。所以它**不数、不算、不补**：记录里没有的格子它说没有，
绝不拿「这个会话今天有什么」去冒充「当时是什么」。一条早于 `model/*` 那两行的老日志，
模型那段就是空的，并如实标出来；一个被人否掉的工具调用不是「跑了 0 秒」，是一条没跑过的记号。

看它的时机只有两个：打开的时候，和一次模型调用结束的时候（一次 run 结束也补一次）。
没有轮询——一次长调用要流好几分钟，这段时间里它**站着不动**，因为它要显示的那部分记录**还不存在**。

### 添加项目：选目录这件事依赖平台

浏览器给不出绝对路径（网页的 file input 给的是没有位置的 File 对象），所以**目录选择的窗只能由服务端
这台机器来开**：窗在那里画，选中的路径从那里回来。它按平台分成三种：

| 平台 | 选目录怎么来的 |
| --- | --- |
| macOS | `osascript` 原生 choose folder |
| Windows | PowerShell（`-NoProfile -STA`）+ WinForms `FolderBrowserDialog`，先找 `powershell.exe` 再找 `pwsh.exe` |
| Linux / 其他 | **不支持**：服务端答 501 并给出一句话，侧边栏随即给出绝对路径输入框 |

关键不是支持哪些平台，而是**「窗没能打开」不能被当成「人取消了」**：从前二者都答 `{:dir nil}`，Windows 上
点「添加项目」于是表现为**彻底没反应**——没有窗、没有错、没有提示。现在选择器的答案是三态
（选好了 / 取消了 / 这台机器上没有），取消依旧静默，没有窗则明说并给出手输路径那条退路。

**没有桌面会话**（以服务方式启的进程、远程会话）是同一个答案：那里没有窗可画，服务端按「没有」处理。

### 停止

`Ctrl+C`，或 `Get-Process clojure,node | Stop-Process`。

### 验证

```bash
node scripts/test.mjs            # 三条腿一起跑，也可以 --backend / --ui / --build 单跑
node scripts/test.mjs --ns harness.edge.http-test   # 只跑几个命名空间，家目录隔离照旧生效
```

**别自己拼命令**：家目录隔离、端口由 OS 分配、跑完删临时目录，都是**调用方式**的事，
手拼一次就漏一次。理由与每一条守什么写在 `AGENTS.md` 与 `scripts/test.mjs` 的头注释里。

后端那条腿跑出来大概长这样（分支 `parallel-sessions`，基线随分支变，报数时带上分支与提交）：

```
Ran 860 tests containing 11269 assertions.
0 failures, 0 errors.
ISOLATION FAILURE: ...   # 只有真实家目录在这段时间被**别的进程**动过才会出现
```

- 断言数被锚点表的 rank/select 往返与去重用例拉高（各自数千条），不是用例变多了。
- 仍然**真竞赛**的是 `http_test/the-projects-listing-joins-the-store-with-the-disk`（它比「列表接口报的
  字节数与 mtime」和「随后从磁盘读的」，中间只要有人往同一份日志落一行就不等）——跑多少次不一定撞上，
  失败条数每次都可能不同，比对看**名字**。
- `ISOLATION FAILURE` 那条不是用例失败，是**进程级**的断言：真实 `~/.clj-harness/harness.db`
  在这段时间里变了。运行时自己写的不会变（它指向临时目录），会变的是**这台机器上另开的**
  harness 实例——退出码因此是 1，但失败集合仍然是空的。
- 前端那条腿 32 个用例，含 11 组：帧 schema / 真 `@ag-ui/client` 驱动 / 二轮续写 / 审批
  park→approve→veto / 技能列表（两层的根）/ 会话统计 / elicitation / 界面取数 / 附件 /
  一轮的折叠算术 / 两个会话同时跑。它自带后端（真 HTTP、真 `@ag-ui/client`，provider 是脚本替身），
  所以不需要 8080、不需要 api-key、不需要模型。
```

细节见 [`docs/architecture/client.md`](docs/architecture/client.md)。
