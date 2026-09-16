# spec: hook 引擎（eval 收敛为它的控制面，memory 解散回各模块）

两件事同时发生：**运行期扩展机制从「eval 随便改」收窄为「hook 引擎」**，以及**为 eval 的读自省而立
的单一自省面（`harness.memory`）随之解散**，各段落回它原来的模块。

## 决策

- **hook 的形态 = 进程内引擎 + 少数真实触发点（2026-09-14，牛总裁定）。** 26 个 hook 点全部**登记为数据**
  （名字 / 时机 / payload 形态 / 是否门禁），没有触发源的点永不触发；只接线四个：SessionStart、
  PreToolUse、PostToolUse、Stop。命令沿用 general-harness 已定的形态：经钉住的 Git Bash spawn，
  payload 走 **stdin JSON**，退出码 **0 放行 / 2 阻断（stderr 回喂模型）/ 其他非零** 按点定的
  fail-open 或 fail-closed；触发落一条 **`hook/<point>` 审计行**。形状与名字沿用该 spec，不另立一套。
- **eval 收敛为 hook 引擎的运行期控制面（牛总裁定）。** 它能做的事被重新表述为两件：**动态改一个 hook**
  （本会话新增一条 / 覆盖一条同名声明）与**动态启动关闭一个 hook**。两条正交轴，与工具表同一套词汇与语义
  （presence 归 register/unregister，availability 归 disable/enable；**关闭不是隐藏**——被关的条目仍在表里、
  只是永不触发，agent 能读到它并自己打开）。
- **工具自我扩展保留为内部 API，退出的只是"对模型宣传"（牛总裁定）。** `session-register!` /
  `session-unregister!` / `session-disable!` / `session-enable!` / `session-require-approval!` 不动、
  不删、测试继续跑；变的是 `prompt.md` 的 **Self-extension 与 Session tools 两段退场**。
- **诚实声明（写进 spec 与 prompt.md）：eval 仍然能执行任意 Clojure。** "仅限于这些功能"约束的是它的
  **定位与文档承诺**，不是语言能力。`prompt.md` 的 secrets 纪律条款（api-key 禁读/禁暴露/禁返回）是这份
  收敛里**唯一不动的**部分——它从来不是为了自省，而是为了一条永远不会因为定位改变而失效的规矩。
- **memory 解散，各回各家（牛总裁定）。** `harness.memory` 这个"单一可自省面"是为 eval 的**读自省**立的；
  读自省在 `762b0d2` 已撤（只留"问现在"的两问一答），写自我扩展在本特征收敛为 hook——面本身没有存在理由。

  | 段落 | 落点 | 依据 |
  |---|---|---|
  | 冻结 prompt / `reset-prompt!` | `harness.llm` | `1d92d5e` 之前它就在这里 |
  | 工具表 + 会话 overlay + 审批状态 | `harness.tools` | 之前工具表就在这里；overlay 与审批是执行缝的状态 |
  | config.edn 解析、api-key、三档解析、provider 时间线 outbox | **`harness.providers`（新名）** | 它就是"本进程服务在哪个 provider 上"的答案 |
  | `log-path` | `harness.home` | 它就是 home 路径的派生 |
  | `active-project` | `harness.project` | 绑定住在那里 |
  | `active-provider` | `harness.providers` | 它是 provider 解析的回答 |
  | init 记账 | http 边（私有） | 唯一读写者就是写入者 |
  | `harness.memory` | 删除 | 没有剩下的东西 |

- **`harness.models` 并入 `harness.providers`（2026-09-14，牛总裁定）。** 目录（厂商 → model 表的形状与校验）
  与"谁赢"（三档折叠、密钥、会话级改动）讲的是同一件事，合成一个 ns 之后 provider 这件事在一个文件里讲完。

  | 段落 | 落点 |
  |---|---|
  | 目录：厂商 → model 表、选择形状、三档折叠、装配、wire | `harness.providers`（原样搬入） |
  | 三档谁赢、api-key、pin 与 session override、时间线 outbox | 同上（memory 那段搬进来） |

  **代价由 03 号票承担并写进 docstring**：两个 ns 时"读 model 目录的人不必经过密钥"由模块边界表达
  ——是**表达**，不是强制，eval 用 var-quote 与 resolve 能到任何 var，那条边界从 `0b131f0` 起就只是写下来的
  规矩。并成一个 ns 之后读目录形状的人也会路过 `.env` 解析那一段，所以 docstring 要分节，而"api-key 只在
  `resolve-provider` 的返回里挂上"这条纪律继续由测试守着。**这不算新债，是把原本就只是"写下来的规矩"的
  那条边界，从两处落成一处的说明。**

  **名字是 `harness.providers`，不是退回去的 `opaque`。** `opaque` 是与 `memory` 对偶造出来的
  ——"不可自省的那一半"——而那个对偶随 memory 一起没了。留下的是它真正装着的东西：
  本进程、本会话服务在哪个 provider 上（哪一档赢、密钥从哪来、进程侧有哪些会话级改动在排队）。
- **审批成为 hook 引擎的一个点（牛总裁定）。** 工具的 pre 相决策统一到 PreToolUse：一次调用有三个结局——
  **放行** / **阻断**（理由回喂模型，run 继续）/ **悬置**（等人点头，即今天的 park）。PermissionRequest 点
  让 hook（脚本或人）代答悬置的调用。**AG-UI 的 interrupt 帧形状不变**，批准/否决的客户端路径不动；
  `:requires-approval` 与 `session-require-approval!` 不删，收窄为"给这个工具装一条悬置型判定"的两种来源。

## 非目标

- 不接线其余 22 个 hook 点的触发源（登记为数据即止；无触发源 = 永不触发，这不是遗漏而是设计）。
- 不碰 MCP / skills / 子代理 / 任务 / 上下文压缩 / worktree（general-harness 的 P1 与 P3）。
- 不改内核事件种类、不改 AG-UI 帧形状、不改既有 JSONL 行的 schema（新增的只有 `hook/<point>` 一种行）。
- 不改 eval 工具自身的实现：仍是 in-process、常驻 `harness.user`、同时捕获 `*out*` 与返回值、
  `pr-str` 截断 8000、不设超时。
- 不做 hook 定义落盘、不做自动重放历史 eval、不引入任何常驻的新真相源。
- 不做真实安全围栏：hook 的阻断与审批一样是流程约定，不是边界（本仓 bash 已是任意代码执行）。

## 验收主线

离线全量 `harness.test-runner` 全绿。**搬迁票（01–06）断言数持平**（基线 189 tests / 930 assertions），
行为逐字节不变——搬家的验收条件就是"什么都没变"；**新能力票（07–13）只增**。

**落地后修正：没有一条既有断言被改写。** 立票时预计 12（审批成为第三种出口）要改 `approval_test`
的既有断言，并在票面要求"改写清单逐条列明"。实际实现下来**一条都不需要改**：既有审批用例走的是
`loop/run!` 与 `run!` 缝，而这一票只在「已经决定要 park」之后插了一问（PermissionRequest 是否代答），
没有 hook 声明的会话里那一问恒无答案、调用照旧 park。`approval_test` 的 20 个 deftest 因此只被改了
命名空间指向（`mem/` → `tools/`），**断言内容一字未动**——这是"先问规则、规则不答就问人"这个设计
的直接结果，也是比票面预期更强的证据。

net 断言：189 / 930 → 232 tests / 1128 assertions（+43 tests / +198 assertions）。

## 跨特性前置 / 推翻对照

- general-harness 的 **P2（Hook 引擎）此前只在纸上**；本特征是它的最小真实落地。hook 点数据形状、
  payload 字段约定（snake_case）、退出码语义、`hook/<point>` 审计行名字全部沿用该 spec。
  `harness.project/cwd-changed` 那个已锁定的 CwdChanged 事件源**本特征不接线**（它是 P2 清单里的另一个点，
  留着给接线它的那一票）。
- **推翻 `eval-introspection` 的"harness.memory 是单一可自省面"** —— 那个面是为 eval 的读自省立的，
  而 eval 的定位已经收敛到 hook。`762b0d2` 撤掉的只是"抄下来的副本"，本特征撤掉的是面本身。
- **不推翻 `tool-toggles`**：四个会话级入口与两条正交轴的语义原样保留，只是从"对模型宣传的能力"降级为
  内部 API。`tool-toggles` 对 `tools-lifecycle` 的那次推翻（remove 单调）也不受影响。
- **不推翻 `pre-tool-approval` 的 park/resume 语义**，只换触发它的判定点（`pre-tool-approval` 自己记的
  那条"eval 侧没有可用的伪造入口"仍然成立）。

## 交付顺序

Phase A（01–06）是 prefactor：先把各段送回原模块、并把 provider 的两半并成一个 ns，行为零变化；
Phase B（07–11）引擎与接线；Phase C（12）审批归位；Phase D（13）定位与文档收口。
Phase A 先落地，是为了让 hook 的接线段（10/11/12 都要改执行缝）与搬家票不同时改同一批调用点。

## 状态

- 01–13：**全部完成**（2026-09-14，一次落地；票已按仓库约定删除，记录归本 spec 与 git）

## 已验证到什么程度（2026-09-14）

**全量：232 tests / 1128 assertions 全绿**（基线 189/930），连跑三轮一致。票已删，逐票记录如下。

- **01–06（prefactor，行为零变化）**：`prompt.md` 的冻结槽与 `prompt` / `reset-prompt!` 回 `harness.llm`；`harness.models` 并入 `harness.providers`（目录那一半，函数代码一字未改）；provider 的解析与密钥、pin 与 override 两槽、三档折叠四个入口、`active-provider`、时间线 outbox 全部入驻；init 记账搬去它唯一的读写者（http 边，私有）；工具表与四个会话级入口回 `harness.tools`（`register!` 同时收成模块内——它此后只有本 ns 用，对外只有会话级四个入口）；审批的回执行缝所在的模块；`log-path` 回 `harness.home`、项目绑定回 `harness.project`，**`harness.memory` 整个文件删除，全仓零引用**。
  - **合并的代价如实记录**：原先"读 model 目录的人不必经过密钥"由模块划分表达——是**表达**不是强制（eval 的 var-quote 与 resolve 能到任何 var，那条边界从 `0b131f0` 起就只是写下来的规矩）。并成一个 ns 后读目录形状的人也会路过 `.env` 解析那一段，所以 docstring 分节说明，而"api-key 只在 `resolve-provider` 的返回里挂上、自省回答任何深度都不出现"继续由测试守着。**这不是新欠的债，是把原本就只是写下来的规矩的那条边界，从两处落成一处的说明。**
  - 顺带修的：`:disabled` 的自助信息原先叫模型去调一个已经搬走了的入口——现在拼出的是本 ns 的完整调用形态（含 thread-id 参数），照抄能跑。
- **07（点表与装配）**：26 个点登记为数据（名字 / 时机 / payload / 匹配对象 / 是否门禁 / **失败语义 `:on-error`**）。EDN 键由 payload 名字派生，两种拼法只在一处绑起来。两级装配（配置家叠项目 `.harness/`，浅合并、项目级整键替换）。逐字段校验：`:command` 必填非空、`:timeout` 正毫秒整数、字段拼错指名并列出认识的字段、`:matcher` 只对有匹配对象的点开放（其余拒绝并告诉它哪些点接受）、**坏正则在加载时就失败**（不是等到某次触发静默不匹配）。
- **08（dispatch）**：新 ns `harness.shell` 收拢"spawn 哪个 shell"（Windows 上钉 Git Bash 的坑是机器的属性，不是调用方的；bash 工具改为经它执行，行为不变）。契约三条落地：0 放行 / 2 阻断（stderr 即理由）/ 其他按点定的 `:on-error`；**超时与起不来的命令也走这一条**。payload 走 stdin JSON（真实值，不是字符串化——JSON 编码不了的才退回印出来的形态）。审计行每次真触发一行；**没声明时一行都不写**。多条匹配时全部执行、**第一条 block 获胜**（顺序从 id 推，因为"第一条"是有意义的）。
- **09（会话级 overlay）**：两条正交轴（presence / availability），四个入口。`effective-hooks` 从"点 → 向量"改成 **id → 声明**（`:id` / `:point` / `:source` / `:disabled?`）——会话要能读自己的表并据此行动，就得有个**能指名**的东西。**关闭不是隐藏**：被关的仍在表里、只是不触发；`:disabled` 的声明在 `declarations-at`（运行视图）里被滤掉，而 `effective-hooks`（自省视图）留着它们。
  - 端到端走**真实 eval tool-call**：模型写 Clojure 长出 hook → 触发（命令真跑、文件真写）→ 关掉（不再触发、文件没写）→ 另一个会话看不见也改不动 → 再打开 → 恢复触发 → 读回自己的表。
- **10–12（接线）**：sink 的绑定点是核心决策——`harness.hooks.dispatch/*sink*` 由 **http 边**在 run 范围内 binding，它下面的每个调用方（离线工具、replay、直接驱动内核的测试）都留 nil，**一个 hook 都不跑**。今天真接线的五个点：三个观察者（SessionStart / PostToolUse / Stop）+ 两个门禁（PreToolUse / PermissionRequest）。
  - **一次调用三个出口**（放行 / 阻断 / 悬置），次序写死在 `run!` 的 cond 里：**关闭 → 缺参数 → 审批规则 → PreToolUse**。关闭的工具**不过门禁**（"关掉"要真的一点活都不干，包括不问 hook——有测试守着：连审计行都不出现、命令脚本根本没跑）；缺参数的也不问（命令自己会拒，问了只是把理由搅浑）。
  - **悬置的调用先问规则、再问人**：先取 parked record 上已有的决定（resume 路径），没有才触发 PermissionRequest；hook 在 stdout 给 `{"decision":"approve"|"deny"}` 就代答了，效力与人的答案相同；**声明了但没给答案就照旧 park**——没有 hook 的会话因此与 hook 存在之前逐字节相同（有专门用例）。`dispatch` 只在退出 0、且 stdout 是 JSON **对象**时才认它是答案（一个 hook 打印一行日志不该被读成做了决定）。
  - **帧形状一个字节都没动**：悬置仍走既有 park/resume 通道（不发 `:tool/result`、不写 tool 消息、run 以 interrupt 收尾）。新增 outcome **`:hook-blocked`** 写进了 `harness.event` 的 docstring——不允许悄悄多一个没人记录的值；工具结果按 veto 的形状回喂，但**说清是谁说不**（读到"人工否决"的模型会不敢再要东西，读到"hook 拦的"的模型知道去看规则）。
- **13（收口）**：`prompt.md` 的 Self-extension 与 Session tools 两段退场，换成一节 hook 自助说明。**诚实条款**：eval 是进程内的 Clojure、**没有沙箱**，"仅限 hook"约束的是**定位与承诺**，不是语言能力；工具表那四个入口在代码里保留（测试、边、e2e server 仍在用），只是不再对模型宣传；**secrets 纪律一字不减**，并点明它不随其他任何东西一起变松。

### 后续：点表多了一行，来源从两层变三层（2026-09-16，`system-prompt-blocks`）

上面那些是 2026-09-14 落地时的记录，**不改写**。这一笔是后来的改动，写在这里免得两份说法打架。

- **第 27 个点 `SystemPrompt` / `:system-prompt`**：`payload #{}`、`:matches nil`、`:gate? true`、
  `:on-error :block`，外加点表里**第一格 `:stdout :content`**。时机是「一次 run 的 system 消息正在被
  组装、模型看到它之前」。它与其他门禁点只差那一格：**匹配到的声明全部跑、全部追加**
  （不是第一条 block 获胜——一条 hook 不该把另一条的文本吃掉），退出 0 的 stdout 就是内容，
  dispatch 收进有序的 `:blocks`；空输出 = 这一条什么都不说；退出 2 = **这次 run 不开始**
  （stderr 逐字是理由，客户端收 RUN_ERROR）。**其它点的返回与审计行逐字节不变**。今天 P2 接线点因此是
  **7 个**：原来的六个加上它。
- **来源从两层变三层**。多了 `:built-in`：内核自己注册的 `:run` 行，**排在最前**（内建 → 文件 → 会话）。
  并且一条声明现在**说它跑什么、且只说一样**：`:command`（非空字符串）或 `:run`（可调用）恰好之一；
  两个都没有、两个都给都指名报错。**`hooks.edn` 里写 `:run` 被指名拒绝**（文件里放不了函数），
  `:timeout` 配 `:run` 也拒绝（`run` 没有 spawn 可限时）。
  **`verdict-of` 之下的东西一个字没改**：退出码语义、`:on-error`、first-block-wins、审计行。
- **内建三条行**（`builtin:tools` / `builtin:project` / `builtin:provider`）在 `SystemPrompt` 点上，
  由**新 ns `harness.system-prompt`** 注册——它要 `tools` / `project` / `providers`，所以声明不进
  `harness.hooks`（`project` 已 require `preamble`，而 system 半要 `tools`，会成环）。
- **`prompt.md` 因此换了定位**：从「整份冻结的 system prompt」变成「system 消息的冻结**开头**」。
  与任何会话无关的话留下（身份、hook 自助、secrets 纪律、「其余自己读」），**本会话的事实**
  （工具集合、绑定目录、provider 档）由 hook 每次 run 现算追加——宁可付一次冷前缀，也不让 system
  消息说一件已经不成立的事。
- **两处 prompt.md 之外的连带**，如实记：`harness.llm/prompt` 的措辞从「system prompt」改成
  「system 消息的开头」；`replay/history` 与 `harness.http` 改调 `system-prompt/assemble`
  （没有 sink 的调用方仍拿到逐字节相同的 `prompt.md`）。
- 数：**607 tests / 9774 assertions 全绿**（基线 `main` @ `63869d2`，569 / 9574）。


### 实现时撞出来的三件事（票面没写，记在这里）

1. **日志的 append 需要一把锁。** 多数写入来自 run 的单个消费线程，但 hook 在它自己的点上触发（PostToolUse 跑在那次调用的线程上），两条线可能同时在飞——而**半行不是更短的记录，是一个坏掉的文件**。加锁是让写入者原有的保证继续成立，而不是让每个调用方都知道这件事。
2. **SessionStart 与 provider init 的"只写一次"必须分开记。** 原先共用一个 atom，但 init **行**在测试装了 pin 时不写（没有可记的解析结果），而会话确实开始了。一个 atom 一个事实。
3. **测试之间会串 hook。** `hooks_wired_test` 最初没有 each-fixture，最后一张用例写下的 `hooks.edn` 被进程里后面所有测试触发——`http_test` 那个 park 用例的调用，被它从没声明过的规则批准了。补 each-fixture 前后各擦一次（`project_test` 对 `harness.edn` 是同一条纪律）。**这是"配置在磁盘上 + 测试同进程"这个组合的通病，不是这一次的偶然。**

### 与既有 spec 的关系

- **推翻 `eval-introspection` 的"harness.memory 是单一可自省面"**：那个面是为 eval 的**读**自省立的，读自省在 `762b0d2` 已撤，写自我扩展在本特征收敛为 hook。面本身因此没有存在理由。
- **不改 `tool-toggles`**：四个会话级入口与两条正交轴的语义原样保留，只是从"对模型宣传的能力"降级为内部 API；它对 `tools-lifecycle` 的那次推翻（remove 单调）不受影响。
- **不改 `pre-tool-approval` 的 park/resume 语义**，只换触发它的判定点；`pre-tool-approval` 自己记的那条"eval 侧没有可用的伪造入口"仍然成立。
- **general-harness 的 P2 引擎**：本特征是它的最小真实落地。点表形状、payload 字段约定（snake_case）、退出码语义、`hook/<point>` 审计行名字全部沿用该 spec；`harness.project/cwd-changed` 那个已锁定的 CwdChanged 事件源**本特征不接线**（它是 P2 清单里的另一个点）。

## 票清单（`.scratch/hook-engine/issues/`）

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 冻结 prompt 回 `harness.llm` | — | prompt 与冻结纪律回 provider 层，调用方与文档改指 |
| 02 | `harness.models` 并入 `harness.providers` | — | 目录那半先并好，03 才只是往里搬；旧 ns 与单数测试名一起退场 |
| 03 | provider 的解析、密钥与进程侧状态回 `harness.providers` | 02 | 三档解析 / api-key / pin 与 override / 时间线 outbox 入驻，init 记账归 http 边 |
| 04 | 工具表与会话 overlay 回 `harness.tools` | — | 注册表 + 四个会话级入口 + `*thread-id*` 回执行缝 |
| 05 | 审批状态回执行缝所在的模块 | — | park / decide / 一次性取用回 `harness.tools` |
| 06 | 收口：自省面归位并删除 `harness.memory` | 01, 03, 04, 05 | log-path 归 home、active-project 归 project、旧 ns 删除、README 模块图 |
| 07 | hook 点表与 hooks.edn 的两级装配 | 06 | 26 点登记为数据 + 两级装配 + 未知字段指名报错 |
| 08 | hook dispatch：spawn、退出码、超时、审计行 | 07 | stdin JSON payload、0/2/其他、超时不炸 run、`hook/<point>` 审计行、无声明即 no-op |
| 09 | 会话级 hook 的增删改与启停 | 08 | eval 的新面：presence 与 availability 两条轴、关闭不是隐藏、跨会话隔离 |
| 10 | SessionStart / PostToolUse / Stop 三个点接线 | 08 | 三个信息型点真的触发，不改帧序列 |
| 11 | PreToolUse 门禁：阻断与回喂 | 10 | 第一处门禁：退出码 2 不执行、stderr 回喂、run 继续 |
| 12 | 审批成为 PreToolUse 的一个结局 + PermissionRequest 代答 | 11 | 放行 / 阻断 / 悬置三出口；interrupt 帧形状不变 |
| 13 | eval 的定位与文档收口 | 09, 12 | prompt.md 与 README 重写、诚实条款、工具面内部保留 |

首次实现建议走 01–06（搬家与合并，行为零变化）→ 07 08 10 11（引擎与接线）→ 09 12（新面）→ 13（收口）；
09 与 10 都只依赖 08，可并行。01 / 02 / 04 / 05 四张互不相干，可以同时开工。
