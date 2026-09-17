# 架构现状

这套文档记录 clj-harness **今天是什么样**，而不是它曾经是什么样、或打算成为什么样。
每条陈述都对着代码核过；快照点写在下面，与它对不上的地方以代码为准。

**快照：`a08ca72`（2026-09-17）。** 工作树里的在办改动不算现状，见文末「在办」。
（`a08ca72` 是把 `composer-image` 合进来的那一提交，本特征给本目录添了
[client](architecture/client.md) 里「附件」那一节并改掉两处已不成立的说法。
这一版里还夹着另一个会话刚落下的 `trajectory-injection-once` 三张票面——只有 markdown，
不改变本目录的任何一条陈述。）

## 与另外两处文档的分工

| 地方 | 是什么 |
|---|---|
| `README.md` | 叙述与操作：怎么装、怎么起、每个特性讲一遍「为什么这样设计」 |
| **`docs/architecture/`（本目录）** | **现状的骨架**：模块地图、一次请求的路径、状态存在哪、接口有哪些 |
| `.scratch/<feature>/` | **历史**：每个特性的 spec 与票面，记的是**当时**的决策与验收。它们**不是**现状描述，且**不再修改**——今天的样子只在本目录和代码里 |

三处的边界是刻意的：历史不动，现状只有一个地方，叙述另有一处。改行为时**不必**回头改 `.scratch`（那是历史），
但**要**看是否动到了本目录（那是现状）。

## 系统一句话

一个 Clojure 写的 agent 内核，唯一对外协议是 AG-UI；前端是 TypeScript + React + assistant-ui，
浏览器直连后端（无中间层，无代理）。会话历史由**客户端持有**，服务端每轮现收现算，jsonl 只是记录。

前端**曾经**是 ClojureScript + helix + CopilotKit，已整体换成 TypeScript + assistant-ui；
`ui/` 下没有 `.cljs`，也没有 shadow-cljs 与 helix。协议侧（AG-UI 帧、interrupt/resume）**一字未改**。

## 模块地图

后端 `src/harness/`（纯 Clojure，无 Java 依赖除 sqlite-jdbc）**分四层**，四层的规则、归属与接线方式见
**[layers](architecture/layers.md)**。下面按层列出来，一张表看在眼里就是那四层。

### `harness.infra` —— 基建：东西落在哪、错了写哪、进程怎么起来

| 命名空间 | 是什么 |
|---|---|
| `infra.home` | 配置根：决定每个文件落在哪。**两层 floor**：`root`（配置家目录，`CLJ_HARNESS_HOME` 可搬）与 `user-home`（OS 家目录，宿主约定文件住那儿，**不跟随** `CLJ_HARNESS_HOME`） |
| `infra.db` | home 的**元数据层**（sqlite）：迁移链（**步骤按名字记账**，不是按版本号位置）、开启时隔离，项目/会话/记账三张表加锚点的四张表 |
| `infra.logging` | 用代码配 Logback——`SizeAndTimeBasedRollingPolicy`，**日期与大小一起** rotate。`ensure!` 在 `root` 变动时重配，所以测试不会写进真 home |
| `infra.log` | 一次调用同时写 stderr 与文件的门面（**后端自己的错误日志**，与 session jsonl 是两回事） |
| `infra.shell` | 唯一决定 spawn 哪个 shell 的地方（bash 工具与 hook 引擎共用）：一条**写明的候选链**（Git Bash → bash → pwsh → PowerShell → cmd）+ 每种 shell 自己的起法（`-lc` / `-NoProfile -Command` / `/c`）；Windows 上那个 `bash` 是 WSL 启动器时它**拒绝**并把链走下一级。两种 spawn：**一次性**的 `run`（stdin、`:dir`、`:timeout-ms`，**到点连子孙一起收**并把已经读到的输出还回来）与**长活**的 `start`（stdout 排队、`:close!` 收整棵树）；后者的 argv 有两种形状——`:program`（启动一个程序，Windows 上走 `cmd /c` 以保住反斜杠路径）与 `:shell`（一条 shell 命令，走解析出来的那个 shell） |
| `infra.env` | **这台机器长什么样**（`<env>` 块的事实来源）：平台、shell（读 `infra.shell` 的解析）、以及一份声明名单里这个 shell 看得见哪些命令行增强工具。每进程探一次并缓存，**探测走同一个 shell**，`System/getenv` 不算数 |
| `infra.rg` | **怎么跑 ripgrep**：二进制名、超时、以及「`rg` 不在 PATH 上」那句点名失败（判据是**退出码 127**，不是 `No such file or directory` 那句字符串——后者也是 `rg` 对**不存在的搜索根**说的话）。`cap.hashline.grep` 与 `cap.glob` 共用它，而 `--json` 的解析留在 `grep` 自己手里 |

### `harness.kernel` —— 机制：只说得清「怎么做」

| 命名空间 | 是什么 |
|---|---|
| `kernel.event` | 内核的全部词汇：11 种事件 |
| `kernel.frames` | 帧折叠回消息：日志的**读侧**引擎 |
| `kernel.loop` | ReAct 循环：流式一轮 → 并发跑工具 → 追加结果 → 再一轮，直到没有工具调用 |
| `kernel.llm` | provider 协议层（一个按 `:protocol` 分派的 multimethod）+ **system 消息开头（`prompt.md`）的冻结载体** |
| `kernel.tools` | **唯一执行缝**：注册表、会话 overlay（两轴）、待决审批、三相执行、工具声明的词汇、**安装门**；外加两条装进来的策略（批的计划器、编辑模式的收窄）与两条**按会话回答**的贡献（`:tools-for` 外部来源的工具、`:disabled-for` 由层提供的停用语）。`suspend!`（工具体从中间停下并抛出）也在这里。**它不认识任何一个具体工具** |
| `kernel.hooks` | **hook 引擎的数据半**：27 个点是数据、一条声明允许带什么、三个来源的档位与顺序 |
| `kernel.hooks.dispatch` | **hook 引擎的执行半**：按声明 spawn 命令（或跑一个进程内的函数）、读退出码、超时、落审计行 |

### `harness.cap` —— 能力：说得清「它会做什么」

| 命名空间 | 是什么 |
|---|---|
| `cap.tools` | **十八个内建工具的「脸」**（`read` / `write` / `edit` / `replace` / `insert` / `undo_last_replace` / `anchor_grep` / `glob` / `bash` / `bash_background` / `bash_output` / `bash_kill` / `eval` / `skill` / `session-configure` / `todo_write` / `web_fetch` / `web_search`）：每个工具的名字、说明与参数，以及它们的 `install!`。**干活的不在这里**——文件编辑在 `cap.hashline/*`、找文件在 `cap.glob`、清单在 `cap.todos`、出网在 `cap.web`、后台命令在 `cap.jobs`；批的计划器与编辑模式的收窄策略也从这里装上 |
| `cap.jobs` | **后台作业**：起一条没人等的命令、读它打出来的新行、停掉它。注册表按会话分家、进程内、有界尾巴（最近 500 行 + 丢了多少行）、每会话游标，并且是**唯一**能让作业离开注册表的地方（`bash_kill` 既停也忘）。进程退出时收尾钩子把它们全部收掉；**不落盘、不进库、不跨重启**，也不随 run 结束而死 |
| `cap.editing` | **两套编辑实现的名字与账**：解析 `harness.edn` 的 `:editing`、决定本会话被服务哪一套、每个模式服务哪些工具名，以及「不服务」时那句话术 |
| `cap.hashline/*` | 按锚点编辑的全部实现：`anchors` / `store` / `serve` / `reading` / `edit` / `replace` / `insert` / `undo` / `write` / `grep` / `files`（锚点分配、落盘、diff、拒绝、批、撤销、搜索） |
| `cap.glob` | **按名字找文件**：答案是 rg 两次列举的**交集**（`rg --glob` 的优先级高于 `.gitignore`，直接交给它会列出 `node_modules`），顺序按路径不按 mtime。列的是**路径**，所以它不属于任何编辑家族、两种模式都服务它 |
| `cap.todos` | **本会话的任务清单**：校验、整份替换、渲染，落在 `infra.db` 的 `todos` 表（一行一个会话，清单整存整取）。判据是「能被整份改写的是状态」 |
| `cap.web` | **出网**：唯一一处发请求的地方（超时、手工跟随并封顶的重定向链、301/302/303 变 GET 而 307/308 保留方法与 body、字节上限、按声明的字符集解码、以及**有损的** HTML→文本抽取器——不是渲染器）。抽取器是纯函数，所以它不靠 socket 也能测 |
| `cap.web.search` | **三家搜索厂商各自的线**（Brave / Exa / Tavily：请求形状、键放在哪个头、响应形状）。厂商由**哪个键在**决定，顺序写在那一张表里；`cap.web` 不知道任何厂商的存在 |
| `cap.hooks` | hook 的**来源**：读配置家的 `hooks.edn` 再叠上绑定项目的 `.harness/hooks.edn`（两级浅合并），以 `install!` 交给内核 |
| `cap.system-prompt` | **system 消息的组装**：`prompt.md` 的冻结开头 + `SystemPrompt` 点上各声明追加的文本；内核自己那两条行（工程目录 / 这台机器）由它的 `install!` 装上。**不并进 `cap.preamble` 是 require 环**：`cap.project` 已 require `cap.preamble`，而这些行要它，也要 `kernel.hooks.dispatch` |
| `cap.project` | 项目与会话绑定、路径重根、围栏、`harness.edn` 两级装配，以及 `skill-roots` / `preamble-files`（配置 + 绑定的配对）与 `before-llm`（每轮 LLM 前的技能注入） |
| `cap.skills` | **技能**：默认根**与它们的层**、目录名即身份、`SKILL.md` 的窄 frontmatter、坏技能是诊断、正文的**派生注入**（两个来源：`skill` 工具与人的 `/name`）、以及**技能列表**（`/` 弹出的那张表）的数据 |
| `cap.preamble` | **user 侧开场块**：指令文件的读与失败语义、清单与指令的**顺序**（唯一决定它的地方） |
| `cap.providers` | provider 目录（厂商 → model 表）、三档解析、api-key、只读的生效配置（`settings`） |
| `cap.mcp` | **外部服务器作为工具来源**：读两级 `mcp.edn`、按（项目身份 × server × 声明形状）缓存连接、两种 transport（stdio 子进程 / HTTP）、把 `tools/list` 桥成工具表里的行、elicitation（服务器反过来问人）与会话级启停。工具是**动态来源**（`:tools-for`），所以它经 `install!` 装上而不是写死在表里 |
| `cap.git` | 会话目录作为 git 工作树：读当前分支、列本地分支、切分支。切只有 `checkout`，**永不 --force**——脏树与被别处占用的分支由 git 自己拒绝，原话回传（含点出文件名的那几行）。分支名先对 `git branch` 的列表校验再插值，且本机 git 是 2.23（`switch`/`init -b` 都还没有） |

### `harness.edge` —— 适配：把内核翻译成别人的协议

| 命名空间 | 是什么 |
|---|---|
| `edge.ag-ui` | 内核事件 → AG-UI 帧（唯一一处做这个转换）；`inbound` 也在这里，**user 侧开场块**由它拼在 system 消息之后 |
| `edge.http` | **AG-UI 边** + 管理边（JSON 端点）+ jsonl 审计写入，并且是**组合根**：`start!` 把上面那些能力装上，`stop` 再把它们撤回去 |
| `edge.replay` | **对话那一半**的记录读侧：重建对话、续跑一场记录。run 外的显式管理动作 |
| `edge.stats` | **审计那一半**的记录读侧：`input` 与 `model/*` 折成一条会话的几个数（轮 / 模型调用 / 用量 / 缓存命中 / 输出速度），composer 下面那条状态条读它。`records->stats` 是对记录的纯函数，`log-stats` 接一个 File——**它不知道 home 在哪**，与 `replay` 同一立场 |
| `edge.trajectory` | **`message` 那一半**的记录读侧：按轮折回「模型每一轮到底看到了什么」——system 消息的字节、拼在它旁边的上下文、每条用户消息、每次工具调用的参数与结果、每次调用发出去的工具表。`GET /api/threads/<stem>/trajectory` 是它唯一的出口；轮的判据与 `edge.stats` **共用一份实现** |

作者/测试工具（`dev/harness/`，不在生产路径上）：`wire`（SSE 解析 + 帧结构校验）、
`evals`（把某 thread 跑过的 `eval` 读出来，供人决定晋升）、`repl`（起服务后落进 REPL）、
`e2e_server`（`npm test` 起的那个后端）。

## 章节

0. **[layers](architecture/layers.md)** — **四层是什么**：判据、归属、允许的边、能力怎么装进核心
1. **[overview](architecture/overview.md)** — 一次请求的完整路径，端到端；三条铁律；状态存在哪
2. **[kernel](architecture/kernel.md)** — event / loop / llm / tools：一轮 run、执行缝的三个出口、悬置与它的 wire 形状
3. **[edge](architecture/edge.md)** — `harness.edge.http`：AG-UI 流、管理端点、jsonl 审计行、入站 parts 与模态守卫
4. **[home-and-storage](architecture/home-and-storage.md)** — 配置根、配置文件、sqlite、日志树、重建
5. **[providers](architecture/providers.md)** — 厂商与 model、三档解析、api-key 纪律
6. **[projects](architecture/projects.md)** — 项目、会话、绑定、围栏
7. **[hooks](architecture/hooks.md)** — 27 个点、契约、三个来源、会话 overlay、`SystemPrompt` 与内建的两条行、eval 与晋升
8. **[skills-and-instructions](architecture/skills-and-instructions.md)** — 一场会话开场拿到什么：指令文件、技能清单、派生的正文、`skill` 工具、围栏里的技能根
9. **[mcp](architecture/mcp.md)** — 外部服务器：声明、连接、桥接、elicitation、账本与界面
10. **[client](architecture/client.md)** — TypeScript 前端：运行时、侧边栏、审批门、样式体系、测试

## 验证

```pwsh
clojure -M:test -m harness.test-runner   # 后端离线全量；基线随分支变，报数带上分支与提交
cd ui && npm test                        # 前端端到端全量（自带后端，不需要 api-key / 模型）
cd ui && npm run build                   # tsc --noEmit + vite build
```

UI 套件驱动的是**真后端**（真 HTTP、真 `@ag-ui/client`），只是 provider 是脚本替身；
它测什么由**脚本文件**决定，服务端不因此多一条测试专用路由。细节见 [client](architecture/client.md)。

两套 suite 都**不写死端口**——服务用 `{:port 0}` 让 OS 分配，测试读绑定后的实际端口。
理由与规则见 `AGENTS.md`：写死的端口要求「此刻这台机器上只有我在跑这套测试」，
而开发者的会话、上一张票留下的 e2e server、另一个 worktree 都在同一台机器上。
后端的离线 suite **以退出码为信号**（0 = 全绿，含「没碰开发者真实家目录」那条断言）。

## 在办（未落地，不是现状）

写下这一节是为了让「文档没写」与「还没做」不会被读成同一件事。

- **Action Fusion**：计划见 `.scratch/action-fusion/`（4 张票，2026-09-15 立，两次复议）。
  一次 `eval` 调用就是一次融合：在里面调用工具表里的任何工具、按返回值决定下一步、循环做批量操作，
  中间不回到模型。**不改任何工具的定义**（初版的 `then_run` 参数已撤销，理由见 spec 的复议段）。
  代码里**一行都没有**：没有 `call!` 这个入口，内层调用的相位事件没有去处。
  **它的说明也不在 `prompt.md` 里，而且这是设计**：名册由 wire 上的 `:tools` 自描述，技法（怎么把工具
  串起来用）才需要一块地方说——那块地方是 `SystemPrompt` 点上的一条内建行，开关在 `harness.edn`
  （票 04），所以 `prompt.md` 到那时仍是一个字都不提本特征。
