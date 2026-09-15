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
