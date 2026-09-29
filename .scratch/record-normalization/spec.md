# spec: 未重整化的会话——判据、强制 fork、以及重整化本身

**Status: ready-for-agent**

主人 2026-09-28 的原话：

> 以后所有的消息都要被那个 start/end 这种信封包裹。如果不包裹的话，要重新调用 fork 进行重整化才能继续。

## 背景（都是探针实测出来的）

- 记录里 `message` 行的写法今天**并不齐**：`~/.clj-harness/logs/*.jsonl` 还是**旧格式**（`kind`、无 `source`、
  无 `producer`）；`~/.clj-harness/projects/<project>/<uuid>.jsonl` 是新格式（`type`/`source`）。
- 更硬的一处：`.scratch/record-stream` 票 05 的探针证明，一次 **resume 里被重放的工具答复**
  **只落了一行 `event`（线上帧），没有任何 `message role="tool"` 行**——那份记录是不可续的。
- 于是需要一条**判据**（什么叫「重整化过」）和一条**纪律**（不满足就不许继续，先 fork 重整化）。

## 判据（「已重整化」的定义，提请主人确认）

一条记录算**已重整化**，当且仅当：

1. 每一行都是**新格式**（`type` + `payload` 信封，不是旧契约的 `kind`）；
2. 每一个**消息**都在它那一组的 `start`/`end` 帧**包裹之内**（没有裸露的消息行）；
3. 该有的行都在（例：一处工具调用有它自己的 `message` 行，而不是只有帧）。

第 3 条正是票 05 那个根因的形式化——**先修那条路的写手**，再谈把老记录判成未重整化。

**2026-09-28 实测（票 01 落地时）**：第 2 条按**成对**读——信封成对（每个 START 有它的 END，
反之亦然）+ 工具调用与答复成对。按**位置**读的那一版（`message` 夹在 START/END **之间**）被真记录
排除了：写手是「帧先落、它描述的那条 message 行紧跟其后」，一条工具答复的正文**按结构不可能**
出现在 `TOOL_CALL_END` 之前（结果得先算出来）。读数与理由：`evidence/real-records.md`。

## 决策

- **未重整化的会话是只读的**：能看（`sofar`/`trajectory`/`stats`/`page` 照旧），
  **不能续**（续跑、resume、compact 一律拒绝并**指向 fork**）；
- **fork 是重整化的门**：它本来就「逐字复制截点之前的行、给被截断的 run 补终局」，
  所以**在同一趟里把它升级为重整化**（补齐信封、补齐缺失的消息行，而不是只补终局）；
- **前端必须挡住**：打开未重整化的会话时，输入框禁用 + 明的提示 + 一个「重整化（fork）」按钮。

## 票

| # | 票 | Blocked by |
|---|---|---|
| 01 | 判定与信号：`normalized?` 与它上不上下行的字段 | 无 |
| 02 | 后端拒绝续跑（并指向 fork） | 01 |
| 03 | fork 变成重整化（补信封、补缺失的消息行） | 01（写手那条修好之后才有「补什么」） |
| 04 | 前端校验：未重整化 → 禁用输入 + 强制走 fork | 01、02 |

## 落地：票 01（2026-09-28）

**判据对着真记录定下来的**（`evidence/real-records.md`，40 条真记录）：

- **两种读法，读数排除了「位置读法」**：它在**每一条**真记录上发声（5–385 行，头几条是
  `system-prompt`/`client`/`opening` 这些出生行）。落地的判据是**成对读法**：
  (1) 每行都是新格式；(2) 信封成对（每个 START 有它的 END，反之亦然）；
  (3) 每次工具调用有一行 `message` 答复它，每条工具答复也有它自己的调用。
- **读数**：40 条里过 29 条；11 条未重整化，理由只有两类——5 条旧格式（读侧在判据之前就按
  名字拒绝 `:old-contract`）、6 条「N 次工具调用没有 message 行答复」（被停/被切/正在跑的
  调用，答复只剩一行帧——票 05 的现场就是它）。**信封不成对的一条都没有**。

**判据与信号**：

- `src/harness/edge/normalized.clj`：`step` / `finish` / `fold` / `normalized?`，一份 `step` 与
  `finish` 两条路共用（`fold` 是挂进 `harness.edge.replay` 那一趟读的形状，`:init` 是 thunk）；
- **读侧**（`harness.edge.http/sofar-get`）：`:normalized` 与 `:normalizationReasons`，判据与消息
  **同走一趟读**（`replay/fold-sofar` 喂 `normalized/fold`），所以两件事不会来自文件的两个时刻；
- **正在本进程写的那条不给判定**（记录天然是半截的：一次还没答复的调用不是损坏），
  正在跑的那条读侧本来就答 `:unfinished`；能不能续由起点那道门（票 02）问记录。

**判据数字**（01 与 02 一起跑的那一轮，2026-09-28）：`normalized-test` + `replay-test` +
`trajectory-test` + `loop-test` + `http-test` = **217 / 1730 / 0**（http 一套 177.9s，
`normalized-test` 先落时是 6/16/0，票 02 加了判例之后 7/19/0）。

**判例里的两个例外，都是真形状逼出来的**（不是我放宽判据）：

- **停车的调用不算缺行**：`RUN_FINISHED` 的 `outcome.interrupts` 点了名的调用，是等人裁，不是没人答
  （`normalized.clj/parked-calls`；与 `replay/open-runs` 同一个例外）。没有这条，`a-parked-run-asks-
  over-http-and-resumes` 与 `an-answer-lands-behind-its-call…` 两条既有用例会被新门拒掉。
- **被停/被切的调用要把那一行写下来**：`RUN_ERROR` 之后只剩帧的调用是真的缺行（走查读数里 6 条就是它）。
  于是**写手那一侧一起补上**：内核按停止键时（`loop.clj` 的停止分支）与修缮一条折断的记录时
  （`replay/closing-frames` 的 `:messages` → `close-off-open-run!` / `fork-session!` 落行）都写下
  那一行，两处共用 `harness.kernel.frames/tool-message`（**一处拼法**，票 03 也用它）。
  所以「按了停的会话还能继续」（`a-stop-does-not-wait-for-a-vendor…` 的「a stop is not a lock」）一个字没变。

## 落地：票 02（2026-09-28）

**未重整化的记录不许写。**

- `handle-run` 的第 4b 条决定：修缮（close-off）之后**只读一次**记录（`record-shape`），一次答两个问题
  ——`:violations`（run 的信封）与 `:normalized`（判据）；两者都不合格就按各自的名字拒绝，拒绝语
  点名 fork 与它的地址（`POST /api/threads/<stem>/fork`）。**修缮在前**，因为修缮本身就是重整化的
  一步（它现在连那一行一起写下），所以「被切/被停的会话还能继续」这条既有行为一个字没变。
- `compact-post` 拿**它已经读的那份记录**答同一个判定，不合格就拒（同一条句子）。
- `rebuild` 不在此列：它是**修缮并交回**那道门（`sofar` 的 400 正指着它），把读回来的路堵死换不来
  安全——这条纪律管的是**会写这份记录**的路。
- **读的门一个字节没动**：`sofar` 照样答（记录路径带上 `:normalized false` 与理由），
  `page`/`stats`/`trajectory`/`frames` 照旧。

**判据数字**：与票 01 同一轮——**217 / 1730 / 0**。新用例「一份未重整化的记录不能写」钉住三件事：
run / resume / compact **都拒**；只读的门不受影响；被拒的 run **一个字节**没往记录里写。

## 落地：票 03（2026-09-28）

**fork 是那把钥匙：它复制一份、把它修成合格的，原会话一个字节不动。**

- `replay/missing-tool-rows`：一份记录里**只有 `TOOL_CALL_RESULT` 帧、没有那一行 `message`** 的调用，
  `{:run-id .. :message <行>}`，按行序；`nil` 当每次调用都有它的行。行用
  `harness.kernel.frames/tool-message` 拼——**与写手同一处**（票 01/02 立的那个函数）。
- `fork-session!` 在同一趟里按 run 分组把它们写进新文件（`log-messages!`）；**没有缺的行就一行不写**，
  这就是**幂等**的来源：对一份已重整化的记录再 fork，产物除了它自己的 header 与 `session/forked`
  审计行之外逐字相同（用例逐行比过）。
- **靠切点丢掉的东西不用补**：半截的信封（`TEXT_MESSAGE_START` 没有 END）在最后一步的结束**之后**，
  切点根本没复制它；而被切断那次调用的答复由修缮补上（帧 + 行，票 01/02 那一处写手）。
- ~~**旧格式不迁移**：顶层 `kind` 的行被读侧按名字拒绝（`:old-contract`），fork 照直答 400 + 那个理由。~~
  **主人 2026-09-28 改了这条口径**（原话：「旧格式拒绝回答，要求 Fork」）：旧格式要**读得动**，
  **写得不得**，出路是 fork——读侧改宽读、写门仍拒、fork 把翻译写出去。整条见文末
  「落地：旧格式迁移（2026-09-28）」。

**真机上的第一次（2026-09-29，主人的会话）**：会话 `1413f25b-…` 被判「1 次工具调用没有 message 行
答复」（那一次调用是 2026-09-28 23:20:17 被按停的），页面给出 fork；主人按了 fork，得到
`3e18d952-…`。复验：**那条缺的行在新记录里**（`call_00_ET_IplQYLpuHcvoBxjynmwZ6778` 的 `message`
行在 fork 里，父记录里没有），原记录一个字节没动 —— 票 03 那条路在真机上成立。

**但同一条读数里露出另一件事，未查**：那次「按停」发生在 2026-09-28 23:20:17，**晚于**票 03 落地
（`71f119d`，21:31），而 `harness.kernel.loop` 的停止分支本该为每一个没答复的调用写下那一行
（`loop.clj:928` 的 `(write! (frames/cut-off-message id))`，走的是 `http.clj` 给的那道 `write!` →
`log-message!`）。真记录里那条 `TOOL_CALL_RESULT` 帧在（"the run was cut off before this call
returned"）、`message` 行不在。所以要么那个分支不是这么走的，要么那一笔写丢了 —— 票 03 说的
「被停的调用由写手补上行」在真机上**至少漏过一次**，值得单独查（探针：那份记录第 2030–2042 行）。

**判据数字**：`normalized` + `replay` + `fork` + `fork-http` + `sessions` + `trajectory` + `loop` +
`stats` + `context` + `http` = **286 / 2047 / 0**（http 一套 189.7s）。新用例三条：缺行的那一份 fork 后
**已重整化**且行在、被切的记录 fork 后**已重整化**、旧格式**按名字拒绝**（这一条 2026-09-28 换成了
「宽读 + 写门」，见文末）；再一条幂等与一条「原会话字节不变」
在第一条用例里一起钉住。

## 落地：票 04（2026-09-28）

**未重整化的会话：输入区禁用 + 一条说清的提示 + 一个真能按的「重整化（fork）」按钮。**

**先修了一条后端的路**（这一票的走查发现的）：`sofar` 的 **live 分支不再无条件答 `true`**。
判据现在挂在会话自己的两条缝上（`start!` 里 `register-fold!` / `register-step!`，与 `turn` /
`pressure` 同一个形状）：出生时从记录折出来，此后每写一行往前走一步。原来那句 `true` 是一句
**没人审过的断言**——在跑着的进程里「打开」一场会话（feed 会认领它）就足以把一份谁都写不得的记录藏起来。

- `ThreadSofar` 带 `:normalized` / `:normalizationReasons`；
- 页面**每开一场会话读一次 `sofar`**（**不走 `rebuild`**：它会把记录修好，答的就不是原来那份了），
  判定交给 `SessionWritableContext`：输入框 `disabled`、Send 不画、提示画在输入框上方
  （`components/normalization-notice.tsx`，服务器给的理由**原样**列出），按钮走 `forkThread` +
  `openSession`（与消息菜单的 Fork 同一套）；
- **文案进 i18n**：`elements-thread` 的 `composer.notNormalized` / `composer.notNormalizedFork`（zh/en）。

**判据数字**：`ui` = **173 / 173 / 0**（新 suite `normalization` 两条：两种语言下提示与按钮都渲染、
理由原样；没有理由时不画空列表）；`tsc` 与 `vite build` 干净。后端那一轮 **292 / 2031 / 1**，那一条是
**既有的** pressure 红（`the-live-band-and-the-record-fold-answer-the-same-thing`，差 9 token——
stash 掉本票的改动后在 HEAD 上同样红，所以不是这一票的账）。**那条后来收了**：根因是锚点取晚了
（`band-step` 在 `model/end` 取会话快照，那时调用自己的答复已经折进来），修法与现场抄在
`.scratch/record-stream/issues/05-two-reds-read-the-field.md` 文末。

**浏览器走查**（`node scripts/dev.mjs --scripted`，2026-09-28）：

1. 正常路径：新会话发一句话 → 回放渲染、输入区照常、没有提示；
2. 钉一条**未重整化**的记录（`projects/<dir>/walkthrough-unnormalized.jsonl`：一次调用只有
   `TOOL_CALL_RESULT` 帧、没有 `message` 行）后打开它 ⇒ 输入框 `disabled`、提示与
   「1 次工具调用没有 message 行答复」都画出来、Send 不在；
3. 按「重整化（fork）」⇒ 页面切到 `[fork]`，会话回来、输入区可用；新记录里那一行在
   （`source "tool"`、`producer "kernel-message"`），原记录一个内容字节没动。

## 落地：旧格式迁移（2026-09-28，主人拍定）

**主人的那句话**：「旧格式拒绝回答，要求 Fork」。自洽的读法只有一种：**读侧宽读**（不宽读，fork 无
从下手——fork 得先读得动它要重建的东西）、**写门一律拒**、**fork 是那把钥匙**（它读旧行、翻译、写出
一份新格式的记录，原文件一个字节不动）。

**为什么必须翻译**：`.scratch/jsonl-two-kinds` 的拍定 3 定的是「旧行不读、不迁移、不"顺带兼容"」，
它成立的前提是「旧记录没有出路」。主人把出路指出来了（fork），而那条出路必须先读，所以
`replay/read-line-rows` 见到顶层 `kind` **不再抛 `:old-contract`**，改走 `legacy-rows`：

- **`input` 行拆开**：它本来就是**整个请求数组**，每条 message 各自成一行（`:id` 移到信封上，`:source`
  由 role 推——那张对应表就是旧读者当时的行为，不是猜）；
- 这一行剩下的东西（`:tools` / `:context` / `:state` …）原样留成一条 CUSTOM 帧、名字仍是旧的 `kind`，
  **一个字都没丢**；
- 其余 `kind` 行一条一行，包成 CUSTOM 帧。

**每行带 `:old-contract <行号>`**。记号是读者的账、不是文件的内容：fork 写出翻译时 `dissoc` 掉它。
带**行号**而不是 `true`，因为**一行会变成好几行**，而判据 (1) 那句「N 行还是旧格式」说的是**文件**的行
数——`normalized/step` 收的是行号的**集合**，`count` 出来就是文件的数（`an-old-format` 那条夹具三行、
五行，句子里说的仍是 3）。

**一处既有的 bug 是这一票撞出来的**：`fork-session!` 里那句
`(if (some? (replay/header? (first records))) 1 0)`——`header?` 答的是**布尔**，`some?` 对 `false` 也为
真，于是**每一份没有 header 的记录 fork 时都被切掉第一行**（`lines` 那条分支同样中招）。以前每个 fork
判例的夹具都带 header，所以它从没露面；旧格式记录正是没有 header 的那一类。现在只问一次 `skip`，
两条分支共用。

**没有做的事，如实写在这里**：

- **`rebuild` 那道门没动**（与票 02 同一条：它是修缮门，修完交给门）。它在旧记录上仍然只写它一贯写的
  东西——`session/rebuilt` 审计行，以及记录断在半路时的收尾帧；它**不翻译**，判据读出来仍是「旧格式」，
  所以一份旧记录不会因为「在侧栏点开过一次」就被悄悄迁移。
- **前端一行没改**：票 04 的提示与 fork 按钮吃的就是 `:normalizationReasons` 那几句话，旧记录只是多
  一种理由，`ui` 的数字因此仍是票 04 那一轮。
- **真机上的旧会话没有挨个打开过**：这一票的现场探针是 `dev/scratch_legacy_fold.clj`（临时目录里手搓
  一份三行的旧记录，走 `fork-session!` 全路，印出翻译后的行与新记录的 sofar），加上套件里的夹具；
  `~/.clj-harness` 里那 5 条旧记录一条都没被 fork 过。那一次的输出原样抄在
  `evidence/legacy-migration.txt`（读数三件事都写在文件末尾）。

**判据数字**：`normalized` + `replay` + `fork-http` = **54 / 291 / 0**；`http` = **117 / 1269 / 0**；
后端全量 **1351 / 14165**，**5 条红**——4 条是 `HEAD` 上本来就红的（`hooks_test` 的 `PreCompact` 多带一个
`:stdout`、`mcp_wired_test` 的 `mcp/connection` 行没落盘：`git stash` 掉这一笔改动后逐字同样红，已复现），
1 条是 `http_test/an-overflow-refusal-compacts-aggressively-and-retries-in-one-turn` 的负载抖动（单独跑绿，
这一笔没碰压缩）。跑完那句 `ISOLATION NOTE` 是本机那个活着的会话写的开发者家，不是失败。
换了的那条判例：`http_test/an-old-record-reads-but-may-not-be-written-to`（`/sofar` 答 200 +
「未重整化」+ 会话读得出来；`/api/agent` 答 409 `:reason "unnormalized"` 并点名 fork；文件一个字节不动），
`fork_http_test/a-fork-normalizes-a-record-written-in-the-old-contract`（fork 的产物是新格式、读得动、
原文件逐字不变），`replay_test/the-record-has-two-kinds-of-row-and-anything-else-is-refused-by-name`
里那一节改成「旧行被**翻译**，不是被拒」。
