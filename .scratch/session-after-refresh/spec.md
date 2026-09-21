# spec: 刷新之后还是那一场（并且看得见它还在跑）

2026-09-17 立。**要的是：刷新页面，回到你刚才那一场；如果那一场还在跑，你看得见它、它不受伤。**

今天的现场是两件事叠加：刷新把你丢进一个全新的空会话，而那条还在跑的 run 你看不见——
并且你只要去点它，就会被**收尾**掉。

> 我希望刷新之后还能看到之前正在跑的会话，而不是像现在这样，[刷新后]进入新会话。

## 先把现场钉死（对着代码核过，六条）

**一、刷新 = 一个新会话，什么也没记。** `ui/src/app.tsx:115` 是
`useState<string>(() => crypto.randomUUID())`——每次挂载一枚新 id。
而 `ui/src` 里既没有 `localStorage`/`sessionStorage`，也没有 `location.hash` 或
`history.replace`（grep 零输出），没有路由，id 无处可去。所以「刷新后进入新会话」不是 bug 的表现，
是今天**唯一的**行为：`AdoptThread` 那条路（`:144-150`）只被侧边栏的点击驱动，没有任何一条路在挂载时把人放回原处。

**二、服务端不会因为客户端走了而停。** run 体是自己的 go 块（`http.clj:454`），
`handle-run` 上没有任何门；唯一的锁是 jsonl 追加那把（`:155`）。断开 web 之后 run 继续跑、
jsonl 继续长——`http.clj:694` 那行注释就是结论，`:310-316` 是 2026-09-17 的实测
（客户端 RESET 之后又写了 4000 帧，每个 `send!` 仍返回 true）。

**三、记录是逐帧可读的。** `log!` 是 `spit f line :append true`（`http.clj:150-156`）——
Clojure 的 `spit` 每次调用开流、写完、关流。所以一条正在跑的 run，它的帧**已经**在文件里，
不需要服务端先做任何事。这是本特征选「读记录」而不是「新做一条扇出流」的技术前提。

**四、可是读的那条路会把活着的 run 结掉。** 一条会话的读法只有 `POST /api/threads/<stem>/rebuild`
（`thread-verbs` 是个闭集，`http.clj:1104-1113`），而它**先**调 `close-off-open-run!`（`:1295-1311`），
里面是 `replay/open-run`——只数文件里的 `input` 与 terminal（`replay.clj:122-141`），
**没有任何存活判断**。于是它给一条正在跑的 run 追加 `TOOL_CALL_RESULT` + `RUN_ERROR`，
再落一行 `session/closed-off` audit；那条 run 之后还会写出自己真正的 `RUN_FINISHED`。
**一条 run 两个 terminal，而且中间还有帧。**
今天就能踩到：刷新（新 runtime，`isRunning` 为假）→ 点那一场 → rebuild → 活的 run 被结掉。

**五、服务端没有「哪些 run 还活着」这个事实。** 没有登记表，`run/start`（`http.clj:528`）
落在**进程日志**里、按 thread-id 能 grep 出来但客户端读不到；会话自己的 jsonl 里能看出「input 没有 terminal」，
可那分不清「还在跑」和「进程死了」（`replay.clj:122-141` 只是在数文件）。
一个请求内部的 `state` atom（`:680`）是最接近「活 run 记录」的东西，而它随着响应流一起消失。

**六、同一会话塞第二条 run 没有门。** `handle-run`（`http.clj:672-718`）没有任何准入，
所以两条 run 可以同时往同一份记录里写：两条 `input`、交错的帧。
今天只有 UI 的 `isRunning` 遮着这件事——而一个刷新后的客户端**没有** `isRunning`。
多标签页（localStorage 之后必然出现）会直接踩到。

**另有一件今天的洞，比上面更糟：parked 的会话刷新后彻底失联。**
park 以 `RUN_FINISHED` 带 `outcome.interrupts` 结束（`ag_ui.clj:144-150`），而 rebuild 把它丢了：
`frames/apply-frames`（`frames.clj:32-62`）只认文本/思考/工具三类帧，
`replay/records->messages`（`replay.clj:182-194`）只折这些。客户端这边，
`getPendingInterrupts()` 要求最后一条 assistant 是 `requires-action`/`interrupt`
且带 `metadata.custom.agui.interrupts`（`@assistant-ui/react-ag-ui/dist/runtime/AgUiThreadRuntimeCore.js:8579-8600`），
而 `toThreadMessages`（`app.tsx:102-109`）把**每一条**消息盖成 `{type:"complete", reason:"unknown"}`。
三处加起来：卡片没了、`assertNoPendingInterrupts()` 反而放行、
那次悬置的调用**永远没有人能答**（服务端那条 parked 记录就此孤儿）。
`app.tsx:95-97` 写着「rebuild 会在日志带着 interrupts 时读回来」——今天不成立，这句注释是本特征的票 06 要改的东西之一。

## 形状（四个定下来的决定）

**决定一：看得见的方式是「读记录 + 轮询」，不是新做一条订阅流。**
记录本来就逐帧落盘（现场第三条），所以一条「可以读没写完的记录」的读路径就够了；
客户端在 `partial?` 为真时按间隔再读一次，读到 terminal 就停。
不加服务端扇出状态、不加 subscriber 生命周期——这也是本仓一贯的立场：
**「谁挂断了」服务端永远不知道**（现场第二条那次实测），一个必须靠订阅者来去来维持正确性的机制
正好逆着这条已经量过的边界。

**决定二：读回来的东西必须自己说清楚它是什么。**
三个状态，不是一个：**在跑**（票 01 说它在）/ **悬置**（有 terminal，是 interrupt）/ **截断**（没有 terminal 且票 01 说它不在）。
前两个要能读，第三个仍然是今天的行为（`close-off-open-run!` 照旧修）。
读侧那个 `ensure-complete!`（`replay.clj:91-106`）的拒绝**不是**要删的东西——它防的是
「悄悄折叠半条 run」；本特征给的是相反的东西：**折叠之前先声明**。

**决定三：「刚才那一场」记在 localStorage。**
刷新、关掉重开、新标签页都回到那一场。这与 `app.tsx:126-130` 那条「视图状态故意不持久化」
不矛盾，而且正是它的反面：那条说的是**看的方式**（conversation/trajectory），
本特征是**你在跟哪一场对话**。代价要认下来：新标签页会落在同一场，
于是**票 05 那条服务端拒绝不是可选项**（客户端永远不可能自己是唯一客户端）。
存下来的 id 指向一场已经不存在的会话（被删/被归档）时，落回一个新会话，不报错。

**决定四：停掉一条看不见的 run，这次做（票 07、08、09）。**
今天 UI 的 Stop 只 abort 浏览器那条 fetch（`@ag-ui/client` 把 `AbortError` 合成 `RUN_ERROR code:"abort"`），
**没有任何东西到达服务端**。而票 04 之后，刷新落进一条还在跑的会话会看到「不能发、在等」——
那时候没有地方能停，就成了新的牢。所以补一条按会话寻址的取消。
**一条 run 的 terminal 不许说谎**：人按的停不是这条 run 失败，用什么帧、什么 reason 由票 07 定，
判据写在票面（本仓的规则是「记录里的每一行都必须是真的」，所以这里不允许随手挑一个）。

## 不改的

- **AG-UI 协议**。没有 cancel 帧、没有断线重连，本特征不发明协议之外的东西：取消是服务端自己的动作，
  客户端看到的是它引发的 terminal。
- **`handle-run` 的「客户端走了不取消 run」**。这条继续成立，而且票 07 的取消是**显式**的一条请求，
  不是从「谁挂断了」推断出来的。
- **`ensure-complete!` 对截断记录的拒绝**，以及 `close-off-open-run!` 对**已死**记录的那套收尾（`replay.clj:143-176`）。
- **`GET /api/projects` 那份侧边栏清单的加载方式**（本特征只往那一行上加一个字段，不改请求形状）。
- **`<Thread/>`、工具行、思考行的渲染**。最后那一轮在没完时的画法是现成的（`ui/src/lib/turns.ts` 里
  `incomplete` 与 `turnIsSettled` 已经是这件事的词汇表）。

## 与别的特征的关系

- **`.scratch/parallel-sessions/`（六票，未落地）重叠最深。** 它要的是「一场会话一份 runtime」，
  票 02 把 `onSwitchToThread`/`onSwitchToNewThread` 退场、「现在看哪一场」变成 App 的 state。
  本特征票 03 落的就是那个 state 的**持久化**，所以**形状必须按票 02 要的写**
  （显示的是哪一场归 App，不走 runtime），否则票 02 得先把本特征拆了。
  它们的票 06 还想要一条「两个 thread-id 同时跑通」的后端用例——**别写两份**：
  那条用例住在本特征票 05（拒绝与放行互为边界，天然是一条）。
  **服务端按会话的「正在跑」**（本特征票 01）也正是 parallel-sessions 票 03 想要的那份真相，
  票 03 是从客户端侧凑出来的版本；两份都落地时，票 03 应该读服务端这一个，而不是各算各的。
- **`.scratch/immutable-data/`：不是本特征的前置，且要说清为什么。**
  parallel-sessions 把 `immutable-data/issues/02-turn-plan-per-turn.md` 列为硬前置，
  因为「两个会话同时跑」是那个全局 `turn-plan` 单槽互相清计划的场景。
  本特征**不制造同会话的两条 run**（票 04 的客户端 + 票 05 的服务端各堵一半），
  刷新的那一刻被恢复的也只有一个会话，所以跨会话并发不是本特征放出来的。理由写在这里，免得下次又推一遍。

## 怎么验证

| 层次 | 手段 |
|---|---|
| 服务端事实 | `timeout 900 clojure -M:test -m harness.test-runner`，失败**用例名**与基线一致；新用例自己出现在输出里 |
| 客户端类型与编译 | `cd ui && npm run build` |
| 客户端 agent 层 | `cd ui && npm test` |
| 界面行为 | **真浏览器走查**，证据写成证据文件（照 `composer-status` 那一份的形式：临时 `CLJ_HARNESS_HOME`、`harness.e2e-server`、vite dev），放在 `evidence/` |
| 「还活着」这类时序断言 | 按仓规：不自造临时文件进 run-wide 的隔离目录；子进程的答案**从文件读**，不从 stdout 读；等 writer 自己收尾（终帧在了且最后一行是 message），不要去抢 |

**不假装界面行为有自动化测试**（`ui/test/` 是 agent 层，不渲染 React；devDependencies 里没有 testing-library，也没有 jsdom）。
要给 React 补一套是另一个特征，不是本特征偷偷加的依赖。

## 仍然要拒绝的（不是全都放开）

- **同一条会话的第二条 run**：客户端不许发（票 04），服务端也要拒（票 05）——两句都要，因为客户端不可信。
- **一场还在跑/悬置的会话被归档、或它所属的项目被删**：这条今天是
  `parallel-sessions` 票 04 的地盘（判据要从「当前页在不在跑」换成「那一场自己在不在跑」）。
  本特征给它送去的正是那个判据的服务端版本（票 01）。**在本特征里不重开这一条**，
  只在票 01 的验收里留一句：这个字段必须够 parallel-sessions 票 04 直接用。

## 票

十张。01/02 分着写，因为「知道谁活着」与「读得回没写完的记录」各自可独立验证；
04 与 05 分着写，因为浏览器侧与服务端侧各自独立落地；
07/08 分着写，因为「取消到得了循环」与「在飞的调用也停得下来」各自可独立验证，
且后者是本特征最重的一张；09 要等前三张（用户 2026-09-17 的复议：再拆细一点）。

- **01** 活着的 run 是服务端说得出的一个事实（无阻塞）
- **02** 还在被写的记录：读得回来，且不被结掉（阻塞：01）
- **03** 刷新回到刚才那一场（阻塞：02）
- **04** 那一场没完的时候，输入框不装作能发（阻塞：01、03）——**「还在跑」那一半已落地
  （2026-09-21）**；「悬置」那一半等票 06，见文末。
- **05** 服务端拒绝同一会话的第二条 run（阻塞：01）——**已落地（2026-09-21）**，见文末。
- **06** parked 的那一场，刷新回来还能答（阻塞：03）
- **07** 服务端：一条 run 停得下来（取消到得了循环，terminal 诚实）（阻塞：01）
- **08** 服务端：正在跑的那次工具调用也停得下来（阻塞：07）
- **09** 浏览器：刷新之后也能停（阻塞：04、07、08）
- **10** 收口：文档、两套全量、走查证据（阻塞：02、03、04、05、06、07、08、09）

## 落地（2026-09-21）：票 05

**在哪落的、为什么不在本特征的分支上**：`.scratch/sessions-live-on-the-server` 的票 03（输入面：
`messages` 退役）把它列为**前置**——服务端一旦成了会话的作者，同一会话的第二条 run 就不再是「两份
客户端历史打架」，而是**两个写者往同一份权威里写**。所以票 05 在那支分支
（`.worktrees/sessions-live-on-the-server`）上先落了，本特征的 06–10 不受影响。

**落了什么**：`harness.edge.http` 的 run 边现在有一道门。`handle-run` 拆成两个函数——
`handle-run` 是门（解析请求 → 同一 thread-id 已有活着的 run 就 **409** → 否则交给 `stream-run`），
`stream-run` 是从前那个函数体（注册、起流、收尾），一行没改。判据是票 01 那张进程内登记表
（`running?` / `live-runs`），拒绝的句子在 `refuse-second-run!` 里：点名 thread-id、点名没跑起来的
run、说出规矩（一次一条）、并告诉调用者怎么回来。`api-response` 从管理接口那一节**上移**到 run 边
之前——它是这一票唯一不是管理路由的用户，两个函数是同一个形状。

**一条用例，两半**（票面要求：「拒绝与放行互为边界，拆开写就丢了『同一台服务器上两件事同时为真』」）：
`test/harness/edge/http_test.clj` 的
`a-session-answers-one-run-at-a-time-and-two-sessions-still-both-run` —— 两个 thread-id 的两条 run
在 `loop/run-chan` 上被按着（都活着）、同会话的第二条被 409 具名拒绝、**被拒的那条没往文件里写任何
东西**（只数 `input` 行）、放行之后两条各自到 terminal 且各自的记录里没有对方的 run；最后再发一条，
证明「前一条终了之后能再发」。**崩掉的 run 之后还要能发**是另一条
（`a-crashed-run-does-not-close-its-session-for-good`）：拿 `window-gate` 先按住在跑的那条、
再让它炸，然后断言同一会话的下一条 `POST` 是 200。

**一处已知窗口，写在 `handle-run` 的 docstring 里而不是藏着**：门问的是「这条 run 起来了没有」，
而登记发生在 run 真正开始的地方（provider 解析之后，故意的：没跑起来的 run 不留登记）。所以
**两次请求落在 setup 那几毫秒里会都进来**。要堵它就得在 setup 之前占位、并在 setup 每一条失败路径上
还回来——漏还一次，这一场就**在进程重启之前再也不能跑**，比它买来的那几毫秒糟。

**后端全量**：`1005 tests / 12196 assertions / 0 failures / 0 errors`（本分支，含新用例）。基线 1003 /
12180 是这两条用例落地之前的数。

## 落地（2026-09-21）：票 04 的「还在跑」那一半

**为什么是「一半」**：票面要的是两种没完都不装作能发——**在跑**的与**悬置**的。落的这一半是前者：
刷新落进一场**服务端正在回答**的会话，composer 不再假装能发。悬置那一半**故意没落**：悬置的判据在
服务端说得出来（窗口的 `state` 就是 `parked`），但那一场刷新回来时**卡片还回不来**（票 06），
门一旦为它关上就是**一扇出不去的门**——所以在 `lib/session-status.ts` 的 `statusOf` 里
`parked` 仍是**本页自己**的读数，服务端的 `parked` 一个字都不取（有一条用例把这个决定钉住）。

**现场（对着真浏览器核过，不是推理）**：脚本替身第一轮调 `bash` 跑 `sleep 45`，等**服务端自己说
`running`**（`GET /api/threads/<stem>/page` 的 `state`）之后刷新。刷新之后那一刻：窗口把那一轮画成
**没完**（不折叠）、侧边栏那一行**在转**、而 composer 的按钮**亮着**——按下去，服务端回
**409**：`this session already has a run in this process … (threadId …, the run going is …)`。
这就是主人报的那两半：「展示不再运行」+「点击发送提示正在运行中」。**这一步的量法本身也是一条教训，
写进走查脚本的头注释**：run 边写下 `input` 之后要花**约两秒**装配系统提示词才登记 run，所以「刷新完
再点发送」如果落在那两秒里，门会答「没有在跑」，然后**真的起第二条 run**——第一版走查就是这么量出一条
服务端允许的并发写，只好改成等 `state` 说 `running`。

**落了什么，四处**：

- **事实的来源**：`App` 的 `SessionHost` 把窗口自己那个 `state` 收成 `runState`
  （`useWindowFeed` 新增 `onState`，在**读到的尾页**与**每一次 commit** 两处上报），它既是
  `isSendDisabled` 的第二个理由，也是那句话与侧边栏那一行的依据。窗口的 `state` 是票 01 那个事实
  （`live-state`：本进程有没有这条 run 在跑），所以这一条**没有新的服务端形状**。
- **门**：`isSendDisabled: gateOpen || runState === "running"`。**只认 `running` 这一个词**：
  `parked`/`unfinished` 都不是「在跑」，而 `settled` 之后它自己会回到 false（feed 为「只变状态、没有
  新条目」专门发的那一帧，`stream-feed!` 里写着为什么）——「跑完之后接着发」是走查的一条验收。
- **那句话**：新文件 `components/session-run-notice.tsx`（`data-slot="session-running"`，
  文案在 `locales/{en,zh}/composer.json` 的 `run.stillAnswered`）。**单开一个模块**是因为它要在 UI
  套件里渲染出来读回去，而 `composer-chrome.tsx` 进不了那个运行（经 `lib/attachments.ts` 摸到
  `lib/i18n.ts`，后者加载时碰 `document`）。它是 context 而不是 prop：composer 在抄来的 `<Thread/>`
  里面，host 只能给 `children`，给不了 prop。
- **两个读数在哪儿合、在哪儿不合**：`lib/session-status.ts` 新增 `statusOf(本页自己的, 服务端那个词)`
  ——**并**，上报给**页面**的那一份（侧边栏那一行据此点灯：列表是快照，可能拍在 run 登记之前，
  实测确实拍到了「没在跑」）。而 `onOwnRun` 上报的仍是**本页自己的**读数：`isOwnRun` 决定 feed 的帧
  能不能 import 进这个 runtime，把服务端的词并进去就等于**让刷新回来的那一轮冻住**——正好把这个特征
  要的东西弄没。这一条写在 `app.tsx` 的类型注释里，也在 `docs/architecture/client.md` 的审批门一节。

**验证**：

| 层 | 做了什么 | 结果 |
|---|---|---|
| 界面 | `node scripts/dev.mjs --scripted evidence/04-go.json --ui-port 5319` + `walkthrough.mjs` | 修前 **2 RED**（按钮亮着、没有那句话），修后 **ALL GREEN**，含「settled 之后又能发，且发进同一场（200）」 |
| 客户端用例 | `cd ui && npm test` | **87 通过 / 1 失败**，失败的是 `skills > asking-for-the-list-changes-nothing`，**基线就有**（改动前两次都红，同一处、同一条）；新增 `running` 两条都绿（`EXPECTED_CASES` 86 → 88） |
| 类型与构建 | `cd ui && npm run typecheck`、`npm run build` | 都过 |
| 后端 | `clojure -M:test -m harness.test-runner` | 本票未动后端；失败用例名与基线一致（见提交信息） |

**没做的事，说清楚**：走查里那句 `bash · sleep 45 && echo slept待审批`——一条**正在跑**的工具调用在
记录折回来之后被画成「待审批」。那不是本票的范围（它与票 06 的卡片、以及工具行状态的重建是一族），
本票没有碰它，也没有假装它不存在。

