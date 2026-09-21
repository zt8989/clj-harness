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

**2026-09-20 立票，当日开工。** 下面的数字都是在**这台机器**上量的（Windows、4 逻辑核、git 2.23、
Git Bash 走 `bash -lc`）：

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
