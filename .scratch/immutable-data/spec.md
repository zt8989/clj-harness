# spec: 不可变数据的纪律（写进 AGENTS.md，并让代码合规）

2026-09-16 立。两件事：

1. **把 Clojure 的不可变数据原则写进 `AGENTS.md`**，重点是**多线程下的读写**——这份代码里
   「只有主线程会碰它」这句话基本都是假的。
2. **按这些原则审一遍代码**，把真正违反规则的代码逐条立票。

审出来的东西分三类，都写在下面：**违反**（→ 票）、**查过不是问题**（别再审一遍）、
**看过、决定不修**（写清理由，免得下次有人当成漏的）。

> **2026-09-18 复议：这个特征改过一次形。** 先读下面那节「复议 2026-09-18」再看 09-16 的原记录——
> 原记录里**有两行已被实测推翻**（表格里划了横线），另外多了四张票，票号也动过。
> 09-16 那部分**保持原样**（它是历史，带日期），不要拿它当现状。

## 规矩写在哪：三处边界，各自只写它该写的

这份仓库已经把「文档写哪」分了工（`docs/architecture.md` 开头那张表）。本特征遵守它，不新开一处：

| 地方 | 写什么 | 不写什么 |
|---|---|---|
| **`AGENTS.md`**（票 01） | **规矩本身**：可做与不可做，以及每条规矩的**正例**（到哪里看一个对的写法） | 反例的位置。`AGENTS.md` 是要求，不是一个会过期的清单 |
| **本文件**（`.scratch/immutable-data/`） | **反例与它们的 `file:line`**，带日期 | 现状描述 |
| `docs/architecture/` | 不动 | —— |

**为什么反例不进 `AGENTS.md`：** 反例只要被修掉就变成假话，而 `AGENTS.md` 是被每个 agent 读、
且没人会回头改的。正例相反：`take-provider-changes!` 的 `swap-vals!` drain 是对的，修完还是对的。
所以规矩配正例，反例连位置一起留在本文件里——本文件是**历史**，写着日期，不会被当成现状。

---

## 违反（2026-09-16 的原表，逐条对应一张票）

> 表里的 `file:line` 是 **2026-09-16 的**。2026-09-18 复核时全部对过一遍，**行号已漂**
> （四层目录那次搬动），「复议」一节给了新行号。**划横线的两条已被推翻**，别照原样做。

先把结论放前面：**两条今天就能咬人**（票 02、03），三条是会丢更新/写错账的（票 04、05、06），
两条是账不对（票 07）或首次开库抢跑（票 08）。

| # | 现场 | 是什么问题 | 票 |
|---|---|---|---|
| 1 | `kernel/tools.clj:546` `turn-plan` 是**一个全局单槽**（`reset!`），`loop.clj:134` 又在**工具线程已经跑起来之后**才 `register-turn!` | ~~两个会话同时在跑时互相清对方的计划；锚点批（「一条消息里的多次编辑合成一次写入」）静默降级成并发独立编辑，而它存在的理由正是防丢更新。`loop.clj:127` 那句注释（「在任何东西跑起来之前就把整个 turn 告诉缝」）与代码不符~~ **——已落地，见复议** | ~~**02**~~ |
| 2 | `hashline/write.clj:112` 与 `hashline/undo.clj:109` 先拿 **path 锁**，再经 auto-read / `undo-rows` 进 `serve/read!` 拿 **session 锁**（`serve.clj:76`） | **锁顺序反了**。规矩写在 `store.clj:411`（session 永远是外层）；正确的调用方是 `replace.clj:558`。两个工具线程（一条消息里的 `write` 与 `replace`/`read` 同一个文件）互相等，**真死锁**，那一轮 run 永远不结束。`ReentrantLock` 的可重入只帮同一个线程 | **03** |
| 3 | `edge/http.clj:214-228` 的 `init-logged?`/`session-started?` 与 `mark-*!` 是两次 atom 操作，中间夹着副作用（`hook/emit :session-start`、`log!`） | check-then-act：同一 `thread-id` 的两个并发 run 都会看到 `false`，于是 `SessionStart` 触发两次、`provider/init` 行写两条——而 `docs/architecture/edge.md:110` 说的是「每 thread 恰好一行」 | **04** |
| 4 | `edge/http.clj:1138-1147` 与 `cap/tools.clj:294-303`：`(override-for id)` → 算 → `(set-override! id (merge before change))` | 读-改-写不是一个原子操作。两个并发改（HTTP 端点与 `session-configure` 工具）丢更新，且两条 `provider/session-changed` 审计行都宣称一个**从未存在过**的 before→after（**措辞已修正**，见复议） | **05** |
| 5 | `infra/logging.clj:184` 的 `(when (not= root @configured-root) (configure! ..))`，而 `configure!`（`:163-174`）是「全摘掉再装上」 | 两个线程同时看到旧值就同时重配：`detach/detach/add/add` 交错 → **每个 appender 装两遍，每行日志写两次**——正是 `:160` 那句注释说要防的「stacked」。另外 detach 与 add 之间到达的日志会被静默丢掉 | **06** |
| 6 | `hashline/serve.clj:114` 与 `hashline/grep.clj:282-283`：`sync!`（锁内）之后才 `store/mark-served!`（**锁外**） | 夹在中间的一次并发编辑会在 `advance-on!` 里修剪 `served`，然后 `mark-served!` 再把已经释放的锚点并回去——「已展示」这份账对不上 | **07** |
| 7 | `infra/db.clj:845-862`：`(inspect f)` 决定 `created?`，**在事务之外**，之后才 `connect-and-migrate!` | ~~新家/坏库的首次开库 TOCTOU：两个并发请求都算出 `created? = true`，都跑迁移；抢输的那个撞 `CREATE TABLE` 已存在，`damage?` 判它不是损坏，于是那个请求以 `:open-failed` 失败。`http.clj` 里**没有任何 `db/` 调用**，所以库是**第一个碰库的请求**才开的，这个窗口就在开机后头两个请求上~~ **——实测打不出来，见复议**；**同一个函数里另一半是真的**（`quarantine!` 的 TOCTOU） | **08** |

## 查过、不是问题（附理由，别再审第二遍）

> 位置是 **2026-09-16** 的；结论在 2026-09-18 复核时**未变**（行号同样漂过）。

- **工具结果进 history 的顺序。** 一度可疑：调用并发跑、`done` 按**完成**顺序写。实际是对的——
  `loop.clj:146` 按 `calls` 顺序从 `@done` 取回，`:150-152` 再按那个顺序 append；`history` 全程只由
  run 的生产者线程 `swap!`。`emit` 走完成顺序，`loop.clj:53-58` 的 docstring 明说了这是故意的。
- **sqlite 连接。** `db.clj:866` `with-connection` 每次调用**新开一条**并 `finally` 关掉，没有
  `defonce` 连接、没有 `DataSource`、没有跨线程共享的 `Connection`。跨连接竞争交给 sqlite 自己
  （busy timeout 5000 + `BEGIN IMMEDIATE`）。
- **函数内的局部 atom。** `db.clj:354` `settled`、`db.clj:199` `moves`、`http.clj:277` `first?`、
  `replay.clj:291` `frames`、`ag_ui.clj:139` `s` 都只在一条线程上被碰（run 的消费线程），
  `moves` 的两次 deref 之间也没有写。不是共享状态。
- **lazy seq 跨线程。** 可疑的几处都在跨界前**已经实化**：`hooks.clj:579` `effective-hooks` 与
  `skills.clj:588` 用 `into {}` 包住 `for`；`hooks.clj:635` `declarations-at` 以 `vec` 结尾；
  `project.clj:633` `fence` 确实返回 lazy `concat`，但每个消费方都在同一次调用里实化它，从不存、
  从不跨线程。
- **一次性初始化。** `anchors.clj:195` 的 `pool` 是 `delay`，且被 force 之后是不可变值——`delay`
  保证只算一次且安全发布。`anchors.clj:337` 的 `ThreadLocal` `MessageDigest` 是每线程一个，
  这个用法是对的。
- **`store.clj` / `project.clj` 的 docstring 声明。** 「没有 atom、没有 delay、重启即无事」
  （`store.clj:6`）与「绑定是一行而不是 atom 里的一条」（`project.clj:2-7`）**都是真的**，
  对着代码核过。
- **`scripted-pins`（`providers.clj:882`）。** 生产代码里没有任何写入方，只有测试，且测试都在
  `finally` 里清理。它是测试缝，不是请求路径上的泄漏。

## 看过、决定不修（写清理由，别当成漏的）

- **按 `thread-id` 长大的进程内容器**：`tools/overlays`、`tools/session-approvals`、
  `hooks/overlays`、`hooks/counters`、`providers/session-overrides`、`http/init-logged`、
  `http/session-started`。它们**按会话分家是对的**（这正是规矩要的形状），长大的是「这个进程
  服务过多少个会话」。要回收就得知道会话**结束**了，而今天没有任何事件宣告这件事（`SessionStart`
  有，`SessionEnd` 没接线）。判据：**量级是「一个进程里的会话数」，不是「每次调用一条」**，
  在一个本地单用户的进程里可以接受。**与 `turn-plan` 的区别要说清**：那个是**单槽**且每轮
  `reset!`，被两个会话共用就会立刻出错，不是长大得慢，是当下就错——所以它进票，这些不进。
  （2026-09-18 补：`turn-plan` 已改成按 `thread-id` 分家，见复议；这条判据本身仍然成立。）
- **`parked-registry`（`tools.clj:343`）。** `take-decision!` 只标 `:consumed`，条目不删，于是
  每次审批留一条。删掉就会让 UI 的「待决清单」问不出来（`parked-calls` 读它），而它的量级是
  「这台机器上的人点过多少次审批」。**留**。
- **`path-locks` / `session-locks`（`store.clj:365-366`）。** 每个改过的路径/会话一个
  `ReentrantLock`，`store.clj:388-390` 已经写明这是「不要注册表来失效」的代价。**留**。

## 没做的（说清楚，别让它悄悄发生）

- **`docs/architecture/overview.md` 的「状态存在哪」只写了进程内存一行**（「待决审批、会话 overlay、
  hook 连接 → 进程内存，重启即失是特性不是缺陷」），没有说**哪一份是进程级、哪一份按 `thread-id`
  分家、哪一份由哪把锁看着**。本特征**不加**这段：`AGENTS.md` 写规矩、本文件写这次审出来的位置，
  足够；要把它做成现状文档里的表，是另一个特征（那时它必须写在**票 02-08 之后**——
  2026-09-18 注：现在是**票 03-12**——否则一落地就在描述改掉之前的形状）。
- **`AGENTS.md` 不加「怎么并行工具调用」的技法。** 编排属于 `kernel.loop` 的实现细节，
  不是一条别人要照着做的要求。

---

## 复议 2026-09-18：一次事故、两条被推翻、四张新票

起因：**2026-09-18 一天之内，`~/.clj-harness/harness.db` 被自己的隔离逻辑搬走了三次**，
包括一份 18 MB 的真库（21 projects / 133 sessions）。查下来，那是「共享可变状态」这条线上
**没人立过票**的一处；顺着这次复核，09-16 那份审计也被逐条重验了一遍。结论按票号写：

### 票 02（turn-plan）——**退役，已落地**

`parallel-sessions` 把它作为**跨特征前置**落了地（提交 `7247e72`，merge `f96d1ef`）：
`kernel/tools.clj:660` 现在是 `(atom {})` 按 `thread-id` 分家、`register-turn!` 回传 token（`:708`），
`kernel/loop.clj:166` 的登记在 `:167` spawn **之前**。它自己有四个用例
（`test/harness/kernel/tools_test.clj`：按 thread-id 分家、token 归属、批角色只读自己那份计划、
缝在跑之前被告知）。**票文件已删，编号留空**（本仓老例：删票不重编号，免得别处按号引用变成悬空）。

同时作废本文件 09-16 那段追加的「**票 02 是一张别处的门**」——门已经开了。
（那段话 09-16 追加、**当时未提交**；原文保留在下面「票的顺序与提醒（2026-09-16）」一节里，
这里点名作废。）

### 票 08（首次开库）——**题面的一半被实测推翻**

原票的核心断言是「抢输的那个撞 `CREATE TABLE` 已存在 ⇒ `:open-failed` ⇒ 请求 500」。
**实测打不出来**：`migrate-connection!`（`infra/db.clj:734-742`）每一步都在自己的
`BEGIN IMMEDIATE` 里先跑 `present?` 探针（`:739`），抢输的那个走 `record-step!`。
子代理在临时 home 上跑了 **8 线程×30 轮 + 16 线程×10 轮 = 400 次首次开库，0 失败**
（其中 160 次带探针的 `inspect` 有 107 次答 `:fresh`，窗口确实被踩到）。

**同一个函数里另一半是真的，而且 30/30 必现**：`quarantine!`（`:188-219`）的 `.exists`（`:202`）
与 `Files/move`（`:206`）之间，8 条线程打一个坏库每轮都有 1~2 条以 `:quarantine-failed` 抛出。
票 08 因此**改题面**，只留这一半。

### 新增的四张票

| 票 | 是什么 | 为什么进这个特征 |
|---|---|---|
| **09** | `inspect` 不再拿「文件比它自己头部声明的短」当判决：判决要来自**一份前后一致的快照**（长度只读一次），而且**旁边有 `-wal` 时这句话根本不是判决** | 它就是这场事故的成因。属于这条线上的一处硬伤：**一份正在被另一个线程写的文件，被当成了静止的** |
| **10** | 搬走一份库之前，由 **SQLite 自己**说它坏（只有 CORRUPT/NOTADB 才搬） | 09 修「哪种短不算坏」，10 修「谁拍板」。两道防线能独立失效 |
| **11** | 库被重建之后，那段落进 `projects/.unbound/` 的记录要接回来 | 事故的后半场：记录分裂成两个文件，而回放/重建只读一个。**它本身不是并发问题**，是并发事故的善后；放这里是因为它是同一次事故的另一半 |
| **12** | `harness.test-support` 的**逼交错闸门**（起跑门 + 中途闸门），03/04/05/06/08 的用例复用它 | prefactor：不逼交错，那些用例全是**假绿**——带着 bug 的代码也过 |

### 新反例：一份正在被写的文件被当成了静止的

```clojure
;; infra/db.clj:166-169     ← 2026-09-18 复核时的行号
(if (< (.length f) declared)                     ; 读数一
  {:state :damaged
   :why (str "its header declares " declared " bytes but only "
             (.length f) " are there")}          ; 读数二（不是同一个瞬间）
```

`head`（`:79-93`）是**裸文件读**：不加锁、不开连接、**也不看旁边有没有 `-wal`**。
WAL 检查点把页拷回主库时，**第 1 页（头部，声明新的总页数）先落地**，余下的页一批批跟上来，
中间那段时间文件确实比它自己头部声明的短。

**决定性证据（现场打印，09:25:13 与 09:30:18 各一次）**：

```
harness.db: the store was damaged (its header declares 18280448 bytes but only 18280448 are there)
```

**两个数字相等**，而进这个分支的条件是**严格小于**。所以 `.length` 被读了两次、两次之间文件长完了：
**这句话自己否证自己**。

三次被搬走的文件事后全部复核过（拷到 `/tmp` 只读检查）：`PRAGMA integrity_check` 全 `ok`、
页对齐、`change_counter == version_valid_for`、`declared == 实际大小`——**一份都没坏**：

| 时刻 | 被搬走的是什么 | 里面有什么 |
|---|---|---|
| 09:09:17 | 真库，18124800 字节 | 21 projects / 133 sessions / 62758 行所有权 |
| 09:25:13 | 恢复后的真库，18280448 字节 | 21 / 133 / 63384 |
| 09:30:18 | 上一次**重建出来的空库**，512000 字节 | 0 / 0 / 1547 |

**善后的形状（→ 票 11）**：隔离后 **9 毫秒**（`.unbound` 首条 ts 09:25:13.996，隔离戳 …987），
两个活着的 run 开始把记录写进 `projects/.unbound/<thread>.jsonl`，一路写到进程被杀：
54184 行 + 20771 行；而项目目录里那两份文件的末条 ts 是 09:25:13.986 / 09:25:13.741——
**两侧时间戳区间不重叠**（这正是手工接回之前该验的那件事）。

### 复核时刷新的行号（09-16 → 09-18）

四层目录那次搬动（`src/harness/{infra,kernel,cap,edge}`）让所有行号漂了一次。存活票的新位置：

- **03**：`cap/hashline/store.clj:411` 那条规矩；`replace.clj:558-561` 是正例；
  `write.clj:112`（path 锁）→ `:87`（`serve/read!`）→ `:123`；`undo.clj:109` → `:45` → `:148`；
  `serve.clj:76`/`:79`；并发工具线程在 `kernel/loop.clj:167-179`，收在 `:185`；
  `cap/editing.clj:77-78` 说明 auto-read 默认开着。
- **04**：`edge/http.clj:219-241`（两个 atom 与四个入口）、调用处 `:536-538` 与 `:545-549`；
  规矩在 `docs/architecture/edge.md:167`（原表写的 `:110` 是旧号）；`handle-run`（`:672-700`）
  **没有通行闸**，UI 侧只按标签页拦（`ui/src/app.tsx`）。
- **05**：`edge/http.clj:1673-1682`（合并）与 `:1659-1664`（清空）；`cap/tools.clj:327-338`；
  `cap/providers.clj:883` / `:903-908` / `:910-935`，正例是 `take-provider-changes!`（`:852-858`）。
- **06**：`infra/logging.clj:70-72`、`:147`、`:160-162`、`:163-174`、`:183-186`。
  **严重度下调**：`home/root` 是 `(or *root-override* (System/getenv "CLJ_HARNESS_HOME") …)`
  （`infra/home.clj:61`），一个进程里不会变，所以**生产里休眠**；但测试每个用例都
  `alter-var-root` 挪一次（`test/harness/test_support.clj:139`），所以**测试里是活的**。
- **07**：`cap/hashline/serve.clj:107`（`sync!`）与 `:114`（`mark-served!`）；
  `grep.clj:185` 与 `:282-283`；`store.clj:220-231`（修剪）、`:249-279`（并集）、`:210-219`（前提）；
  `replace.clj:217-235` 是那道 `:not-shown` 拒绝。
- **08**：`infra/db.clj:838-862`、`:734-742`、`:188-219`（TOCTOU 在 `:202`→`:206`，抛出在 `:207-212`）。

### 05 的措辞修正

原表说「两条 `provider/session-changed` 审计行」。**实际只有 HTTP 那条**写
`provider/session-changed`（`http.clj:1679`）；工具那条走 `record-provider-change!` 的**排空写出**，
落成 `provider/changed`（排空在 `http.clj:567-574`）。真正的问题是两条都宣称一对**从未存在过**的
`before → after`。

---

## 票的顺序与提醒（2026-09-16，原文保留）

> 这一节是 09-16 的原话，**已被下面那一节取代**。留着是因为它记着当时的排序理由。

八张票，**都没有阻塞边**（01 与 02-08 互不依赖：规矩写得早不早，不影响某一处代码本来就该改成什么样）。
按严重度读：

- **02、03** 今天就能咬人（一条静默降级、一条死锁）。先做这两张。
- **04、05、06** 会丢更新或把审计行写错。
- **07、08** 账不对 / 首次开库抢跑。

**04 与 05 都改 `src/harness/edge/http.clj`**（改的是不相邻的两个函数区域：`:214-228` 与
`:1125-1147`）。工作树是与别的 agent 共用的：提交要按路径点名，别把别人的在办改动一起带走。

**票 02 是一张别处的门。** 2026-09-16 追加的要求「同时支持多会话并行」立成
`.scratch/parallel-sessions/`，而它的第二张票**阻塞在票 02 上**：两个会话同时在跑正是
`turn-plan` 那个全局单槽互相清计划的场景，那一票不落地，并行就是带着丢更新一起放出去。
换句话说，**本特征里的一部分不是「清理」，是另一个特征的前置**。

（2026-09-18：**这一段作废**——票 02 已由那次并行工作本身落地，见上面复议。）

## 票的顺序与阻塞边（2026-09-18 重切后）

**只有一条真的阻塞边**：**票 10 阻塞在 09 上**（10 是「搬走前让引擎拍板」；09 没落地的话，
10 只是给同一种误报加一道同样的误报）。
**票 12 是前置**：03、04、05、06、08 五张的「今天必须红」用例都要靠它的闸门做成**必然**，
所以先做 12。其余各票互不依赖。

**建议的读法（按严重度，不是阻塞边）：**

- **09** 最急：它今天毁掉过真库，是唯一一条**已经造成过损失**的。
- **03** 次之：一轮 run 永久挂死（用户侧最难看的一种失败）。
- **10** 给 09 兜底；**11** 把事故的善后补上。
- **12** 先做（前置），随后 **04 / 05 / 07** 会丢更新或写错账。
- **08** 的窗口只在库被判坏时才走得到（09 落地后更少），所以排在后面。
- **06** 最后：生产里休眠，但会污染测试证据。
- **01** 与谁都不冲突，随时可做。

**同一处代码，别让两个 agent 同时动：**

- **03 与 07 都改 `cap/hashline/`**（写/撤销的锁区，以及 `serve.clj`/`grep.clj` 的标账），
  改的是不相邻的区域；
- **04 与 05 都改 `src/harness/edge/http.clj`**（两个「首见」atom 与覆盖的读写，是不相邻的两处）；
- **09、10、08 都在 `infra/db.clj` 的同一小片**（判定、开库、隔离），建议**顺序做**：
  09 → 10 → 08（10 本来就阻塞在 09 上）；
- 工作树是与别的 agent 共用的：提交要**按路径点名**，别把别人的在办改动一起带走。

---

## 落地记录 2026-09-18

### 票 09 —— **已落地**（票文件已删）

改的是 `infra/db.clj`，形状与票面一致：

- 新的 `journal-beside` 看的是**侧车在不在**（`-wal`，以及仓里创建期会用的 `-journal`）；
- 新的 `short-of-its-header?` 要一份**稳定的快照**：长度先读一次（`inspect` 里只读这一次，`:why`
  里印的就是它），**先读长度、后看侧车**（这样两次之间冒出来的日志会被看见），长度再读一次；
  两次一致、且旁边没有侧车、且确实短——才叫 `:damaged`。docstring 写明了这条修法**覆盖不到**的残余
  （侧车被「非检查点完成」的方式弄没），以及那份残余交给开库那一步（票 10）。
- `:foreign` 那个 `:bytes` 也改用这一次读数（原来它自己又读了一次）。

**用例（新增，`test/harness/infra/db_test.clj`）**：
`a-file-short-of-its-own-header-while-a-journal-is-beside-it-is-not-a-wreck`。
它不赛跑：新增的 helper `declare-more-pages!` 把一份**真库**的头部页数改大，**直接摆出**检查点
写到一半的那个状态（头部先落、页还没到），然后分两半断言——旁边放一个 `-wal` 时 `schema-version`
**答一个数**（不是判死），拿掉它之后**同一份文件仍然是 `:damaged`**，并且 `:why` 里那两个数字
**不相等**（自相矛盾那个形状被钉住）。

**红绿都见过**：把 `short-of-its-header?` 里那条「旁边没有侧车」的条件临时拆掉，这条用例**红**，
失败信息正是今天现场的同一句话——

```
.../harness.db is damaged: its header declares 331776 bytes but only 69632 are there.
```

——一份**健康的**库被判成 wreck。恢复那条条件后转绿。

**验收**：定向 `node scripts/test.mjs --ns harness.infra.db-test` = 22 用例 / 141 断言 / 0 失败；
全量 `node scripts/test.mjs --backend` = **861 用例 / 11275 断言 / 0 失败 / exit 0**；
既有用例**一字未改**。

### 票 10 —— 实现前的一个坑，先记在票面上

试着往下做 10 的时候撞到一件事，写进票里免得下一个人拿它当十分钟的改动：
「搬走之前让 SQLite 自己说一句」这一步**不能想当然地用只读方式打开**。这份库是 WAL 模式，
只读打开一个需要恢复的库可能以 `SQLITE_READONLY_RECOVERY` 一类的错误码失败——那不是
CORRUPT/NOTADB，`damage?` 不认，于是会落进 `:open-failed`，**比今天的隔离更糟（一个 500）**。
所以「用哪种打开方式、认哪些错误码」必须先用实测钉住，再改 `ensure-connection!`。

### 票 12 —— **已落地**（票文件已删）

`test/harness/test-support.clj` 多了三件东西，都带 docstring 写清「作证」与「超时」：

- `start-gate`（起跑门，一参/两参：`[n]` 用默认 30s，`[n ms]` 让超时本身可测）：N 条线程全到了才放行；
  没全到就**抛一条点名「到了几条、等了几毫秒」的错**——卡住是红的失败，不是挂住的套件。
- `window-gate`（中途闸门）：用 `alter-var-root` 包住一个 var，调用**卡在窗口里**等人放行；
  `:entered` 是作证用的计数。docstring 写明了为什么是 `alter-var-root` 不是 `binding`。
- `holds-within?`：有界的轮询（「MS 之内会不会成真」），两边的用例共用。

**它自己的用例**：新增 `test/harness/test-support-test.clj`（4 条：起跑门确实一起放行且能作证、
没人填满时**抛错点名**而不是挂、中途闸门确实把调用卡在窗口里且 `:restore` 之后 var 复原、
没放行的闸门到点**自行放行**而不是拖死），并加进 `test_runner.clj` 的名字清单（**只加一次**；
合并过的清单一律 `sort | uniq -d` 一遍）。

**一个真实调用方**：`test/harness/infra/db_test.clj` 手写的 `hammer` 改成用它——
**它原来只是「一起跑」，不是「一起进事务」**，而丢更新要的正是后者；改成闸门后它多了一句
「N 条里只有 M 条到了闸门」的失败。语义一字未改，既有那条丢更新用例照旧通过。

定向 `node scripts/test.mjs --ns harness.infra.test-support-test,harness.infra.db-test`
= 26 用例 / 151 断言 / 0 失败。

### 票 03 —— **已落地**（票文件已删）

`cap/hashline/write.clj` 与 `cap/hashline/undo.clj` 的两个 `perform!`：`with-session-lock` 提到
`with-path-lock` **外面**（session → path，与 `replace.clj` 一个形状），两个 docstring 各加一段
说清**锁的顺序是数据的一部分、反着拿就是死锁**。

**用例与「必然红」的证据（这张票的真正难点）**：头两次写出来的用例**红不了**，两次都红不了才看清
那个环长什么样，值得记下来：

1. 先用 12 的 `window-gate` 卡住 `store/advance!`，想让「读」握着 session 等「写」——**红了不了**，
   因为 `serve/read!` 是先 session 再 path，被卡住的那个读**两把锁都握着**，写卡在 path 上，
   形不成环。
2. 改让测试自己握着 session、再问「文件锁还在不在」——**还是绿**，因为工具层到 `perform!` 之间
   有一段不确定的耗时，500ms 的窗口没测到点上；而且 `:file-lock` 那条断言本身是**竞态**
   （探针可能先抢到文件锁），只在绿的一侧可靠。

最后成型的形状：**`perform!` 直接调**（绕开工具层的不确定耗时），把调用**冻结在拿第一把锁之前**
（闸门卡在 `store/canonical`——两种锁序都得先过它），再断言**文件有没有被改**。
这一条是唯一由锁序（而不是由时钟）决定的事实：session 锁在别人手里时，先 session 后 path 的调用
根本碰不到文件；先 path 后 session 的调用**已经写完了**。见证：

- 旧锁序：**2 条用例各红一条**（`an-undo-…` 与 `a-write-…` 在 `(false? (:file-changed? out))` 上失败）；
- 新锁序：绿。定向 `--ns harness.cap.hashline.write-test` = 17 用例 / 61 断言 / 0 失败。

**顺带一条教训**（写给下一个写竞态用例的人）：**「deref 超时所以没死锁」这种断言是不成立的**——
一个还没开始的线程也会超时。用例必须**作证**它真的把线程放到了那个位置上。



### 票 10 —— **已落地**（票文件已删），但它的价值要按实测改写

改的是 `ensure-connection!`：**字节判定不再搬文件**。`:damaged` 时不再当场 `quarantine!`，
而是照常开库，由 `connect-and-migrate!` 里已有的 `damage?`（只认 `SQLITE_CORRUPT` / `SQLITE_NOTADB`）
决定要不要隔离；同时 `created?` 从「不是 `:ours`」收窄成**只有 `:fresh`** ——
被判定可能损坏的文件**不是新家**，我们的身份不该写进一个还没读通的文件里。
`wrecked` 的措辞也跟着改了：它现在说的是**一次字节读数，不是判决**，
因为那条只读路径不许开库、也就问不了引擎。

**实测推翻了我自己在票里写的期待**，这一条比改动本身重要：

> 我在票里写「打得开 ⇒ 不搬」，还写了一个用例打算证明「字节看着短、SQLite 打得开」。
> 用例先红了——**SQLite 自己也判它是坏的**：
> `[SQLITE_CORRUPT] The database disk image is malformed`。
> 也就是说「头部声明多于文件实有」这个形状，**引擎不会替我们救下来**。

所以整件事的承重墙是 **09 的侧车守卫**（旁边有 `-wal` 时根本不判），不是 10 的「让引擎拍板」。
10 真正买到的是两件小事，用例改成钉这两件：

1. **隔离原因现在是引擎自己的话**（`SQLITE_CORRUPT …`），不再是那句字节读数——
   今天现场那种 `declares N … only N` 从此不可能出现在搬迁记录里；
2. 只读路径的拒绝**说得诚实**（「这是字节读数，不是判决；这个状态下的库很可能开得好好的」），
   并且它照样一个字节都不动。

**红绿都见过**：把 `ensure-connection!` 临时改回旧样子（`:damaged` 当场搬），用例**红**，
失败信息正是旧行为留下的证据——

```
expected: (str/includes? why "SQLITE_CORRUPT")
  actual: (not (str/includes? "its header declares 331776 bytes but only 69632 are there" "SQLITE_CORRUPT"))
```

——**字节读数被当成了搬迁的原因**。还原后绿。

**验收**：`node scripts/test.mjs --ns harness.infra.db-test` = 23 用例 / 147 断言 / 0 失败；
全量后端在这一步之后会再跑一遍（见末尾的总结）。

### 票 08 —— **已落地**（票文件已删），范围比票面写的大

票面只写了 `quarantine!` 的 TOCTOU。**实测发现这个竞态比那更宽**，所以修成了三件事：

1. **`quarantine!` 容忍「源文件已经被别人搬走」**：`Files/move` 抛 `NoSuchFileException` 不再算失败
   （那正是这次调用要找的答案），`-wal`/`-shm` 半路被搬走同理。
2. **一次事故只记一笔**：`recovery-log` 只由**真的搬走了那个数据库文件**的那次调用记账；
   什么也没搬的调用在 stderr 上说一句「已经被别的线程搬走了」，不追加事实。
3. **建库/重建一次只许一个线程**（新加的进程级 `rebuild-lock`）：这不是「慢一点」，是**对与错**的区别——
   一个线程把库搬走时，另一个线程正开着它，失败码是
   `SQLITE_READONLY_DBMOVED`、`SQLITE_IOERR_FSTAT`、`cannot commit - no transaction is active`，
   **一个都不是 `damage?` 认的损坏**，于是全都落进 `:open-failed` ⇒ 500。
   锁只包住「不是 `:ours`」那条路（`:ours` 是每条连接都会走的热路径，保持不加锁），
   并且在**锁内重新判定**：另一个线程可能刚刚把库建好。
   `recovery-log` 的判定也从「不是 `:ours`」收窄成**只有 `:fresh`**
   （被怀疑损坏的文件不该被写入我们自己的身份）。

**红绿都见过**：把 (1) 与 (3) 按旧行为临时改回，`--ns harness.infra.db-test` **红 4 条**，
而且失败模式里多出一种比票面更凶的：

```
.../harness.db is a SQLite database, but not this store's: its application id is 0
(this store claims 1751216750) ...
```

——一个线程读到了**正在被创建**的库，判定成「外来库」并**拒绝启动**。锁修掉的正是这一类。

**验收**：`node scripts/test.mjs --ns harness.infra.db-test` = 25 用例 / 162 断言 / 0 失败
（新增 2 条：确定性的「第二次搬运不算失败也不记第二笔」+ 8 线程的搬运竞态）。

### 票 04 —— **已落地**（票文件已删）

`edge/http.clj` 的两个「首见」事实（`SessionStart` 触发过没有、`provider/init` 行写过没有）从
「`contains?` 一下、干点事、`conj` 一下」改成**判定即占位**的一个入口：

```clojure
(defn- claim-once! [^clojure.lang.Atom a thread-id]
  (let [[before _] (swap-vals! a conj thread-id)]
    (not (contains? before thread-id))))
```

两个 atom 仍然**一个事实一个**（脚本 pin 的会话不写 init 行、但它确实起了，是两件不同的事）。
占位发生在副作用**之前**，docstring 写明这个方向是故意的：这条规矩要挡的是**第二行**，不是保证第一行。

**红绿都见过**：把两个调用点临时改回旧形状，跑 `--ns harness.kernel.hooks-wired-test`，新加的
`two-runs-of-one-thread-start-it-once` **红**，失败信息就是现场：

```
SessionStart fired once, not once per run:
["hook/SystemPrompt" "hook/SystemPrompt" "hook/SessionStart" "hook/SessionStart"]
```

——同一个 thread-id 两条并发 run，`SessionStart` 触发了**两次**。还原后绿。

**一条要记的事实**：`provider/init` 行在**脚本 pin** 下根本不写（没有可记录的解析结果），
所以那条「恰好一行」在并发用例里断言的是**零行**；顺序版的那半由 `harness.edge.http-test`
的时间线用例覆盖。另加一条原语用例（16 条线程同时叫入口，恰好一条拿到 `true`）。

**验收**：`node scripts/test.mjs --ns harness.kernel.hooks-wired-test` = 18 用例 / 95 断言 / 0 失败。

### 票 05 —— **已落地**（票文件已删）

`cap/providers.clj` 多出**一个原子入口**，两处调用方都改用它：

```clojure
(swap-override! thread-id change)  ; -> {:before .. :after .. :resolved ..}
```

CAS 重试（`compare-and-set!` 比的是 map 的同一性），`resolve-override` 在**写之前、重试之外**跑——
它会在改动服务不了时抛，塞进 `swap!` 的函数里就会在一次无罪的竞争重跑中把写入丢掉。
`after` 与 `resolved` 是它在同一次 CAS 里得到的那一对，所以审计行记的 `before → after`
**是真的发生过的一次转变**，不是调用方自己的两份拷贝。改的是
`cap/tools.clj` 的 `session-configure` 与 `edge/http.clj` 的 `/api/model`（合并分支 + 清空分支）。

**用例与红的证据**：`providers_test.clj` 新增确定性窗口用例——**闸门卡住第一个调用者**
（`providers/selection` 正在「已读、还没写」那一步上），让第二个改动落进窗口。这需要给 12 号的
闸门加一个「只卡前 N 次」的 arity（**补记在 12 号票面之外，已加**：`window-gate` 三参形式
+ 一条自己的用例）。把 `swap-override!` 临时改成旧的读-改-写形状，这条**红**：

```
FAIL in (a-change-that-lands-while-another-is-in-flight-is-not-lost)
and the change that was in flight was NOT lost: {:model "alpha-large"}
```

——飞行中的 `:reasoning-effort` 被吃掉了。原子形状下绿。

**一条要记的教训**：我第一版写的是「12 条线程各改一个旋钮、25 轮」那种跑跑看，它在**非原子形状下也是绿的**
——只采样最终状态根本抓不住丢更新。竞态用例必须是**把两个操作的交错逼出来**，
不是跑很多次指望它发生。

**验收**：`node scripts/test.mjs --ns harness.cap.providers-test` = 95 用例 / 505 断言 / 0 失败。

### 票 01 —— **已落地**（票文件已删）

`AGENTS.md` 多出 `## 不可变数据与线程` 一节（与 `## 测试` 并列），**七组**规矩，每组点到至少一个
**真存在**的正例：位置要有人认领 / 进程级容器按键分家 / 两个原子操作之间不许夹副作用 /
锁的顺序是数据的一部分 / **快照不是事实**（这场事故教的那条）/ 线程自己的东西给线程 /
跨线程动态绑定只有两条路。验收两条 grep 都跑过：正例全在 `src/harness` 里对得上，
这一节里没有 `file:line`、没有票号、没有会过期的「今天/目前」。

### 票 06 —— **已落地**（票文件已删），并且它把票里那个「首选做法」实测否掉了

`infra/logging.clj`：一把进程级 monitor `reconfigure-lock`，`configure!` 与 `ensure!` 都在它里面
做「判断 + 重配」，`ensure!` 的 `(home/root)` 读取也在锁内。

**实现者去验了票里的首选做法（「别摘了，logback 的 `addAppender` 按名字替换」），实测是错的**：

1. `Logger.addAppender` → `AppenderAttachableImpl` → `COWArrayList.addIfAbsent` → `CopyOnWriteArrayList.addIfAbsent`，
   比的是 `equals`，而 `AppenderBase` 不重写 `equals` ⇒ **不摘就是堆**（实测第二个 `"console"` 变成 3 个 appender，没有按名字替换）。
2. 更关键：`RollingFileAppender.start()` 有一个**按名字比的碰撞检查**，同一个文件模式上第二个
   滚动的 appender 会**放弃启动**（实测：装了 9 个、启动了 1 个），而 `stop()` 才会把名字从那张表里去掉
   —— 所以旧的**必须**先停。也就是说票里「一个洞比一条重复更坏」的偏好**做不到**，
   只能走票里给的那条退路，并把「搬 root 那一瞬间到达的那一行会丢」写进 docstring。

**红绿都见过**：把锁去掉，`--ns harness.infra.log-test` **红 10 条**（`(not (= 2 16))`、
频率表 `{"console" 8, "rolling" 8}`、以及 8 条「每句话恰好一次」）；带回锁 **0 失败**。
**一条要记的事实**：这份 logback 下**文件侧不会重复**（滚动的那个只可能启动一个），
重复是**实测在 console 上**（8 倍），所以用例同时把 `*err*` 绑到一个共享 writer 上断言——
那条断言才是红的那条；文件那条照票面保留，并注明了它为什么两侧都绿。

**2026-09-18 补记（08 的锁范围后来改了，以这段为准）**：上面写的「非 `:ours` 才拿锁」**不够**，
全量跑暴露了第三个失败模式：**一个正在被建的库从外面读起来就是 `:ours`**（身份是第一个写的，
namespace docstring 里那条 load-bearing 的次序），于是读者跳过锁去开半成品，
引擎回 `SQLITE_CORRUPT`，**把别人正在建的库搬走了**——而那个人自己的连接随后死于
`READONLY_DBMOVED`（实测：两条 `recovery` 记录相隔 3 毫秒、两条 `READONLY_DBMOVED`）。
改成**判定与开库整段在同一把 monitor 里**（`store-open-lock`），并去掉了「先判、再按结果选锁」
那个形状（它会引出读锁升写锁的死锁）。代价是一把无竞争的 monitor —— 而 `with-connection`
本来每次就要新开一条 JDBC 连接，**只有开库那一步被串行化，后面的查询不受影响**。
去掉 monitor 复跑即红（同一张票的 2 条断言），带上即绿；全量 **882 用例 / 11410 断言 / 0 失败**。

### 票 11 —— **已落地**（票文件已删）

`edge/http.clj` 里加了 carry-back：`log!` 即将写进**项目工作区**、而
`projects/.unbound/<thread>.jsonl` 还在（非空、不是同一个文件）时，在 `log-lock` 内
先验**两段区间不重叠**（`dest-last-ts <= segment-first-ts`），再追加、并把源文件改名为
`<thread>.jsonl.carried-<stamp>`（**不是** `.jsonl`，所以列目录与 `replay` 都不会把它当一段对话）。

- 重叠、乱序、或缺时间戳 ⇒ **一个字节都不追加**，源文件原样留着，落一条 `log/carry-refused`
  审计线**点名两个绝对路径**与原因；
- **每个 thread 每个进程最多处理一次**（`carry-back-checked` 只在真有过一段时标记）——
  今天的现场是**进程中途**发生的，所以「第一条记录时标记」那种闸门永远不会触发；
- **绝不把异常抛进写日志的路径**：任何失败都留原样并落一条拒收审计线；
- 读取侧（`replay`）一个字没改：搬运只碰文件系统。

**红绿都见过**：`--ns harness.edge.http-test`，改之前 **14 条红**（新用例里
`[1 2 3 4 5]` 对 `[1 2 5]`、`logs-for` 数出两份、没有 `log/carried-back` 审计线；
拒收那一半则是「没有拒绝记录」），改之后 **61 用例 / 679 断言 / 0 失败**。

### 票 07 —— **已落地**（票文件已删）

`serve.clj` 的 `serve!` 与 `grep.clj` 的 `perform!`：读与标账放进**同一个** `store/with-session-lock`
（`sync!` 自己拿 session→path，锁可重入，顺序不变），两处各加一段 docstring 说清为什么标之前必须拿锁。

**用例是「锁替身」，不是跑跑看**（票面要求的形状）：`alter-var-root` 把 `store/mark-served!`
换成一个**先问自己这条线程有没有握着本会话的锁**、再转调原函数的替身，跑一次 `read` 与一次 grep 工具。
**红绿都见过**（把 serve/grep 暂时 stash 回旧形状，`--ns …read-test,…grep-test`）**红 3 条**，其中一条正是
那条不变量的破坏——

```
expected: (every? (set (:anchors st)) (:served st))
  actual: (not (every? #{"MXhe" "BWiz" "THes"} #{"MXhe" "BWiz" "THes" "hfAi"}))
```

——`served` 里多了一个已经不在 `:anchors` 里的 `hfAi`，也就是「模型看过一个已经不存在的锚点」
（锚点名字会回收，所以它可能已经属于**另一行**）。还原后绿。
