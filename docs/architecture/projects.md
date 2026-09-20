# 项目、会话、围栏

## 两个词

- **项目** = 一个绝对目录，按 **canonical 路径**去重（`projects` 表一行一个目录）。
- **会话** = 一段对话，`thread-id` 是它的标识（`sessions` 表一行一个会话）。

一个会话**至多**属于一个项目。**未绑定是允许的**（服务端容忍库里没有的 thread id 跑起来，
日志落 `projects/.unbound/`）——这条边界上不引入「先建会话」的协议前置，否则整套既有测试都要先学会建会话。

**未绑定的会话分两种，而界面只把其中一种叫「任务」。** 库里两个列一起为 NULL 才算任务
（`project-id IS NULL` **且** `last-project-path IS NULL`）：一段从来没有过家的对话，
或者一个被人**显式放走**的会话（`bind! id nil` 连记忆一起清掉）。**移除项目放走的会话不是任务**——
它留着 `last_project_path`，正等着那个目录回来，`add-project!` 的收养就是为它写的。
这条分界只被一个读者用到（`project/tasks`，侧边栏那块平铺的列表），而它是承重的：
整张表按 `project_id IS NULL` 一句话筛，移除一个项目就会把它名下所有会话倒进任务里。

**一条会话什么时候“存在”也在这里。** `register-session!` 是 find-or-create 的一行（没有项目、
没有记忆），两个调用方：「新建任务」那条路由（`POST /api/sessions`），以及 AG-UI 那条边在第一次收到
一个库里没有的 thread id 时。**`bind!` 的 nil 方向照旧不建行**：放掉一段对话与开始保管它，
是两句不同的话。

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
2. **配置家**（读自己的 `config.edn` / `.env` 是围栏刻意留的自留地，
   **strict 不收紧它**——配置家是 harness 自己的地盘，不是项目的）；
3. **本会话的技能根**（`skill-roots`，默认 `<user-home>/.agents/skills` 与 `<项目>/.agents/skills`），
   与配置家**同级、同一条理由**，`:approval {:strict true}` 同样收不走；
4. `:approval {:allow [..]}` 声明的额外路径，各自按工具路径的规矩解析（相对项目根）。

**技能根为什么在里面，而指令内容为什么不在。** 技能正文常写「读 `references/x.md`」，那个路径就落在
技能目录里、项目目录之外——不放行的话每读一份参考文件都要人点一次批准，技能等于白装。
反过来，**一份 AGENTS.md 里提到的路径照常停泊**：允许的是「配置里说过的根」，不是「某份文档说可以读的
东西」——文档能给自己扩权是另一套安全故事。同一条边界还有一个推论：指令文件本身**不经过围栏**，
它们是服务端读的，不是 `read` 工具调的。（`out-of-bounds?` 因此 require `harness.cap.skills`，
而 `skills` 不许反向 require `project`——那条环见 [skills-and-instructions](skills-and-instructions.md)。）

几条性质：

- **围栏只在有绑定时生效**，未绑定会话对每条路径都答 false（与 `resolve-path` 同一条回归保证）。
- **`bash` 刻意不动**：只约束 cwd 在项目目录，**命令内容永不判定**——`cat /etc/passwd`、管道、`cd ..`
  皆可出界。这是**明示接受的已知逃逸面，不是遗漏**。
- **后台执行同样不挂审批**：`job` / `job_kill` 与 `bash` 是同一个逃逸面（一条命令就是一条命令），
  给它们单独挂个 park 是装样子。它们的 cwd 与 `bash` 同一处解析——**它们的记录也一样**：`job` 把命令
  的输出写进配置家的 `<root>/jobs/<会话>/<句柄>.log`，所以 `read` / `grep` 读它**不 park**（配置家是围栏
  的自由路径）。这是一句陈述，不是一条新规：围栏从一开始就把配置家列为自由。
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

`.harness/` 下的 `mcp/`、`hooks/` 子目录**留给各自的消费者**；
`harness-config` 只认 `harness.edn` 一个文件（`hooks.edn` 由 `harness.kernel.hooks` 自己读）。

**技能与指令不在 `.harness/` 下。** 它们的项目级位置是**宿主自己的约定**——`<项目>/.agents/skills/`
与 `<项目>/AGENTS.md`——因为同一个技能目录要同时服务于在场的每个 agent，而不只是 clj-harness；
`.harness/` 里放的仍然是「本产品的配置」。要改用别的路径就写 `:skills {:roots ..}` /
`:instructions {:files ..}`，见 [skills-and-instructions](skills-and-instructions.md#配置skills-与-instructions)。

`harness.cap.project/skill-roots` 与 `preamble-files` 是这两个键的**会话级答案**：位置解析本身是纯函数
（`(配置值, 项目目录)`，不查绑定），配对做在这里——只有 `project` 同时看得见配置读取、绑定、
以及两个刻意的纯函数消费方。

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
