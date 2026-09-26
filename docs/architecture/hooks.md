# Hook 引擎

## 形态

一个 hook = 配置里的一行声明，指向**一条命令**——或者，在能放函数的那两个来源里，指向**一个进程内的函数**：

```edn
{:pre-tool-use      [{:matcher "bash|write" :command "scripts/gate.sh" :timeout 10000}]
 :permission-request [{:command "scripts/auto-approve.sh"}]
 :system-prompt     [{:command "scripts/print-tool-policy.sh"}]
 :stop              [{:command "scripts/notify.sh"}]}
```

**一条声明说它跑什么，且只说一样**：恰好 `:command`（一条命令）或 `:run`（一个进程内的函数）之一——
两个都没有、两个都给，都指名报错。**能写在哪一档也是规则的一部分**：`:run` 只在会话与内核那两档成立，
`hooks.edn` 里写它被指名拒绝。逐键的取值、默认值，以及每一条会被指名拒绝的写法，都在下一节。

## 声明里能写什么

一条声明说它跑什么，**恰好一样**：

| 键 | 必填 | 是什么 | 写错会怎样 |
|---|---|---|---|
| `:command` | 二者之一 | 非空字符串，经 shell spawn，payload 走 stdin | 空串 / 不是字符串 / 与 `:run` 同时给 / 两个都不给 —— 一律指名报错 |
| `:run` | 二者之一 | 本进程里的可调用（`fn` of payload map） | **`hooks.edn` 里写 `:run` 被指名拒绝**：文件放不了函数。要写得从会话 `session-add!`，或者那本来就是 `:built-in` 的一行 |
| `:matcher` | 否 | 正则，**`re-find` 语义（部分匹配，不是全匹配）**，对象是该点 `:matches` 指名的那个 payload 字段 | 点没有匹配对象时被拒绝（并列出哪些点有）；**编译不过的正则在读文件时就报错**，不留到触发那一刻 |
| `:timeout` | 否，默认 **10000** | 正整数毫秒，限住**等待**命令的时间 | 非正整数报错；与 `:run` 同给也报错——`run` 没有 spawn 可限，收下一个不起作用的字段正是这条校验存在的理由（文件里 `:run` 先被拒，所以这一条只在会话 `session-add!` 那一侧撞得到）|

**除这四个键以外的任何键都指名失败。** `:commnd` 这种拼错不会静默丢掉：那会让声明变成「一条什么都不跑的
hook」，比报错更糟。

文件层还有四条规矩，**都在读文件时就成立**：

| 情形 | 结果 |
|---|---|
| 文件不存在 | `{}`——什么都没说。不 spawn、不等待、不落审计行 |
| 文件存在，但**零字节**或**只有注释** | **指名失败**。空内容读出来是 `nil` 而不是空 map，所以「我没有 hook」要写成 `{}` |
| 不是 EDN、不是 map、点键不认识、点的值不是 vector、声明不合法 | 指名失败，消息里带**绝对路径**与该键 |
| — | 逐点替换：配置家的 `hooks.edn` 被绑定项目的 `.harness/hooks.edn` 覆盖，**一个点写了就整个换掉那个点**，两份不叠加 |

**每次触发现读**：改完下一次触发就生效，不用重启。可复制的完整起点在仓库根目录的
[`hooks.edn.example`](../../hooks.edn.example)，逐键参考与可跑的例子都在那里。

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
  审计行的字段：`{point, thread_id, matched, verdict, reason, answer?, results[{exit, timeout,
  outcome, verdict, reason, answer?}]}`——一条声明一个 `results`，`reason` 是第一条没放行的声明写的。
- **`:reason` 那句话是谁写的**：退出 **2** 时逐字是命令的 stderr，没有前缀；**其他非零退出**与**超时**
  由引擎加一句前缀（`hook exited 3: ` / `hook timed out after its timeout: `），后面才是命令自己的话
  ——stderr 为空时用 stdout。审计行里的 `reason` 与执行缝返回的是同一句。

### 命令是在什么条件下被问的

上面说的是「命令怎么回答」，这一节说的是**它在什么条件下被问**。六条，都是写 hook 时会被咬到的：

- **工作目录是 harness 进程启动时那个目录，不是会话的项目目录。** hook 命令不给工作目录
  （`harness.infra.shell/run` 的 `:dir` 这条路不给），所以相对路径的 `:command`、以及命令自己写的相对
  路径，都按**进程的 cwd** 解析。**写绝对路径。**（对照：stdio 的 MCP 服务器**是**给了工作目录的，
  给的是绑定项目的 canonical 目录——见 [mcp](mcp.md#stdio-服务器在哪个目录起来)。）
- **经钉住的登录 shell 起**（本机 `bash -lc`；Windows 上是 Git Bash、`pwsh -NoProfile -Command` 或
  `cmd /c`）。登录 shell 会重新 source profile，**命令看到的 PATH 可以与 JVM 的不同**——而那正是人在
  终端里拿到的那一份。
- **stdin 写一次随即关掉**：命令读到底是 EOF，不会无谓地挂住；反过来，**等更多输入的命令会一直等到
  超时**。
- **超时到了杀的是整棵进程树**，不是握着的那条 shell。`npx` 这种再包一层的命令是常态，只杀 shell
  会留下真正的服务器。
- **hook 是同步的**：它在 run 自己的路径上跑完才走。`:pre-tool-use` 上一条慢 hook，就是在拖慢它正在
  检查的那次工具调用。
- **命令这一侧的每个 payload 值都是文本**（`str` 渲染）。所以 `tool_input` 到手上是它的**打印形式**
  ——`{:path "src/x.clj", :offset 3}`，不是 JSON。`:run` 拿到的是有类型的值，那两边唯一的差别
  （见 [上面契约](#契约) 第一条）。

payload 是 stdin 上的一个 JSON 对象，键名 snake_case：三个公共键 `hook` / `thread_id` / `project_dir`，
加上该点自己的字段（下一张表）。**`project_dir` 在没有绑定项目的会话里是 `null`**。

### 装上去之前先试一次

一条 hook 就是「stdin 收一段 JSON、用退出码回答」的普通程序，所以不用开着 agent 试：

```sh
echo '{"hook":"PreToolUse","thread_id":"t","project_dir":null,
       "tool_name":"bash","tool_input":"{:command \"ls\"}"}' \
  | /abs/scripts/fence-check.sh ; echo "exit=$?"
```

- **退出码 0 / 2 / 别的**就是上面那张表说的一切（`2` 时 stderr 原样回喂给模型）。先在这一行上把判定
  调对，再装进 `hooks.edn`。
- **写坏了不会静默**：不是 EDN、点键拼错、`:matcher` 编译不过……都在**下一次触发**时按**绝对路径 + 那个
  键**报出来。所以「装上去好像没反应」这种含糊状态没有立足之地——要么它跑了，要么有句话指出了是哪一行。
- **看现在到底生效了哪些**：在会话里 `eval` 一句 `harness.kernel.hooks/declarations-at`（按点问，已经滤掉
  被关掉的），或者 `effective-hooks`（三层折在一起，每条带 `:id` 与 `:source`）——见
  [三个来源](#三个来源一条读取)。

## 27 个点，全部是数据

`harness.kernel.hooks/points` 是一张表，每个点是
`{:name :when :payload :matches :gate? :on-error}`，另有一格可选：`:stdout`。**加点 = 加一行**，
引擎里没有 per-point 代码——这是整个 hook 设计赖以成立的性质。

**一张表查完**：怎么写（EDN 键）、它给你什么（payload 字段）、能不能拦（门禁）、跑砸了算哪边。
`点` 列是 payload 与审计行里的拼法，**EDN 键**列是你写进 `hooks.edn` 的那一个。

| EDN 键 | 点 | 有触发源 | payload 额外字段 | `:matcher` 对象 | 门禁 | 超时 / 起不来 |
|---|---|---|---|---|---|---|
| `:session-start` | `SessionStart` | ✓ | `source` | — | 观察者 | 放行 |
| `:user-prompt-submit` | `UserPromptSubmit` | — | `prompt` | — | **门禁** | 阻断 |
| `:pre-tool-use` | `PreToolUse` | ✓ | `tool_name` `tool_input` | `tool_name` | **门禁** | 阻断 |
| `:permission-request` | `PermissionRequest` | ✓ | `tool_name` `tool_input` `interrupt_id` | `tool_name` | **门禁** | 阻断 |
| `:permission-denied` | `PermissionDenied` | — | `tool_name` `reason` | `tool_name` | 观察者 | 放行 |
| `:post-tool-use` | `PostToolUse` | ✓ | `tool_name` `tool_input` `result` | `tool_name` | 观察者 | 放行 |
| `:post-tool-use-failure` | `PostToolUseFailure` | — | `tool_name` `tool_input` `error` | `tool_name` | 观察者 | 放行 |
| `:stop` | `Stop` | ✓ | — | — | 观察者 | 放行 |
| `:stop-failure` | `StopFailure` | — | `error` | — | 观察者 | 放行 |
| `:notification` | `Notification` | — | `message` | — | 观察者 | 放行 |
| `:system-prompt` | `SystemPrompt` | ✓ | — | — | **门禁** | 阻断 |
| `:instructions-loaded` | `InstructionsLoaded` | ✓ | `path` | — | 观察者 | 放行 |
| `:config-change` | `ConfigChange` | — | `what` | — | 观察者 | 放行 |
| `:cwd-changed` | `CwdChanged` | — | `project_dir` `before` | — | 观察者 | 放行 |
| `:session-end` | `SessionEnd` | — | — | — | 观察者 | 放行 |
| `:file-changed` | `FileChanged` | — | `file` | `file` | 观察者 | 放行 |
| `:elicitation` | `Elicitation` | ✓ | `server` `request` | — | 观察者 | 放行 |
| `:elicitation-result` | `ElicitationResult` | ✓ | `server` `response` | — | 观察者 | 放行 |
| `:pre-compact` | `PreCompact` | — | — | — | 观察者 | 放行 |
| `:post-compact` | `PostCompact` | — | — | — | 观察者 | 放行 |
| `:subagent-start` | `SubagentStart` | — | `subagent` | — | 观察者 | 放行 |
| `:subagent-stop` | `SubagentStop` | — | `subagent` | — | 观察者 | 放行 |
| `:teammate-idle` | `TeammateIdle` | — | `teammate` | — | 观察者 | 放行 |
| `:task-created` | `TaskCreated` | — | `task` | — | 观察者 | 放行 |
| `:task-completed` | `TaskCompleted` | — | `task` | — | 观察者 | 放行 |
| `:worktree-create` | `WorktreeCreate` | — | `path` | — | 观察者 | 放行 |
| `:worktree-remove` | `WorktreeRemove` | — | `path` | — | 观察者 | 放行 |

「有触发源」列是「**今天真的会触发**」——9 个点。**没标的 18 个永不触发，这是设计，不是遗漏**：
它们声明得下、校验得过、进得了表，只是等各自的子系统（文件监视、上下文压缩、子代理、任务、worktree）
落地时才接——这就是为什么一个 P3 点的代价是一行数据，而不是一个接口。另外两处读表时要留意：
**匹配对象是 `—` 的点拒绝 `:matcher`**；**`:system-prompt` 的 stdout 不是答案而是内容**（下一节）。

两个装配点**并排**，各管 run 开场的一半，且**不可能交错**（不同的 message role）：

| 点 | 管什么 | 什么时候 |
|---|---|---|
| `SystemPrompt` | system 消息（`prompt.md` 的冻结开头 + 各声明追加的文本） | 第一条消息正在被组装，模型看到它之前 |
| `InstructionsLoaded` | user 侧开场块（指令文件、技能清单） | 会话出生那一轮，一个指令文件被折进对话（`.scratch/session-opening`；此后它只是历史） |

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
- 文本**原样**进 prompt，引擎不包装：块自己带 `<project>` 这样的标签，作者写什么就是什么；
  块之间恰好一个空行。

**退出 2 表示这次 run 不开始**：stderr 逐字是理由，客户端收到 RUN_ERROR，日志里有 `hook/SystemPrompt` 行。
超时 / 起不来照点自己的 `:on-error`（`:block`）走同一条。**这是硬失败而不是 fail-open**，与 AGENTS.md 同族：
这些 hook 写的是**本该进 system 消息的话**，吞掉它就是撒谎。

组装住在 `harness.cap.system-prompt`（它同时装上下面那两条内建 hook）；它为什么不并进 `harness.cap.preamble`
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
（本会话第几次加）、内建 `builtin:env`（名字就是 id，因为内核写得出名字）。

在会话里经 `eval` 这么用（工具表那套 `session-require-approval!` 是同一形状）：

```clojure
(harness.kernel.hooks/session-add! harness.kernel.tools/*thread-id* :stop {:command "notify.sh"})  ; => "stop@1"
(harness.kernel.hooks/session-disable! harness.kernel.tools/*thread-id* "stop@1")
(harness.kernel.hooks/session-enable! harness.kernel.tools/*thread-id* "stop@1")
(harness.kernel.hooks/session-remove! harness.kernel.tools/*thread-id* "stop@1")
(harness.kernel.hooks/session-disable! harness.kernel.tools/*thread-id* "builtin:env")  ; 内建的也一样
```

`effective-hooks` 是**引擎唯一读的那一面**：它把内建、磁盘、会话三层折在一起，
每条声明带 `:id`（关闭时用哪个名字）、`:point`、`:source`（`:built-in` / `:config` / `:session`）、
内建行还有 `:name`、以及 `:disabled?`。
`declarations-at` 已经**滤掉**被关的——一个被关掉的 hook 就是「不触发」，那是表的事实，
不该让 dispatch 记得去判断。

## 内建的两条行

`harness.cap.system-prompt` 在自己的 `install!` 里装上两条 `:run` 行，都在 `SystemPrompt` 点：

| id | 块 | 说什么 |
|---|---|---|
| `builtin:project` | `<project>` | 绑在哪个目录（绝对路径）、相对路径/bash/绝对路径各自怎么解析、围栏的自由路径集合（含 `:approval {:strict true}` 与 `:approval {:allow ..}` 的两种变化）；未绑定就明说没绑定、不提围栏 |
| `builtin:env` | `<env>` | 这台机器：平台、命令实际交给哪个 shell（kind + 路径 + 起法）、声明名单里这个 shell 看得见哪些命令行增强工具（`rg` / `fd` / `jq` / `git`）——**有说，没有也说**；外加这一家说的语言（`language: English (en)` / `Chinese (zh)`），**永远在场** |

**`<tools>` 与 `<provider>` 都退场**（2026-09-16）：名册在 wire 的 `:tools` 数组里**自描述**（每次请求都
带着每个工具的名字与描述），说第二遍是把同一件事说两遍。**2026-09-24 主人补了一句边界**：「tools 应该写进
`role=system`」——但它的意思不是把 `<tools>` 块加回**正文**（那正是「说第二遍」、模型白付 token），而是
把整张表写到 **system 那条 `message` 行的信封上**（`:tools`，由 `harness.edge.http` 在写那行时带上，
`replay/payload` 把它挡在消息之外）：记录里回读得到、模型读不到。所以这里的两条行**不再增加**，
`.scratch/system-prompt-blocks/spec.md` 决策 6 也不改；`harness.cap.system-prompt` 只管正文的两块。
（`.scratch/model-surface-and-meter` 票 04：那张表曾经一字不差重复 672 遍、占 129.7 MB 日志四成。）
`<provider>` 同理退场，如实记在 `.scratch/session-context/spec.md`。

两块都是**现算**的：binding 随 `project/bind!` 变，机器事实随进程变。因此**事实不动则文本逐字节不动**
（prefix 照旧命中），事实动了（换目录、关掉一条 hook）就付一次冷前缀——**宁可冷一次，也不让 system
消息说一件已经不成立的事**。

`<env>` 自己带三条纪律，值得单独写下来。**机器的事实每进程算一次，块每 run 现算**：平台、shell、
增强工具与 `harness.infra.shell` 的解析同住一个 `defonce`，而块本身照旧每 run 组装——那条纪律管的是
**会话**事实。**探测走同一个 shell**：一次 spawn 跑一个逐个 `command -v` 的小循环，不是
`System.getenv`——登录 shell 会重新 source profile，它的 PATH 与 JVM 的可以不同，而模型真去跑的时候
用的是前者，用 JVM 的 PATH 答出来的是那句到执行时才不成立的话。探测答不上来（起不来、超时）就
**如实说「不知道」**：这一半报问不出来，不让整块消失，也不假装没有。
**语言那一行是唯一的非机器事实**：它来自这一家的 `config.edn`（`:ui :language`），每次 run 现读，
缺了就按系统语言 → 终端语言 → 英语往下落（`harness.infra.language`）；系统语言按平台问系统本身
（macOS 的 `AppleLanguages`），不是这个进程的 locale、也不是终端里的 `LANG` / `LC_ALL`。

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

**两个 MCP 点（`Elicitation` / `ElicitationResult`）也是观察者**，而且这是刻意的：一条规则可以
「替人回答一次审批」（那是合理的委派），但「替人填一张表」不是——所以那条答复通道不给它们。

**没声明任何 hook 时整条路径是 no-op**：不 spawn、不等待、不落行，帧与审计线与这个能力存在之前
逐字节相同。hook 只在**边**绑定了 run 的 sink 时触发，所以离线工具、replay、直接驱动内核的测试
一个 hook 都不跑。**内建的行是这条的性质的唯一例外，而且只在一个地方**：它们在每个 thread 的表里，
所以一次真触发的 `hook/SystemPrompt` 行会落——那是这些行在**干活**，不是引擎在无声明时留下痕迹；
把两条都关掉，那一点又回到一行都不写。

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
