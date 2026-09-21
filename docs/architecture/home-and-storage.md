# 配置根与存储

## 配置根：一个根，三层优先级

`harness.infra.home/root` 每次现读（读环境变量很便宜），顺序：

1. `*root-override*` —— **测试专用**的动态 var，测试运行器绑它到临时目录；
2. `CLJ_HARNESS_HOME` —— 环境变量，真实部署搬家的方式；
3. `~/.clj-harness` —— 默认。

三层都现读、不缓存：缓存了根，环境变量与测试绑定都会在进程中途失效。

**唯一的例外是 `prompt.md`**：它留在仓库里，因为它是被 review 的代码资产，每次改动都需要 git 历史。
它是 system 消息的**冻结开头**——同一条消息的其余部分每次 run 现算（见 [hooks](hooks.md)），
所以「住在仓库里」的是那半份**与任何会话无关的话**。

## 第二层 floor：OS 家目录

`harness.infra.home/user-home` 是 `(System/getProperty "user.home")`，**它不跟随 `CLJ_HARNESS_HOME`，
也不跟随任何配置**。住在那儿的是**宿主自己的约定文件**：`~/AGENTS.md` 与 `~/.agents/skills/`——
与 ZCode / Claude 读的是同一份。

**为什么是两层而不是一层。** 配置根是 harness 自己的地盘，`CLJ_HARNESS_HOME` 说的是「把这个产品的
配置搬到别处」；把技能目录挂在它下面，等于「换一次配置路径就让另一批技能凭空消失」。两层的答案不同，
所以它们是**兄弟而不是嵌套**。这条在测试缝上还有第二个后果：`*user-home-override*` 是与
`*root-override*` 并列的独立绑定，把用户家目录放进配置根里会让它落进围栏的允许集，
而「围栏放行哪些路径」正是围栏测试要问的问题——安排本身就会替它回答。

`user-home` 与 `root` 一样**每次现读、不缓存**：测试夹具会在进程中途移动它，缓存了那个绑定就失效了。

两个消费者，默认位置各一处（见 [skills-and-instructions](skills-and-instructions.md)）：
`skills/roots` 取 `<user-home>/.agents/skills`，`preamble/instruction-files` 取 `<user-home>/AGENTS.md`。

## 家目录里有什么

```
~/.clj-harness/
├── config.edn        **唯一一份配置**，两节：:default（三个旋钮的默认档）与
│                     :providers（厂商 endpoint + 它的 model 表）；每轮重读。
│                     没有就在**开机时**由组合根写一份空骨架（`ensure-config!`），
│                     读侧从不创建它
├── harness.edn       用户级 harness 配置（围栏的 allow/strict、技能根、指令文件都在这）
├── hooks.edn         hook 声明（每轮重读；可以不存在）
├── .env              一家厂商一把钥匙：`<ID>_API_KEY`（如 `ACME_GATEWAY_API_KEY`），
│                     外加全局 `HARNESS_API_KEY` 兜底与三个搜索键（Brave/Exa/Tavily）
│                     ——优先于真实环境变量，顺序见 harness.infra.home/env-source
├── harness.infra.db        sqlite：home 的元数据层
└── projects/
    ├── <sanitized-project-canonical-path>/
    │   └── <sanitized-thread-id>.jsonl
    └── .unbound/     不属于任何项目的会话
```

**日志按项目分 workspace，不按 thread 平铺。** workspace 名由项目**身份**（canonical 路径）
经 `home/sanitize` 得来，所以同一个目录的两份写法落进同一个 workspace。

`.unbound` 这个留白**由构造保住，不由约定保住**：sanitize 把 `[A-Za-z0-9._-]` 之外一律换成下划线，
而绝对路径必然以 `/`（或盘符）开头，所以项目名永远不会以点开头——反过来写字面 `_unbound`
会和 `/unbound` 撞名，两份互不相干的日志混进同一个目录。

**谁回答「日志在哪个目录」**：`harness.infra.home` 只知道根与命名规则，`harness.cap.project` 知道会话属于哪个项目，
两个事实在**写入侧**（`harness.edge.http/log-dir-for`）合起来。读侧（`harness.edge.replay`）只走文件系统、
由调用方递目录进去——**这是「内核 run 中永不读自己的日志」在代码结构上的样子**。

### 一个会话一份文件

**重放、重建、eval 读法各自都只读一个文件**，所以「一个会话一份文件」不是整洁癖，是这几条路能工作的前提：
一次会话的记录被劈进两个 workspace，那几条路谁都看不见另一半，而它们都不会报错——它们只是读到一段更短的
对话。于是**换绑要把文件一起搬**（`harness.edge.http/move-log!`）：搬之前先问整棵树「这个名字还有没有别的
`.jsonl`」，有就**按名字拒绝**（两份合起来会读成一个顺序错乱的对话，覆盖则毁掉一次 run 的记录），
由人决定哪一份才是那段对话。落空的来源不止重复绑定：库被隔离重建过、树从备份恢复过、进程死在
「库已提交、文件还没搬」之间——所以问的是树，不是那两个目录。

**一个 2026-09-18 的事故是这条规矩的来历**：库被移开重建、`project/identity-for` 一时答 nil，
活的 run 于是把记录写进 `projects/.unbound/`；库恢复后新记录又回到项目 workspace，**一次对话成了两份文件**。
现在写手自己会收拾：一条记录要写进项目 workspace 时，先看保留区里有没有这个 thread 的残留段，
**时间区间不重叠且顺序对**就把它 append 进来、再把源文件改名成 `<thread>.jsonl.carried-<stamp>`
（故意不以 `.jsonl` 结尾——它只是证据，不该被列表当成第二段对话），并留一行 `log/carried-back`。
重叠或倒序就拒绝（`log/carry-refused`，两边都不动），因为拼起来会读成一段错乱的历史。

**换绑与写手共用 `log-lock`，而且是两种事实共用一把锁**：一个决定「这条记录写哪个文件」
（`log!` 里连 `log-file-for` 都在锁内），一个决定「这个文件在哪」（`/api/project` 里读旧绑定、写库、
搬文件都在锁内）。缺了任何一半，一次**落在 run 中途**的 bind 就能和写手抢同一个重命名——
轻的是一句假的「日志搬不动」，重的是写手按新绑定另开一个文件、而旧段被判定重叠而拒绝归位，
**一次会话真的被劈成两份**。这不是罕见路径：侧栏现在是**发送才建会话**，所以「绑的时候 run 正在写」
是常态（见 [client](client.md)）。判据是一条后端用例：
`a-bind-that-arrives-while-the-writer-is-mid-run-leaves-one-file`——十二次换绑、一个不停的写手，
两边的次序随便怎么交错，最后必须只有一份文件、每一行都在、且按顺序。

**`~/.clj-harness/logs/` 现在住着另一样东西，别和上面那棵树混起来。** 它是**后端自己的
运行日志**（`harness.infra.log`，按日期与大小 rotate，见 `harness.infra.logging`），不是会话日志：会话日志是
`projects/<workspace>/<thread>.jsonl`，是**记录**；`logs/harness.infra.log` 是**诊断**，没有任何会话读它，
删掉也不会丢一段对话。旧版那种**平铺的会话** jsonl 也曾经住在这个目录名下，
**那批退了役，而且不导入**。
不迁移、不从文件名反推归属、不为了「看起来没丢」把它们塞进某个项目。理由在库与文件的分工里——文件名
不含项目身份，自动迁移只能猜，而猜错的表现是一个会话**悄悄挂到别的项目下**，比看不见更难发现。
字节和 mtime 一个都不动（它们只是不再是本产品的视图），要接着用就手动挪进 `projects/<workspace>/`。
`/api/threads` 与 `/api/projects` 都不扫它，重建按 stem 找也找不到它——**这三条各有各的机制会把它复活**，
所以有一条用例分别钉住。

**这份诊断日志记什么**：每个 run 起止各一行（`run/start` / `run/terminal`）、流的关闭（`run/stream-closed`，
带 http-kit 自己的 status），以及**三类本来会静默的结局**——没跑起来的工具（`run/tool-not-run`，只写非 `:pass`
的 outcome，普通调用不写）、没有终帧就结束的流（`run/events-closed-without-terminal` 是事件通道先关，
`run/stream-closed-before-terminal` 是流先关）、以及 run 自己抛出的异常（`run/crashed`：core.async 会把 go block
的异常丢进没人读的 channel，不裹起来就是彻底静默加上客户端干等）。进程退出本身也留一行（`:shutdown`）。

**读一份断掉的记录**：`projects/<workspace>/<thread>.jsonl` 停在半句——典型是 `tools/pre-execute` 之后没有
`tools/execute`——就把这份日志按 `thread-id` 过滤，四件事一次看清：有没有 `run/terminal`（这句「再见」说没说完）、
最后一条的 `:last=` 停在哪一步、有没有 `run/crashed`（是服务端自己抛的）、以及后面有没有 `:shutdown`（进程是不是
被停掉的）。`SIGTERM` 会写 `:shutdown`，`SIGKILL` 与崩溃不会（都实测过），所以**没有这一行不等于没死**，
它只在和「run 没有终帧」一起读的时候给答案。

**服务端看不出「浏览器走了」**，这是这套日志的边界而不是它的疏漏：客户端 abort 之后 run **不会**被取消
（没有任何东西取消它，服务端会照跑到底），而且这个 socket 也不会告诉服务端对端离开了——实测客户端 RESET 之后
服务端又写了四千帧，`send!` 每帧都返回成功，`on-close` 直到服务端自己关闭才触发。浏览器那句
`BodyStreamBuffer was aborted` 因此是**客户端侧的措辞**，服务端没有对应的事实可记；能分辨「人停了」与「进程停了」
的只有上面那几行的组合。

## 配置文件的两级装配

三份配置都是**用户级 + 项目级**两层，用户级在配置家，项目级在 `<project>/.harness/`：

| 文件 | 合并方式 | 含义 |
|---|---|---|
| `harness.edn` | 顶层浅合并，项目级**整键替换** | 项目写了 `:approval` 就整个换掉用户的 |
| `hooks.edn` | 逐**点**替换 | 项目写 `:pre-tool-use` 就整个换掉用户的那些声明 |
| `mcp.edn` | 逐**表**替换（`:servers` 是它唯一的键） | 项目声明自己的服务器集合 |

**都不深合并、都不做并集**，理由相同：「实际会跑什么」应该在一个文件里读得出来，
而不是从两个文件怎么嵌套里推。代价照旧的接受：项目只想加一条声明，得把它要的那些一起写出来。

**`harness.edn` 还有一个方向更远的替换：`:skills {:roots ..}` 与 `:instructions {:files ..}`
整表替换的是那两个半边的「内置默认」**——不是用户级对项目级，而是「配置说了什么」对「宿主约定位置」。
写 `{:instructions {:files ["AGENTS.md"]}}` 的会话只读项目那一份，`<user-home>/AGENTS.md` 整个退出画面。
空向量 `[]` 是合法的，意思就是「什么都不读」。两个键的段本身必须是 map：`{:skills 42}` 在问它要 `:roots`
**之前**就指名失败，因为 `contains?` 撞上非 map 抛的是一句既不说键也不说文件的 JVM 错误。
细节见 [skills-and-instructions](skills-and-instructions.md#配置skills-与-instructions)。

纪律也是一致的：**缺失 = `{}`（不是错误），存在却坏 = 指名绝对路径硬失败**。
一份被静默忽略的配置，与一份什么都没说的配置，从外部看没有区别——而那个区别正是这些文件的全部意义。

**都要现读**（config.edn 纪律），所以改配置不需要重启。

## sqlite：home 的元数据层

`harness.infra.db` 是本仓**唯一的二进制依赖**，而且是刻意引的：它存在的理由是**一次写入多个事实**——
一个临界区里推进若干条状态，这正是事务要做的事。

### 库与文件的边界

| 进库 | 留在文件 |
|---|---|
| 会被**改写**的状态：项目、会话归属、归档、hashline 的锚点 | 只追加的记录：会话 jsonl |
| | 手编的配置：`config.edn` / `harness.edn` |

判别标准**不是「改得勤不勤」，是「能不能被改写」**。推论：

- **库不是日志索引**：jsonl 里的任何内容都不进库——没有消息表、没有全文索引、没有会话摘要。
- 库也不镜像文件大小与 mtime：那是**记录**的属性，读的时候现问文件。**但「上次发送时间」进库**，
  因为它不是文件属性：那是**人按了发送**的那一刻（`sessions.last_sent_at`，每一次 run 的 input 到达时
  重写，与标题同一句 `UPDATE`），跟「日志最后被追加是什么时候」是两回事——一次跑五分钟，
  这个数停在按下去的时候。侧栏那一列要的正是它（见 `.scratch/store-backed-sidebar/spec.md`）。

### 三张表

**第一张是记账的，不是状态的**：`schema_steps` 记这个库跑过哪些**具名**迁移步骤。它取代了
`user_version` 作为判断依据——版本号是迁移链的**下标**，只对一条链有意义，而两个分支会各自在同一个
下标追加步骤（`hashline-edit` 在下标 1 追加 `hashline-store`，main 在那里追加
`sessions-remember-the-project-path`），于是同一个数字有了两个意思，而本机真 home 的库正是被其中一条链
迁过之后，**另一条再也打不开**。`user_version` 还在写，但只是面包屑，没有任何代码从它做决定。

**合并之后这两条链合成了一条**：`projects-and-sessions`、`sessions-remember-the-project-path`、
`hashline-store`、`hashline-served`、`hashline-undo-served`、`todos`、`sessions-remember-their-title`、
`sessions-remember-their-last-send`。每一步还带一个 `:present?` 探针回答
「这份 schema 里已经有了吗」，所以被**任一**条旧链迁过的库都打得开：认识的步骤**记为已做**而不是重跑，
不认识的表是惰性的。本机真 home 那个库就是这么被治好的——它缺的列由探针发现并补上，不再需要手写 ALTER。

```sql
CREATE TABLE projects (
    id             INTEGER PRIMARY KEY,     -- rowid 别名，不用 AUTOINCREMENT（不引入 sqlite_sequence）
    canonical_path TEXT NOT NULL UNIQUE,    -- 一个目录一行，无论怎么拼写
    created_at     INTEGER NOT NULL);

CREATE TABLE sessions (
    id         TEXT PRIMARY KEY NOT NULL,   -- SQLite 的 TEXT PRIMARY KEY 不隐含 NOT NULL，显式写上
    project_id INTEGER REFERENCES projects(id) ON DELETE SET NULL,
    path       TEXT,
    archived   INTEGER NOT NULL DEFAULT 0,
    created_at INTEGER NOT NULL,
    CHECK ((project_id IS NULL) = (path IS NULL)),
    last_project_path TEXT,                 -- 由 sessions-remember-the-project-path 加
    title      TEXT,                        -- 由 sessions-remember-their-title 加
    last_sent_at INTEGER);                  -- 由 sessions-remember-their-last-send 加

CREATE TABLE schema_steps (
    name       TEXT PRIMARY KEY NOT NULL,   -- 步骤的**名字**即身份，改名字等于重跑
    applied_at INTEGER NOT NULL);
```

**两个表各答一个问题，一个装身份、一个装拼写**：`projects.canonical_path` 是**身份**
（一个目录永不变成两个项目）；`sessions.path` 是**这个会话被绑定时用的那个拼写**
（`bind!` 原样回显、shell 在里面跑——这些都不该因为别的会话用另一种拼写绑过同一目录而改变）。

**未绑定是一种状态，不是两种**：`project_id` 与 `path` 同生共死，`CHECK` 拒绝半绑定的行。

**移除项目 = 解绑它的会话，不删会话**。这在 schema 里是两处配合：

- FK `ON DELETE SET NULL` 明说「解除隶属，不删除」；
- 但光靠它会把行清成「project 空、path 还在」——正是 `CHECK` 要拒的半绑态，级联失败、删除也跟着失败。
  所以 `projects` 上有一个 **BEFORE DELETE 触发器**，一条语句把两列一起清掉，级联随后无事可做。

**`title` 是这个库里唯一一列对话内容**，而它进得来是主人**推翻**过一条守卫的结果（2026-09-21）：
`harness.infra.db-test/sessions-hold-no-conversation-content` 原先点名的例子正是「给 `sessions` 加一个
`title` 列」。它能被推翻而守卫本身仍然成立，靠的是**第一句永远不会变**——存下来的是会话**获得**的一个
名字，不是会长的记录的副本（`summary` 那种才是这条守卫要防的第二份真相）。写它的是**第一次收到消息的
那次 run**（`harness.edge.http/run-agent!`，与 `input` 帧同一处），`AND title IS NULL` 保证只写一次；
**不回填**（主人的判决）：这一列之前跑过的会话没有值，侧边栏退回显示 thread-id，直到它下一次再跑
——那次 run 的 input 带着整段历史，第一条 user 消息仍旧是第一句。代价写在
`sessions-remember-their-title` 的 docstring 里：手改过的日志会和这一列不一致，日志删了标题还在。

**`last_sent_at`（`sessions-remember-their-last-send`）是侧栏那一列**：每次 run 的 input 到达时重写，
与标题同一句 `UPDATE`（`cap.project/remember-send!`），所以「人按了发送」与「这个会话叫什么」是一次写入的
两个事实。**它回填**（与标题相反）：迁移步顺手把老行的值填成那条日志文件的 mtime——正是列表从前显示的那个
数（语义是「上次活动」，与新值差一次跑的时长，docstring 里写明），不填的话本机几十条老会话会在侧栏上
一个时间都没有。这是**全仓唯一一次读磁盘的迁移**，它也因此是这一列最后一次看文件：此后再没有
「没有就去看文件」的兜底分支。stem → 文件的查找补在 `harness.infra.home/log-file-for-stem`
（infra 里不许 require edge，所以不是 `replay/locate`）。

**`last_project_path`（v2）是「移除项目可撤销」的凭据**：它的值是会话上一个项目的 canonical 路径，
**不是绑定**（`binding-for` 永不读它，工具路径永不经它解析）。移除项目时触发器只清 `project_id` 与 `path`，
这一列存活下来，所以**重新添加同一个目录会把会话接回来**（归档标记与历史都在）。
而**显式解绑**（`bind! thread nil`）会把它一起清掉——差别就是重点：让人把会话放掉就是放掉，
移除项目则是对**目录**的陈述。

### 锚点的四张表

按锚点编辑在这里落盘（`harness.cap.hashline.store`）。四张表答的是**同一个想法的四个问题**——一行的四字母
名字归谁、命名的是哪一版：

| 表 | 装什么 |
|---|---|
| `hashline_snapshots` | **一个文件被一个会话上次看到的那个样子**：整文件校验和、行数、逐行锚点与逐行校验和。主键 `(path, thread_id)` |
| `hashline_ownership` | **一个会话手上有哪些锚点**：anchor → 它命名的那一个文件。锚点的排他性就是它——铸造时走开这里已有的，陈旧或借来的锚点在这里查不到即拒。主键 `(thread_id, anchor)` |
| `hashline_sessions` | **一个会话的锚点探针停在哪**：就是下一次铸造从池子里哪个位置开始走。存下来，会话就不必每次从种子重走，重启也接着上次的位置 |
| `hashline_undo` | **唯一那笔可以撤回的编辑**，按文件：改之前正文、它的编码、命名它的锚点、改完之后正文。编辑写它、撤销读它，**`write` 清它**（那是「这个文件不再是模型看的那个」的边界） |

**快照为什么按 `(path, thread_id)` 而不是只按 path。** 上游按 path 存，读起来天然，直到注意到那一行装的
是**锚点**：锚点是给**一个**会话铸的，不是另一个会话可以用的名字。只按 path 存会把 A 手上的锚点交给 B，
B 的编辑就用 A 拥有的名字寻址。拆开主键，就是「两个会话读同一个文件」变成两行而不是一次竞争——
代价是每个会话多一份整文件校验和。

**锚点与校验和为什么是列里的 JSON 而不是一行一行。** 一行一行的话，一个大文件就是一万行，每个会话一份，
而本仓的规矩是「一张表要有人做一次决定」（`harness.infra.db-test` 的元断言）。这些列各自是**一个值**——
数组，整写整读，从不按元素查——给它们建表什么也买不到，还要付「会话读过的每个文件 × 行数」的代价。

**列名是对着正则挑的。** `harness.infra.db-test` 禁止任何看起来像对话内容的列名，`prior_text` /
`resulting_text` 直说里面装的是什么（文件正文，改前 / 改后），而 `content` 既会踩那条守卫、又说不清
是哪一份正文。它们在这里算**状态**的判据是：**每次编辑都重写**，而且没有它们撤销就不存在。

### 任务清单的表

`todos` 装**一个会话的待办**（`harness.cap.todos`，写它的工具是 `todo_write`）：

| 表 | 装什么 |
|---|---|
| `todos` | **一个会话的清单，一行**：`thread_id`（主键）、`items`（清单本身，JSON 数组）、`updated_at` |

**它是状态的判据在「写」里，不在「行」里。** `todo_write` 每次都送**完整**清单并**整份替换**，
没有追加、没有部分更新——所以「能被整份改写」这条判据在这里成立，而「改过几次」没有任何人需要。
这也是它进库、而不是留在对话里的理由：清单要活过一次 run（重启、另一个进程、将来某个面板读它），
而对话是客户端手里的东西。

**一行而不是一项一行**，与上面锚点那两张表同一条理由：`items` 是**一个值**，整写整读、从不按元素查，
读它的人渲染整个列表。逐项建表买不到任何东西，还要多一个「位置」列来维护——而读写它的调用本来就
是整份的。`items` 这个列名同样是对着那条正则挑的：叫 `content` 会既踩守卫、又说不清是哪一份内容。

### 迁移

`migrations` 是一条**只增不改**的链，每一步是 `{:name :present? :run}`。**名字即身份**：store 把做过的
步骤名记进 `schema_steps`，所以重命名一个步骤等于让它重跑。链长仍写进 `user_version`，但那只当面包屑。
**落过地的步骤永不修改**——外面已经有 store 跑过它了。

- 每一步与它的记录在**同一个事务**里，所以抛了就是原地不动，不存在半迁移、也不存在「记录了却没做」。
- `:present?` 探针是**认领老库**的路径：一份在 `schema_steps` 存在之前写下的库没有记录，版本号又不
  可信（见上），于是让探针回答——schema 里已经有那件事的**记为已做**，没有的才跑。
- 应用标识（store 的「这是谁的文件」）不是一步，它是文件创建时盖的；`schema_steps` 本身也不是一步，
  因为记录步骤的那张表没法记录自己。
- **为什么只有一条链**：schema 版本是关于文件的全序事实，所以产生它的步骤必须在一条链里、
  在一个顺序上；按租户拆开再在加载时拼起来，正是这个 store 要避免的注册机制。
  **DDL 住这里（store 拥有 schema），各租户表上的查询住在实体自己那里**（`harness.cap.project`、`harness.cap.hashline.store`）。
- 打开时若文件**不是**本 store 的，按三种情形**指名拒绝、一个字不写**：
  `:not-sqlite`（压根不是 sqlite 文件）、`:foreign`（是别人的 store）、`:unidentifiable`（认不出来是谁的）。
  本 store 但**坏掉**的，则**隔离**（挪成 `.corrupt-*`）并重建，同时记一条 recovery；重建再失败就是硬错误。
  **不覆盖、不猜**——一个文件为什么在那儿，永远是个要解释的问题，不是要顺手清掉的问题。

## 日志的读侧：frames / replay

- `harness.kernel.frames` 把记录的 AG-UI 帧**折叠回消息列表**（`terminal?` / `apply-frames`）。
- `harness.edge.replay` 重建对话：`threads`（扫目录列清单）、`locate`（stem → 唯一文件）、
  `rebuild`（种子 = 第一条 input、折叠全部 event 帧、把 context 带回来）。

**重建 = 交还，不是接管**：服务端把重建结果交给客户端持有，之后照常走 AG-UI；
服务端不因此成为会话状态权威，也不引入第二条流式路径。

**两个读法，两件事**（2026-09-20）：`rebuild`（POST）是「**交给我，我接手**」——它会把停在半途的日志合上（一个写动作），
`sofar`（GET）是「**给我看看**」——只折已记下的帧、一个字不写，答案里带状态（`running` / `parked` / `settled`）。
客户端要轮询一条**正在被写**的记录，只能用后者：前者每次轮询都会去修它正在读的那个文件，而且本来就会被拒绝
（活的 run 在 `ensure-complete!` 眼里就是截断）。同一个折叠（`replay/fold-frames`）被两个读法共用：
`records->messages` 折之前先判完整（继续那条路要走它），`messages-so-far` 不判（只看「已经到了什么」）。
而**合上那一步（`close-off-open-run!`）动手之前先问 `running?`**：文件里的「没有终帧」不是「已经死了」，
一条本进程还在答的 run 被合上就会有两个终结帧——一份「每一行都为真」的记录里的一句假话。

**坏 JSON 行仍然拒绝**（指名行号）→ 400。**跑到一半就断的日志在重建时「合上」而不是拒绝**：有 input
而无终结帧的那一轮，末尾补上「每个没回来的调用一条 `TOOL_CALL_RESULT`」再加一个 `RUN_ERROR`，并在这些帧
**之前**落一行 `session/closed-off`（哪一轮、断在哪一帧、补了什么）。补 result 不是整理：重建结果要被当作
下一轮 history 交给厂商，而 `tool_calls` 没有对应 tool 消息正是厂商会拒的形状。终结帧取 `RUN_ERROR` 而不是
`RUN_FINISHED`——没走到终结帧的那一轮就是没跑完，而这份追加记录唯一的资产是「每一行都为真」。**读侧仍然拒绝**
（`lines->messages` 不变）：静默折叠半个 run 才是这条契约要防的事，所以合上只发生在「有人要求继续」这个动作上。

**哪一轮没终结，按 run id 认，不按位置认**（2026-09-18）：日志里每条 input 行带它开启的 run id，每条帧行带发出它的
run id——写侧一直守着这个配对（合上一轮时，补的帧就落在**那一轮**的 id 下）。所以「哪个 run 没结束」是 id 的问题。
按「最后一条 input / 最后一条终结帧」来问，在**一个 thread 同时有两个 run 在飞**时就不是答案：它们的行交错在同一份文件里，
**最后结束的那个 run 可以排在那个从未结束的 run 之后**。那次事故正是这个形状：三条 run 同时在跑，进程在中间被停，
一条更早的 run（`Cuqwlef`）没有终结帧，而最后一条 input 的 run 正常收尾——拒绝侧数着「6 个 input 对 5 个终结帧」
拒绝，修复侧问「最后一条 input 的 run 结束了吗」答「结束了」，于是**一个字的修复都不做**，这份日志永远读不出来。
现在拒绝与修复走**同一次遍历**（`replay/runs` → `open-runs`）：拒绝指名那个 run 与它自己的最后一帧，
修复给出**每一条**没终结的 run 的补帧（各带自己的 `session/closed-off`，各落在自己的 run id 下）。
读侧对截断日志照旧拒绝——合上仍然只发生在「有人要求继续」那一刻。
stem 什么都指不到、或指向两个 workspace 里同名的两份日志 → 404（两份都指名）：「没找到」什么也没重建，
那是 404 不是 400。

**第五问：「这一轮还在跑吗」——记录答不了，问登记表**（2026-09-20）。上面四问都是问文件的，唯独这一问答不了：
一个 input 行后面没有终结帧，既可能是**运行中**，也可能是**进程被杀了**——两种留下的是同一份文件，
`open-runs` 只能数行，数不出这个区别。**而这个区别是三种读法的分界**：还在跑 ⇒ 读要声明 `partial?`、
收尾那条路不许动文件（票 02）、composer 要关着（票 04）。所以它是**进程自己的一个事实**，
落在 `harness.edge.http/live-runs`（`thread-id -> {:run-id ..}`）：`run/start` 那一行旁边登记
（**在拒绝之后**——没跑起来的 run 登记了就永远没人注销），注销有三处终点：终结帧（`runner`）、
通道没给终结帧就关掉、以及 `run/crashed`；`unregister-run!` 按 run id 比对着删，所以任何一个终点
都不会误删**另一条** run 的登记。它暴露在侧边栏本来就读的那一行上（`session-row` 的 `:running`），
不放进 `stats` / `trajectory` 那两条折记录的读法里——**这个事实不在记录里**，塞进去会让那两条说谎。

**只有审计行、没有任何 run 的日志是合法的空对话**（`{:messages []}`）——每个会话都从那个状态开始
（「绑定即出生」：新建任务先落一个绑定，写一行 `project/bound`，于是刚建的会话就**有文件而没有 run**）。
把它当成「截断」会让刚建的会话打不开。

## 配置不搬进库，而且看得见

`config.edn` / `harness.edn` / `hooks.edn` **不搬进库**：它们是手编的配置，库装的是
会被**改写**的状态。两边各自现读，所以改配置不需要重启，打开库也不会去读配置文件。

**一份配置一个文件**：厂商目录与默认档是 `config.edn` 的两节（见 [providers](providers.md)）。
它从前是两份文件（`config.edn` + `providers.edn`），合并的理由是那两节回答的是同一个问题的两半——
「这台机器能到哪些厂商、从哪一个开始」——而一个人回答它时不该开两个文件。

这条纪律唯一看得见的地方是「设置」那一版只读报告（`GET /api/settings`）：它每次调用重读全部配置，
所以改一个文件再问一次就是新答案。**「库装状态、文件装记录」这条边界可以这样测**：
库那边的用例断言打开 store 不读 `config.edn`，而这边的用例断言问配置不写库、不建库。
