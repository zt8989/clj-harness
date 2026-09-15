# 项目、会话、围栏

## 两个词

- **项目** = 一个绝对目录，按 **canonical 路径**去重（`projects` 表一行一个目录）。
- **会话** = 一段对话，`thread-id` 是它的标识（`sessions` 表一行一个会话）。

一个会话**至多**属于一个项目。**未绑定是允许的**（服务端容忍库里没有的 thread id 跑起来，
日志落 `projects/.unbound/`）——这条边界上不引入「先建会话」的协议前置，否则整套既有测试都要先学会建会话。

## 绑定：三个动作

| 动作 | 调用 | 语义 |
|---|---|---|
| 绑定 / 换绑 | `bind! thread-id dir` | 路径经校验（存在且是目录，否则**指名**抛错）；写 `sessions.project_id` + `path` |
| 解绑 | `bind! thread-id nil` | 清两列，**并清 `last_project_path`**（放掉就是放掉） |
| 添加项目 | `add-project! dir` | find-or-create 一行 `projects`，**并把「记得这个目录」的会话接回来** |
| 移除项目 | `remove-project! canonical` | 删掉那一行 `projects`，**schema 的触发器**把它的会话解绑；`projects/` 下的日志一个字节不动 |

**移除一个项目 ≠ 删除一段对话**，而且这一点写在 schema 里而不是某个动词里：FK 的
`ON DELETE SET NULL` 明说「解除隶属」，`projects` 上的 `BEFORE DELETE` 触发器在同一句 DELETE 里把
`project_id` 与 `path` 一起清掉（只清一个会撞上 `CHECK`，级联会失败）。会话于是变成**普通的未绑定
会话**——`binding-for` 答 nil、围栏关掉，与从未绑过项目的会话逐字节同一种行为，这就是「移除」不需要
在任何别处加特例的原因。这条路显式**不写审计行**：审计行写在某个会话的日志里，而 workspace 是项目的
函数，它移除的正是那个项目。界面那一侧：行悬停出「更多」，确认框说的是「会话留在磁盘上」而不是
「删除」，页面绝不停在一个已被移除的项目上。

绑定是**默认值，不是围栏**——它重根文件工具与 shell：

- `resolve-path`：相对路径解析到项目目录；**绝对路径原样通过**。
- `binding-for`：`bash` 的 cwd、管理端点、agent 自问自答用的都是它。
- **未绑定的会话行为与绑定概念出现之前逐字节相同**（这套回归保证是这个命名空间的核心承诺）。

两个问句两个列，差别是承重的：

- `binding-for` 答**这个会话被绑定时用的那个拼写**——它要被回显、要被 shell 执行，
  不该因为别的会话用另一种拼写绑过同一目录而改变；
- `identity-for` 答**项目的身份**（canonical）——由项目派生的名字（比如日志 workspace）必须用它，
  这样一个项目的每个会话无论怎么拼写都落进同一个 workspace。

所以 `bind!` 同时写 identity（`projects.canonical_path`）与 spelling（`sessions.path`）。

## 围栏

`out-of-bounds?` 是**独立的、显式的**执行问题：一条已解析的路径是否落在一个绑定会话可以碰的目录之内。

允许集：

1. **项目目录本身**——除非项目的 `harness.edn` 写了 `:approval {:strict true}`（项目内也要审批）；
2. **配置家**（读自己的 `config.edn` / `providers.edn` / `.env` 是围栏刻意留的自留地，
   **strict 不收紧它**——配置家是 harness 自己的地盘，不是项目的）；
3. `:approval {:allow [..]}` 声明的额外路径，各自按工具路径的规矩解析（相对项目根）。

几条性质：

- **围栏只在有绑定时生效**，未绑定会话对每条路径都答 false（与 `resolve-path` 同一条回归保证）。
- **`bash` 刻意不动**：只约束 cwd 在项目目录，**命令内容永不判定**——`cat /etc/passwd`、管道、`cd ..`
  皆可出界。这是**明示接受的已知逃逸面，不是遗漏**。
- **审批是流程约定，不是安全边界**：它挡手滑，不承诺隔离（本仓 `bash` 已是任意代码执行，
  安全论据在更外层——部署环境）。

「要不要因为出界而 park 这次调用」是**执行缝**的问题（见 [kernel](kernel.md#悬置先问规则再问人)），
不是这个命名空间的——这里只回答「在不在里面」。

`no-session-slot`：`(bind! dir)` 这个单参数形式**被指名拒绝**，因为「没有会话的绑定」是这张 schema
装不下的东西（`sessions.id` 是 NOT NULL）。保留这个 arity 是为了让调用方的错**在错的地方**报出来，
而不是变成某个无关调用点上的 arity 错误。

## 项目级配置

`.harness/harness.edn` 两级装配（用户级 + 项目级），逐键替换、每次现读、坏文件指名硬失败——
详细规则见 [home-and-storage](home-and-storage.md#配置文件的两级装配)。

`.harness/` 下的 `skills/`、`mcp/`、`hooks/` 子目录**留给各自的消费者**；
`harness-config` 只认 `harness.edn` 一个文件（`hooks.edn` 由 `harness.hooks` 自己读）。

**注意这里源是混的**，而这是 home 的边界不是意外：**绑定来自库，配置来自文件**。
状态被改写，配置被手编——所以改 `harness.edn` 仍然不需要重启，而这次调用任何一步都不写库。

## 事件源：CwdChanged

绑定变更是 `CwdChanged` hook 点的**事件源**。事实的形状由纯函数
`cwd-changed` 锁定（`{:hook "CwdChanged" :thread_id .. :project_dir <新目录> :before <旧目录|null>}`，
snake_case 对齐 hook payload 约定）。

它**只产事实**：spawn 命令、超时、门禁都是 hook 引擎的事。

**「移除」可撤销的凭据是 `sessions.last_project_path`（schema v2）**：会话上一个项目的 canonical
路径，**不是绑定**（`binding-for` 永不读它，工具路径永不经它解析）。移除时触发器只清 `project_id`
与 `path`，这一列存活，所以**重新添加同一个目录会把会话接回来**——归档标记与历史都在（日志从没动过）。
**「已绑定」的会话不会被接回来**：`add-project!` 只收养 `project_id IS NULL` 的行，所以一个在移除
之后被搬到别处的会话不会因为一次「重新添加」而被拽回来。而**显式解绑会把这个记忆一起清掉**——差别
就是重点：让人把会话放掉就是放掉，移除项目则是对**目录**的陈述。
