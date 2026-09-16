# layer-layout —— 扁平 → 四层

`src/harness/` 现在是扁平的 33 个命名空间：基建（`home` / `db` / `logging` / `log` / `shell`）、
内核机制（`event` / `loop` / `llm` / `tools` / `hooks` / `editing` / `frames`）、
能力（`hashline/*` / `skills` / `preamble` / `system-prompt` / `project` / `providers` / `git`）、
以及对接前后端的三个（`ag_ui` / `http` / `replay`）全在同一个目录下，
而**依赖方向是乱的**：核心层的 `tools` / `llm` / `loop` / `editing` 反过来 require 能力层。

本特性把它们分成四层，并把那些反边断掉——不是靠新加一个目录，是靠让核心层真的不再认识能力。
落地方式是 13 张票：先纯改名（02–06，行为零变化），再逐条断依赖（07–12），最后加一条守卫测试
把规则钉住（13）。

**本文件是这 13 张票共用的判断来源。** 每张票都要回答「这个命名空间属于哪一层」，
答案在这里，不在这里的都在票面上。

---

## 一、四层与判据

| 层 | 前缀 | 是什么 | 一句话判据 |
|---|---|---|---|
| 基建 | `harness.infra` | 东西落在哪、错了写哪、进程怎么起来 | 与「这个 harness 会做什么」无关，换个产品也照样需要 |
| 核心 | `harness.kernel` | **只提供机制，不提供能力** | 说得清「怎么做」，说不清「做什么」 |
| 能力 | `harness.cap` | 这个 harness 具体会做的事 | 说得出「它会做什么」，去掉它 harness 就少一样能力 |
| 适配 | `harness.edge` | 对接前后端：AG-UI 帧、HTTP 边、日志记录 | 把内核翻译成别人的协议 |

判据要能用一次，不是每次凭感觉：**删掉它，内核还转不转？** 删掉一个能力，内核照转，只是少做一件事
（删光 `cap.tools` 的内建表，循环仍然流式、仍然调 provider、只是工具表空了）。
删掉一个机制，内核自己的部件就散了。

---

## 二、归属表（落地之后的 35 个，一个不漏）

### `harness.infra`（5）

| 命名空间 | 是什么 |
|---|---|
| `home` | 配置根，决定每个文件落在哪。**两层 floor**：`root`（配置家，`CLJ_HARNESS_HOME` 可搬）与 `user-home`（OS 家目录，**不跟随**） |
| `db` | home 的 sqlite 元数据层：迁移链（步骤按名字记账）、项目/会话/记账三张表加锚点四张表 |
| `logging` | 用代码配 Logback：`SizeAndTimeBasedRollingPolicy`，日期与大小一起 rotate；`ensure!` 在 root 变动时重配 |
| `log` | 一次调用同时写 stderr 与文件的门面：`kind` + 排序过的 `k=v`，Throwable 直通 |
| `shell` | 唯一决定 spawn 哪个 shell 的地方（bash 工具与 hook 引擎共用） |

### `harness.kernel`（7）

| 命名空间 | 是什么 |
|---|---|
| `event` | 11 种事件的词汇 |
| `frames` | 帧折回消息（AG-UI 帧 → 消息列表） |
| `loop` | ReAct：流式一轮 → 并发跑工具 → 追加结果 → 再一轮 |
| `llm` | provider 协议层（一个按 `:protocol` 分派的 multimethod）+ **冻结开头的载体**（`prompt.md`） |
| `tools` | **缝**：注册表、会话 overlay 两轴、待决审批、三相执行、工具声明的词汇、**以及「谁可以从这张表里减名字」这个槽** |
| `hooks` | 点表（27 行是数据）、一条声明的契约与校验、来源**档位与排序** |
| `hooks/dispatch` | **引擎**：跑一条声明（spawn 命令或调进程内函数）、读退出码、超时、审计行 |

### `harness.cap`（20）

| 命名空间 | 是什么 |
|---|---|
| `hashline/{anchors,store,serve,reading,edit,replace,insert,undo,write,grep,files}` | 按锚点编辑的全部实现（11 个） |
| `tools` | **内建 11 个工具的定义**：`read` / `write` / `edit` / `replace` / `insert` / `undo_last_replace` / `anchor_grep` / `bash` / `eval` / `skill` / `session-configure` |
| `editing` | **两套编辑实现的名字与账**：`:hashline` / `:str-replace`、每个模式服务哪些工具名、两级 `harness.edn` 的解析与 `:editing` 逐键合并那个唯一例外、那句拒绝话术 |
| `hooks` | hook 的**来源**：读配置家的 `hooks.edn` 再叠上绑定项目的 `.harness/hooks.edn`（两级浅合并） |
| `system-prompt` | system 消息的组装 + 内核自己那三条内建 hook 行（工具集合 / 工程目录 / provider 档） |
| `project` | 项目与会话绑定、路径重根、围栏、`harness.edn` 两级装配、`skill-roots` |
| `skills` | 技能：默认根、目录名即身份、`SKILL.md` 的窄 frontmatter、坏技能是诊断、正文的派生注入 |
| `preamble` | user 侧开场块：指令文件与技能清单，以及它们的**顺序** |
| `providers` | 厂商 → model 表、三档解析、api-key、只读的生效配置 |
| `git` | 会话目录作为 git 工作树：读分支、列分支、切分支（永不 `--force`） |

### `harness.edge`（3）

| 命名空间 | 是什么 |
|---|---|
| `ag-ui` | 内核事件 ↔ AG-UI 帧（唯一一处做这个转换） |
| `http` | AG-UI 边 + 管理边 + jsonl 审计写入。**组合根**：整个进程里唯一把这些能力装到一起的地方 |
| `replay` | 记录读侧：帧折叠回消息、重建对话。run 外的显式管理动作 |

### 不进四层

- **`harness.user`** —— eval 的常驻命名空间，在进程里被 `create-ns` 出来，没有文件；
  它既不是机制也不是能力，是模型写代码的落点。保持现名。
- **`dev/` 与 `test/` 下的工具** —— `harness.wire` / `harness.evals` / `harness.repl` /
  `harness.e2e-server` / `harness.fake` / `harness.test-support` / `harness.test-runner`。
  四层只管 `src/` 生产路径上的东西，作者工具保持现名。

---

## 三、允许的边

```
infra    → infra
kernel   → infra, kernel
cap      → infra, kernel, cap
edge     → infra, kernel, cap, edge
test/dev → 任意
```

**核心层不 require 能力层。** 这是本特性要买到的东西，也是 13 号票守卫测试断言的规则。

守卫测试的边界要说清：它**只读 require**，看不见内容。一个在核心层里写死 `"anchor_grep"` 的
命名空间它抓不到——11 号票那一次是靠人眼看出来的。所以「内容泄漏」这一类判断只能写在这里、
靠人读。

---

## 四、核心层怎么接到能力

两样手段，**按优先级**，不许颠倒：

1. **参数（首选）** —— 能力把事实算出来交给核心。例：`loop` 的技能正文注入由调用方传进去
   （10 号票）；工具按模式换脸、批的计划器都在能力层，**直接 require** `cap.editing`，
   连缝都不用过（11 号票）。
2. **安装（install）** —— 参数到不了的地方才用它：缝在并发里被叫
   （`specs` / `run!` / `declarations-at`），没有参数位可传。由**组合根在 setup 时**
   把能力装进核心层留的一道门。

### 安装的形状（一处，不是每处自己发挥）

```
(install! {...贡献表...})  →  teardown    ; 一个无参函数，撤掉自己装的那一层
```

四条约束，逐条都是验收项：

1. **同名后者覆盖前者**（last-wins）。要换一个实现不必先卸载。
2. **禁用不是删除**。指名禁用一个已有名字，它**仍在表里**、仍被模型看见，只是执行被拒、
   并说明是被谁禁的。`CONTEXT.md` 已经写死这条（「没有 `:removed`……被拒绝的名字仍然存在」），
   那条决定是付过代价的（模型看不见的能力会去绕），安装机制必须服从它。
3. **`teardown` 撤自己那一层，并还原下面那层**。注册表按层记账，卸载弹出自己写过的那一层，
   而不是「把这个名字删掉」。否则「A 装了 X，B 覆盖了 X，A 先卸载」会把 B 的 X 一起带走——
   卸载顺序于是变成 load-bearing。**这条要有测试。**
4. **会话那一层不受影响**。安装是进程级的，与 `session-register!` / `session-disable!`
   那两轴是两个层次，不互相顶替。

**不采用「require 即注册」**（加载命名空间时产生副作用）。它把「装了什么」变成隐式的：
一个测试凭什么有内建工具，答案会是「因为别的某个命名空间 require 过它」。
setup 时显式装上，答案就在 setup 那一行。

**`teardown` 的真正消费者是测试，不是生产**：生产路径上它只在进程收尾时用得到，
而今天测试之间靠「进程级 atom 谁也不清」互相污染——`forget-turn!` 那种手工清理函数
就是征兆。装得上、卸得掉，一个测试才能拿回干净的缝。

---

## 五、需要落定的边界判断

每一条都带理由。**反面意见一并写下**，因为守卫测试抓不到这些，将来翻案只能靠这张纸。

### 1. `llm` 里的 `prompt.md` 载体 —— 留核心层

冻结是**机制**（provider 前缀缓存的前提：一条逐字节稳定的首消息），读哪个文件不是能力。
`slurp` 一个文件谁都会。

### 2. `providers` —— 能力层，不是基建层

它是「能跟哪个厂商说话」。基建层只有「东西落在哪、错了写哪、进程怎么起来」，
`providers` 三样都不是。

### 3. `git` —— 能力层

它是会话目录上的**一项能力**，不是宿主约定。基建层是「宿主给我什么」，git 是「我用会话目录做什么」。

### 4. `db` —— 基建层，尽管它装的是状态

它是 sqlite 的封装，是机制。状态**内容**是别处的事（`project` 拿着查询，`hashline.store`
拿着锚点四张表）。

### 5. `replay` —— 适配层，不是独立一层

它是边写下的日志的**读侧**，与写侧是同一份契约的两半（`frames` 是它的引擎，`ag-ui` 是它的逆）。
放进 `edge` 是因为它做的是同一件事的另一半，不是因为「它也是个工具」。

### 6. `preamble` 与 `skills` —— 能力层，尽管 `loop` 现在要它们

那条反边是 10 号票要断的，不是把它们留下的理由。

### 7. `editing` —— 能力，**没有 `harness.kernel.editing`**

`:hashline` / `:str-replace` 是两套**具体编辑实现的名字**，它们服务哪些工具名
（`family-of` / `search-tool` 那两张表）也是能力的账，两级 `harness.edn` 的解析更是。
全在 `harness.cap.editing`。核心层只要「一张表可以被一条安装进来的策略**收窄**，
收窄的表现是**拒绝**而不是消失」这个机制——它是工具缝的一部分，不是单独一个命名空间。

> **这条在本文件的第一稿里写反了。** 当时的理由是「工具表这个机制在核心层」——
> 那条理由把**一张表**与**这张表的账**混为一谈：工具表在核心层，不等于一个能说出
> `edit` 和 `anchor_grep` 的命名空间是机制。记在这里是因为守卫测试抓不到这种泄漏，
> 而它差一点就混进去了。

### 8. `hooks` 一分为二，**引擎留核心层**

点表、声明契约、**引擎（`hooks/dispatch`）**、来源档位与排序是机制；
「读配置家的 `hooks.edn` 再叠上绑定项目的 `.harness/hooks.edn`」是能力（`cap.hooks`）。
分界线是那个 `project/binding-for`：核心层不该知道「会话绑到哪个目录」是什么意思。

**引擎为什么不是能力**：`tools/run!` 的三个出口里，「阻断」的命名来源之一就是 `PreToolUse`
的退出 2；「悬置」的一个应答者就是 `PermissionRequest`；`Stop`、`SystemPrompt` 分别落在
`loop` 与 `cap.system-prompt` 的流程上。也就是说引擎不是内核在**一处**咨询的策略，
而是内核**几个判决点的形状**。何况 `docs/architecture/overview.md` 的**铁律 2**
（「system 消息只有一条，它的开头冻结，hook 追加其后」）把 hook 写进了内核的定义里，
而铁律是「每一处设计都能追到它们之一」的那种东西。

引擎自己也是点无关的：`dispatch.clj` 里**没有出现过任何一个点的名字**——
`:system-prompt` 的「stdout 就是内容」被读成 `(= :content (:stdout p))`，
`:on-error` / `:matches` / `:payload` / `:name` 全部从点表那一行取。
一个跑数据表的解释器是机制，表里的**内容**才是能力（12 号票把内容搬走）。
它也不自己写任何东西：审计行走调用方传进来的函数，sink 由边绑，spawn 是 `infra.shell`。

> **反面意见**：铁律 2 说的是**一条 system 消息**这条内核事实，hook 只是它现在的实现；
> 而且引擎能「无声明即不可见」地缺席（`fire` 在没声明时不 spawn、不等待、不写行），
> 这正是**槽**的样子——`cap.editing` 的收窄槽在没装时也一样隐形。
> 不过内核控制流真正被 hook **改道**的地方只有 `tools/run!` 一处：`loop` 的 `:stop`
> 是观察者，判决被丢掉，run 的终止不依赖它。

> **翻案路径（唯一站得住的一条）**：`hooks` + `hooks/dispatch` 整体进 `cap`，
> `kernel.tools` 收两个安装进来的 handler（门禁、应答），`loop` 的 `:stop` 同理，
> `cap.system-prompt` 直接 require `cap.hooks`。它能过守卫测试，形状与 11 号票的收窄槽一致。
> 代价：三个出口里有两个不再由内核定义，而铁律 2 会指向一个内核里没有的东西。
> 本特性选「留」，理由是那两条代价比「内核里多一个引擎」更贵。

### 9. `harness.user`、`dev/`、`test/` —— 不进四层

见上文的「不进四层」。

### 10. 不是判断，但别混：两个 `log`

`harness.log`（后端的**错误日志**，落到 `<root>/logs/harness.log`）与 session 的 jsonl
**记录**是两个东西。搬家之后 `harness.infra.log` 与边里那个写 jsonl 的 `log!`
会长得更像一家人，扫文档时别把 `docs/architecture.md` 里「与 session jsonl 是两回事」那句抹掉。

---

## 六、本次重构不做的事

- **不引入任何数据库迁移**。`hashline_*` 四张表名、`projects` / `sessions` 表名、
  迁移链的形状都不动——表名是库里的东西，与 Clojure 命名空间无关。
- **不动配置文件名**。`~/.clj-harness/harness.db`、`harness.edn`、`providers.edn`、
  `hooks.edn`、`config.edn`、`.env` 都是用户手上有、磁盘上有的东西。
  注意 `harness.db` 在仓库里**既是命名空间又是文件名**，判据：前面是 `(:require [` / `:as` /
  限定名 → 命名空间，要改；前面是 `~/.clj-harness/` 或引号包着的文件名 → 文件，不改。
- **不动锚点表**：`resources/hashline/anchor-table.bin.gz` 与 `deps.edn` 的 `:paths`。
  它按 classpath 路径找，不按命名空间找。
- **不动用户接口**：27 个 hook 点、点的名字（`PreToolUse` 那套拼写）、`hooks.edn` 的键名、
  工具名与它们的描述文本、`AG-UI` 帧的形状。改名是破坏性变更，这次重构不做。
- **改名票不许顺手改行为**：02–06 每张票的验收都是「除命名空间名与 require 里的名字外，
  不动任何一行」，并且「用例数与断言数与上一票相同」。行为改动一律留给 07–12。
- **不改 `.scratch/` 下的历史**。别的特性的 spec 与票面记的是**当时**的样子，
  把 `harness.tools` 改成 `harness.kernel.tools` 就是让一句真话变成一句当时的假话。

---

## 七、改名票容易漏的三类地方（02 号票踩过的）

改一个命名空间的名字，正则替换抓不到的地方有三类，每一类都在 02 号票上真出现过：

1. **字符串里的 munged 名字**（横线变下划线）。`log_test.clj` 断言栈跟踪里出现
   `"harness.log_test"`——它是 `harness.log-test` 的 munged 形式，`harness\.log\b` 匹配不到
   （`_` 是词字符，没有词边界）。**它是命名空间自己的名字，要跟着改**，但看起来像一个普通字符串。
   每次改名后搜一遍 `harness\.<旧名>_`。
2. **同一个词同时是另一个东西**。`harness.db` **既是命名空间又是磁盘上的文件名**
   （`~/.clj-harness/harness.db`，用户的真实家目录里就有），而且两者在同一个文件里交替出现
   （`infra/db.clj` 一个文件里两种用法都有）。**判据**：`(:require [` / `:as` / `qualified/`
   / `-test` / `'s` 后面说的是命名空间 → 改；`<root>/harness.db`、`"harness.db"`、
   「a stray harness.db」→ 磁盘上的文件，不改。
3. **特征名被当成命名空间名截住**。`test/harness/hooks_wired_test.clj` 的命名空间叫
   `harness.hooks-wired-test`——它是**特征名**（hooks 接线），不是「`harness.hooks` 这个命名空间」，
   但 `harness\.hooks\b` 照样命中（`-` 是词边界），于是它被改成 `harness.kernel.hooks-wired-test`，
   而文件还在平铺目录里。Clojure 的硬规矩是**命名空间名必须与文件路径对应**，所以那一刻它是**加载不了**的。
   `hooks_wired_test.clj` 因此搬到了 `test/harness/kernel/`。同类的还有
   `harness.approval-test` / `harness.session-tools-test`（起点不是被搬的名字，所以没被命中）。
   **每张改名票跑完后，用一段脚本校验「路径 ⇔ 命名空间」全树一致**——它一次就能抓出这类错，
   比逐个眼睛看可靠。
4. **不在 `src`/`dev`/`test` 里的引用**：`deps.edn` 的 `-m` 目标与注释、`README.md`、
   `docs/`、`CONTEXT.md`、`prompt.md`、`ui/`。这些由 13 号票统一扫，
   但**改名的票要知道它们存在**，别以为 grep 干净了就完事。
   **`ui/` 里有一处是会真坏的**：`ui/test/suites/approval.ts` 把一段 **Clojure 源码当字符串**
   发给内核的 `eval`（`(harness.tools/session-require-approval! harness.tools/*thread-id* "write")`）。
   06 号票把它改漏了，后果是**静默**的：`eval` 解析不到那个命名空间，把它当成一句错误文本回给模型，
   脚本化的一轮照常往下走——测试不报错、不崩，只是那次 `write` 不再 park 了，
   于是 `npm test` 里 `a-parked-write-runs-after-approval` 失败。
   **教训：名字进了字符串就没了编译期保护，而 eval 字符串连运行时都不会喊。**
   每张改名票跑完 `grep -rnE "\(harness\." ui/` 一遍，它只花一秒。

## 八、怎么落地

13 张票在 `issues/` 下，`01`（本文件）之后的顺序是：

- **02–06 纯改名**，每张一绿，行为零变化：基建层 → 核心层叶子与能力层第一批 → `hashline` 整批
  → 适配层（含 `deps.edn` 的 `-m` 目标）→ 核心层剩下的与 `cap.system-prompt` / `cap.editing`。
- **07–12 断依赖**：07 缝与表分开（并落地安装门）→ 08 围栏改成工具自己声明 →
  09 批的算术改成安装进来的一段 → 10 技能正文改成注入 → 11 `editing` 变成能力 →
  12 hook 的两条来源与内建三行改成装上。
- **13 守卫测试 + 文档对齐 + 全量验证**。

02–06 是串行的（同一棵工作树，前一票合完再开），07–12 同样串行，因为每一票都在改
`kernel.tools` 或它的邻居。

**一处容易漏的改名**：`deps.edn` 的 `:run` alias 写着 `-m harness.http`，随 05 号票
（适配层搬进 `harness.edge`）改成 `harness.edge.http`。漏掉它的后果是 `clojure -M:run` 静默起不来，
而测试套件**不会**发现——测试自己拼 main 入口，不走 alias。

---

## 九、基线（每张票的「用例数与断言数与上一票相同」对着它比）

**`d0fedf1`，分支 `layer-layout`，工作树 `.worktrees/layer-layout`：
`clojure -M:test -m harness.test-runner` → 607 tests / 9774 assertions / 2 failures。**

那 2 个 failure **不是本特性造成的，也不该在本特性里修**：

- `a-binding-survives-a-real-restart`（`project_test.clj:344` 与 `:347`）。
  它 fork 一个真 JVM，helper 故意 `(.redirectErrorStream true)`（把子进程 stderr 并进 stdout，
  因为那条链路只有文本），然后断言 stdout **只**是那个路径。
  这台机器的 JDK 是 **25**（Homebrew），而 `deps.edn` 是按 Java 17 写的：
  sqlite-jdbc 加载本地库时，JDK 24+ 会往 stderr 打四行
  `WARNING: A restricted method in java.lang.System has been called ...`，
  于是 `str/trim` 之后多了一段。**是 JDK 版本与断言假设的碰撞，不是逻辑 bug。**
  比较方式是「同样的 2 个名字、同样的 2 处，前后一致」，不是「变绿」。

另有一个**环境上的坑**，遇到时别误判成自己写的 bug：`harness.test-runner` 的隔离检查
会在跑之前给开发者的真 store 取指纹、跑完再比一次。这台机器上**有活的 `harness.http`
与 `vite` 在跑**（用的是真 `~/.clj-harness`），所以只要有真的会话被服务，
这个检查就会**无辜地报 ISOLATION FAILURE**。第一次基线跑就撞上过一次：
store 的 mtime 变了，同一个瞬间 `projects/.unbound/<uuid>.jsonl` 也被写了——
而那是一个**被服务的会话**的签名，离线套件（root 被指到临时目录）造不出来。
**已证实是外部写入者，不是套件。** 硬证据：真 store 最新被写的几个 jsonl 属于
`projects/_Users_zhouteng_Documents_workspace_win-ai-harness/` 与
`..._clj-harness/` 下的**裸 uuid 会话**——`win-ai-harness` 是**另一个工作区**，本套件从不知道它存在；
而本套件造出来的 thread id 一律带前缀（`proj-` / `sparse-` / `rebuild-` …）。
也就是说这台机器上有别的 agent 在用跑着的那个 app（它用的是真 home），我的测试窗口正好压在它的写上面。

判别办法：连着测两次，或先看一眼 `find ~/.clj-harness -type f -mmin -25`。
**不要**为了让这条检查变绿去改测试。同样的理由，`http_test` 那场赛跑在外部负载下更容易输——
这台机器同时跑着另一个 agent 的活，输一次重跑即可。

**第二个环境上的坑：`http_test` 有一场必输的赛跑。**
`the-projects-listing-joins-the-store-with-the-disk`（`http_test.clj:1205`）在一场真 run 之后断言
`(= (.length f) (:bytes session))`。`RUN_FINISHED` 是**在** `:run/done` 那批尾部 `message` 行落盘**之前**
发出去的（`harness.http` 的流式契约），所以客户端看见终帧、测试立刻去问列表时，jsonl 可能还差几行——
实测差 180 字节。它**偶发**（四次全套里输过一次），基线里也存在，不是本特性造成的。
判据：同样的 2 个名字出现 = 通过；多出来的那条如果是这场，重跑一次即可。

**第三种表现：它会挂住，不只是失败。** 05 号票那次全套在 `edge.http-test` 里停了 14 分钟不动，
日志里是 `SQLITE_READONLY_DBMOVED`（"The database file has been moved since it was opened"）——
某场测试的 fixture 把自己那个临时 root 删了，而一条后台 run 线程还握着指向它的 sqlite 连接，
异常从 async 状态机里抛出，客户端就一直等在 channel 上。重跑即过（第二次零 DBMOVED）。
是既有的 teardown 竞态 + 机器负载，不是本特性。**操作上记住**：跑套件给一个硬超时
（`timeout 600 clojure ...`），别让它无限期挂着；挂了就杀掉重跑，两次都挂才需要查。

---

## 十、安装门落地之后的两个后果（07 号票实测）

门只是形状，真正会咬人的是下面两条。08–12 号票都会碰到。

**1. 会话开关只对**表里已经有**的名字生效，所以顺序有讲究。**
`session-disable!` 的守卫是「这个名字我看得见才记这笔」（它保证「禁用不会凭空造出一个工具」，
`session_tools_test` 里有断言钉着）。表从加载期变成可安装之后，这条守卫就把**调用时机**变成了语义的一部分：
在能力装上**之前**禁用某个名字，等于什么也没禁——而且**不报错**。
07 号票上真踩了一次：`hooks_wired_test` 的 `a-disabled-tool-is-refused-without-asking-the-gate`
在 `with-server` **外面**禁用 `read`，于是门禁照跑、`PreToolUse` 审计行照写。
修法是把那句话挪进服务里面（表已经在了），并把这条写进 `session-disable!` 的 docstring。
**测试里改开关，一律放在安装之后。**

**2. 组合根的 `stop` 必须带回 teardown。**
`harness.edge.http/start!` 装上能力，返回的 stop 把它们撤回去。不这么做的话，
测试套件里每一次起服务都往层栈上叠一层（http_test 起上百个），层不会被回收，
而「缝是空的」那条断言就会看到一个别人留下的表。`start!` 的 stop 是**包了一层的**：
`(with-meta (fn stop! [] (server) (teardown)) (meta server))`——meta 要原样带过去，
因为每个测试都是靠 `(:local-port (meta stop))` 才知道 OS 给了哪个端口。
