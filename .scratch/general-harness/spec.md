# spec: general-harness

Status: ready-for-agent

把 clj-harness 从极简探索内核升级为**通用 harness 工程**：前后端保持纯 Clojure + ClojureScript，
支持项目目录绑定、skills、tools 泛化、MCP、从 jsonl 恢复会话，以及 26 个生命周期 hook。
本 spec 推翻 minimal-kernel 的部分「明确不做」，但**保留其已验证的内核骨架**。

---

## Problem Statement

clj-harness 验证了内核命题，但它不能当工具用：

- **没有项目概念**——read/write/bash 对任意路径裸奔，行为圈不进一个工作区；也没有项目级配置的容身之处。
- **能力面封闭**——工具写死在 tools.clj，接一个 MCP 服务器、加一个 skill 都要改源码重启。
- **会话不可恢复**——进程一死对话就散了，jsonl 只有 dev 侧手工读法，没有产品路径。
- **没有生命周期钩子**——人无法在提示提交前、工具执行前、会话开始时插入自己的策略（审计、拦截、注入上下文），
  只能改内核代码。

## Solution

同一个内核骨架（AG-UI 边 + provider 形状消息 + tools/run! 唯一执行缝 + core.async 事件流 + 审批 park/resume）
上叠四层能力，每层都不破坏既有铁律：

1. **项目目录（workspace）**：会话绑定一个项目目录，成为文件工具与 bash 的默认根和审批默认边界；
   项目级配置集中在项目内 `.harness/` 目录（skills / mcp / hooks / harness.edn）。
2. **工具来源泛化**：注册表从单一内建表变为多来源装配（内建 / MCP / 会话注册），按工具配审批策略与超时；
   一切来源走同一个执行缝与审计行。
3. **Skills**：SKILL.md 目录发现 + 渐进披露——agent 经工具查索引、按需读正文，不污染冻结的 system prompt。
4. **MCP 客户端**：stdio / HTTP 两种 transport，server 工具桥接进注册表（`mcp__<server>__<tool>`）；
   elicitation 复用既有审批 park/resume 通道。
5. **jsonl 会话恢复**：dev 侧 replay 晋升为一等能力——服务端从日志重建完整对话交还客户端，UI 可列出并恢复历史会话。
6. **Hook 引擎**：26 个 hook 点全部是引擎里的数据（名字/时机/payload/语义），外部命令形态执行；
   随各自触发子系统分批接线。

## User Stories

### 项目目录

1. 作为用户，我想在 UI 里选择或输入一个项目目录并开始会话，agent 的文件与 shell 操作默认发生在该项目内。
2. 作为用户，我想让 read/write/edit 的相对路径自动解析到当前项目目录。
3. 作为用户，我想让 bash 以项目目录为 cwd 执行。
4. 作为用户，我想在工具要访问项目目录与配置家之外的路径时被要求人工审批（默认边界，可配）。
5. 作为用户，我想把会话切换到另一个项目目录，并让 agent 与 hook 感知到这次切换（CwdChanged）。
6. 作为用户，我想让项目级配置（skills / mcp / hooks / harness.edn）从项目的 `.harness/` 目录加载，跟随项目走。
7. 作为作者，我想让会话与项目目录的绑定在 jsonl 里可见（可审计）。
8. 作为用户，我想让项目配置覆盖用户级同名配置，且加载失败时指名是哪个文件坏了。

### 工具系统

9. 作为作者，我想把工具注册表从「内建硬编码」泛化为「多来源装配」（内建 / MCP / 会话注册），新增能力不改内核。
10. 作为用户，我想按工具或按来源配置审批策略（总是审批 / 从不 / 按 matcher）。
11. 作为用户，我想给工具执行配超时，挂死的工具不会拖死整个 run。
12. 作为作者，我想让 MCP 工具、会话注册工具与内建工具走完全相同的执行缝、审计行与禁用语义。
13. 作为用户，我想在 UI 工具面板看到当前会话的全部有效工具及其来源。
14. 作为用户，我想在会话里禁用/启用任意工具（沿用 tool-toggles 语义：工具永不离工具表）。
15. 作为作者，我想让工具定义带上 `:source` 字段，出问题能追溯到来源。

### Skills

16. 作为用户，我想把一个 skill 放进项目 `.harness/skills/<name>/SKILL.md`，agent 就能发现并使用它。
17. 作为用户，我想让 agent 先看到 skill 索引（名称 + 描述），按需读正文，而不是全部塞进上下文。
18. 作为用户，我想让 skill 支持用户级（配置家）与项目级两层，项目级覆盖同名用户级。
19. 作为作者，我想让 skill 索引不进 system prompt（冻结前缀不可破坏），经工具按需发现。
20. 作为用户，我想在 UI 里浏览可用 skills 及其来源层级。
21. 作为用户，我想让格式坏掉的 SKILL.md 被跳过并指名，而不是让整个 skills 目录不可用。

### MCP

22. 作为用户，我想在项目 `.harness/mcp.edn` 里声明 MCP 服务器（stdio 命令或 HTTP URL）。
23. 作为用户，我想让 MCP 服务器的工具自动出现在 agent 工具表里（`mcp__<server>__<tool>`）。
24. 作为用户，我想让 MCP 工具调用走与其他工具相同的审批/禁用/审计路径。
25. 作为用户，我想在 UI 里看到 MCP 服务器连接状态与工具清单，并能启停。
26. 作为用户，我想让 MCP 服务器崩溃后可重连而不重启 harness。
27. 作为用户，我想让 MCP 服务器在工具调用中请求用户输入（elicitation）时，run park、UI 弹出表单、响应经 resume 回传。
28. 作为作者，我想让 elicitation 复用现有 interrupt/park 机制，而不是新发明一条恢复通道。
29. 作为用户，我想让连不上的 MCP 服务器被指名报错，其余服务器照常工作。

### jsonl 会话恢复

30. 作为用户，我想在 UI 里看到历史会话列表（从日志目录扫描）。
31. 作为用户，我想点开一个历史会话，服务端从 jsonl 重建完整对话（reasoning 与 tool calls 都在）并继续聊。
32. 作为用户，我想让恢复后的对话交还给我（客户端）持有，之后照常走 AG-UI——服务端不因此变成会话状态的权威。
33. 作为用户，我想让截断/损坏的日志被拒绝恢复并指明原因，而不是拿到半截对话。
34. 作为用户，我想让会话列表显示每个 thread 的最后活动时间与规模。
35. 作为用户，我想让恢复动作本身可审计（落 jsonl）。

### Hooks：引擎

36. 作为用户，我想用 EDN 声明 hooks：hook 点 → `[{matcher, command, timeout}]`，项目级与用户级合并。
37. 作为用户，我想让 hook 命令从 stdin 拿 JSON payload，退出码 0 = 放行、2 = 阻断（stderr 回喂模型）。
38. 作为用户，我想让 PreToolUse 能阻断一次工具调用，阻断原因作为工具结果回给模型（走既有 veto 路径）。
39. 作为用户，我想让 UserPromptSubmit 能阻断一次提交，prompt 根本不进 run。
40. 作为用户，我想让 PermissionRequest hook 能代答审批（自动批准/拒绝），不必每次都弹给真人。
41. 作为用户，我想让 PermissionDenied hook 的输出能带 `{retry: true}` 语义告知模型可重试。
42. 作为作者，我想让 hook 引擎是唯一的：新增 hook 点 = 一条数据，不是一段新代码。
43. 作为用户，我想让 hook 超时与错误不炸 run——门禁型 hook 的失败语义逐点定义（fail-open 或 fail-closed），信息型静默记录。
44. 作为作者，我想让每次 hook 触发落一条 jsonl 审计行（`hook/<point>`）。

### Hooks：随子系统交付的点位

45. 作为用户，我想让被监视文件变更时收到 FileChanged hook（matcher 指定文件名）。
46. 作为用户，我想让 MCP elicitation 请求与用户响应分别触发 Elicitation / ElicitationResult hook。
47. 作为用户，我想让上下文压缩前后分别触发 PreCompact / PostCompact hook。
48. 作为用户，我想让子代理启动/完成触发 SubagentStart / SubagentStop，团队成员将空闲触发 TeammateIdle。
49. 作为用户，我想让任务创建/完成触发 TaskCreated / TaskCompleted。
50. 作为用户，我想让 git worktree 创建/移除触发 WorktreeCreate / WorktreeRemove。

### UI

51. 作为用户，我想让 UI 有项目选择与会话管理入口（列表 / 恢复 / 新建）。
52. 作为用户，我想让 UI 有工具 / skills / MCP / hooks 的管理面板（只读起步）。
53. 作为用户，我想让审批门能展示 elicitation 的表单形态输入。
54. 作为作者，我想让 UI 保持纯 ClojureScript（helix + shadow-cljs + Vite），不引入 TypeScript。

## Implementation Decisions

### 保留的内核骨架（不动）

- 唯一对外协议 AG-UI；消息保持 provider 原始形状；core.async `run-chan` 事件流。
- `harness.tools/run!` 是唯一工具执行缝（三相、post 恒闭合）；审批 park/resume 语义不变。
- jsonl 只 append、内核 run 中永不读自己的日志；system prompt 冻结；「客户端持有会话」是常规路径。
- 工具永不离工具表；关闭 = 调用时拒（:disabled）。

### 新模块

- **harness.project**：thread-id → 项目目录的会话状态；相对路径解析与「出界」判定；`.harness/` 项目配置装配
  （项目级覆盖用户级，逐文件合并）。文件工具与 bash 重根到项目目录；出界访问默认 park 审批（审批仍是流程约定，
  不是安全边界——哲学不变）。
- **harness.hooks**：**唯一新缝**。hook 点是数据 `{:name .. :when .. :payload .. :gate? ..}`；引擎职责 =
  读合并配置 → matcher → spawn 命令（payload 走 stdin JSON）→ 退出码/stdout 决策 → 超时 → 审计行。
  门禁型（PreToolUse / UserPromptSubmit / PermissionRequest）同步阻塞其触发点；信息型 fire-and-forget 带超时。
- **harness.skills**：两级目录扫描 + frontmatter 解析；两个内建工具 `skills-index` / `skills-read`（渐进披露）。
- **harness.mcp**：客户端生命周期（spawn stdio 进程 / HTTP 连接、健康检查、重连）；工具桥接 = 把 server 的
  tools/list 装配成注册表工具，`:source :mcp`，run fn = 调 server；elicitation 请求 → `park-approval!` 同通道
  （payload 带表单 schema），用户响应 → 回传 server + ElicitationResult hook。
- **harness.replay（晋升）**：dev/harness/replay.clj 的重建逻辑迁入 src/，行为不变（种子 = 第一条 input、
  折叠全部 event 帧、截断拒绝）。新增 http 端点：
  - `GET /api/threads`——扫描日志目录，返回会话列表；
  - `POST /api/threads/<thread-id>/rebuild`——重建消息列表返回给客户端，客户端随后以常规 AG-UI run 继续
    （**重建结果交还客户端持有，服务端不建会话状态权威**）；SessionStart hook 以 source=resume 触发。
- **harness.tools 泛化**：注册表装配器支持多来源；工具定义增加 `:source` 与 `:timeout`；执行缝、审批、禁用、
  审计全部不变。

### 配置形状（schema 级决策）

```edn
;; .harness/hooks.edn（用户级 ~/.clj-harness/hooks.edn 同形，项目级优先合并）
{:pre-tool-use  [{:matcher "bash|write" :command "scripts/gate.sh" :timeout 10000}]
 :user-prompt-submit [{:command "..."}]}

;; .harness/mcp.edn
{:servers {"github" {:command "npx" :args ["-y" "@modelcontextprotocol/server-github"]}
           "remote" {:url "https://example.com/mcp"}}}
```

Hook payload（stdin JSON，字段名对齐 CodeBuddy 习惯）：`{hook, thread_id, project_dir, tool_name?, tool_input?,
prompt?, message?, ...}`。退出码 0 = 放行；2 = 阻断（stderr 回喂模型）；其他非零 = 非阻断错误（按点定义的
fail-open/closed）。stdout 可带 JSON 高级决策（如 PermissionDenied 的 `{"retry": true}`、PermissionRequest 的
代答）。hook 命令经 Git Bash 钉路径 spawn（沿用 bash 工具的 Windows 经验），UTF-8 边界显式。

### Hook 点 × 交付批次（26 个全覆盖）

| Hook | 触发时机 | 门禁 | 批次 | 触发源 |
|---|---|---|---|---|
| SessionStart | 会话开始或恢复（source: new/resume） | 否 | P2 | run 生命周期 |
| UserPromptSubmit | 用户提交、AI 处理前 | 可阻断 | P2 | http 边 |
| PreToolUse | 工具执行前 | 可阻断 | P2 | tools/run! pre 相 |
| PermissionRequest | 审批对话框出现时（可代答） | 可代答 | P2 | tools/run! park |
| PermissionDenied | 调用被拒时（{retry:true} 回喂） | 否 | P2 | veto / disabled 路径 |
| PostToolUse | 工具成功后 | 否 | P2 | tools/run! post 相 |
| PostToolUseFailure | 工具失败后 | 否 | P2 | tools/run! post 相 |
| Stop | AI 完成响应 | 否 | P2 | :run/end |
| StopFailure | 轮次因 API 错误结束 | 否 | P2 | :run/error |
| Notification | 通知发出时（park/完成/错误） | 否 | P2 | 通知点 |
| InstructionsLoaded | 指令文件加载进上下文 | 否 | P2 | AGENTS.md 等装配 |
| ConfigChange | 会话期配置变更 | 否 | P2 | 配置热载 |
| CwdChanged | 项目目录变更 | 否 | P2 | harness.project（P0 提供事件源） |
| SessionEnd | 会话终止 | 否 | P2 | 会话关闭 |
| FileChanged | 监视文件变更（matcher） | 否 | P3 | 文件监视器（P3 新建） |
| Elicitation | MCP 请求用户输入 | 影响 park | P3 | MCP elicitation |
| ElicitationResult | 用户响应后、回传 server 前 | 否 | P3 | MCP elicitation |
| PreCompact | 上下文压缩前 | 否 | P3 | 压缩子系统（P3 新建） |
| PostCompact | 上下文压缩后 | 否 | P3 | 压缩子系统 |
| SubagentStart | 子代理启动 | 否 | P3 | 子代理子系统（P3 新建） |
| SubagentStop | 子代理完成 | 否 | P3 | 子代理子系统 |
| TeammateIdle | 团队成员将空闲 | 否 | P3 | 子代理子系统 |
| TaskCreated | TaskCreate 创建任务 | 否 | P3 | 任务子系统（P3 新建） |
| TaskCompleted | 任务完成 | 否 | P3 | 任务子系统 |
| WorktreeCreate | worktree 创建 | 否 | P3 | worktree 隔离（P3 新建） |
| WorktreeRemove | worktree 移除 | 否 | P3 | worktree 隔离 |

P3 的 hook 点在引擎里**先定义为数据**（无触发源时永不触发），随对应子系统交付接线——引擎不需要为它们改代码。

### jsonl 恢复流程（决策）

恢复 = 重建 + 交还，不是接管：`rebuild` 端点返回重建的完整消息列表 → UI 把它当作客户端持有的历史 →
用户输入下一条消息 → 走常规 AG-UI run。不引入服务端会话状态权威，AG-UI 契约不动，流式路径只有一条。
截断日志（末帧非 RUN_FINISHED/RUN_ERROR）拒绝并指名原因。

### Skills 渐进披露（决策）

索引（名称 + 描述 + 层级）经 `skills-index` 工具按需查询；正文经 `skills-read` 按需读入。二者都不进
system prompt、不进每 run 尾部 context——避免上下文膨胀，也避免破坏冻结前缀。模型何时查 skill 由
prompt.md 指引（这属于 prompt 资产，走其既有变更流程）。

## Testing Decisions

**验证缝（本 spec 的关键决策）——只加一条新缝：**

- **唯一新缝 = `harness.hooks` 的 dispatch 函数**：26 个 hook 点全部经它。测试在 `CLJ_HARNESS_HOME` 隔离下
  声明 hooks.edn，用 stub 命令脚本（可控 exit code / stdout / stderr / 挂起）驱动：放行、阻断回喂、超时、
  崩溃不炸 run、审计行落盘。
- 其余全部复用既有缝：
  - 项目目录 → `tools/run!` 缝（相对路径解析、出界审批、bash cwd）+ harness.home 的配置根隔离先例；
  - 工具泛化 / MCP → 工具执行缝；MCP 用 fake stdio server 脚本（应答 initialize / tools/list / tools/call），
    端到端沿用 http_test 的 `with-server` + fake provider 先例；
  - skills → 工具缝（skills-index / skills-read 的外部行为）；
  - jsonl 恢复 → replay_test 既有先例 + http 边端到端（列表、重建、拒绝截断）。
- 好测试只测外部行为：hook 的效果 = run 是否被阻断、上下文是否变化、审计行是否存在；不窥探进程内部状态。

## Out of Scope

- **四个子系统的深设计**：子代理 / 任务 / 上下文压缩 / worktree 隔离各自独立 spec。本 spec 只保证 hook 引擎
  为其预留数据位（hook 点定义），不预支其实现。
- MCP 的 resources / prompts / sampling（v1 只桥接 tools）。
- 进程级沙箱与硬安全边界（审批仍是流程约定，不是安全边界——既有哲学不变）。
- 多用户 / 鉴权 / 远程部署。
- UI 管理面板的编辑能力（v1 只读 + 手编 EDN）。

## 优先级分层（交付顺序）

**P0 — 工程地基**

1. `harness.project`：项目目录绑定、工具重根、出界审批、`.harness/` 装配（CwdChanged 事件源随之就位）。
2. jsonl 恢复一等公民化：replay 晋升 src + `/api/threads` + `/rebuild` 端点 + UI 会话列表/恢复。

**P1 — 能力面**

3. 工具注册表泛化：多来源装配、`:source`/`:timeout`、按工具审批策略。
4. Skills：发现 + 渐进披露 + 两个内建工具。
5. MCP stdio client：工具桥接 + 生命周期 + 审批接入（HTTP transport 随后）。

**P2 — Hook 引擎**

6. 引擎 + EDN 配置 + 既有事件全接线（表中批次 P2 的 13 个点：SessionStart/End、UserPromptSubmit、PreToolUse、
   PermissionRequest/Denied、PostToolUse/Failure、Stop/StopFailure、Notification、InstructionsLoaded、
   ConfigChange、CwdChanged）+ `hook/<point>` 审计行。

**P3 — 随子系统交付的 hook 点**

7. FileChanged（文件监视器）；
8. Elicitation / ElicitationResult（MCP elicitation park/resume）；
9. PreCompact / PostCompact（压缩子系统）；
10. SubagentStart/Stop + TeammateIdle（子代理）；
11. TaskCreated/TaskCompleted（任务）；
12. WorktreeCreate/Remove（worktree 隔离）。

**依赖链**：P0 是 P1 的前提（skills/mcp 配置都在项目目录里）；P2 的门禁型 hook 在 P1 工具泛化后才有完整对象；
P3 各项彼此独立、可按需插队。

## Further Notes

- **推翻对照**：minimal-kernel 的「明确不做」表中，plugin/extension（= skills + MCP + hooks）、会话恢复、
  项目概念被正式推翻；「客户端持有历史」保留为常规路径，jsonl 恢复是显式逃生门；「上下文压缩」推迟到 P3
  与其 hook 一起立项。
- Hook 命名与语义对齐 CodeBuddy 的 26 点清单，迁移心智零成本。
- Windows 延续既有坑位：hook 命令与 MCP stdio spawn 走 Git Bash 钉路径；全部字节边界显式 UTF-8。
- 每阶段交付自己的 UI 切片（P0 带项目选择与会话列表；P1 带工具/skills/MCP 面板只读版），不设统一的 P4。

### jsonl 契约（随交付补充）

- **`project/bound`**（01 号票，`harness.http` 管理边写入；**04 号票演进为 before/after**）：
  会话绑定项目目录的审计行。payload `{:before <绝对路径|null> :after <绝对路径> :via "http"}`——
  对齐 provider/changed 行的 before→after 风格，首次绑定 before 为 null；`runId` 为 null
  （绑定发生在任何 run 之外）。同一 thread 重复绑定各落一行，append-only 语义下
  **逐行连读即目录变更时间线**；读者以最后一行为准。
  对应端点：`GET /api/project?threadId=..`（未绑定答 `:dir null`，不是错误）、
  `POST /api/project {"threadId","dir"}`（`harness.project/bind!` 先校验目录存在且是目录，
  校验失败的 400 指名报错且不落行；旧绑定值在校验通过后才被覆盖——审计行是它唯一的存身之处）。
  **CwdChanged 事件源（04 号票就位，P2 接线）**：绑定变更点是 hook 表 CwdChanged 行的触发源
  （本表 P2 批次）；事件事实的 payload 形态由 `harness.project/cwd-changed` 纯函数锁定并有测试
  ——`{:hook "CwdChanged" :thread_id .. :project_dir <新目录> :before <旧目录|null>}`，
  snake_case 对齐本 spec 的 hook payload 约定。本票只产事件事实与审计行，不 spawn 命令、
  不落 `hook/` 审计行——那是 hook 引擎（P2）在变更点接线时的职责。
- **`session/rebuilt`**（05 号票，`harness.http` 写入**被重建的日志自身**）：重建动作的
  唯一痕迹。payload `{:messages <重建消息数> :via "http"}`，`runId` 为 null。重建本身
  只读日志。对应端点：`GET /api/threads`（目录扫描，空/缺失目录 → `[]`）与
  `POST /api/threads/<stem>/rebuild`（种子 = 第一条 input、折叠全部 event 帧、context
  带回；截断/坏 JSON 行/无日志指名 400 且不落行）。

## 已验证到什么程度

- 2026-09-13 提交 `0b131f0`（00 号票，预备重构）：harness.opaque 整体并入 harness.memory——结构屏障挡不住 eval 的 var-quote/resolve，屏障改由 prompt.md 的 secrets 纪律条款承担（禁读/禁暴露 api-key、禁 deref `scripted-pins`/`session-overrides`、禁返回 `:api-key`、provider 变更走 session-configure 审批流）。函数名不变，全部调用方 `opaque/*` 改 `mem/*`；`opaque` 私有 `config` 删除，resolve-provider 直用本 ns 的 `config`。全量测试 103 tests / 478 assertions 全绿（与基线持平）。架构铁律相应更新：不再有 memory/opaque 依赖环约束。
- 2026-09-13 提交 `e3950de`（01 号票，项目目录绑定）：`harness.project` 新建（`bind!` 校验目录存在且是目录否则指名抛错，nil 显式解绑，存绝对路径；`resolve-path` 相对重根/绝对直通/未绑定恒等；`binding-for` 即 bash 的 cwd 来源）。read/write/edit 经 `resolve-path` 重根并回报**已解析**路径；bash `:dir` 按需附加；工具描述同步重根语义。`harness.memory/active-project` 自省面（问，不抄副本）。`harness.http` 增管理边：`GET/POST /api/project`，CORS 放行 GET，校验失败的 400 不留痕，`project/bound` 审计线（runId null，契约见上文 jsonl 契约段）。prompt.md 增「你的项目」自省指引。UI：app.cljs 项目面板（输入 + 绑定 + 当前绑定，threadId 读自 agent 实例，CopilotKit 写入），vite build 通过。测试 +11 个 deftest（project 单元 8、tools 接缝 1、http 端到端 2，含真 AG-UI run 相对写落进项目目录）；全量 **114 tests / 532 assertions 全绿**。票 01 已删。
- 2026-09-13 提交 `9433e24`（05 号票，jsonl 重建端点）：frame-applier（`terminal?`/`apply-frames`）晋升 `src/harness/frames`；`harness.replay` 整体 dev→src（含 `resume!`），新增 `threads`（目录扫描列表）与 `rebuild`（种子+全帧折叠+context 带回）；dev/harness/wire 只剩测试工具（frames-from-sse / violations），evals 零变化。管理边新增 `GET /api/threads` 与 `POST /api/threads/<stem>/rebuild`；截断/坏 JSON 行/无日志指名 400；重建在被重建日志上落 `session/rebuilt` 审计线（契约见 jsonl 契约段）。铁律表述随代码落位：内核 run 中永不读日志，重建是 run 外的显式管理动作。测试 +3 deftest（列表单元含空/缺失目录 → `[]`、端点列表+重建+审计线、三种拒绝）；踩坑记录：**with-server 的 pin 名必须等于 run 实际使用的 threadId**（随机 tid 走 config 裸 `:fake` 解析 → 空流，折叠只剩种子）。全量 **117 tests / 563 assertions 全绿**。票 05 已删。
- 2026-09-13 提交 `1b05551`（06 号票，UI 会话面板与恢复）：app.cljs 增会话面板——`GET /api/threads` 列表（stem/最后活动/大小）+ 刷新 + 新建会话；**恢复 = POST rebuild → 只把 `threadId` 与 `messages` 写上 agent 实例**：`AbstractAgent.prepareRunAgentInput` 以 agent 自身 threadId/messages 构造 `RunAgentInput`，续聊因此落回同一日志、服务端保持无会话状态；刻意不走 CopilotKit `setActiveThreadId` 显式线程路径（会牵入 connectAgent 握手与消息清空规则）。截断/损坏指名 400 内联展示、面板存活可换会话；恢复后列表自动重列（审计线落盘后重读）；project-panel 增订 `OnMessagesChanged` 使恢复后重读绑定。README「会话列表与重建」段补 UI 侧契约。**实测**（真实 8080 + playwright + 回显式 stub LLM + `harness.fake` 经真实 HTTP 边生成种子日志）：恢复种子会话 → 历史 5 条全呈现（reasoning 常展开卡、read 工具卡 complete、assistant 文本）；续聊 → stub 自报收到 **6 条消息**并回显新输入，日志证据：续写同一 jsonl，最后 input 行 messages=6 `[user,reasoning,assistant,tool,assistant,user]`——重建历史全量回传即「模型可见前文」；两个 run 帧序各 RUN_STARTED→RUN_FINISHED 一次（terminal-once，violations 等价检查）；`session/rebuilt` 审计线 ×2；截断（"ends mid-run…refused"）与损坏（"line 4 …not valid JSON"）在面板内联指名报错，随后换会话（seed-small）成功；新建会话走新 threadId 新日志。console 仅 favicon 404 与预期 400。**顺带修复**：replay_test 列表测试的 ldir 清理（`io/delete-file` 删不掉非空目录，tmpdir 跨 JVM 残留 t-list-* → 第二次跑套件空目录断言失败）改递归删除后重建，连续两轮全量 **117 tests / 563 assertions 全绿**（fix 提交 `898a92f`）。票 06 已删。
- 2026-09-13 提交 `50a011d`（02 号票，出界审批）：`harness.project/out-of-bounds?` 回答围栏的包含问题——**绑定会话**的解析路径须落在项目目录或配置家之内（canonical 前缀 + 分隔符边界：`C:\proj` 不含 `C:\project2`；`..` 段、大小写、符号链接在 canonical 化后收敛为同一答案；不可 canonicalize 的路径 = 无法证明在内 = 出界，成本即一次审批）。**未绑定恒 false**——01 号票「未绑定即恒等」回归保证的围栏面。read/write/edit 带 `:fence-paths` 标记；`approval-required?` 升格为 `approval-reason`（`:tool-declares` / `:session-asks` / `:out-of-bounds`，每 transit 只算一次），reason 随 parked record（内存 registry + run! 返回的 `:parked` map）——**interrupt wire 形态不变**（仍只取 id/tool-call-id/name/args 四键 facts），jsonl 审计线零变化。人工批准 = 人 override 围栏（resume 时路径仍出界，verdict 仍执行，`:approved` 审计）；否决走既有 veto 语义回喂 payload reason。**边界声明（票收项 5）**：bash 刻意不动——只约束 cwd 在项目目录、命令内容永不判定，`cat /c/...`、管道、`cd ..` 皆可出界；这是 spec 明示接受的已知逃逸面，不是遗漏——审批是流程约定不是安全边界，既有哲学不变。prompt.md 项目段一句声明围栏与配置家豁免（config/providers/.env 语义不因出界误伤，读自家配置被显式允许）。测试 +5 deftest（project 单元：未绑定回归、项目内相对/绝对、配置家含根自身、出界与 `..` 逃逸、同形兄弟目录边界、解绑即关围栏；缝级沿用 park → `decide-approval!`/resume 模式：出界 park 且 parked record 带 `:reason :out-of-bounds`、项目内相对+绝对+配置家三条不 park 且读到真实 config、批准执行落盘、否决回喂含 "stays inside the project"、未绑定线程端到端不 park）。全量 **122 tests / 600 assertions**，连续两轮全绿。踩坑：**Clojure 1.12.6 默认导入已不含 java.io.File**（实测 ns-imports 96 个无 File），裸 `File/separator` 报 "No such namespace: File"，须显式 `:import`；另 `under?` 曾把 `cd` 重绑成带尾分隔符形态致 `(= cp cd)` 恒假（根目录自身被判出界），拆开比较修复。票 02 已删。
- 2026-09-13 提交 `6df4cf8`（03 号票，`.harness/harness.edn` 装配）：`harness.project/harness-config`（双 arity，无绑定只答用户级）两级装配——配置家 `harness.edn`（用户级）+ 绑定项目 `.harness/harness.edn`（项目级），每次现读（config.edn 纪律），顶层浅合并、项目级**整键替换**（项目提到 `:approval` 就整个换掉用户的，不深合并不做并集——合并语义就此写死）。缺失 = `{}` 不报错（含 `.harness` 目录在而文件缺）；坏文件（EDN 语法坏或非 map）指名绝对路径硬失败（`:invalid-edn` / `:not-a-map`），不静默回退。首个真实消费者：`out-of-bounds?` 读装配——`{:allow [..]}` 相对项目根解析（走 resolve-path 同语义）加进允许集；`{:strict true}` 把项目目录本身移出允许集（项目内也 park），**配置家永不收紧**（strict 只作用项目目录——config home 是 harness 自身的地盘，不是项目的）。skills/mcp/hooks 子目录留给 P1/P2，本装配器只认 harness.edn。测试 +6 deftest（project 单元 3：两级装配与整键替换、坏文件指名、围栏消费 allow/strict/现读；approval 缝级 3：allow 免审出邻居、strict 项目内 park 带 `:reason :out-of-bounds`、项目级整体替换用户级）。**测试卫生**：project_test 增 `:each` fixture 前后双删用户级与项目级 harness.edn（tmpdir 跨 JVM 残留会静默移动下次运行的围栏），fence-rig 先 rm-r! 再重建。全量 **128 tests / 625 assertions**，连续两轮全绿。**世纪坑（本轮元凶，探针钉死）**：clojure.test 的 fixture 形状是 `(fn [f] ... (f) ...)`——直接在 f 上执行前后件；曾把整个 body 包进多余一层 `(fn [])`，compose-fixtures 调 fixture 时它只返回闭包从不调用，test-var 永不执行——**整个 ns 的测试静默消失**（:test 0、无异常无输出）；1.12.6 的 use-fixtures/defmulti/test-vars 完全无辜（each-fixtures 在 ns meta 在位、12 个 `:test` var 全在、手动 join-fixtures 链也吞 body——三探针定位到形状）。README 项目目录段补围栏与装配契约。票 03 已删。
- 2026-09-13 提交 `f8f6474`（04 号票，会话切换项目目录）：重新绑定走**同一端点的普通调用**（`POST /api/project`）——路径解析立即切到新目录，`project/bound` 审计行演进为 `{:before <绝对路径|null> :after <绝对路径> :via "http"}`（对齐 provider/changed 行的 before→after 风格，首次绑定 before 为 null）；**旧绑定值在 bind! 之前读**——bind! 覆盖后审计行是它唯一的存身之处。逐行连读即目录变更时间线。**CwdChanged 事件源就位**：`harness.project/cwd-changed` 纯函数锁定 payload `{:hook "CwdChanged" :thread_id .. :project_dir <新目录> :before <旧目录|null>}`（snake_case 对齐 hook payload 约定），单元测试锁形态防漂移，P2 hook 引擎在变更点接线直接消费；本票不 spawn 命令、不落 `hook/` 审计行。**UI 零改动**：切换 = 面板既有 POST，成功后重读绑定显示。测试 +2 deftest（http 端到端：首次绑定 before nil → rebind 后 GET 显示新绑定 → 真 AG-UI run 相对写**只落新目录** → 日志两行 before/after 形态；project 单元：payload 形状含首绑 before nil），既有 1 断言 `:dir`→`:after`。全量 **130 tests / 642 assertions**，连续两轮全绿。坑强化：tmpdir 带上轮 JVM 的 e2e.txt 残留，`io/delete-file` 静默拒删非空目录 → 「不在旧目录」断言被残留炸掉——fixture 改 file-seq 深→浅递归清理。票 04 已删。
