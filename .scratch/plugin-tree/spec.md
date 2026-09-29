# spec: plugin-tree

Status: ready-for-agent

把 clj-harness 的**四层**（`infra / kernel / cap / edge`，`docs/architecture/layers.md`）重切成
**三类服务**：**核心脊柱**（核心服务）、**能力接缝**（定义 / 实现 / 消费三角）、**组合点**
（一份运行时组装清单，两份 profile）。运行时是 `.scratch/cordis-clj/` 那份 Cordis（Clojure 版）。

参照对象是 DeepSeek Harness（dsh，[官方架构文档](https://github.com/deepseek-ai/deepseek-harness/blob/master/docs/architecture.md)）：
「一切皆插件」、无特权核心、注册即效应（卸载自动回滚）、缝的三方角色、事件三域、profile 分层装配。
**借的是形状与那些判据，不是它的包名与代码组织**——本仓的语汇照本仓的来。

## 这一轮撤销的决定

上一轮（`.scratch/cordis-clj/spec.md` 决定 1）写的是「**不动 `src/harness/`**，cordis 与 harness 并存」。
**这条被本目录撤销**：本目录要动 harness，而且是一次宽重构。撤销是**有痕迹的**——那一份 spec 的决定 1
已标注被撤销并指到这里，不是悄悄改掉。cordis-clj 自己那份计划（运行时 + Web 基础模块）照旧。

## 四条已定的决定

1. **目标**：本仓 harness 的四层 → dsh 的三类服务。改造对象是 `src/harness/`。
2. **做法**：**expand–contract 的宽重构**。先把新形状**立在旁边**（旧形状照旧跑），再按接缝分批迁移，
   每批都保持全量套件绿；最后一次性收口、删旧形状。
3. **割法**：照 dsh 的核心表——**脊柱 = `session` / `system-prompt` / `tools` / `agent` / `agent-loop` / `scope`**
   （外加**事件三域的词汇**）；**所有能力都是接缝**——`llm` / `fs` / `shell` / `subprocess` / `web` /
   `sandbox` / `subagent` / `compaction` / `mcp`（+ 本仓特有的一条：**会话投影**，见下）。
   本仓对应件**全部重新归位**。
4. **装配**：**只要 profile**——一份有序的运行时组装清单，**两份 profile**（`web` / `headless`）。
   原先还写了第三份 `sdk`，**不做了**：本仓今天的程序化驱动走的就是 `web` 那一层同一条 AG-UI 边与那些
   管理边，另立一份只是把同一张清单抄一遍。
   **不要** bundle、不要 patch 层。自省那一式（`--dump-config` 的同类）要。

## 归属表（草案，票 01 定稿）

| 今天的命名空间 | 归到 |
|---|---|
| `kernel.session` | 脊柱 `ctx.sessions`（记录的字在实现侧） |
| `kernel.tools` | 脊柱 `ctx.tools` |
| `kernel.llm` | 接缝 `ctx.llm` 的**定义**；`prompt.md` 的冻结头搬去 `ctx.systemPrompt` |
| `kernel.event` | 脊柱：事件三域的词汇（票 11） |
| `kernel.frames` | 接缝「会话投影」（读侧引擎） |
| `kernel.loop` | 脊柱 `ctx.agentLoop`（`ctx.agents` 契约的唯一默认实现） |
| `kernel.hooks` / `kernel.hooks.dispatch` | **待定**：留脊柱，还是成为挂能力事件的能力插件（票 11 作答） |
| `cap.system-prompt` | 脊柱 `ctx.systemPrompt` |
| `cap.tools` | 能力插件：消费 `ctx.tools` 的那二十张「脸」 |
| `cap.editing` | 能力插件：消费 `ctx.tools` + `ctx.scope`（按会话的收窄策略） |
| `cap.hashline/*` | 接缝 `ctx.fs` 的**实现侧**（本地文件系统 + 锚点账本）与消费（文件工具） |
| `cap.glob` | 接缝 `ctx.fs` 的消费（列举走 `ctx.subprocess` 跑 rg） |
| `cap.jobs` | 接缝 `ctx.subprocess` / `ctx.shell` 的消费（记录那棵树是它的状态） |
| `cap.web` / `cap.web.search` | 接缝 `ctx.web` |
| `cap.spill` | 接缝 `ctx.fs` 的消费（取回指引） |
| `cap.providers` | 接缝 `ctx.llm` 的**实现侧**（目录 / 三档 / 凭据） |
| `cap.skills` / `cap.preamble` / `cap.project` 的指令那半 | 票 21：`ctx.systemPrompt` 的贡献 |
| `cap.project` 的绑定与围栏 | `ctx.scope`（域）+ 接缝 `ctx.fs` 的策略（围栏） |
| `cap.claims` | 脊柱 `ctx.sessions` 的 `:claim` 缝的实现 |
| `cap.mcp` | 接缝 `ctx.mcp` |
| `cap.subagents` | 接缝 `ctx.subagent` |
| `cap.git` | 接缝 `ctx.subprocess` 的消费 |
| `cap.todos` / `cap.ask` / `cap.frame-bus` | 能力插件（各自消费 `ctx.sessions` / `ctx.agents` / 事件） |
| `edge.sessions` | 脊柱 `ctx.sessions` 的**实现侧**（jsonl、认领） |
| `edge.replay` / `edge.stats` / `edge.context` / `edge.trajectory` / `edge.pressure` | 接缝「会话投影」的消费者（dsh 的 `ctx.sessionProjections` 是同一件事） |
| `edge.compaction` / `edge.prune` / `edge.relieve-pressure` | 接缝 `ctx.compaction` |
| `edge.delegation` | 接缝 `ctx.subagent` 的一种实现 |
| `edge.ag-ui` / `edge.http` / `edge.ui` / `edge.mux` / `edge.host` | 组合点（profile 的入口与出口；`edge.http/start!` 那份装配变成**数据**） |
| `infra.db` | 接缝「会话持久化」的实现 |
| `infra.shell` / `infra.env` / `infra.rg` | 接缝 `ctx.subprocess` / `ctx.shell` 的实现 |
| `infra.home` / `infra.logging` / `infra.log` | **待定**：新形状里叫什么（运行时事实？还是也算插件？）——票 01 作答 |

**为什么 `llm` 既在 dsh 的核心表里、又是接缝**：那是两种分类法在分工。核心表说的是「每次运行都会挂的件」；
接缝说的是「有定义/实现/消费三角、可以整条换掉的件」。一个件可以同时在两张表里。本仓照这个分工，
**不合并**这两张表。

**多出来的一条缝**：dsh 的架构文档里有一条**投影缝**（`ctx.sessionProjections`：注册的折子逐条增量折、
宿主读一份类型化状态）。本仓的 `edge.replay` / `edge.stats` / `edge.context` / `edge.trajectory` /
`edge.pressure` 正是同一件事，今天却都躺在适配层里。所以本仓的接缝名单在用户给的九个之外**加这一条**，
理由与出处写在这里，票 20 把它立起来。

## 探索得到的事实（票面按它们写）

- `harness.layers-test` 是**法则本身**（`src/` 下每个命名空间都归一层、不许向上 require、路径与名字一致）。
  改分层就是改这条法：票 02 把它改成**按一张声明表判**，此后「搬一个命名空间 = 改表里一行」。
- `harness.test-runner/test-namespaces` 是**字面量**，漏一条就整轮静默不跑。
- 本仓的三条铁律（`docs/architecture/overview.md`）与两条 ADR（0005 会话机制在核心里、0012 记录是一条流）
  是**判据不是摆设**：票 04、07、11 的验收直接引用它们，别当成可以顺手放松的东西。
- `docs/architecture.md` 与 `layers.md` 是「现状」，改行为**必须**回头改它们（`.scratch/` 是历史，不改）。
  所以收口那一票（23）里有文档这一半。

## 待定（票里必须作答，不许含糊）

| 待定 | 谁答 |
|---|---|
| hook 引擎：留脊柱还是进能力插件 | 票 11 |
| `infra.home` / `logging` / `log` 在新形状里的名字与归属 | 票 01 |
| 凭据 / settings 归 `ctx.llm` 的实现侧还是单立一条缝 | 票 12 |
| **两套 HTTP 栈只留一套**：`profile:web` 用今天的 `edge.http`，还是用 `.scratch/cordis-clj/` 票 10–13 新建的那套 Web 插件（这决定那几张票还成不成立） | 票 22 |

## 不做什么

- 不要 bundle、不要 patch 层、不做「按 id 改一行配置」那套（这一轮明确不要）。
- 不改前端（`ui/` 一行不动）；不改 AG-UI 的 wire 形状。
- 不引新依赖。
- 不重写行为：每一步的判据都是「记录与答案**逐字节相同**」，除非某张票明说要改语义。
