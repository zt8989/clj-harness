# spec: `bash` 选壳 —— 一次调用指名它跑在哪只 shell 里

2026-09-20 立。**要的是：`bash` 调用能指名用哪只壳跑。** 默认还是今天那只（Windows = Git Bash），
但可以要 **cmd**、**PowerShell 7**（`pwsh`）、**PowerShell 5**（`powershell`）。前台与后台
（`run_in_background`）都要能选。

## 问题

今天**一个进程只有一只壳**：`harness.infra.shell/resolution` 是个 `defonce`，链上第一个找得到的
赢，此后每一次 spawn 都用它（Windows 上是 Git Bash）。模型想跑一条 cmd 或 PowerShell 的命令，
只能把它**包在 bash 里再起一只**：

    bash {command: "cmd /c \"dir %TEMP%\""}          ; 引号要过两层
    bash {command: "pwsh -NoProfile -c 'Get-ChildItem $env:TEMP'"}   ; 而且 $env: 先被 bash 吃掉

这样错的东西不止引号：`%VAR%` 与 `$env:VAR` 的展开、`&` 与 `;` 的分隔、退出码的传播，全都在
两层壳之间被改写一遍，而报错指向的是**外面那只**。模型要么绕，要么放弃。

## 决策

1. **参数名 `shell`，不叫 `shape`。** `shape` 在本仓已经指**另一根轴**：`spawn-argv` / `start` 的
   `:shape` 说的是「`:program`（程序 + 参数，Windows 上走 `cmd /c`）还是 `:shell`（一条写给 shell
   的命令行）」。选壳这件事的词用 `candidates` 里已有的那个：`:kind`。取值即 kind：

   | `shell` 的值 | 是什么 | 怎么起（`how-to-start`） |
   |---|---|---|
   | `git-bash` | Git Bash（**默认**，即今天的行为） | `-lc` |
   | `cmd` | Windows 自己的 cmd | `/c` |
   | `pwsh` | PowerShell 7+ | `-NoProfile -Command` |
   | `powershell` | Windows PowerShell 5.1 | `-NoProfile -Command` |

   不传 = 逐字保持今天的行为（链上解出来的那只）。**这是本期唯一不许动的兼容面。**

2. **解析从「一进程一只」改成「按 kind 解析、各自缓存」。** `resolution` 今天缓存一个答案、而且
   `require-shell!` / `require-posix!` 都读它。改成按 kind 缓存之后，那两个函数的语义一个字不变
   （rg、git 仍然按名字要求一只 POSIX 壳），变的只是「问哪一只」多了一个参数。

3. **只有 `bash` 工具吃这个参数。** `rg`（`anchor_grep` / `glob`）、`git`、hooks 拼的都是 POSIX
   命令行，它们本来就 `require-posix!` 按名字拒绝非 POSIX 的壳 —— 让它们也能选壳要另一套引号契约
   （`rg` 用的是它自己的 `quoted`，不是 `quote-arg`）。**不在本期。**

4. **机器上没有那只壳 ⇒ 按名字拒绝，并说出这台机器有什么。** 这与本仓既有姿态同一条
   （`resolution` 的注释：「A resolution that names a program which cannot run is worse than one
   that admits there is none」）。在本机要 `shell: "pwsh"` 而没装 pwsh，答案必须说「没有 pwsh，
   这台机器有 git-bash / cmd / powershell」，**不是**静默回退到默认壳 —— 静默回退会让模型以为
   自己写的 `$env:` 语法被执行了。

5. **`command` 始终是「写给那只壳的一行」。** 选了 cmd 就得写 `&`、`%VAR%`、`dir`；选了 pwsh 就得
   写 `;`、`$env:VAR`。工具描述必须把这条说明白 —— 参数换的是**解释这行字的壳**，不是「同一行字
   换个人执行」。`stdin` 与 `workdir` 不受影响（`run` 对三种壳都是写 stdin 再关、都用
   `.directory` 设 cwd，实测三只壳都吃 Windows 路径）。

6. **后台作业记住壳，但答案不加第三个事实。** `jobs/start!` 今天只带 `{:command :dir}`，壳取自
   进程默认。这一票让它把 kind 带下去。**答案仍然是「job id + 记录路径」两条**：作业用哪只壳是
   「这一行是谁写的」的属性，不是作业的身份，`job_output` 读记录时也不需要知道。
   **2026-09-23 补（`.scratch/job-receipt-no-path/`）**：那两条事实里的**记录路径**换成了一句**读法**
   （``read it with `job_output {"job": "j1"}`.``）——「不加第三个事实」这一半不变，一行仍是一行。

7. **答案的形状一个字不改。** 仍然是被杀前的输出 +（到点时）那一条，仍然是按流封顶 + 记录文件。
   换壳不新增表头行、不改 `[exit N]`。
   **2026-09-23 补（`.scratch/job-receipt-no-path/`）**：形状仍是「id + 一句话」，但第二段不再是路径，
   而是读法。这一条按上面的补记读。

## 非目标

- **WSL（`wsl.exe` / `System32\bash.exe`）本期不做。** 顺带把实测到的事实记在这里，因为
  `wsl-launcher?` 的 docstring 里那条理由**已经不成立**：「from a JVM, answers nothing at all」
  —— 2026-09-20 在本机实测，从 JVM spawn 它 `exit=0` 且输出正确（`echo hi; pwd` →
  `/mnt/c/Users/...`），`.directory` 传 Windows 路径会被自动翻译，超时杀掉之后 WSL 里
  `pgrep sleep` / `pgrep ping` 都是空的（没有漏在 Linux 侧）。真要做，要面对的是：**另一个文件
  系统**（命令里的路径得是 POSIX 的 `/mnt/c/...`，`~` 是 Linux 的家），以及**冷启动 ~14s**
  （热 ~1.9s）—— 一个短 `timeout` 的调用会在 VM 起来之前就被判超时。链继续按名字拒绝它是**对的**
  （默认不该悄悄变成 WSL），这一票不动它。
- 不让 `rg` / `git` / hooks / MCP 选壳（理由见决策 3）。
- **不做「自动挑壳」**：没有「这条命令看起来像 PowerShell 就换过去」这种猜测。模型指名，或者不指。
- 不动 `-lc`（那是超时杀树的承重结构，见 6652097）。

## 验收主线

- **每只壳都保住「到点回来 + 保住被杀之前的输出」**：这是本期最重要的一条，因为 `bash` 的时间保证
  是模型的依靠。已实测（2026-09-20，本机，`timeout: 3000`）：

  | 壳 | 到点回来 | 被杀前的输出 | 一次 spawn（热） |
  |---|---|---|---|
  | git-bash | 3034ms | 在 | ~770ms |
  | cmd | 3031ms | 在 | 66ms |
  | pwsh | 3041ms | 在 | 781ms |
  | powershell | 3039ms | 在 | 1042ms |

  四只**都没有 5s 拖尾**（拖尾正是 `-c` 那个陷阱的签名：见 6652097），也都没有丢输出。用例要一条
  壳一条地钉住这两件事。
- **默认逐字不变**：不给 `shell` 时，既有用例一条不改、全绿。
- **两条拒绝各说各的**：`shell: "nope"`（不认识的词）与 `shell: "pwsh"`（认识、本机没有）是两句
  不同的话，且后者列出本机有的。
- 后台：`run_in_background: true` + `shell` 时，作业真的跑在那只壳里（用一条只有那只壳认得的写法
  验，例如 cmd 的 `%CD%`）。
- 全量报数两套（改前 / 改后），失败名单逐个比。

## 跨特征对照

- **`.scratch/bash-record-persistence`**（已落地，2d2095f）：记录活过写它的进程。作业的记录与壳无关
  —— 记录里是命令说出来的话，不是谁解释的它。本特征不动记录。
- **`shell.clj` 里「登录 shell 是承重的」**（6652097）：那条查的是**同一个 namespace** 的另一个
  属性（启动成本 vs 杀树正确性）。本特征加的是「哪只壳」，**不碰**任何一只壳的 flag —— 尤其不许
  顺手把 Git Bash 的 `-lc` 改掉。
- **`.scratch/job-tools`**（已落地）：`bash` 收 `stdin` / `workdir` 那一票立了「参数从一处插值、
  描述里说清默认值」的先例，`shell` 的描述照它办。
- **`CONTEXT.md`**：运行时状态那张表里「作业 = 进程内存，命令的记录 = 文件」那一带，可能要加一句
  「一次调用跑在哪只壳里由调用指名」——留给收口那张票定。

## 交付顺序

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | `shell.clj` 按 kind 解析并缓存 | — | 每个 kind 一份解析 + 缓存（默认答案与今天逐字相同）；`require-shell!` / `require-posix!` 语义不变；本机没有的 kind 能问出「没有」而不是抛 |
| 02 | `bash` 加 `shell` 参数（前台） | 01 | 参数、取值校验、两条拒绝（不认识 / 本机没有）、描述里说清「command 是写给那只壳的一行」；答案形状不动 |
| 03 | `run_in_background` 也带壳 | 02 | `jobs/start!` 带 kind，`shell/start` 按它起；答案仍是两条（决策 6）；一条只有那只壳认得的写法验它 |
| 04 | 话术、文档、全量报数 | 03 | `CONTEXT.md` 那一句按需加、`docs/architecture/kernel.md` 的 shell 那一节、工具描述的最终一遍；两套全量报数；本特征自己的落地记录 |

## 状态

**2026-09-20 立票，当日开工。** 上面表里的实测数字都是这台机器（Windows、4 逻辑核、Git Bash
`C:\Program Files\Git\bin\bash.exe`）上量的，不是从别处抄的。

基线（立票当天，`main` @ `2d2095f`）：
`clojure -M:test -m harness.test-runner` → 972 用例 / 12036 断言 / 0 失败 / 0 错误。

### 01 — `shell.clj` 按 kind 解析并缓存

- **两张表，一张是派生的。** `candidates` 仍是「链的顺序」那一个 `def`；按 kind 找行是
  `(group-by :kind candidates)`（`rows-by-kind`），**没有抄第二份** —— 一个 kind 能在链上被落到，
  就能被调用方指名，两者不会对「Git Bash 的两个安装路径谁先」有不同意见。
- **两个问题，一个造法。** `resolve*`（链）与 `resolve-kind*`（指名的 kind）都经 `as-resolution`
  造那个 map：两个造法就是两次机会，让一个问题的答案多出另一个没有的键。
- **缓存从一个格子改成一格一键。** 原来是 `(atom nil)` 装一个 vector（「问了，没有」也是缓存下来的
  答案）；现在是一个 map 加 `contains?` —— 一个 nil 值表达的「问了，没有」对多少 key 都成立，而
  单格 vector 只对一个成立。
- **「没有」有两种来源，这一层故意不区分**：这台机器没装、与根本没有这个 kind，对 spawn site 是
  同一件事（不要用一只没人要的壳去跑）。**指名拒绝的那句话在 `require-shell!`** —— 它把「这台机器
  有什么」列出来，读的人才能把拼错与没装分开。
- **`run` / `start` 收 `:kind`，缺了就是拒绝，不是回退。** 在**没人要的壳里跑**比不跑更糟：调用方
  写的 `%VAR%` / `$env:VAR` 会被一个不懂它的人解释，而答案看起来像命令自己的 bug。
- 接缝按 kind 断言，不靠改这台机器的 PATH：`kind-candidates` 是 `candidates` 的按 kind 版（纯），
  `resolve-kind*` 是它的不纯读法（测试里 `with-redefs` 数调用次数，验两个缓存与 reset）。

落地的数（本分支）：`harness.infra.shell-test` 10 用例 / 56 断言 → **14 / 83**；
全量 972 / 12036 / 0 失败 / 0 错误 → **976 / 12063 / 0 / 0**（多出来的正是这四条用例）。

### 02 — `bash` 收 `shell`：前台调用指名它跑在哪只壳里

- **名字表是派生的，而且比原计划多了一个 `bash`。** `shell-names` 取 `candidates` 里的 kind
  （`git-bash` / `bash` / `cmd` / `pwsh` / `powershell`）。多出 `bash` 不是加功能，是**去掉一句
  会撒谎的拒绝**：`shells-here` 在 mac 上会说「这台机器有 bash」，而如果 `bash` 不是合法取值，
  那句话就在推荐一个下一句会被拒的名字。一张表同时喂描述、schema 的 `:enum`、校验与拒绝句。
- **两条拒绝是两句话**：不认识的词说「必须是这一列里的一个」（读一遍就能改）；本机没有的壳说
  「这台机器有什么」（再读一遍没用）。
- **校验在任何东西被解析或启动之前**，所以指名一只不存在的壳不会留下 job id、记录文件或半起的进程。
  `named-shell` 先问 `require-shell!`（它写那句「有什么」），再把 `:argument :shell` 补上 ——
  壳那一层不知道这个名字是当**参数**递进来的。
- **`shell` + `run_in_background` 暂时按名字拒绝**（票 03 解除）。这是本工具既有的规矩（`stdin`
  在后台就是被拒绝的）：声明了却忽略，比拒绝更坏 —— 调用方会把「没换壳」读成「换了」。
- 描述里说清 `command` 是**写给那只壳的一行**，每只壳一个最小例子（`%CD%` / `$env:TEMP` /
  `$PWD`），并写明它**不是**「同一行字换个人执行」。

落地的数（本分支）：`harness.kernel.tools-test` 41 用例 / 183 断言 → **46 / 196**；
全量 976 / 12063 / 0 失败 / 0 错误 → **981 / 12076 / 0 / 0**（多出来的正是这五条用例）。

### 03 — 后台作业也记住壳

- **壳一路带到 `shell/start`，而且在 id 被取走之前解析。** `jobs/start!` 多一个 `:kind`；
  `shell/start` 在起进程之前就 `require-shell!`。所以指名一只本机没有的壳，**没有 id、没有记录
  文件、没有进程**留下 —— 与 `start!` 对「命令根本起不来」的既有承诺同一条。
- **答案仍是两条事实，而且是被钉住的。** 新的用例把「指了壳的后台调用」与「没指的后台调用」
  放在同一句话下（~~`job \S+ started; its record is \S+`~~，一行）：壳是「这行是谁写的」的属性，
  不是作业的身份，`job_output` 从来不需要知道。记录里也不写壳 —— 记录是命令说出来的话。
  **2026-09-23 补（`.scratch/job-receipt-no-path/`）**：那句话的第二段不再是路径，是一句读法
  （``job \S+ started; read it with `job_output \{"job": "\S+"\}`.``）。
- **后台的两条规矩与壳无关，一条没动**：`timeout` 不适用（作业没有时限），`stdin` 按名字拒绝。
- 证据仍是 `%CD%`：后台用 `shell: "cmd"` 跑 `echo %CD%`，**记录文件里**出现 Windows 路径。
  bash 不会展开 `%CD%`，所以这不能是巧合。取记录用 `holds-within?` 等它落盘，与既有的
  「按文件读作业」纪律一致。
- 票 02 那条临时拒绝（`shell` + `run_in_background` 按名字拒绝）随本票**删除**，它立的规矩
  （声明了却忽略比拒绝更坏）留在原处：现在这个模式真的认这个名字了。

落地的数（本分支）：`harness.kernel.tools-test` 46 用例 / 196 断言 → **47 / 200**；
全量 981 / 12076 / 0 失败 / 0 错误 → **982 / 12080 / 0 / 0**。

### 04 — 话术、文档、两套全量报数

- **文档改在它真正住的地方，不是票面猜的地方。** 票面写「`docs/architecture/kernel.md` 的 shell
  那一节」，但 shell 的解析在本仓记在 **`docs/architecture.md` 的模块表**里（`infra.shell` 那一行），
  kernel.md 提到 shell 只有「这台机器长什么样」那一句。所以改的是 `architecture.md` 那一行 +
  `CONTEXT.md` 的词条 —— **跟着代码的事实走，不跟着票面的猜测走**。
- **`CONTEXT.md` 加的是一个词条，不是一段说明**：`shell`（选壳）与既有的 `stdin` / `workdir` 并列
  （同一族：一次 `bash` 调用的进路），写明「不给就是这台机器自己那只」，并立下「不许叫 shape」
  ——那个词已经指 `:program` 与 `:shell` 那根轴。
- **描述最终读过一遍**：`shell` 那段与 `stdin` / `workdir` / `timeout` / 后台那四段并排看，没有打架
  —— 后台那段说「不适用 `timeout`、按名字拒 `stdin`」，两条都与壳无关，而 `shell` 恰好明说
  「后台也认这个名字」。
- **两套全量逐条比过，不只比绿不绿**（本仓纪律）：基线 972 / 12036 / 0 / 0（`main` @ `2d2095f`）
  → 落地 982 / 12080 / 0 / 0。**差值 +10 用例 / +44 断言**，逐条对得上：`shell-test` +4 / +27、
  `tools-test` +6 / +17（含票 03 把一条临时拒绝换成两条）。两套的失败名单都是空 —— 这台机器上
  基线本来就没有失败（立票当天量的）。
- 前端：`npm run typecheck` 0；`npm test` 52 / 52。本期没碰 `ui/src`，跑一次确认没被牵动。
- `-lc` 一字未动（6652097）；WSL 那段留在「非目标」里，没有随文件整理消失。

**四张票走完。** 票面按本仓约定删除，记录只活在 `git log` 与这份 spec 里。




