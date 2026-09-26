# spec: 作业的回执不再报记录路径 —— 它说去哪读

**一句话**：`job` 的答案今天是 `job j1 started; its record is <配置家>/jobs/<会话>/j1-<进程戳>.log`，
`job_kill` 的答案也照着报一遍同一个路径。那是**本仓自己的目录结构**，对模型既不是它能用的东西
（`job_output` 收的是 id，不是路径），也把配置家的样子摊在每一次调用里。本特征把两份回执里的路径换成
**一句读法**——与作业结束那条通知逐字同形：`read it with `job_output {"job": "j1"}`.`。

2026-09-23 立。**主人当天的一句话**：「job 工具输出错误，不应该输出日志位置；提示用户用 job_output。」

## 问题

1. **路径是回声，不是事实。** `job` 的答案要说的只有两件事：**哪条作业**（`j1`）与**接下来怎么办**
   （去 `job_output` 读）。路径是第三件，而且是**唯一一件模型用不上的**：`job_output` 的参数是 id，
   `job_kill` 的参数也是 id。模型拿这条路径做不了任何事，它只是被摆在那儿。
2. **它是本仓的内部形状。** `<配置家>/jobs/<会话>/<句柄>-<进程戳>.log` 里没有一个字段是模型的词汇：
   配置家在哪、会话怎么拼、进程戳是什么。`.scratch/receipts-not-echoes` 已经把这条从**通知**里拿掉了
   （那里的理由写的是「`job` 的答案已经交过一次」）——回执这一半当时没动，于是同一条路径在一场会话里
   说两遍：起作业时说一次，停作业时再说一次。
3. **一句读法比一条路径有用，而且已经有现成的样子。** 通知那三行里的第三行就是
   `Read what it said with job_output {"job": "j1"}.`——同义词、同形状、模型已经见过。
   回执与通知在这里该说同一句话，因为模型接下来要做的是同一件事。

## 决策

1. **两份回执都换：路径退场，读法进场。**
   - `job`：`job j1 started; read it with `job_output {"job": "j1"}`.`（后面照旧跟重定向那条注）。
   - `job_kill`：哪条作业、怎么结束的照旧（`stopped` / `was already over ([exit 3])`），路径换成同一句读法。
   拼法只在一处：`cap.tools` 里一个 `read-with` 只写一遍，两个 body 都用它。与通知那句**同形**是故意的
   （通知的第三行是 `Read what it said with job_output {"job": "j1"}.`）：两处说的是同一个下一步，
   模型在两处看到的是同一件事。
2. **路径仍由 `cap.jobs` 拼、仍从 `start!` / `stop!` 的答案里出来。** 拿掉的是**工具面的回声**，不是模块
   的那个字段：注册表里那份 `:path` 是 `output` 读记录时的把手（`record-lines` / `ending-of` 都收它），
   删了就得把同一个字符串在别处再拼一遍——正是 `record-path` 的 docstring 早就拒过的那件事。
3. **记录这份文件还在、还活过写它的进程；退场的是「模型按路径去读」这条路。** 一份文件从头到尾只被
   追加、落在围栏的自由路径上，这两条没变（`bash` / `read` / `grep` 读得了它这件事是**文件的性质**）；
   变的是**没人再把路径交给模型**——`job_output` 是模型那一侧的读者，`offset` 让它从头翻到尾。
   人（以及下一场会话里知道去哪找的人）照旧读得到那份文件。
   （前台 `bash` 溢出时那条路径留着：那份 `cN` 记录没有别的读法——后台那一族三个动词都不读它。）
4. **三处描述跟着改。** `job` 的描述（答案是 id 与「去哪读」，不是路径）、`job_kill` 的描述（答案说怎么
   结束的，加一句读法）、`job_output` 的描述（「`job` 的答案给出路径」那句删掉——它已经不是说谎，
   是句空话）。`cap.jobs` 里几处把「答案里那条路径」当现状写着的 docstring 与 `unknown-job` 的拒绝
   话术一起核过。
5. **通知一个字不改。** 它早就是 id + 结论行 + 命令 + 一句读法（`.scratch/receipts-not-echoes` 决策 4），
   本特征让**回执跟上通知**，不是反过来。

## 复议（主人第二轮，同日）

主人的第二句话：「job_output 或者 bash 在溢出的时候 会展示一个日志文件地址」——这一条是要**保留/补上**，
不是拿掉。定下来的规矩是：**回执不报路径，读的那一处报**。

- **`job_output` 的答案带上记录的路径**，条件是**窗口不是整份记录**（`from > 1` 或 `to < total`）——那正是
  读的人需要那份文件的时刻：`read` / `grep` / `bash` 直接读它，而不是一页页 `offset` 翻。窗口就是整份记录时
  没有「剩下的」可指，答案照旧短。拼法仍只在一处（`cap.jobs/output` 的 `:path`，由 `record-path` 拼），
  `t-job-output` 只把它写进结尾那一行：
  `[60000 lines in all; this answer shows lines 58668-60000; the whole record is <path>]`。
- **`bash` 溢出那条截断行一个字不改**（`[truncated: omitted N bytes of stdout; the whole output is <path>]`）：
  它本来就是「整份在哪」的那一句。
- 三处描述与 `cap.jobs` 的 docstring 跟着回到这个形状：**回执说读法，读的那一处说文件在哪**。

## 验收

- [x] `job` / `job_kill` 的两份回执里没有配置家路径，各含一句
      ``read it with `job_output {"job": "j«id»"}`.``（带引号的 JSON 形，与通知那句同形）
- [x] `job_output` 的描述里不再出现「`job` 的答案给出路径」
- [x] `cap.jobs` 里 `:path` 的用途（`output` / `stop!` 的把手）写进了 docstring，且 `start!` / `stop!`
      的返回形状没变
- [x] `tools_test` 里那几条靠「答案取路径、再 `slurp` 记录」的用例改成按 id 找文件
      （`record-file` 助手：`<配置家>/jobs/<会话>/<id>-*.log`），或直接走 `job_output`；那条「记录是一份
      文件、`bash` / `read` / `grep` 读得了它」的用例留了下来（改名，理由从「答案给的路径」改成
      「记录本身落在围栏的自由路径上」）
- [x] 后端全量全绿（条数见下）
- [x] `job_output` 在**窗口不是整份记录**时说出记录的路径（`a-window-that-is-not-the-whole-record-names-the-file`），
      窗口就是整份记录时不说；`bash` 溢出那条截断行的路径一个字没动
- [x] `docs/` / `README.md` / `CONTEXT.md` 里「答案是 id + 记录路径」的说法一处不剩

## 报数

- **后端全量（落地当天，工作树里）**：`Ran 1129 tests containing 13142 assertions. 0 failures, 0 errors.`
  （退出码 0）。
- **定向**（改完先跑的一轮：`harness.kernel.tools-test` / `harness.cap.jobs-test` /
  `harness.edge.trajectory-test`）：`Ran 107 tests containing 476 assertions. 0 failures, 0 errors.`
- **前端与真浏览器走查都没跑**：本特征**一个字节没碰 `ui/`**——回执是工具结果，工具卡照旧显示它，
  `TOOL_ICONS` / `subjectOf` 都不受影响；`AGENTS.md` 那条「动过 `ui/src` 就走一次脚本走查」不适用。
- **新会话才看得见**：改的是 harness 自己进程里的工具面，**已经在跑的会话还是旧字节**（这一条就是在那样
  一场会话里写的）——重启之后 `job` 的答案才是新形状。


**2026-09-24（`.scratch/right-pane-tasks` 收口时核过）：** 「回执不报路径，**读的那一处**报」这条规矩
一个字没改，只是**读的那一处现在是两处**：`job_output`（模型那一侧）与 `cap.jobs/listing`（人那一侧的
任务视图——行上的 `:path` 进那一行的 `title`，这一栏就是「读的那一个」）。两份回执仍不报路径。
