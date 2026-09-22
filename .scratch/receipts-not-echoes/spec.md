# spec: 回执，不是回声 —— `write` / `todo_write` 的答案不带内容，起动词还给 `job`

**一句话**：三件事，都是「模型手里的东西收窄」——

1. **答案不回放模型自己刚写进去的东西。** `write` 与 `todo_write` 的输入就在**这次调用的参数里**
   （历史里躺着），答案只报事实：写了多少、清单存了几项。`write` 那一半是把**已立票的
   `.scratch/write-no-content/`** 收编进来。
2. **后台的「起」回到自己的名字。** `job` 回来，`bash` 只剩前台（推翻 `.scratch/bash-background`
   决策 1，工具数 **17 → 18**）。
3. **作业结束注入上下文的那条通知只报状态，并指名读法。**
   `<job-ended id="j1">[exit 0]</job-ended>` 加一句「用 `job_output` 读它」；**路径从通知里退场**
   （`.scratch/bash-background` 决策 3 的反面）。

2026-09-22 立（牛总）。分支 `receipts-not-echoes`，工作树 `.worktrees/receipts-not-echoes`，
从 `main` @ `e8556fa` 切出。

## 问题

1. **答案回放输入，是本仓唯一一处「同一份 token 付两次」的地方。** `write` 交回头 20 行带锚点的行
   （`:auto-read`，默认 `true`）——那是一笔**被读成「整份都能编辑」的有界授予**，而且让 `write` 的
   锁区里多出第二把锁（`immutable-data/03` 那半张票的现场）。`todo_write` 把整份清单连着序号念回来，
   而那份清单就是这次调用的参数。**写不是读**（`.scratch/write-no-content/spec.md` 有全文与 omp 的原文）。
2. **`bash` 的两个模式挤在一张脸里。** 一条描述同时讲前台与后台（`cap/tools.clj` 的 `bash` 描述里
   FOREGROUND / BACKGROUND 两段），模型要在一个布尔里选「这次等不等」。而**模型读错轴这件事本仓付过
   学费**：`bash-record` 的现场就是模型把后台读成「慢的那个」、再拿 `sleep` 拼一个 `join`。
   拆开之后，起后台是一个动词（`job`），读与停是两个（`job_output` / `job_kill`），三张脸各自只说自己的事。
3. **通知里没有读法，而它恰恰是模型唯一没主动要过的东西。** 今天的通知是「三样事实」，一个字不说怎么读
   （`bash-background` 决策 3 有意删掉的）。模型收到一个 id 与一条路径，得自己想起来 `job_output` 这个动词
   —— 而这条告知的全部意义就是**它没在等**。

## 决策

1. **`write` 的答案只剩事实加一句指路。** 逐条照办 `.scratch/write-no-content/` 的决策 1–5：
   锚点模式下答案是 `wrote N chars to PATH` + 「锚点已释放，去 `read`」；`:auto-read` 这个键、`auto-read-lines`、
   `auto-read-note` 与 `perform!` 那个只为它存在的 `config` 参数一并退场；描述不再承诺交回开头；
   `write` 之后只拿 path 锁；UI 一行不改；`str-replace` 模式一个字不改。
2. **`todo_write` 的答案只报状态。** 存了几项、各自什么状态（`5 items stored for this session
   (1 in progress, 2 completed)`），**不念清单**；空清单仍答一句「现在是空的」——那不是回声，是事实。
   清单的另外两个读者一个字节不动：库里的行（`todos/items-for`）与 UI 工具卡（读**调用参数**，不读答案）。
3. **`job` 回来当起动词。** 立刻返回，答案是 **id + 记录路径**；没有时限、没有 `stdin`
   （模型仍传就**指名拒绝**，话与合并时那句同源）、重定向注跟着它。
   `bash` 只剩前台：`timeout` / `stdin` / `workdir` / `shell` / 答案上界，行为逐字不变。
   工具数 **17 → 18**，三处硬编码清单与文档名单/数目跟着改。
4. **通知 = id + 状态行 + 命令 + 一句读法，不带路径。**
   `<job-ended id="j1">[exit 0]</job-ended>`、`<command>make</command>`，再加一行指 `job_output`。
   路径在 `job` 起的答案里，记录内容 `job_output` 给，所以通知不需要它。命令是**落地当天补上的**
   （主人的话：「通过上下注入的时候还要带上原始的命令」）：`j1` 光靠 id 认不出是哪个作业，通知又可能
   在好几轮之后才到，那时调用那条命令的现场早不在模型手里了；它用**自己的元素**装而不是当属性，
   因为命令是带引号的任意文本，属性会把转义这件事强加上来。**不裁**：认不出来的命令等于没提醒。
   仍然**说一次、不推送、不带正文**（记录五千行与空记录，通知一样大——现在它与命令一样大）。跟着改的
   谎话：`job_output` 描述里「every answer -- and the notice … — names its path」那句、
   `docs/architecture.md` 的 `cap.jobs` 一行、`kernel.md`、`README.md` 与 `CONTEXT.md` 的通知形状；
   三处「通知里没有命令说了什么」的断言也换了钉子（命令原文进了通知，钉子得是命令**打印**出来的词）。
5. **描述负责用法，答案负责事实 —— 这条规矩不动**（`bash-background` 决策 4）：`job_output` / `job_kill`
   的答案仍是事实，没有「read it with …」。**通知是唯一的例外**，因为它是**没人要过**的那一次告知。

## 非目标

- **不动** `read` / `replace` / `insert` / `undo_last_replace` / `grep` / `glob` / `eval` / `skill` /
  `session-configure` / `web_*`。
- **不改记录那一侧的任何规矩**：一份文件、住配置家、末行 `[exit N]` / `[stopped]`、随进程退出只关不删、
  `job_kill` 不删、不进 jsonl / 库 / 轨迹。
- **不改 `job_output` 的语义**（`offset` / `limit` / `wait` / `timeout` 一个字不动）与 **`job_kill` 的幂等**。
- **不给后台喂 `stdin`**、不做推送、不做作业面板、不改 `ui/src`（新动词在 `TOOL_ICONS` 里没有就退到扳手）。
- **不给 `write` 加「可选地读回来」的第二个开关**：那正是被撤掉的东西，换个名字回来不算复议。
- **不碰别的会话正在改的文件**（工作树里那几处未提交改动属于别的特征）。

## 验收主线

一条真会话（或直调 `tools/run!`），六件事看得见：

1. 锚点模式一次 `write` → 答案里**没有 `│`、没有内容行**；紧接着用**旧锚点** `replace` → 指名「先 read」。
2. 一次 `todo_write` → 答案只有条数与状态，**没有清单正文**；同一份清单在库里与 UI 工具卡里都还在。
3. `bash` 的参数表里**没有** `run_in_background`；`job` 在表里；总数 **18**（两处清单用例 + `CONTEXT.md`
   闭清单是证据）。
4. `bash {command: "echo hi"}` → 与今天**逐字相同**（`hi`）。
5. `job {command: "sleep 1; echo hi"}` → 立刻返回 id 与记录路径；`job_output {job, wait: true}` 拿得到
   `[exit 0]` 与那行。
6. 通知：`<job-ended id="j1">[exit 0]</job-ended>` + `<command>…</command>` 加一句指 `job_output`；
   **命令在里面、路径与正文不在**，五千行的记录与空记录的通知一样大（与命令一样大）；说一次、不推送照旧。

## 跨特征对照

- **`.scratch/write-no-content/`（已立票未开工）**：本特征**收编**它的那一票（票 01），它的 `spec.md`
  留作理由（omp 的原文引用、三处代价、撤销清单都在那儿）。落地当天删它的票面（仓库规矩：完成的票不留）。
- **`.scratch/bash-background/`（已落地）**：**决策 1**（一个动词两个模式）被本特征翻回来；
  **决策 3**（通知只有三样事实）被本特征决策 4 改成「三样事实 + 一句读法、去掉路径」；
  **决策 4**（答案只说事实）不动。旧话不逐字改：三处划删除线 + 一行日期注。
- **`.scratch/job-tools/`（已落地）**：`job` 这个名字、它的参数态度（无时限、不喂 `stdin`）与
  「起不是 `job_output` / `job_kill` 其中之一」的分工一个字不改，只是重新成为独立动词。
- **`.scratch/job-output/` / `.scratch/job-endings/` / `.scratch/bash-record-persistence/`**：
  记录那一侧一个字节不动；`job-endings` 的「谁算告知、说一次、不推送」本特征照旧遵守，只是通知多一句话。
- **`.scratch/immutable-data/`**：`03-session-lock-outer` 的 `write.clj` 现场（auto-read → `serve/read!`
  的第二把锁）随票 01 消失；该目录此刻正被别的会话改动，**本特征不碰它的文件**，只在落地记录里记一句。
- **`docs/architecture/kernel.md` 的通知那一段**、**`docs/architecture.md` 的 `cap.jobs` 一行**、
  **`CONTEXT.md` 的注入词条**：通知形状改了，跟着改（票 04 与票 05）。

## 交付顺序

`01 / 02 / 03 / 04` 四张各自独立可验，`05` 收在最后。

**01 与 02 都不挡别人**（一个是 `cap.hashline.write`，一个是 `cap.todos`，两处不相干）。
**03 挡着 05**：工具数 17 → 18 与文档名单是 05 要核的东西。
**04 不挡别人**：它只动 `cap.jobs/notice` 与描述里关于通知的那一句。

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | `write` 的答案不带内容：`auto-read` 退场 | — | 锚点模式 `write` 的答案只剩释放通知；`:auto-read` 键、`auto-read-lines`、`auto-read-note`、`perform!` 的 `config` 参数退场；描述换句；五处文档例子换键；四条用例退场、两条改写 |
| 02 | `todo_write` 的答案只报状态 | — | `cap.todos/render` 换成状态渲染（条数 + 各自状态；空清单一句「现在是空的」）；不再念清单；库与 UI 不动 |
| 03 | 后台的「起」回到自己的名字：`job` 回来，`bash` 只剩前台 | — | `job` 重新注册（立刻返回、答 id + 记录路径、无时限、`stdin` 指名拒绝、重定向注）；`bash` 去掉 `run_in_background` 与后台那半段描述；工具数 17 → 18、三处硬编码清单与文档数目/名单跟着改；`.scratch/bash-background` 决策 1 划删除线 + 日期注 |
| 04 | 作业结束的通知：状态一行 + 指名 `job_output` | — | `cap.jobs/notice` 只剩 id + 状态行 + 一句读法（路径退场）；`job_output` 描述里「通知会说出路径」那句改掉；`docs/architecture.md` / `kernel.md` / `CONTEXT.md` 的通知形状跟着改；`.scratch/bash-background` 决策 3 划删除线 + 日期注 |
| 05 | 收口：词、文档、报数 | 01,02,03,04 | `CONTEXT.md` 的词与闭清单（+ `job`、+ 「回执」）；`docs/architecture*.md` 的工具数/名单/描述、`system-prompt.md`；README 那一节；两套全量 + 一次真会话走查；本特征自己的落地记录 + 删 `write-no-content` 的票面 |

## 基线

**立票当天（2026-09-22，`main` @ `e8556fa`，这个工作树里实测）：**

- 后端 `timeout 1800 clojure -M:test -m harness.test-runner` →
  `Ran 1090 tests containing 12833 assertions. 0 failures, 0 errors.`（退出码 0）。
  控制台里另有一行 `ISOLATION NOTE`（开发者真家的 `harness.db` 在跑动期间被**别的进程**写了，
  本进程从没开过它）——按 `docs/rules/testing.md`，那不是失败。
- 前端：立票当天未跑（本特征不碰 `ui/src`，收口那天与票 05 一起跑）。

## 状态

**2026-09-22 立票并开工，当日落地。** 分支 `receipts-not-echoes`，工作树 `.worktrees/receipts-not-echoes`，
从 `main` @ `e8556fa` 切出。票面按仓库约定已删除（`docs/agents/issue-tracker.md`：完成的票不留），
记录留在这里与 git 历史里。

## 落地记录

### 五张票各自落地了什么

1. **`write` 的答案不带内容。** `cap.hashline.write/serve` 的 `:auto-read` 段、`auto-read-lines`、
   `auto-read-note`、`perform!` 的 `config` 参数一起退场：答案是 `wrote N chars to PATH` 加一句
   「锚点已释放，去 `read`」。**`write` 现在只拿 path 锁**（从前经 auto-read 再进 `read`，是
   `immutable-data/03` 那张票现场的另一半）。遗迹清干净：`cap.editing` 的默认值与词汇、
   `harness.edn.example` 的 `:auto-read` 块、五处文档例子（`CONTEXT.md` / `README.md` /
   `docs/architecture/kernel.md` / `skills-and-instructions.md` / `harness.edn.example`），
   以及 `.scratch/hashline-edit/spec.md` 三处旧话划线 + 日期注。
2. **`todo_write` 的答案只报状态。** `cap.todos/render` 不再念清单：`2 items stored for this
   session (1 in progress, 1 completed).`（空清单仍是那句「现在是空的」）。清单的两个读者没动：
   库里的行与 UI 工具卡（读**调用参数**）。描述里补了一句「答案不念回来」，免得模型以为要核对。
3. **`job` 回来当起动词。** `t-job` 从 `t-bash` 里分出来（`work-dir`、`named-shell`、`cap.jobs`
   三处共享没变）；`bash` 只剩前台，参数表里没有 `run_in_background` 了。工具数 **17 → 18**：
   `cap.tools` 的 ns docstring、`docs/architecture.md`、`layers.md`、`system-prompt.md`、
   `CONTEXT.md` 闭清单、两处测试清单跟着改。`.scratch/bash-background/spec.md` 的
   一句话/决策 1/验收 1·3·4·6 划删除线 + 日期注。
4. **通知 = id + 状态行 + 一句读法。** `cap.jobs/notice`：
   `<job-ended id="j1">[exit 0]</job-ended>` 加 `Read what it said with job_output {"job": "j1"}.`
   ——**路径退场**（`job` 的答案已经交过一次），**「去读」那半句回来**（作业存在的理由就是模型走开了）。
   `job_output` 描述里「通知会说出路径」那句改掉；`docs/architecture.md` / `kernel.md` / `README.md` /
   `CONTEXT.md` 通知形状跟着改；`.scratch/bash-background/spec.md` 决策 3/验收 5 与
   `.scratch/job-endings/spec.md` 决策 3/验收 4 各划删除线 + 日期注。
   **落地当天补上命令**（主人的第二句话）：`notice` 多一行 `<command>…</command>`，`jobs/start!` 把命令
   留在注册表条目上（从前跑完就丢），`jobs_test` / `project_test` / `http_test` / `trajectory_test` /
   `ui/test/suites/injections.ts` 的字节跟着改，四份文档与证据重跑。
5. **收口。** `CONTEXT.md` 新增词条**回执（receipt）**（连同「回执可以带一句指路」那条边界），
   `job` 词条与工具名闭清单改到今天的形状；README 的 `todo_write` / `bash` / `job` /
   通知四段改到今天；`.scratch/write-no-content/issues/01-*.md` 删除（它的 `spec.md` 留作理由）。

### 与票面不一致的三处（都是「同一个态度多做一步」）

- **`job` 也指名拒绝 `timeout`**（票面只说「没有时限」）。理由与 `stdin` 同源：一个限制不了任何
  东西的数字读起来像承诺，而静默丢掉调用方明确送来的东西是本仓到处都拒绝的那种沉默。拒绝话术里
  顺带指名 `job_output` 的 `wait`。
- **`bash` 指名拒绝残留的 `run_in_background`**（票面只说「参数表里没有它」）。理由同上：拆开之后
  最可能出现的正是这个从旧形状带过来的键，静默按前台跑就成了一次没人说出口的分歧。
- **`.scratch/job-endings/evidence/go.json` 改回 `job`**（外加 README 一段日期注）。那条证据在
  `bash-background` 落地时被改成 `bash {run_in_background: true}`，现在那个形状会被指名拒绝——
  「证据要能重跑」这条被同一个坑反向踩了一次，改的只是能重跑的那一步，那天看见的东西一字未动。

### 落地当天追加的一句：通知带上命令

主人的第二句话：「通过上下注入的时候还要带上原始的命令」。于是 `notice` 多一行
`<command>…</command>`，`jobs/start!` 把命令留在注册表条目上（从前跑完就丢，`notice` 也就无从说起）。
用**自己的元素**而不是标签属性：命令是带引号的任意文本，属性会把转义强加上来（`jobs_test` 里那句
「THE PATH GOES IN UNESCAPED」的同一笔账）。**不裁**——认不出来的命令等于没提醒。

它不推翻回执那条规矩，`CONTEXT.md` 的**回执**词条里新写了一句边界：**通知不是回执**（注入不是答案）。
跟随改动：`jobs_test`（逐字 + 三行上界）/ `project_test` / `http_test` / `trajectory_test` /
`ui/test/suites/injections.ts`（112 字节）；三处「通知里没有命令说了什么」的钉子换成命令**打印**出来的
词（`$((6*7))` → `JOB-SAYS-42`）——命令原文进来之后，写在命令里的字证明不了任何事了；四份文档
（`README.md` / `docs/architecture.md` / `docs/architecture/kernel.md` / `CONTEXT.md`）与两处旧
spec 的日期注跟上；证据重跑一遍（88 B → 133 B）。

### 报数

- 后端全量（落地当天，工作树里）：`Ran 1088 tests containing 12841 assertions. 0 failures,
  0 errors.`（退出码 0）。
  对基线 1090 / 12833：**用例 −2**（`auto-read` 四条退场、两条新的进场），**断言 +8**。
- 前端：`npm test` → **96 passed**；`npm run typecheck` 过；`npm run build` 过。
- 真会话走查：`.scratch/receipts-not-echoes/evidence/`（脚本 `receipts.json` + README）。看见的：
  三张工具卡的答案都是回执（`job` → id + 路径；`todo_write` → `2 items stored …`，清单只在参数格；
  `write` → `wrote 18 chars …` + 去 `read`）；注入卡折叠行是 `注入的上下文 · job-ended · 133 B`，
  点开逐字三行（id + `[exit 0]`、命令、一句读法），**命令在里面、路径与正文不在**；轨迹那一栏第 2 轮是
  `用户 → 上下文 <job-ended id="j1">[exit 0]</job-ended> → 助手`。同一份字节在那一场的 jsonl 里
  是一条 `source: "job"` 的 `message` 行。

### 留给下一次的两句

- **回执这条规矩现在有名字了**（`CONTEXT.md`）：下一个「写类工具的答案要不要回放」的问题，
  读那一条词条就够，不必重新推一遍。
- **轴是动词还是参数**：`bash` / `job` 这一对是「拆成两个动词」的先例；如果哪天又想把它们并回去，
  `bash-record` 与本次两处现场都在 `.scratch/` 里躺着。