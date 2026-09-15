# Hook 引擎

## 形态

一个 hook = 配置里的一行声明，指向一条**命令**：

```edn
{:pre-tool-use      [{:matcher "bash|write" :command "scripts/gate.sh" :timeout 10000}]
 :permission-request [{:command "scripts/auto-approve.sh"}]
 :stop              [{:command "scripts/notify.sh"}]}
```

声明里只有三个键：`:matcher`（正则，与**该点声明的那个 payload 字段**比较）、`:command`（必填）、
`:timeout`（毫秒）。**任何别的键都指名失败**——`:commnd` 这种拼错会静默丢掉，然后那条声明就变成
「声明了一条什么都不跑的 hook」，比报错更糟。

## 契约

| 退出码 | 含义 |
|---|---|
| `0` | 放行 |
| `2` | **阻断**，stderr 是理由，回喂给模型 |
| 其他非零 | 按点的失败语义：门禁 `:block`，观察者 `:proceed` |
| 超时 / 起不来 | 同上——**「没能替你判断」不等于「判断为是」** |

- payload 走 **stdin JSON**：`{hook, thread_id, project_dir, ...该点的字段}`，键名 snake_case。
- **stdout 可以再带一个 JSON 对象**作为「退出码说不出来的那个决定」，且**只在退出 0 时读**；
  只有 `PermissionRequest` 用得上：`{"decision":"approve"|"deny","reason":".."}`。
  读不懂、或不是对象，就不算答案——**一个 hook 打印一行日志不该被读成做了决定**。
- 命令经钉住的 shell spawn（`harness.shell` 的 Windows 陷阱见 [client](client.md) 之外的 README）；
  超时与崩溃都不炸 run。
- **一次真触发的落一行 `hook/<Point>` 审计行**；没匹配到任何声明 = 不 spawn、不等待、不落行。

## 26 个点，全部是数据

`harness.hooks/points` 是一张表，每个点是
`{:name :when :payload :matches :gate? :on-error}`。**加点 = 加一行**，引擎里没有 per-point 代码——
这是整个 hook 设计赖以成立的性质。

| 状态 | 点 |
|---|---|
| **已接线（5）** | `SessionStart`、`PreToolUse`、`PermissionRequest`、`PostToolUse`、`Stop` |
| 已登记、无触发源（21） | `UserPromptSubmit`、`PermissionDenied`、`PostToolUseFailure`、`StopFailure`、`Notification`、`InstructionsLoaded`、`ConfigChange`、`CwdChanged`、`SessionEnd`、`FileChanged`、`Elicitation`、`ElicitationResult`、`PreCompact`、`PostCompact`、`SubagentStart/Stop`、`TeammateIdle`、`TaskCreated/Completed`、`WorktreeCreate/Remove` |

**没有触发源的点永不触发——这是设计，不是遗漏。** 这就是为什么一个 P3 点的代价是一行数据，
而不是一个接口。它们等各自的子系统（文件监视、上下文压缩、子代理、任务、worktree）落地时再接。

`EDN 键 ↔ 点的名字`由 `point-for` 一处对应（`:pre-tool-use` ↔ `"PreToolUse"`），
payload 里带的与审计行里写的都是后者（CodeBuddy 的拼法，迁移心智零成本）。

## 两级装配 + 会话 overlay

**文件层**：配置家的 `hooks.edn`（用户级）被绑定项目的 `.harness/hooks.edn` 覆盖，
**逐点替换**（项目写了 `:pre-tool-use` 就整个换掉用户的那些）。每次现读。

**会话层**（`eval` 的新面）：一个运行中的会话可以给自己加 hook、撤掉自己加的、把任意一条
（**包括磁盘上声明的**）关掉再打开。只影响本 thread、进程重启即失。

```
thread-id → {:added {id decl}    ; presence：本会话贡献的
             :disabled #{id}}     ; availability：本会话关掉的
```

两条轴，与工具表**同一套词汇**：

- `session-add!` / `session-remove!`（presence——remove 只撤本会话加的，**磁盘声明只能关不能撤**）；
- `session-disable!` / `session-enable!`（availability）。
- **关闭不是隐藏**：被关的声明仍在 `effective-hooks` 里、带 `:disabled? true`，只是不再触发。
  藏起来会让「没有这条 hook」和「这条 hook 关着」变成同一个观察，而前者是谎话——声明就摆在文件里。

`effective-hooks` 是**引擎唯一读的那一面**：它把磁盘与会话两层折在一起，
每条声明带 `:id`（关闭时用哪个名字）、`:point`、`:source`（`:config` / `:session`）、`:disabled?`。
`declarations-at` 已经**滤掉**被关的——一个被关掉的 hook 就是「不触发」，那是表的事实，
不该让 dispatch 记得去判断。

## dispatch：谁在跑

```
fire {point thread-id fact audit}
  → 取该点在本 thread 生效的声明（磁盘 + 会话，按书写顺序）
  → 按 :matcher 过滤（只与该点的 :matches 指名的那个 payload 字段比较）
  → 逐条 spawn，payload 走 stdin，读退出码 / stdout / 超时
  → 折叠成一个 verdict + 一行审计
```

几条写死的规则：

- **全部匹配的声明都跑**——一条 hook 不能把另一条该知道的事藏起来。
- **第一个 block 胜**（按书写顺序），所以模型读到的理由是**最早那条门禁**写的。
- `:answer` 取**第一个**给了答案的声明（同一套「最早者胜」的规矩）。
- 观察者失败不改判定，但理由会被带回去——否则引擎就是唯一知道「有 hook 坏了」的地方，
  而调用方说不出来。
- 审计行**每条真触发恰好一行**；`runId` 是 sink 的事（`harness.http` 绑），不是这里返回的东西。

## 与工具缝的关系

工具缝里那两类「悬置型规则」（工具自带 `:requires-approval`、会话级 `session-require-approval!`）
**不是另一套机制**——它们是本会话给自己装的悬置型规则，与 `hooks.edn` 里写的门禁是同一族。
`PermissionRequest` 就是让**规则**去回答本来要打断人的那个问题的那一点，详见
[kernel 的悬置一节](kernel.md#悬置先问规则再问人)。

**没声明任何 hook 时整条路径是 no-op**：不 spawn、不等待、不落行，帧与审计线与这个能力存在之前
逐字节相同。hook 只在**边**绑定了 run 的 sink 时触发，所以离线工具、replay、直接驱动内核的测试
一个 hook 都不跑。
