# 架构现状

这套文档记录 clj-harness **今天是什么样**，而不是它曾经是什么样、或打算成为什么样。
每条陈述都对着代码核过；快照点写在下面，与它对不上的地方以代码为准。

**快照：`main` @ `a90ddbb`（2026-09-15）。** 工作树里的在办改动不算现状，见文末「在办」。

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

后端（`src/harness/`，纯 Clojure，无 Java 依赖除 sqlite-jdbc）：

| 命名空间 | 是什么 |
|---|---|
| `event` | 内核的全部词汇：11 种事件 |
| `loop` | ReAct 循环：流式一轮 → 并发跑工具 → 追加结果 → 再一轮，直到没有工具调用 |
| `llm` | provider 协议层（一个按 `:protocol` 分派的 multimethod）+ **system prompt 的冻结载体** |
| `tools` | **工具表与唯一执行缝**：内建表（七个，含 `skill`）、会话 overlay（两轴）、待决审批、三相执行 |
| `ag_ui` | 内核事件 → AG-UI 帧（唯一一处做这个转换）；`inbound` 也在这里，**开场块**由它拼在 system 消息之后 |
| `http` | **AG-UI 边** + 管理边（JSON 端点）+ jsonl 审计写入 |
| `providers` | provider 目录（厂商 → model 表）、三档解析、api-key、只读的生效配置（`settings`） |
| `home` | 配置根：决定每个文件落在哪。**两层 floor**：`root`（配置家目录，`CLJ_HARNESS_HOME` 可搬）与 `user-home`（OS 家目录，宿主约定文件住那儿，**不跟随** `CLJ_HARNESS_HOME`） |
| `project` | 项目与会话绑定、路径重根、围栏、`harness.edn` 两级装配，以及 `skill-roots` / `preamble-files`（配置 + 绑定的配对） |
| `skills` | **技能**：默认根、目录名即身份、`SKILL.md` 的窄 frontmatter、坏技能是诊断、正文的**派生注入** |
| `preamble` | **开场块**：指令文件的读与失败语义、清单与指令的**顺序**（唯一决定它的地方） |
| `db` | home 的**元数据层**（sqlite）：迁移链、开启时隔离，两张状态表 |
| `frames` / `replay` | 日志的**读侧**：帧折叠回消息、重建对话 |
| `hooks` / `hooks.dispatch` | **hook 引擎**：点表是数据；按声明 spawn 命令、读退出码、超时、落审计行 |
| `shell` | 唯一决定 spawn 哪个 shell 的地方（bash 工具与 hook 引擎共用） |

作者/测试工具（`dev/harness/`，不在生产路径上）：`wire`（SSE 解析 + 帧结构校验）、
`evals`（把某 thread 跑过的 `eval` 读出来，供人决定晋升）、`repl`（起服务后落进 REPL）、
`e2e_server`（`npm test` 起的那个后端）。

## 章节

1. **[overview](architecture/overview.md)** — 一次请求的完整路径，端到端；三条铁律；状态存在哪
2. **[kernel](architecture/kernel.md)** — event / loop / llm / tools：一轮 run、执行缝的三个出口、悬置与它的 wire 形状
3. **[edge](architecture/edge.md)** — `harness.http`：AG-UI 流、管理端点、jsonl 审计行、入站 parts 与模态守卫
4. **[home-and-storage](architecture/home-and-storage.md)** — 配置根、配置文件、sqlite、日志树、重建
5. **[providers](architecture/providers.md)** — 厂商与 model、三档解析、api-key 纪律
6. **[projects](architecture/projects.md)** — 项目、会话、绑定、围栏
7. **[hooks](architecture/hooks.md)** — 26 个点、契约、两级装配、会话 overlay、eval 与晋升
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
- **hashline 编辑**：在 `hashline-edit` 分支上，不在 `main`。
