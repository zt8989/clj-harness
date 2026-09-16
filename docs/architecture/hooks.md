# Hook 引擎

## 形态

一个 hook = 配置里的一行声明，指向**一条命令**——或者，在能放函数的那两个来源里，指向**一个进程内的函数**：

```edn
{:pre-tool-use      [{:matcher "bash|write" :command "scripts/gate.sh" :timeout 10000}]
 :permission-request [{:command "scripts/auto-approve.sh"}]
 :system-prompt     [{:command "scripts/print-tool-policy.sh"}]
 :stop              [{:command "scripts/notify.sh"}]}
```

声明里有四个键：`:matcher`（正则，与**该点声明的那个 payload 字段**比较）、`:timeout`（毫秒）、
`:run`（可调用的函数），以及 `:command`（非空字符串）。**一条声明说它跑什么，且只说一样**：恰好
`:command` 或 `:run` 之一——两个都没有、两个都给，都指名报错。

**`:run` 是会话与内核那一侧的事**：`hooks.edn` 里写 `:run` 被**指名拒绝**（文件里放不了函数），
理由里说清该写 `:command`、还是该从会话 `session-add!`、还是那本来就是一条 `:built-in` 行。
这不是特例，是与 `:timeout` / `:matcher` 同一套逐字段校验。**任何别的键都指名失败**——`:commnd`
这种拼错会静默丢掉，然后那条声明就变成「声明了一条什么都不跑的 hook」，比报错更糟。同理，
`:timeout` 配 `:run` 也指名失败：`run` 没有 spawn 可限时，静默接受一个不起作用的字段正是这条校验存在的理由。

## 契约

| 退出码 | 含义 |
|---|---|
| `0` | 放行 |
| `2` | **阻断**，stderr 是理由，回喂给模型 |
| 其他非零 | 按点的失败语义：门禁 `:block`，观察者 `:proceed` |
| 超时 / 起不来 | 同上——**「没能替你判断」不等于「判断为是」** |

- payload 走 **stdin JSON**：`{hook, thread_id, project_dir, ...该点的字段}`，键名 snake_case。
  **进程内的 `:run` 拿到的是同一批键的 map，值保类型**（它不绕一圈 JSON：函数能装 map，JSON 只能装文本）。
- **stdout 可以再带一个 JSON 对象**作为「退出码说不出来的那个决定」，且**只在退出 0 时读**；
  只有 `PermissionRequest` 用得上：`{"decision":"approve"|"deny","reason":".."}`。
  读不懂、或不是对象，就不算答案——**一个 hook 打印一行日志不该被读成做了决定**。
- **`SystemPrompt` 那一格的 stdout 是内容本身**，见下。
- 命令经钉住的 shell spawn（`harness.infra.shell` 的 Windows 陷阱见 [client](client.md) 之外的 README）；
  `:run` 是进程内的一次调用，抛异常 = 起不来（`{:exit nil :err <消息>}`）。两者都折进同一个
  `{:exit :out :err :timeout}`，所以退出码语义、失败策略、审计行在下面完全一样。
- **一次真触发的落一行 `hook/<Point>` 审计行**；没匹配到任何声明 = 不 spawn、不等待、不落行。

## 27 个点，全部是数据

`harness.kernel.hooks/points` 是一张表，每个点是
`{:name :when :payload :matches :gate? :on-error}`，另有一格可选：`:stdout`。**加点 = 加一行**，
引擎里没有 per-point 代码——这是整个 hook 设计赖以成立的性质。

| 状态 | 点 |
|---|---|
| **已接线（7）** | `SessionStart`、`PreToolUse`、`PermissionRequest`、`PostToolUse`、`Stop`、**`SystemPrompt`**、`InstructionsLoaded` |
| 已登记、无触发源（20） | `UserPromptSubmit`、`PermissionDenied`、`PostToolUseFailure`、`StopFailure`、`Notification`、`ConfigChange`、`CwdChanged`、`SessionEnd`、`FileChanged`、`Elicitation`、`ElicitationResult`、`PreCompact`、`PostCompact`、`SubagentStart/Stop`、`TeammateIdle`、`TaskCreated/Completed`、`WorktreeCreate/Remove` |

**没有触发源的点永不触发——这是设计，不是遗漏。** 这就是为什么一个 P3 点的代价是一行数据，
而不是一个接口。它们等各自的子系统（文件监视、上下文压缩、子代理、任务、worktree）落地时再接。

两个装配点**并排**，各管 run 开场的一半，且**不可能交错**（不同的 message role）：

| 点 | 管什么 | 什么时候 |
|---|---|---|
| `SystemPrompt` | system 消息（`prompt.md` 的冻结开头 + 各声明追加的文本） | 第一条消息正在被组装，模型看到它之前 |
| `InstructionsLoaded` | user 侧开场块（指令文件、技能清单） | 一个指令文件被折进 run 的上下文 |

`InstructionsLoaded` 是这套说法最近一次被兑现的例子：它自引擎落地起就声明着（`payload #{:path}`），
触发源是一个指令文件被折进 run 的上下文——见
[skills-and-instructions](skills-and-instructions.md#instructionsloaded-接线)。接线时撞出来的约束值得写在这里，
因为它是**接线层面**的事而不是那个特征的事：run 作用域的 sink 由 http 边绑定，而折叠发生在第一条消息
组装之前，所以那个 `binding` 必须包住 set-up 而不只是 run，否则这个点拿到 nil sink、永远静默。
`SystemPrompt` 接线时落进同一格：system 文本也是在 set-up 里组装的，所以它也靠那个 binding 才活着。

`EDN 键 ↔ 点的名字`由 `point-for` 一处对应（`:pre-tool-use` ↔ `"PreToolUse"`），
payload 里带的与审计行里写的都是后者（CodeBuddy 的拼法，迁移心智零成本）。

## `SystemPrompt`：stdout 是内容的那一格

**点表里只有这一行标了 `:stdout :content`**，而这一格就是它与其他门禁点的全部差别：

- 其它点：stdout 只在退出 0 时被当成一个 JSON 答案读，退出码才是判定。
- 这一点：**退出 0 时 stdout 就是它**——dispatch 把每条声明的 stdout（trim 后非空）收进一个
  **有序的 `:blocks`**，顺序即来源档位与声明顺序。
- **匹配到的声明全部跑、全部追加**，不是「第一条 block 获胜」：一条 hook 不该把另一条的文本吃掉。
  （`fire` 的返回在其它点上**逐字节不变**——`:blocks` 这个键只在这一点出现。）
- **空输出 = 这一条什么都不说**，不是错误。所以「本会话答不出 provider」那种情况的正确写法是
  打印空字符串，而不是报错。
- 文本**原样**进 prompt，引擎不包装：块自己带 `<tools>` 这样的标签，作者写什么就是什么；
  块之间恰好一个空行。

**退出 2 表示这次 run 不开始**：stderr 逐字是理由，客户端收到 RUN_ERROR，日志里有 `hook/SystemPrompt` 行。
超时 / 起不来照点自己的 `:on-error`（`:block`）走同一条。**这是硬失败而不是 fail-open**，与 AGENTS.md 同族：
这些 hook 写的是**本该进 system 消息的话**，吞掉它就是撒谎。

组装住在 `harness.cap.system-prompt`（它同时注册下面那三条内建 hook）；它为什么不并进 `harness.cap.preamble`
见 [architecture](../architecture.md) 的模块地图与 [kernel](kernel.md#prompt-的载体冻结的是开头)。

## 三个来源，一条读取

一条声明从哪来，决定了它排在哪、以及它能跑什么：

| 来源 | `:source` | 谁能声明 | 跑什么 | 排在哪 |
|---|---|---|---|---|
| 内核自己注册的 | `:built-in` | `harness.cap.system-prompt` | 进程内的函数（`:run`） | 最前 |
| 配置家 / 项目的 `hooks.edn` | `:config` | 作者 | 命令（`:command`） | 次之，按文件里的顺序 |
| 本会话 `session-add!` 的 | `:session` | 运行中的会话 | 两者皆可 | 最后，按加入的顺序 |

**先后由来源档位决定**（内建 → 文件 → 会话），同一来源内按各自的顺序。这就是全部优先级规则，
而且对每个点都一样：后加进来的行不会悄悄压过已经在生效的行。

**文件层**：配置家的 `hooks.edn`（用户级）被绑定项目的 `.harness/hooks.edn` 覆盖，
**逐点替换**（项目写了 `:pre-tool-use` 就整个换掉用户的那些）。每次现读。

**内核自己那几条也是行，不是另一套机制**：同一张表、同一条缝、同一套退出码与审计行。差别只有两处，
且都写在行上——`:source` 是 `:built-in`（因此排在最前），跑的是 `:run`。会话能像关掉别的 hook 一样
关掉它、再打开；**「内核自己的」是「这行从哪来」，不是「这套引擎的例外」**。

**会话层**（`eval` 的面）：一个运行中的会话可以给自己加 hook、撤掉自己加的、把任意一条
（**包括磁盘上声明的、也包括内核自己的**）关掉再打开。只影响本 thread、进程重启即失。

```
thread-id → {:added {id decl}    ; presence：本会话贡献的
             :disabled #{id}}     ; availability：本会话关掉的
```

两条轴，与工具表**同一套词汇**：

- `session-add!` / `session-remove!`（presence——remove 只撤本会话加的，**磁盘声明与内建只能关不能撤**）；
- `session-disable!` / `session-enable!`（availability）。
- **关闭不是隐藏**：被关的声明仍在 `effective-hooks` 里、带 `:disabled? true`，只是不再触发。
  藏起来会让「没有这条 hook」和「这条 hook 关着」变成同一个观察，而前者是谎话——声明就摆在文件里。

id 的拼法让三个来源一眼分得开：文件 `pre-tool-use#0`（点在文件里的位置）、会话 `pre-tool-use@1`
（本会话第几次加）、内建 `builtin:tools`（名字就是 id，因为内核写得出名字）。

在会话里经 `eval` 这么用（工具表那套 `session-require-approval!` 是同一形状）：

```clojure
(harness.kernel.hooks/session-add! harness.kernel.tools/*thread-id* :stop {:command "notify.sh"})  ; => "stop@1"
(harness.kernel.hooks/session-disable! harness.kernel.tools/*thread-id* "stop@1")
(harness.kernel.hooks/session-enable! harness.kernel.tools/*thread-id* "stop@1")
(harness.kernel.hooks/session-remove! harness.kernel.tools/*thread-id* "stop@1")
(harness.kernel.hooks/session-disable! harness.kernel.tools/*thread-id* "builtin:tools")  ; 内建的也一样
```

`effective-hooks` 是**引擎唯一读的那一面**：它把内建、磁盘、会话三层折在一起，
每条声明带 `:id`（关闭时用哪个名字）、`:point`、`:source`（`:built-in` / `:config` / `:session`）、
内建行还有 `:name`、以及 `:disabled?`。
`declarations-at` 已经**滤掉**被关的——一个被关掉的 hook 就是「不触发」，那是表的事实，
不该让 dispatch 记得去判断。

## 内建的三条行

`harness.cap.system-prompt` 在加载时注册三条 `:run` 行，都在 `SystemPrompt` 点：

| id | 块 | 说什么 |
|---|---|---|
| `builtin:tools` | `<tools>` | 本会话**实际被服务**的工具集合、本会话关掉了哪些、哪些来自外部程序（`mcp__<server>__<tool>`） |
| `builtin:project` | `<project>` | 绑在哪个目录（绝对路径）、相对路径/bash/绝对路径各自怎么解析、围栏的自由路径集合（含 `:approval {:strict true}` 与 `:approval {:allow ..}` 的两种变化）；未绑定就明说没绑定、不提围栏 |
| `builtin:provider` | `<provider>` | 生效的 vendor / model / 思考档；答不出就一个块都不出；**永不含 api-key** |

三块都是**现算**的：工具集合随会话注册、关闭、编辑模式变；binding 随 `project/bind!` 变；
provider 随 `session-configure` 变。因此**事实不动则文本逐字节不动**（prefix 照旧命中），
事实动了就付一次冷前缀——**宁可冷一次，也不让 system 消息说一件已经不成立的事**。

`<tools>` 报的是 wire 上 `:tools` 数组里的那套（编辑模式减过的那套），不是整张表：列一个模型调不动的
工具，正是这一块存在要消灭的那种过时。它也**不复制每个工具的描述**——描述已经在 `:tools` 里了。

## dispatch：谁在跑

```
fire {point thread-id fact audit}
  → 取该点在本 thread 生效的声明（内建 + 磁盘 + 会话，按来源档位与书写顺序）
  → 按 :matcher 过滤（只与该点的 :matches 指名的那个 payload 字段比较）
  → 逐条跑，payload 给过去，读退出码 / stdout / 超时
  → 折叠成一个 verdict + 一行审计（:stdout :content 的点另收一份有序的 :blocks）
```

几条写死的规则：

- **全部匹配的声明都跑**——一条 hook 不能把另一条该知道的事藏起来。
- **第一个 block 胜**（按书写顺序），所以模型读到的理由是**最早那条门禁**写的。
- 唯独 `SystemPrompt` 不是「第一个胜」而是「全部追加」——见上面那一节。
- `:answer` 取**第一个**给了答案的声明（同一套「最早者胜」的规矩）。
- 观察者失败不改判定，但理由会被带回去——否则引擎就是唯一知道「有 hook 坏了」的地方，
  而调用方说不出来。
- 审计行**每条真触发恰好一行**；`runId` 是 sink 的事（`harness.edge.http` 绑），不是这里返回的东西。

## 与工具缝的关系

工具缝里那两类「悬置型规则」（工具自带 `:requires-approval`、会话级 `session-require-approval!`）
**不是另一套机制**——它们是本会话给自己装的悬置型规则，与 `hooks.edn` 里写的门禁是同一族。
`PermissionRequest` 就是让**规则**去回答本来要打断人的那个问题的那一点，详见
[kernel 的悬置一节](kernel.md#悬置先问规则再问人)。

**没声明任何 hook 时整条路径是 no-op**：不 spawn、不等待、不落行，帧与审计线与这个能力存在之前
逐字节相同。hook 只在**边**绑定了 run 的 sink 时触发，所以离线工具、replay、直接驱动内核的测试
一个 hook 都不跑。**内建的行是这条的性质的唯一例外，而且只在一个地方**：它们在每个 thread 的表里，
所以一次真触发的 `hook/SystemPrompt` 行会落——那是这些行在**干活**，不是引擎在无声明时留下痕迹；
把三条都关掉，那一点又回到一行都不写。

## eval 的定位，与晋升路径

`eval` 是本会话给自己长**行为**的地方，而今天那个行为是 **hook**——不是「长出新工具」。
工具表那套入口（`session-register!` / `session-disable!` 等）一个都没删、测试还在跑，
只是不再是对模型的承诺：它能表达的止于「又一个工具定义」，而 hook 能表达作者没枚举过的事
（拦住一次调用、替人回答一次审批、在 run 收尾时做点什么），不需要有人先把那个点写进工具表。
`eval` 仍能执行任意 Clojure：收敛的是它的**定位与承诺**，不是它的能力。

**会话级的一切都是进程内的，重启即失**——这是特性不是缺陷：没落过盘的东西天然可回滚。
要把一段值得留的东西固化下来，走**人工晋升**：读日志 → 判断哪段值得留 →
抄进 `src/harness`（正式工具表，或 `hooks.edn` 里一份默认声明）→ git 提交。

```
clojure -M:evals <thread-id> [log-dir]   # 列出该 thread 每次 eval 的 code 与返回值
```

**绝不自动重放历史 eval**——那等于把日志变成可执行输入，确定性与安全一起崩。

读日志找 eval 的配方（写工具时用得上）：`eval` 的 **code** 在 assistant message 的
`tool_calls[].function.arguments`（JSON **字符串**，要二次解码取 `:code`），**返回值**在对应
`tool_call_id` 的 tool message 的 `:content`。审计三行 `tools/*` **不带 args**，别去那里找 code。
