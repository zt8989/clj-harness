# spec: bash 的命长命短 —— 一次调用的时限，与不等人那种跑法

**一句话**：`bash` 今天**没有时限**（挂住的命令挂住整个 run），也**没有后台**（想同时干两件事只能排队）。
本特征给前台调用一个**默认 120 秒的时限**（到点把命令连**子孙**一起停掉，已经打出来的东西照原样还回去），
再给「没人等它」那种跑法三个工具：**起**一条后台命令、**读**它打出来的新行、把它**停**掉。

2026-09-17 立。四张票，见文末「交付顺序」。

## 问题

1. **一次 `bash` 调用可以永远不回来。** `cap.tools` 的 `t-bash` 走 `infra.shell/shell`，那是
   `clojure.java.shell/sh` 的一层皮——**没有超时，也没有 stdin 的收口**。而同一个 `infra.shell` 里
   **已经躺着**一条带时限的路：`run`（stdin、`:dir`、`:timeout-ms`，到点返回 `:timeout true` 与
   **它已经读到的输出**）。rg、env、git、hook 引擎四个调用者都走 `run`，**只有 bash 没走**。
2. **那条路今天也杀不干净。** `run` 到点只 `destroyForcibly` **它自己 spawn 的那个 shell**；命令真正的
   进程是那个 shell 的**子孙**——`npm test` 的普通形状就是「bash 起 npx，npx 起 node」。杀了壳，
   `npm` 还在跑，而且从此没人认领（本仓实测过两个 16 小时没人收的 JVM，PPID 1）。
   `kill-tree!` 已经写好了，但它今天只给 `shell/start` 的 `:close!` 用，`run` 不碰它。
3. **「等」只有一种：等到死。** 想同时做两件事——起个 dev server、同时跑一套慢测试——只能顺序来。
   `infra.shell/start` 已经有长活进程的那整套机器（stdout 排队、`:next-line` 带等待上限、
   `:stderr`、`:alive?`、`:close!` 整棵树），但它今天**只有一个调用者**：MCP 的 stdio 服务器。
4. **这件事哪儿都没记着。** `CONTEXT.md` 里没有「超时」这个词（`grep -i timeout CONTEXT.md` 无输出），
   也没有「后台」。`.scratch/general-harness/spec.md` 的需求 11（「我想给工具执行配超时，挂死的工具
   不会拖死整个 run」）从来没被兑现过，而 `.scratch/action-fusion/spec.md` 的非目标里还写着
   「不做超时（`bash` 的既有立场）」——那是一条**决定**，本特征把它翻过来。

## 决策

1. **时限是「这一次调用等多久」，不是「命令跑多快」。** `bash` 多一个 `timeout` 参数，**毫秒**，
   **默认 120000**。到点做的事是：把命令停掉，把**它已经打出来的东西原样带回来**，末尾补一行说它停在
   哪一刻。**这不是错误**：答案是字符串，`run!` 的 `:error` 仍是 false——与 `[exit N]` 同一条纪律
   （非零退出是事实，不是失败），也与 `cap.git` 那句「挂了的 git 是一个事实，调用者可能想报告它」同源。

2. **到点要连子孙一起收。** 判据不是「答案里写着 timed out」，是**命令自己起的那个进程在调用返回后不在了**。
   所以 `kill-tree!` 要成为 `run` 也走的那条路，并且**先收子孙、再杀壳**（父进程一死，子孙就被重新
   领养，那棵树再也走不到了——`kill-tree!` 的注释已经写着这条）。

3. **默认值只有一个来源。** 一个 `def`，描述串里插值（`cap.hashline.grep/default-limit` 在它的工具描述里
   就是这么用的）。描述里手写第二个 `120000` 就是留一次「改了默认值、描述没跟上」。

4. **参数名是 `timeout`，毫秒，不设上限（牛总 2026-09-17 选定）。** 仓库对「超时」只有一个词
   （`hooks.edn` 与 `mcp.edn` 的 `:timeout` 都是毫秒，`shell/run` 内部的键是 `:timeout-ms`），
   叫 `timeout_ms` 就是同一个概念的第二个拼法。**不封顶**的理由：bash 是任意代码执行
   （`docs/architecture/projects.md` 明说这是「明示接受的逃逸面」），给 `timeout` 封顶是装样子——
   真正在保护的是那个**默认值**，而显式写一个大数是模型的明确选择。所以描述里要写清这件事：
   写一个很大的数，意味着这次的 run 真的会等那么久。

5. **`shell/shell` 退休。** bash 改走 `run` 之后它在生产里**一个调用者都没有了**，留着它就是
   「跑命令的第二条路」——超时、stdin、输出收口各写两遍，日后只会修一处。剩下两个测试调用点
   （`edge.http_test` 里的两个小助手）一并改用 `run`。

6. ~~**后台是三个名字，一个动词一个**（牛总 2026-09-17 选定）：`bash_background` / `bash_output` /
   `bash_kill`。与本仓编辑工具集的先例一致（`replace` / `insert` / `undo_last_replace` 是三个名字，
   而不是一个 `edit {action}`）。代价是每次请求多三段工具描述（工具表每次都发）；换来的是每个 schema
   恰好只有自己的参数（缝上的 `missing-args` 直接能查）、每个动词的拒绝话术独立、模型读到的是一张
   「起 / 读 / 停」的清单而不是一个 enum。~~
   **2026-09-18**：被 `.scratch/job-output/spec.md` 改掉——**两个名字**（`job` 起、`job_kill` 停），
   读的动词退场（读的是记录那份文件，用 `bash` / `read` / `grep`）。旧的这条话不逐字改。

7. **后台作业没有时限。** 后台执行的全部意思就是**没人等它**；给作业配超时是把两件事混在一起。
   跑飞的作业由 `bash_kill` 收（票 03），进程退出时由收尾钩子收（票 02）——两层都是**指名收**，
   没有「过一会儿自动死」这种第二种语义。

8. **作业的寿命 = 这个进程，按会话分家。** 注册表是进程内的 `{thread-id {job-id job}}`，Job id 是
   **每会话的计数器**（`j1`、`j2`…，短而可读；它只在会话里有意义，所以不需要 UUID 那样的全局唯一）。
   立场与 `kernel.tools` 的 parked 记录、overlay 一样：**不跨重启**——一个活着但没人认领的进程，
   比一个不活的作业更坏。**run 结束不杀它**（杀了就等于后台执行没用），会话被删也不动它；
   JVM 退出时全部收掉（`cap.mcp/ensure-exit-hook!` 的先例已经证明这条路走得通）。

9. ~~**输出尾巴有界，丢了就说丢了。** 作业的输出留在**内存里的一圈尾巴**（最近 500 行，一个 `def`），
   不落文件：落文件要选家（配置家的 `logs/`？项目目录？）并配一套清理策略，而这不是会话历史，
   不进记录。读的时候给**上次读之后的新行**（每个作业一个游标），游标是作业状态的一部分——
   它只对「这个会话读到了哪里」有意义。~~
   **2026-09-18**：被 `.scratch/job-output/spec.md` 翻过来——输出落成**一份文件**（配置家的
   `jobs/<会话>/<句柄>.log`，随进程退出消失），没有尾巴、没有游标、没有丢行计数。

~~10. **读不阻塞，而且没有人通知。** 没有推送通道：作业跑完了**没有任何东西会告诉模型**，所以工具描述里
    必须写明「你得自己来问」。也不做「等 N 毫秒看有没有新输出」那种半阻塞——那是第二种等待语义，
    而这个特征的整个题目就是「谁在等」。~~
    **2026-09-18**：`.scratch/job-tools/spec.md` 决策 5 翻掉「不做半阻塞」这一半——`job_output` 的
    `wait` 挂到终态，超时回 `[running]`。
    **2026-09-18（同日晚些）**：`.scratch/job-endings/spec.md` 把「没有任何东西会告诉模型」那一半也改了
    ——不是翻成推送，而是**下一次开口时摆在面前**（前置步骤里那条 `<job-ended …>` 注入，说一次）。
    **没有推送通道**这一半仍然一个字不动：不唤醒、不新起 run、不发帧。

11. **三个新工具是普通行。** 不挂 park（`docs/architecture/kernel.md` 那句「`bash` 今天就能 `curl`
    任何地址且不带审批，给它挂个 park 是装样子」对后台执行逐字成立）、不新加审计行、不动 AG-UI、
    不动轨迹（`model/start` 的 `:tools` 自己会多出这几个名字）、PreToolUse / PostToolUse 天然看得见
    它们（按工具名匹配）。也不属于任何编辑家族（不碰文件），所以两种编辑模式都服务它们——照
    `glob` / `todo_write` 的规矩，什么都不用做。

12. **后台命令走的是 `bash` 工具用的那个 shell。** `shell/start` 今天是给「**启动一个程序**」用的
    （Windows 上走 `cmd /c`，理由写在 `windows-argv` 的注释里：要保住反斜杠路径），
    而本特征起的是一条**shell 命令**，它的承诺就是 bash——Windows 上必须是 Git Bash。
    所以 argv 的构造要能区分「一条 shell 命令」与「一个要启动的程序」两种形状，
    并且**是一个纯函数**（好让 Windows 那一支在这台 mac 上也断言得了）。

13. **UI 一行不改。** 通用渲染已经读得对：`message-parts.tsx` 的 `subjectOf` 兜底取**第一个字符串参数**，
    对 `bash_background {command}` 就是那条命令，对 `bash_output {job}` 就是那个 id。
    `TOOL_ICONS` 里没有它们，就退到扳手图标——这是该表的既定行为，不是缺口。
    （`docs/architecture/client.md` 已经写着「新增一个工具**不动**这两张表也能用」。）

## 非目标

- **不给后台作业喂 stdin。** `shell/start` 的 `:write-line!` 就在那儿，需要时（一个交互式 REPL）再挂；
  本特征只有起、读、停。
- **不做半阻塞的读**（决策 10），不做「跑完了通知我」。
- **不封顶 `timeout`**（决策 4），不做默认值的按工具配置（`general-harness` 那张愿望单上的
  「按工具配审批策略与超时」是另一件事，本特征只动 `bash` 这一个工具的参数）。
- **不做命令内容的判定、不挂审批、不做重试**——`bash` 的既有立场一个字不改。
- **不跨重启**（决策 8）：作业不进 sqlite、不进 jsonl、不加审计行、不动 AG-UI 与轨迹。
- **不落文件**（决策 9），不做作业列表端点和作业面板（要停在哪个 run 里看它的输出，读的就是那三个工具）。

## 验收主线

一条真会话（或一个直调 `run!` 的测试），四件事看得见：

1. `bash {command: "echo hi; sleep 300", timeout: 1000}` 在**一秒多**就回来，答案是 `hi` 加一行
   「到点被停掉」，而且那个 `sleep` 的进程**不在**了（`kill -0` 说不）。
2. 不给 `timeout` 时，缝上收到的就是 120000（这条用**替身**证明，不在测试里等两分钟——
   见票 01 的验收）。
3. `bash_background` 起一条会分三次打印的命令 → 拿到句柄；两次 `bash_output`：第一次拿到新行，
   第二次只说「没有新东西，还在跑」；跑完之后读到最后一行与退出码 0。
4. `bash_kill` 一条 `sleep 300` 的作业 → 进程不在、作业从注册表消失、再 `bash_output` 指名说它不在了；
   对一条**已经退出**的作业，`bash_kill` 报出它的退出码。

## 跨特征对照

- **`.scratch/general-harness/spec.md` 的需求 11**：本特征就是它的兑现（挂死的工具不再拖死整个 run）。
  旧话不动（`.scratch/` 是历史）。
- **`.scratch/action-fusion/spec.md` 的非目标「不做超时」**：那条说的是 `bash` 的**当时**立场，
  本特征把它翻过来。该文件不改，此处记一笔。
- **`.scratch/minimal-kernel/spec.md` 的「不设超时」**：同上，那是立仓时的决定，不是今天的现状。
- **`docs/architecture/kernel.md` 的「不做超时，也不做跨进程持久化」**：那句讲的是**审批**
  （人一直不响应就永远待决），与命令的执行时限是两件事。收口那票要把这层区分写清，
  免得下次有人拿那一句当本特征的依据。
- **`.scratch/hook-engine` 与 `cap.mcp`**：它们各有一套自己的超时（hook 声明 `:timeout`、
  服务器声明 `:timeout`），都在 `infra.shell/run` 或 `start` 之上。本特征**不改**它们；`run` 只是
  多了一条「到点收整棵树」的性质，那对它们只有好处（今天的 `run` 到点同样只杀壳）。
- **`.scratch/immutable-data` 的纪律**：注册表是**进程级容器按键分家**（`thread-id` 为键）、
  读写都在同一个 `swap!` 里完成、句柄这类可变 Java 对象由注册表持有——那节规矩对票 02 直接适用。
- **`.scratch/composer-status` 与 `.scratch/trajectory`**：记录侧一个字节不动。作业的输出**不是**会话历史，
  读它的是 `bash_output` 的返回值，不是日志。
- **`.scratch/tool-parity`（已落地）**：它的先例是「一个工具一张脸、都在 `cap.tools` 里注册、
  干活的不在这儿」——票 02 的注册表因此落在 `cap.jobs`，不塞进 `cap.tools`。

## 交付顺序

`01 → 02 → 03 → 04`。

**02 挡在 01 后面**，理由说清楚：不是因为它缺 01 的某个能力，而是两者动**同一处**
（`infra.shell` 的收尾与 `cap.tools` 的工具表）与**同一套词**（毫秒、`timeout`，
以及「到点/要停的时候收整棵树」这一件事只写一遍）。本仓的工作树是几个会话共用的，
两张票同时改一处只会换来一次合并冲突。

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 前台的时限：一次调用最多等 120 秒，到点连子孙一起收 | — | `bash` 的 `timeout` 参数（毫秒，默认 120000，描述里插值）；`t-bash` 改走 `run`；`run` 到点收整棵树；`shell/shell` 退休 |
| 02 | 后台执行：起一条命令拿句柄，读它打出来的新行 | 01 | `cap.jobs` 的注册表（按会话、进程内、退出时收掉、有界尾巴、游标）；`bash_background` / `bash_output` 两个工具；工具数、三处硬编码清单、`CONTEXT.md` 的闭清单跟着改 |
| 03 | 停掉一条后台作业 | 02 | `bash_kill`：整棵树收掉，已经退出的作业报退出码并忘掉它，不存在的句柄指名拒绝 |
| 04 | 收口：词、文档与全量验证 | 03 | `CONTEXT.md` 立三个词（时限 / 后台作业 / 句柄）；`docs/architecture*` 的模块地图、状态表加一行、审批那句区分；README 报数；两套全量 |

**一票落地那天就会撒谎的东西，跟着那一票改**：硬编码的工具名清单、工具数（`cap.tools` / `kernel.tools` /
`test_support.clj` / 两页文档里那个「十五个」）、`CONTEXT.md` 那张闭的工具名清单——不留给 04。
04 只做**需要新写一段话**的地方（模块地图的 `cap.jobs` 一行、状态表、审批与时限的区分、报数）。

## 状态

**四张票全部落地（2026-09-17）。** 分支 `bash-lifetime`，从 `main` @ `7ed63b0` 切出，
**直接在这个工作树上做**（没有另开 worktree）。票面已按仓库约定删除，四段的落地记录在下面。

**基线（立票当日实测）：`main` @ `7ed63b0`。**

- 后端：`timeout 1500 clojure -M:test -m harness.test-runner`
  → `Ran 824 tests containing 11135 assertions. 0 failures, 0 errors.`（退出码 0）。
- 前端：`cd ui && npm test` → `Test Files 1 passed (1)` / `Tests 26 passed (26)`（退出码 0），
  `EXPECTED_CASES` 钉在 26；`cd ui && npm run build` 过。

## 落地记录

### 01 + 02 — 时限与后台（2026-09-17，分支 `bash-lifetime`，提交 `d7b6815`）

**两支票落在同一个提交里**，理由写在这里：它们动的是同一处（`infra.shell` 的收尾与 `cap.tools`
的工具表）与同一套词，拆成两个提交只有仪式价值，而中间那个提交会带着一个「说了却没接上」的状态。

- **时限**：`cap.tools` 里一个 `bash-default-timeout-ms`（120000，唯一一处字面量），描述串插值；
  `bash` 的描述同时把三件事说清（单位毫秒、默认值、到点会怎样），并指向 `bash_background`。
  `t-bash` 从 `shell/shell` 改走 `shell/run`，参数经 `positive-int` 校验（0 / -3 / 1.5 / "soon"
  都是模型真会发的东西，各自得到同一条指名拒绝）。
- **收整棵树**：`kill-tree!` 移到 `run` 上方（它现在服务两种 spawn），`run` 到点调它而不是
  `destroyForcibly` 单个壳；docstring 里补了两条：到点收整棵树、stdin 写空后**关掉**。
- **`shell/shell` 退休**：删掉，`edge.http_test` 两个小助手改用 `run`。
- **新 ns `harness.cap.jobs`**：`{thread-id {:jobs {j1 ..}}}`；作业记句柄、有界尾巴（500 行，
  一个 `def`，描述插值）、`total` 与 `cursor`；一条**每作业一个**的排空线程把 `start` 的
  行队列倒进尾巴（队列没人排空就是随进程长大）；`read-output` 一次 `swap-vals!` 取行并推游标；
  `shutdown!` + `ensure-exit-hook!`（`compare-and-set!` 只装一次，`install-hook!` 与
  `reset-exit-hook!` 是给测试的两扇门，与 `shell/reset-resolution!` 同一个立场）。
- **两个工具**：`bash_background`（答 `job j1 started; …`）与 `bash_output`（新行 + 一行状态，
  没有新东西就是 `(no new output)`）；不认识的句柄抛，话里列出本会话活着的句柄。
- **argv 两种形状**：`spawn-argv` 公开成 `[shape command]` + `[shape command on-windows? shell]`；
  `start` 收 `:shape`（默认 `:program`，MCP 那条路一个字节没变），`cap.jobs` 传 `:shell`。
- **跟着改的**：三处硬编码清单（`kernel/tools_test`、`editing_mode_tools_test`）、`CONTEXT.md` 的
  闭清单、两页文档的工具数（15 → 17）。顺手把 `cap.tools` / `kernel.tools` / `test_support` 里那三处
  「十五个」改成语义说法（不再背一个每加一个工具都要改的数）。

**测试**：`harness.cap.jobs-test`（新，注册进 `harness.test-runner`；9 条）：
起/读/状态、`(no new output)` 不等于结束、游标（读过的行不再回来）、有界尾巴与丢行计数、
按会话分家、不认识的句柄指名拒绝、`shutdown!` 连子孙一起收（子进程 pid 由命令自己写，用
`ProcessHandle` 轮询 5 秒）、退出钩子只装一次。
`harness.infra.shell-test` +3：到点收整棵树（本特征的核心判据）、到点前打出来的东西还回来、
两种 argv 形状（Windows 那一支在 mac 上断言）。`harness.kernel.tools-test` +7：默认值经替身
证明、非正整数拒绝、到点真的停（墙钟 < 20 秒）、没到点时行为逐字不变、后台工具的 cwd 与 `bash`
同一处（相对路径找 marker 文件）、调用不等命令、断言参数检查按名字各查各的。

**证据**：`.scratch/bash-lifetime/evidence/`（可重跑脚本 + 输出 + README 写明哪三条被测、
哪四件事**没**做）。

**报数**：`timeout 900 clojure -M:test -m harness.test-runner` →
`Ran 843 tests containing 11206 assertions. 0 failures, 0 errors.`（立票基线 824 / 11135）。

### 03 — 停掉一条后台作业（2026-09-17，分支 `bash-lifetime`，提交 `d103150`）

- `cap.jobs/stop!`：一次 `swap-vals!` **取走没读的输出并让作业离开注册表**（同一个动作，
  所以读者看到的是「有作业且有输出」或者「没有作业」，不会是中间态）；`[stopped]` 只可能出自这里，
  读那边只有 `[running]` / `[exit N]`。
- 工具 `bash_kill`：整棵树收掉（与票 01 同一个 `kill!`），已经退出的作业报 `[exit N]` 并照样忘掉——
  **这是唯一一个能让作业离开注册表的动词**，所以问第二次得到的是同一条指名拒绝。
- 测试：`jobs-test` +3（停一条在跑的：答案 `[stopped]`、子进程消失、再读得到指名拒绝；
  停之前没读的输出一并带回；自己结束的报退出码并被忘掉）、`kernel/tools-test` +2
  （经缝停一条并确认它「不存在了」；不认识的句柄是错误）。
- 工具表 17 → 18，两页文档与 `CONTEXT.md` 的清单跟上。

**报数**：`Ran 848 tests containing 11222 assertions. 0 failures, 0 errors.`

### 04 — 收口：词、文档与全量验证（2026-09-17，分支 `bash-lifetime`，本文件随它一起落地）

- **`CONTEXT.md` 立三个词**（新一节「跑命令」）：**时限**（并明说它不是审批那条「不做超时」）、
  **后台作业**（只在进程里、按会话、不跨重启、不随 run 死）、**句柄**（只在会话里有意义、不回退）。
  另把「工具名的写法」那张闭清单补上三个名字。
- **`docs/architecture/overview.md`** 的状态表加一行「后台作业 = 进程内存」，理由写在行里
  （它是一条**正在跑的命令**，不是一条事实；run 结束**不**收它）。
- **`docs/architecture.md`**：模块地图加 `cap.jobs` 一行（注册表 + 有界尾巴 + 唯一出口），
  `cap.tools` 那行点明「后台命令在 `cap.jobs`」，`infra.shell` 那行补上「到点收整棵树」与
  两种 argv 形状；工具数 16 → 17。**「在办」里本特征那一条删掉**（它落地了）。
- **`docs/architecture/kernel.md`**：模式表补上三个后台工具（它们不属于任何编辑家族），
  并给「**不做超时**」那句加上范围——它讲的只是审批，命令的时限是另一件事（后台作业反过来没有时限）。
- **`docs/architecture/projects.md`**：围栏那节加一条——后台执行同样不挂审批（与 `bash` 是同一个
  逃逸面，单独挂 park 是装样子），cwd 与 `bash` 同一处解析。
- **`README.md`**：新增一节「命令等多久，以及不等人那种跑法」，与「另外四只手」并列；验证那节的
  报数换成实测（848 / 11222，分支与切出提交写清）。
- **没动的**：`docs/architecture/client.md`——UI 一个字没改，那一页已有的话（「新增一个工具不动这两张表」）
  仍然成立，核过一遍没有要改的。

**收口时跑了一次 code-review（两轴各一个子代理），它抓出四处真问题，改在收口这次提交里：**

1. **`[exit N]` 可以在最后几行还在队列里时就报出来**（Spec 轴）。原来状态行问的是**进程**，
   而尾巴由一条每 1s 轮询的排空线程填，于是「`(no new output)` + `[exit 0]`」会被读成「它没有更多输出了」。
   改法：作业多一个 `:stream-ended?`，由排空线程**在看见 eof 时**记下（eof 只在它读完全部输出之后才到），
   状态行据此判断——流没结束就答 `[running]`，所以**说 exit 的那个答案一定带着最后那几行**。
   两个回归用例：`the-exit-line-arrives-with-the-output-that-went-with-it`，以及
   `a-command-that-let-go-of-its-stdout-is-still-running`（`exec 1>&-` 之后进程还在，不许编一个退出码）。
2. **工具数是十八，我写成了十七**（Standards 轴，硬错）。十五 + 三个新工具 = 十八；两页文档、
   `cap.tools` 的 docstring、落地记录里那两处都改了。
3. **一个被停掉的作业会以 nil 的形式回来**（证据跑第二遍时撞上的真 bug，也是上面第 1 条改完之后暴露的）：
   `update-in` 会把函数返回的 nil **assoc 进去**，所以「任务不在就返回 nil」的守卫会**重建**这个条目；
   而停一条作业与它进程的 stdout 结束之间恰好有那一刻（排空线程还攥着一个 `:next-line`）。
   症状是所有后来的指名拒绝里都列着一条不存在的作业。改法：`update-job!` 先判断路径在不在，
   不在就**原样返回 registry**。回归用例 `a-job-that-was-taken-out-does-not-come-back`（先红后绿）。
4. **三份重复的测试助手**（`alive?` / `gone-within?` / 「读到满足条件为止」的循环散在三个文件里）。
   收进 `harness.test-support`，三个文件都改成调用它。

另外两条**看过后不改**：工具参数叫 `job`（模型眼里「哪条作业」）而 ns 里叫 `job-id`（那个字符串是 id），
两个名字指向两个角色；`:minimum 1` 不是 spec 里没写的东西，它就是 `offset` / `limit` 同一个校验
（`positive-input`），没它 `timeout: 0` 会变成「立刻停」。

**报数**（收口这次实测）：

- `timeout 900 clojure -M:test -m harness.test-runner` → `Ran 852 tests containing 11233 assertions.
  0 failures, 0 errors.`（退出码 0；立票基线 824 / 11135）。
- `cd ui && npm test` → `Test Files 1 passed (1)` / `Tests 26 passed (26)`；
  `cd ui && npm run build` 过（本特征没动 `ui/`，`EXPECTED_CASES` 仍是 26）。
  **这个工作树是几个会话共用的**：收口那天另一个会话的 UI 改动（`turn-steps.tsx` / `lib/turns.ts` /
  一个新套件，未提交）正在树里，那一刻再跑 `npm test` 会看到 29 条。上面这个 26 是**本特征自己的**
  UI 状态（本特征一个字节没改 `ui/`），收口提交**只 stage 本特征自己的路径**，他们的改动一个都没带走。
- 不留进程：整套跑完 `pgrep -fl "sleep 30"` 空。机器上那两条 `harness.test-runner` 的 JVM 是
  **2026-09-16 17:06 与 18:05 起、PPID 1 的孤儿**（就是本特征存在的理由之一），不是这次跑留下的。

