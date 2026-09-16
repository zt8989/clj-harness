# 架构现状

这套文档记录 clj-harness **今天是什么样**，而不是它曾经是什么样、或打算成为什么样。
每条陈述都对着代码核过；快照点写在下面，与它对不上的地方以代码为准。

**快照：`layer-layout` 分支上的四层落地（2026-09-16）；提交号在合并时填。** 工作树里的在办改动不算现状，见文末「在办」。

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
| `infra.shell` | 唯一决定 spawn 哪个 shell 的地方（bash 工具与 hook 引擎共用） |

### `harness.kernel` —— 机制：只说得清「怎么做」

| 命名空间 | 是什么 |
|---|---|
| `kernel.event` | 内核的全部词汇：11 种事件 |
| `kernel.frames` | 帧折叠回消息：日志的**读侧**引擎 |
| `kernel.loop` | ReAct 循环：流式一轮 → 并发跑工具 → 追加结果 → 再一轮，直到没有工具调用 |
| `kernel.llm` | provider 协议层（一个按 `:protocol` 分派的 multimethod）+ **system 消息开头（`prompt.md`）的冻结载体** |
| `kernel.tools` | **唯一执行缝**：注册表、会话 overlay（两轴）、待决审批、三相执行、工具声明的词汇、**安装门**；外加两条装进来的策略（批的计划器、编辑模式的收窄）。**它不认识任何一个具体工具** |
| `kernel.hooks` | **hook 引擎的数据半**：27 个点是数据、一条声明允许带什么、三个来源的档位与顺序 |
| `kernel.hooks.dispatch` | **hook 引擎的执行半**：按声明 spawn 命令（或跑一个进程内的函数）、读退出码、超时、落审计行 |

### `harness.cap` —— 能力：说得清「它会做什么」

| 命名空间 | 是什么 |
|---|---|
| `cap.tools` | **十一个内建工具的定义**（`read` / `write` / `edit` / `replace` / `insert` / `undo_last_replace` / `anchor_grep` / `bash` / `eval` / `skill` / `session-configure`）与它们的 `install!`；批的计划器与编辑模式的收窄策略也从这里装上 |
| `cap.editing` | **两套编辑实现的名字与账**：解析 `harness.edn` 的 `:editing`、决定本会话被服务哪一套、每个模式服务哪些工具名，以及「不服务」时那句话术 |
| `cap.hashline/*` | 按锚点编辑的全部实现：`anchors` / `store` / `serve` / `reading` / `edit` / `replace` / `insert` / `undo` / `write` / `grep` / `files`（锚点分配、落盘、diff、拒绝、批、撤销、搜索） |
| `cap.hooks` | hook 的**来源**：读配置家的 `hooks.edn` 再叠上绑定项目的 `.harness/hooks.edn`（两级浅合并），以 `install!` 交给内核 |
| `cap.system-prompt` | **system 消息的组装**：`prompt.md` 的冻结开头 + `SystemPrompt` 点上各声明追加的文本；内核自己那三条行（工具集合 / 工程目录 / provider 档）由它的 `install!` 装上。**不并进 `cap.preamble` 是 require 环**：`cap.project` 已 require `cap.preamble`，而这三条要 `kernel.tools` / `cap.project` / `kernel.hooks.dispatch` |
| `cap.project` | 项目与会话绑定、路径重根、围栏、`harness.edn` 两级装配，以及 `skill-roots` / `preamble-files`（配置 + 绑定的配对）与 `before-llm`（每轮 LLM 前的技能注入） |
| `cap.skills` | **技能**：默认根、目录名即身份、`SKILL.md` 的窄 frontmatter、坏技能是诊断、正文的**派生注入**（两个来源：`skill` 工具与人的 `/name`） |
| `cap.preamble` | **user 侧开场块**：指令文件的读与失败语义、清单与指令的**顺序**（唯一决定它的地方） |
| `cap.providers` | provider 目录（厂商 → model 表）、三档解析、api-key、只读的生效配置（`settings`） |
| `cap.git` | 会话目录作为 git 工作树：读当前分支、列本地分支、切分支。切只有 `checkout`，**永不 --force**——脏树与被别处占用的分支由 git 自己拒绝，原话回传（含点出文件名的那几行）。分支名先对 `git branch` 的列表校验再插值，且本机 git 是 2.23（`switch`/`init -b` 都还没有） |

### `harness.edge` —— 适配：把内核翻译成别人的协议

| 命名空间 | 是什么 |
|---|---|
| `edge.ag-ui` | 内核事件 → AG-UI 帧（唯一一处做这个转换）；`inbound` 也在这里，**user 侧开场块**由它拼在 system 消息之后 |
| `edge.http` | **AG-UI 边** + 管理边（JSON 端点）+ jsonl 审计写入，并且是**组合根**：`start!` 把上面那些能力装上，`stop` 再把它们撤回去 |
| `edge.replay` | 记录读侧：重建对话、续跑一场记录。run 外的显式管理动作 |

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
7. **[hooks](architecture/hooks.md)** — 27 个点、契约、三个来源、会话 overlay、`SystemPrompt` 与内建的三条行、eval 与晋升
8. **[skills-and-instructions](architecture/skills-and-instructions.md)** — 一场会话开场拿到什么：指令文件、技能清单、派生的正文、`skill` 工具、围栏里的技能根
9. **[client](architecture/client.md)** — TypeScript 前端：运行时、侧边栏、审批门、样式体系、测试

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

- **MCP**：计划见 `.scratch/mcp/`（6 张票，01 号票已细化到接线形状）。代码里**一行都没有**；
  `harness.mcp` 这个命名空间不存在，`mcp.edn` 不存在，工具表里没有外部来源。
- **Action Fusion**：计划见 `.scratch/action-fusion/`（4 张票，2026-09-15 立，同日复议改版）。
  一次 `eval` 调用就是一次融合：在里面调用工具表里的任何工具、按返回值决定下一步、循环做批量操作，
  中间不回到模型。**不改任何工具的定义**（初版的 `then_run` 参数已撤销，理由见 spec 的复议段）。
  代码里**一行都没有**：没有 `call!` 这个入口，内层调用的相位事件没有去处。
  （`prompt.md` 对本特征**一个字都没写**——它现在只留下与任何会话无关的话，见
  [overview 的铁律 2](architecture/overview.md#三条铁律)；`eval` 的定位由工具自己的描述承担。）
