# 配置根与存储

## 一个根，三层优先级

`harness.home/root` 每次现读（读环境变量很便宜），顺序：

1. `*root-override*` —— **测试专用**的动态 var，测试运行器绑它到临时目录；
2. `CLJ_HARNESS_HOME` —— 环境变量，真实部署搬家的方式；
3. `~/.clj-harness` —— 默认。

三层都现读、不缓存：缓存了根，环境变量与测试绑定都会在进程中途失效。

**唯一的例外是 `prompt.md`**：它留在仓库里，因为它是被 review 的代码资产，每次改动都需要 git 历史。

## 家目录里有什么

```
~/.clj-harness/
├── config.edn        模型默认档（三个旋钮，每轮重读）
├── providers.edn     provider 目录：厂商 endpoint + 它的 model 表（每轮重读）
├── harness.edn       用户级 harness 配置（围栏的 allow/strict 在这）
├── hooks.edn         hook 声明（每轮重读；可以不存在）
├── .env              HARNESS_API_KEY（优先于真实环境变量）
├── harness.db        sqlite：home 的元数据层
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

**谁回答「日志在哪个目录」**：`harness.home` 只知道根与命名规则，`harness.project` 知道会话属于哪个项目，
两个事实在**写入侧**（`harness.http/log-dir-for`）合起来。读侧（`harness.replay`）只走文件系统、
由调用方递目录进去——**这是「内核 run 中永不读自己的日志」在代码结构上的样子**。

## 配置文件的两级装配

三份配置都是**用户级 + 项目级**两层，用户级在配置家，项目级在 `<project>/.harness/`：

| 文件 | 合并方式 | 含义 |
|---|---|---|
| `harness.edn` | 顶层浅合并，项目级**整键替换** | 项目写了 `:approval` 就整个换掉用户的 |
| `hooks.edn` | 逐**点**替换 | 项目写 `:pre-tool-use` 就整个换掉用户的那些声明 |
| `mcp.edn`（未落地） | 逐**表**替换 | 见 `.scratch/mcp/` |

**都不深合并、都不做并集**，理由相同：「实际会跑什么」应该在一个文件里读得出来，
而不是从两个文件怎么嵌套里推。代价照旧的接受：项目只想加一条声明，得把它要的那些一起写出来。

纪律也是一致的：**缺失 = `{}`（不是错误），存在却坏 = 指名绝对路径硬失败**。
一份被静默忽略的配置，与一份什么都没说的配置，从外部看没有区别——而那个区别正是这些文件的全部意义。

**都要现读**（config.edn 纪律），所以改配置不需要重启。

## sqlite：home 的元数据层

`harness.db` 是本仓**唯一的二进制依赖**，而且是刻意引的：它存在的理由是**一次写入多个事实**——
一个临界区里推进若干条状态，这正是事务要做的事。

### 库与文件的边界

| 进库 | 留在文件 |
|---|---|
| 会被**改写**的状态：项目、会话归属、归档、hashline 的锚点 | 只追加的记录：会话 jsonl |
| | 手编的配置：`config.edn` / `providers.edn` / `harness.edn` |

判别标准**不是「改得勤不勤」，是「能不能被改写」**。推论：

- **库不是日志索引**：jsonl 里的任何内容都不进库——没有消息表、没有全文索引、没有会话摘要。
- 库也不镜像文件大小与 mtime：那是**记录**的属性，读的时候现问文件。

### 两张表（schema version 2）

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
    last_project_path TEXT);                -- v2 加的
```

**两个表各答一个问题，一个装身份、一个装拼写**：`projects.canonical_path` 是**身份**
（一个目录永不变成两个项目）；`sessions.path` 是**这个会话被绑定时用的那个拼写**
（`bind!` 原样回显、shell 在里面跑——这些都不该因为别的会话用另一种拼写绑过同一目录而改变）。

**未绑定是一种状态，不是两种**：`project_id` 与 `path` 同生共死，`CHECK` 拒绝半绑定的行。

**移除项目 = 解绑它的会话，不删会话**。这在 schema 里是两处配合：

- FK `ON DELETE SET NULL` 明说「解除隶属，不删除」；
- 但光靠它会把行清成「project 空、path 还在」——正是 `CHECK` 要拒的半绑态，级联失败、删除也跟着失败。
  所以 `projects` 上有一个 **BEFORE DELETE 触发器**，一条语句把两列一起清掉，级联随后无事可做。

**`last_project_path`（v2）是「移除项目可撤销」的凭据**：它的值是会话上一个项目的 canonical 路径，
**不是绑定**（`binding-for` 永不读它，工具路径永不经它解析）。移除项目时触发器只清 `project_id` 与 `path`，
这一列存活下来，所以**重新添加同一个目录会把会话接回来**（归档标记与历史都在）。
而**显式解绑**（`bind! thread nil`）会把它一起清掉——差别就是重点：让人把会话放掉就是放掉，
移除项目则是对**目录**的陈述。

### 迁移

`migrations` 是一条**只增不改**的链：`(nth migrations i)` 把 store 从版本 i 带到 i+1，
链长就是本 harness 说的版本。**落过地的步骤永不修改**——外面已经有 store 跑过它了。

- 每一步与版本号升级在**同一个事务**里，所以抛了就是原地不动，不存在半迁移。
- 应用标识（store 的「这是谁的文件」）不是一步，它是文件创建时盖的。
- **为什么只有一条链**：schema 版本是关于文件的全序事实，所以产生它的步骤必须在一条链里、
  在一个顺序上；按租户拆开再在加载时拼起来，正是这个 store 要避免的注册机制。
  **DDL 住这里（store 拥有 schema），各租户表上的查询住在实体自己那里**（`harness.project`，将来还有锚点存储）。
- 打开时若文件**不是**本 store 的，按三种情形**指名拒绝、一个字不写**：
  `:not-sqlite`（压根不是 sqlite 文件）、`:foreign`（是别人的 store）、`:unidentifiable`（认不出来是谁的）。
  本 store 但**坏掉**的，则**隔离**（挪成 `.corrupt-*`）并重建，同时记一条 recovery；重建再失败就是硬错误。
  **不覆盖、不猜**——一个文件为什么在那儿，永远是个要解释的问题，不是要顺手清掉的问题。

## 日志的读侧：frames / replay

- `harness.frames` 把记录的 AG-UI 帧**折叠回消息列表**（`terminal?` / `apply-frames`）。
- `harness.replay` 重建对话：`threads`（扫目录列清单）、`locate`（stem → 唯一文件）、
  `rebuild`（种子 = 第一条 input、折叠全部 event 帧、把 context 带回来）。

**重建 = 交还，不是接管**：服务端把重建结果交给客户端持有，之后照常走 AG-UI；
服务端不因此成为会话状态权威，也不引入第二条流式路径。

**拒绝而非猜**：坏 JSON 行（指名行号）、跑到一半就断的日志（有 input 而无终结帧）→ 指名 400；
stem 什么都指不到、或指向两个 workspace 里同名的两份日志 → 404（两份都指名）。
重建半截对话是最坏的失败模式，而「没找到」什么也没重建，那是 404 不是 400。

**只有审计行、没有任何 run 的日志是合法的空对话**（`{:messages []}`）——每个会话都从那个状态开始
（「绑定即出生」：新建任务先落一个绑定，写一行 `project/bound`，于是刚建的会话就**有文件而没有 run**）。
把它当成「截断」会让刚建的会话打不开。
