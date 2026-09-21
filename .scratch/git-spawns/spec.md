# spec: 少起 git 进程 —— 一次状态读 4 → 2，夹具建一次拷多次

2026-09-20 立。起因是一次计数：`harness.cap.git-test` 只有 **7 个用例**，一次运行却起了 **100 次
进程**。逐条数下去，**真正被测的动作只有 6 次**。

## 问题

三处乘法，一处比一处贵：

1. **`git/state` 一次调用 = 4 个 git 进程。** 它问了四个独立的问题，每个新起一个 `git`：
   `rev-parse --is-inside-work-tree`、`rev-parse --abbrev-ref HEAD`、`branch --format=…`、
   `status --porcelain`。
2. **`git/switch!` 一次成功切换 = 9 个进程**（`state` 4 + `checkout` 1 + `state` 4）；被拒绝的也
   要 4 —— 那张 `state` 读出来的列表就是 allow-list。
3. **测试夹具 `scratch-repo` 一次 = 8 个进程**（`init` + 3 条 `config`（为了不碰开发者的全局
   git config）+ `add` + `commit` + `branch -m main` + `branch side`），而 5 个用例各建一个新仓库。

于是 100 次的分布是：**夹具 40、读状态 53、被测动作 6**。

**这不是测试的问题，是 `state` 的问题。** 它在产品里被调用：`http.clj:2541` 那个分支条的端点
（每次请求 **4 个进程**）、`:2574` 那个切换端点（`state` 4 + `switch!` 内部 9 = **13 个进程**）。
而每个进程在 Windows 上还要过 `bash -lc`（本机实测 ~770ms，其中 ~700ms 是登录 profile；见
6652097），所以一次状态读取 ≈ 4 × (770ms + git 本身) ≈ **3.5s 起**。

## 决策

1. **`state` 用一条命令问三个问题。** `git status --porcelain=v2 --branch` 一次给出三件事：
   是不是仓库（非仓库时 **exit 128**）、当前分支（`# branch.head`）、改动（非 `#` 开头的行数）。
   **4 → 2** —— `branches` 那条 `branch --format` 省不掉，porcelain 不给分支列表。

   映射表（本票的核心，写进代码注释）：

   | porcelain v2 | 今天的来源 | `state` 的答案 |
   |---|---|---|
   | exit 128 | `rev-parse --is-inside-work-tree` ≠ `true` | `{:repo? false}` |
   | `# branch.head main` | `rev-parse --abbrev-ref HEAD` = `main` | `:branch "main"` |
   | `# branch.head (detached)` | 同上 = 字面量 `HEAD` → 特判 nil | `:branch nil` |
   | `# branch.oid (initial)` | 同上 **exit 128** → nil | `:branch nil`（**不是** porcelain 给的名字） |
   | 非 `#` 的行数 | `status --porcelain` 的行数 | `:dirty n` |

2. **`(initial)` 那一格是本票新发现的坑，必须与 detached 一样落到 nil。** 未出生的分支
   （`git init` 之后没有 commit）porcelain 会答出分支名（`# branch.head master`）而 **exit 0**，
   今天 `rev-parse` 在那里 exit 128、`:branch` 恰好是 nil。**照抄 porcelain 就会把一个不在
   `:branches` 里的名字当成当前分支画出来** —— 正是 `state` docstring 已经警告过的那种不一致
   （它警告的是 detached，同一个道理在这里又出现了一次）。实测过：见「状态」一节。

3. **`branches` 同步读，不懒加载。** 懒加载能把 4 → 1，但它改的是 API 契约（`:branches` 从
   「总是有」变成「可能要」），而两个调用方都要它（选择器要画、切换要当 allow-list）。**本期不做**，
   记在这里。

4. **夹具建一次、拷多次。** `use-fixtures :once` 建一个**由真 git 建的**模板仓库，每个用例
   `cp -r` 一份 —— 纯文件操作、**0 spawn**，而拷过去的仍然是一个真仓库。拷贝是最强的隔离形式：
   比 `git clean` 可靠，因为不用信任 git 的状态。

5. **不许为了省进程删掉「读回真仓库」的判据。** 那个测试文件的立场是「断言从仓库读回来，而不是
   从我们自己的答案读回来」；`run-in` 那几次验读是**判据**不是开销，留着。

## 非目标

- **profile 那三个开关**（`WINELOADERNOEXEC` / `LANG` / `TERM`，770 → 333ms）是**另一条线**：它改
  的是每条被 spawn 的命令看到的环境，是产品语义决定，不该混进一个 git 票里。实测与理由在
  6652097 与 `shell.clj` 的注释里。
- **让 `git` 走 `cmd`**（66ms vs 770ms）：`cap/git` 拼的是 POSIX 单引号命令行，换壳要另一套引号
  契约。记在 `.scratch/bash-shell-choice/spec.md` 的「只有 `bash` 工具吃 `shell`」那条下面。
- 不给 `state` 加缓存或请求内去重；那是另一件事。

## 验收主线

- **进程数用计数器量**（临时给 `shell/run` 装一个、跑完还原 —— 就是量出 100 的那套办法）：
  `state` 4 → 2，`switch!` 成功路径 9 → 5。
- **答案逐字不变**：`git-test` 既有的 7 个用例**一条不改**、全绿。
- **两个今天没覆盖的格子补上**：detached HEAD ⇒ `:branch nil`；未出生 HEAD ⇒ `:branch nil`
  且**不是** porcelain 给的那个名字。（今天整套里没有这两个用例。）
- **夹具**：`scratch-repo` 只被 invoke 一次，其余用例从拷贝来，而每个用例仍拿到一个**干净、独立**
  的仓库 —— 一个用例写的文件不能出现在下一个用例的目录里。
- 全量两套报数（改前 / 改后），**失败名单逐个比**，不只比绿不绿。

## 跨特征对照

- **`.scratch/bash-shell-choice`**（已落地）：`bash` 的 `shell` 参数与按 kind 解析。本票不碰它 ——
  `git` 走哪只壳是另一个决定（见非目标）。
- **6652097**（`-l` 是承重的）：那一条量的是**一个 spawn 的成本**（770 vs 333ms）；本票量的是
  **spawn 的次数**。两笔账相乘，但要分开记。
- **`docs/rules/testing.md`**：临时目录与隔离的纪律。夹具拷模板要落在这个纪律里（每进程唯一的
  临时根）。

## 交付顺序

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | `git/state` 一条命令答三个问题 | — | porcelain v2 折叠（4 → 2）；detached 与未出生两个映射写下来并配用例；`switch!` 跟着降到 5；docstring 里点名的那几条命令跟着改 |
| 02 | 夹具建一次、拷多次 | — | `use-fixtures :once` 建模板（真 git 建的）+ 每用例 `cp -r`（40 → 8）；每用例仍是干净独立的真仓库 |
| 03 | 收口 | 01, 02 | `docs/architecture.md` 的 `cap.git` 那一行（第 86 行）补上「一次状态读几个进程」、两套全量报数、落地记录 |

## 状态

**2026-09-20 立票，当日开工，三张票当日落地。** 下面的数字都是在**这台机器**上量的（Windows、
4 逻辑核、git 2.23、Git Bash 走 `bash -lc`）：

- `git-test`：7 用例 / **100 次 spawn** / 107.7s（≈1.08s 一次，与「770ms profile + git 本身」吻合）
- 一次 `state` = 4；一次成功 `switch!` = 9；一次 `scratch-repo` = 8
- 100 次的分布：夹具 **40** / 读状态 **53** / 被测动作 **6**
- 产品侧：`http.clj:2541` = 4 次/请求；`:2574`（切换）= 13 次/请求
- porcelain v2 的两个边角，实测（本机 git 2.23）：

  ```
  未出生 HEAD   : # branch.oid (initial)          # branch.head master   exit 0
                  rev-parse --abbrev-ref HEAD → exit 128（今天因此答 nil）
  detached HEAD : # branch.head (detached)                              exit 0
                  rev-parse --abbrev-ref HEAD → HEAD（今天特判成 nil）
  ```

基线（立票当天，`main` @ `19e9d9f`）：
`clojure -M:test -m harness.test-runner` → 982 用例 / 12080 断言 / 0 失败 / 0 错误。

### 01 — `git/state` 一条命令答三个问题

- **4 → 2 是量出来的，不是算出来的。** `dev/scratch_git_spawns.clj` 给 `shell/run` 装一个临时计数器
  （`with-redefs`，出一次调用就还原），并把 `HEAD` 上那一版 `git.clj` 用 `git show` 读回来、换个命名
  空间名加载 —— 于是**同一批仓库**被两个版本各答一次：`state` **4 → 2**、成功 `switch!` **9 → 5**、
  一次夹具仍是 **8**。计数在 macOS / git 2.52 上量的，与立票那台 Windows / git 2.23 的 4 / 9 / 8
  一致，因为**次数与平台无关**（6652097 量的是另一样东西：一次 spawn 的 770ms，那个才随平台变）。
- **答案逐字不变，除了两处故意变的。** 脚本拿 7 个场景（干净的库、一个未跟踪文件、非仓库、`nil`、
  `""`、不存在的路径、未出生 HEAD）把两版答案对过，全等 —— `:branches` 按集合比，它的顺序是 git
  自己的（`--sort=-committerdate`），不是本仓承诺的东西。
- **`(initial)` 与 detached 两格按票面落地**：`:branch` 只在「porcelain 也说有 commit」且「不是
  detached」时才取值。未出生时 porcelain 热情答出的 `master` 一个字节都不采信 —— 那个名字在
  `:branches` 里无处可去，而 `:branches` 是空的。
- **票面没写、落地时撞见的一处既有 bug，顺手修了，因为它是同一句话的另一半**：
  `branch --format=%(refname:short)` 在 detached 上会把整句 `(HEAD detached at 0082ef1)` **当成一个
  分支名**交出来 —— 于是选择器里挂着一个谁都不能切过去的名字，而同一时刻 `:branch` 恰好是 nil。
  改成问 `%(refname)`、只留 `refs/heads/` 前缀的行（`local-branches`）：「本地分支」由**引用的定义**
  给出，不是猜字符串的形状。detached 那条新用例钉的就是这一格，写它之前这一格是错的。
- **一处按票面的映射表故意改了口径，记在这里而不是藏起来**：库**索引读不出来**时
  （`fatal: .git/index: index file smaller than expected`），`status` 与「不是仓库」一样 exit 128，
  于是现在答 `{:repo? false}`；四个 spawn 那一版答的是 `{:repo? true :branch "main" :dirty 0}` ——
  对一个读不出来的索引作出的「路上什么都没有」的肯定回答。两害相权，**说不出真话时不再假装说得
  出**。脚本把两版都打印出来。
- **0 个 spawn 的那条没变**：`nil` / `""` / 不存在的路径仍在任何 spawn 之前就被答掉；存在但不是仓库
  的目录花 1 个 —— 「这是不是一个仓库」只有 git 能答。

验收：`harness.cap.git-test` 7 用例 / 28 断言 → **9 / 37**（两条新用例），**既有 7 条一条不改**；
`harness.edge.http-test` 走 `state` 的那条路径绿；全量 982 / 12080 / 0 失败 / 0 错误 →
**984 / 12089 / 0 / 0**。`dev/scratch_git_spawns.clj` 15 项全绿（`clojure -M:dev -m scratch-git-spawns`）。

### 02 — 夹具建一次、拷多次

- **40 → 8 也是量出来的，且量的就是「整份文件跑一遍」。** 同一个计数器把两个版本各跑一遍：基线那一版
  文件（7 用例）里 **5 次 build = 40 个 spawn**；这一版（10 用例）**1 次 build = 8 个**。夹具的
  「build」按 `git config commit.gpgsign false` 数 —— 只有 `build-repo!` 会设它。整轮 74 → 57，省下
  的正是夹具那 32 个加成票 01 省下的读次数。
- **多出来的 3 个仓库命令是 unborn 那条用例自己的**，不是夹具的：它要一个**没有 commit** 的仓库，而
  模板的拷贝做不到这件事。所以「每个用例拿到一个新仓库」这句话仍然成立，只是其中 9 条改成拷来的。
- **拷贝比「共享仓库 + 用例之间清理」更强的隔离，这一点单独钉了一条用例**：同时拿两份拷贝，在 a 里
  写一个未跟踪文件、改一个已提交文件，然后断言 b 里什么都看不见、模板本身也没被写。共享仓库那条路
  会让 dirty-switch 的两条用例**因为错的理由变绿** —— git 在别人弄脏的库里也照样拒。
- **`Path.relativize`，不是字符串切片**：Windows 上 `File.getPath` 答的是反斜杠，减掉一个用斜杠拼的
  前缀什么都匹配不到 —— `harness.layers-test` 的 docstring 记的就是那一次（98 个文件一起误报），
  这里用的是同一套算法。
- **模板是一次运行一份，不是一次进程一份**（`atom` 而不是 `delay`）：`:once` fixture 跑完把模板根
  **删掉**（不在 `java.io.tmpdir` 里过夜，这是验收里的一条），而同一个 JVM 第二次跑这个命名空间必须
  还能建起来 —— `delay` 会把一个已经不在的路径交出去。脚本里那条断言直接读
  `#'harness.cap.git-test/template-root`，跑完它不在。
- 模板**仍然由真 git 建**：预制一份 `.git` 会把「断言是从 git 建的仓库里读回来的」这个前提换掉，
  省的只能是**次数**，不能是真实性。

验收：`harness.cap.git-test` 9 用例 / 37 断言 → **10 / 43**（新用例钉隔离与模板），票 01 的两条与
原来的 7 条**一条不改**；全量 984 / 12089 / 0 失败 / 0 错误 → **985 / 12096 / 0 / 0**；
`dev/scratch_git_spawns.clj` 全绿（票 03 又给它加了「整份文件」的那一条，见下）。

### 03 — 收口：文档、两套报数、落地记录

- **`docs/architecture.md` 第 86 行补了进程数，没有新开一节**：那一行现在是「**读一次状态 2 个进程**
  ——一条 `status --porcelain=v2 --branch` 一次答出『是不是仓库 / 在哪个分支 / 路上有什么』，再一条
  列本地分支；**一次成功切换 5 个**。两者各自降了一半（4 → 2、9 → 5）」。位置就是票面说的理由：那一
  行是读者判断「这个端点贵不贵」时唯一会看的地方。
- **旧命令名复查过了**：`src/harness/cap/git.clj` 里真正在跑的只剩三条 —— `status --porcelain=v2
  --branch`、`branch --format=%(refname)`、`checkout`。`grep` 旧名字还剩 5 处，**每一处都明说
  「旧」**：`porcelain-branch` 那张映射表的中间一列（票 01 点名要写的），加三句
  「`rev-parse` 在那里 exit 128」/「used to answer」/「the old probe」。没有一句在说现在的代码这么
  做，所以留着 —— 它们是这次折叠**为什么这么折**的唯一记录。
- **两套全量报数是判据**：改前（`main` @ `19e9d9f`）**982 用例 / 12080 断言 / 0 失败 / 0 错误**；
  改后 **985 / 12096 / 0 / 0**。**失败名单逐个比**：两边都是空的，所以这次的结论就是「没有多出来的
  失败」。+3 用例 / +16 断言能逐条对上：票 01 两条（detached 3 条断言 + 未出生 6 条 = **+9**）、
  票 02 一条（隔离与模板 **+7**）。
- **spawn 计数，本特征的全部意义所在**：`git-test` 整份文件 **100 → 57 次 spawn**，而用例是
  **7 → 10 条**（多了 3 条）。分解：夹具 **40 → 8**（票 02），其余 **60 → 46**（票 01 把一次状态读
  从 4 降到 2，但新加的三条用例自己也要读）。**那个 100 与立票那天在这台 Windows 机器上量到的 100
  逐字相同** —— spawn 的**次数**与平台无关，这正是这条数能跨机器比的原因。
- **时间只在这台机器上量，结论也只在这里成立**：整份 `git-test` 在本机（macOS、git 2.52）从
  **2861ms → 1661ms**（计数脚本里量的；runner 单独报这个命名空间 2.0s）。立票那台的预期
  **107.7s → 约 45s** 是按「一次 spawn ~1.08s（`bash -lc` 的登录 profile）」算的，而这里一次
  spawn 约 10ms —— 差两个数量级，**那一条在这台机器上验不了**，要验只能在 Windows 那台上跑。
- **`ui` 复跑一次确认没被本特征碰到**：`cd ui && npm test` → **52 用例全过**（vitest，13.89s）；
  `npm run typecheck` → **退出码 0**（tsc）。两条都在
  `/Users/zhouteng/Documents/workspace/clj-harness`（**主检出**）里跑的：工作树里没有 `node_modules`，
  而两边的 `ui/` 逐字节相同（本特征一个提交都没碰 `ui/`，`git log 19e9d9f..HEAD -- ui/` 是空的）。
  按 `AGENTS.md` 那条「动过 `ui/src/` 才走走查」：**没动，所以不走** `scripts/dev.mjs --scripted`。
- **非目标那两条没有被顺手做掉**：profile 三个开关（6652097 那条线）与「让 git 走 cmd」
  （`.scratch/bash-shell-choice`）都没碰。

收口验收：`dev/scratch_git_spawns.clj` **18 项全绿**（`clojure -M:dev -m scratch-git-spawns`）；
三张票面删除，记录只活在 `git log` 与这一节里。
