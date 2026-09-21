# spec: 会话归服务端 —— 内存是权威、记录异步落后、浏览器只发动作

**一句话**：会话从「客户端每轮递上来的一份消息列表」变成「服务端内存里的一场对话」——初次从记录重建，
之后在内存里操作，异步写回记录（落后允许），浏览器降为**只发动作的只读副本**；而服务端把它当作一条
**带窗口的 feed** 供出去：**尾页 + 增量推送 + 按需补页**，浏览器拿到的从来不是全史。决策见
`docs/adr/0002-sessions-live-on-the-server.md`（十条）与 `docs/adr/0003-the-feed-is-a-window.md`（九条，
**修正 0002 的决策 8**）；本文件是它们的落地。

2026-09-20 立票；同日按 ADR 0003 重切为七张票：`01 → 02 → 03 → 04 → 05 → 06 → 07`。

## 问题

**读侧已经是副本了，写侧还是客户端；而读侧读的是全量、走的是轮询。**

1. **客户端已经会从服务端读历史。** `session-after-refresh` 的 01–03 已落地：页面把「我在哪一场」记在
   `localStorage`（`ui/src/lib/session-memory.ts`），挂载时 `sessionHistory.load` 走 `rebuildThread` /
   `readSofar`（`app.tsx:207`），adapter 的 `append` / `update` 是 **no-op**。
2. **但每一轮还是把整份累积的 `messages` 送回去。** 服务端只读 `(:messages input)`，`ag/inbound` 只是
   转换它——所以「谁是会话的作者」仍然在客户端。由此：
   - **两个标签页是常态，而服务端不拒绝第二条 run。** 同一个 id 共享 localStorage
     （`session-memory.ts`：「TWO TABS LANDING IN ONE SESSION is the ordinary case」），那条门是
     `.scratch/session-after-refresh/issues/05`，**没落地**（`http.clj:513` 的注释指着它）。
   - **注入每轮重算、每轮一张卡。** 注入不在任何一方**持有**的历史里（客户端不回发它），所以每次模型调用
     都要现算、现送、现画——`.scratch/context-frames` 的卡因此每轮重复一张。
   - **两份真相并存**：客户端手里那份 vs 记录那份，没有裁决处；`rebuild` / `sofar` 读记录，
     live 视图读帧，两边可以不一致。
3. **读侧还是全量 + 轮询**（ADR 0003 的背景）。挂载时 `sofar` / `rebuild` 答的是**整份** `:messages`
   （`http.clj:1948`）——长会话的 wire 成本随会话长度线性涨；而「只是看着」的标签页每 1.2 秒轮询一次
   （`app.tsx:110` 的 `WATCH_INTERVAL_MS = 1200`），读的还是**记录**（落后那份）。`app.tsx:336` 的注释
   自己写着「never a second streaming path」——它是一个妥协。参考实现在这里走的是另一条路，
   同时解掉这两件事。

## 决策

见 ADR 0002 与 ADR 0003；这里只列每一票要吃的那一条，不重复理由。

## 非目标

- **不做鉴权、不做多人协作**（ADR 的边界）。feed 是本特征唯一新接的端点（票 05）——不是「不接新端点」，
  那条 0002 立票时的边界被 ADR 0003 改了。
- **不改记录的格式**（一行一个 JSON，只 append，不迁移）与 `message` 行的形状。
- **不改 AG-UI 那几帧的形状**：`context-frames` 刚立的 `CUSTOM` / `data` 契约不动；序号是**新增字段**，
  不引入 `STATE_SNAPSHOT` / `STATE_DELTA`。
- **不推全史、不做订阅注册表、不自动加载全史**（ADR 0003）：存量是尾页，更早的历史是一颗按钮。
- **不做 `cancel` 的语义**（`.scratch/session-after-refresh` 票 07/08/09 的活）；本特征只把「停止」当成
  输入面上的一件**动作**登记，不实现它。
- **不做消息的编辑/删除/重放**：副本不许改历史。
- **不改 `before-llm` 的派生逻辑**（技能正文、作业通知）：会话成了权威之后它们是内存里的追加，
  但它们**怎么算出来**不变。

## 验收主线

1. **历史归服务端**：一轮跑完，客户端**不再**送 `messages`；下一轮服务端用内存里那份继续。
   （**2026-09-20 修正**：原措辞要求它与记录里 `message` 行 submitted 侧**逐字节相同**——票 01 落地时
   推翻了这一条：那份里有每轮现组装的 system 消息与每轮现读的注入物，冻结它们就是「改了没生效」。
   会话持有的是 `replay/sofar` 折出来的**对话**，卡消息被丢掉。见票 01 的落地记录。）
2. **关掉浏览器不丢会话**：关标签页 → 重新打开 → 回到那一场，历史与屏幕上最后看到的**逐字节一致**。
3. **两个标签页**：第二个标签页进同一场是**一等的读者**（连同一场 feed，看得到增量），但**不能**发起
   第二条并发 run：服务端拒绝并说出理由（不是静默、不是分叉）。（**修正**：原措辞只说「拒绝第二条
   run」；ADR 0003 之后「看」与「发」要分开说——门只需要拒第二条 run，不必拒第二个读者。）
4. **落后可见但不假装完整**：进程在 run 中途被杀 → 重开时重建先 `close-off-open-run!` 补收尾帧，
   客户端看见的是「它在那儿断了」。
5. **写失败说出来**：把记录目录变成不可写 → 那个会话进降级态并在界面上说出来，run 不因此静默丢历史。
   （**2026-09-21 票 02 落地**：降级是**写者**的状态——那一行停在队首，记录因此永远是有序前缀，
   `retry!` 是回来的路；会话照跑。界面那一半是 `:record` 字段 + 一根常驻的条。
   证据：`test/harness/edge/record_test.clj`、`http_test.clj` 的一个用例、`ui/test/suites/record.tsx`。）
6. **（作废，2026-09-20 实现票 01 时发现）注入仍然每轮重算，卡仍然每轮一张。** 会话持有的是**对话**，
   不是 system 消息、也不是注入物：指令文件与清单每轮现读现拼（`opening-blocks!` 的 docstring 写着
   「改了没生效」正是它要防的），冻结进会话就是把那条纪律换掉。所以本特征**不解**这个问题；
   它解的是「客户端当作者」，而那是另一半。（要不要把注入挪进缓存前缀／改成只画一次，是另一条决定，
   见票 07 的判断 3。）
7. **窗口**（ADR 0003）：打开一场长会话，第一个响应只带**尾页**（条数是一个写下来的数字）；更早的
   历史要点一下才来；断档只重建**已加载窗口**，不重读全史。
8. 两套全量与真浏览器走查证据（动过 `ui/src/`，走查是硬要求）。

## 跨特征对照

- **`.scratch/session-after-refresh` 票 05（拒绝同一会话的第二条 run）**：本特征的**前置**——不过
  ADR 0003 之后它的分量变了：第二个标签页有了正当的读法，那条门只需要拒**第二条 run**，不必拒第二个
  **读者**。**已落地（2026-09-21，就落在本分支上）**：`handle-run` 成了门（同会话已有活着的 run 就
  409、具名拒绝），函数体搬进 `stream-run`；用例是
  `test/harness/edge/http_test.clj` 的
  `a-session-answers-one-run-at-a-time-and-two-sessions-still-both-run`（拒绝与放行同一条，票面要求）
  与 `a-crashed-run-does-not-close-its-session-for-good`。它的票面已删、落地记录写进
  `.scratch/session-after-refresh/spec.md`；`parallel-sessions` 票 06 里留了指针，别写第二份。
  票 06（parked 刷新回来还能答）与 07–09（停止）**不改**，但它们的「停止」在本特征里是一件
  **动作**。
- **`.scratch/context-frames`**：它的卡与帧契约不动。**原以为本特征会让注入不再每轮重画，实现票 01 时
  发现不成立**（见验收 6）：注入之所以每轮重算，是因为指令文件必须每轮现读，不是因为客户端持有着历史。
  两件事看着像一条根，其实不是。
- **`.scratch/job-output` 决策 3（「帧就是客户端的会话」）**：**被推翻**的地方要写进那一份的对照，不改它。
- **`.scratch/skills-and-instructions`**：「对话归客户端所有，服务端每轮现收现算」那一节的推导要重写
  （票 07），但「技能正文是派生的」**不改**。
- **`.scratch/parallel-sessions` / `.scratch/immutable-data`**：认领（决策 7）与新的一堆进程内状态要和它们
  的既得分家规矩对齐，票 04 核。**票 02 已经动了其中一处描述**：`immutable-data/spec.md` 把 carry-back 写成
  「在 `log-lock` 内」办的事，而 `log-lock` 已随单消费者写者退役（`harness.edge.record`），那条措辞不再成立
  ——收口（文档层面）留给票 07。
- **`.scratch/jsonl-message-record`**：`message` 行是「那次真送出去的那份」这一定义**不动**；它是验收 1
  的凭据。注意它同时是**序号重放**的底座：记录 append-only、一个写者，第 N 条就是第 N 条（票 05 判断 1）。
- **参考实现（DeepSeek Harness）**：ADR 0003 的形状来自它的 `dsh-api-session-controller`
  （`open()` / `loadOlder()` / `loadThrough()`、`windowSnapshot(hasMore, revision, change)`）、
  `dsh-client-connection`（带重连的 journal 流）与 `dsh-client-ui-trajectory`
  （`hasEarlierRecords` / `onLoadEarlier`）。**参照的是形状，不是它的代码**：本仓的记录格式、帧词汇表、
  锁与线程纪律都不同——票 05 的判断 4/5 就是要在这里做自己的选择。

## 交付顺序

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 会话表：出生、寿命、上界 | — | **已落地**。`thread-id → 会话` 的内存表；出生时从记录重建一次；空闲 30s 放掉；两个钉子（正在跑 / 还有没落盘的）；限制同时运行的会话数。修正项见该票末尾（序号、窗口读法、`running?` 二份） |
| 02 | 异步写：每帧、失败进降级态 | 01 | **已落地**。帧入队、单消费者逐行 append、每帧；`flushed-seq` 是一条序号（`(+ flushed pending)` = 下一条要铸的序号）；写失败 ⇒ 降级态，`sofar` / `rebuild` 带 `:record`，界面上一根常驻的条；退出时收干净。修正项（prepare 每行一问、水位重新基准化）见该票末尾 |
| 03 | 输入面：`messages` 退役 | 01, `session-after-refresh` 票 05（跨特征） | **已落地**。动作的载荷是新字段 `append`（不是 AG-UI 的 `messages`）；`messages` 具名 400、不认识的 id 具名 404、`runId` 门里铸并进记录、`provider` 只认会话档、`context` 只在出生那一轮读；页面不再铸任何会话 id（`POST /api/sessions` / `POST /api/project` 由服务端铸）；客户端那半是 `ui/src/lib/agent.ts` 的 `HarnessAgent`。落地记录见下（含留给 04/05/06 的边界） |
| 04 | 认领：一个 thread 归一个进程 | 01 | **已落地**。库里一行 owner（`session_claims`，不是锁文件）；会话出生时认领、放掉会话（显式 `drop!` 或空闲 30s）时交还、进程正常退出走 shutdown hook 交还；`kill -9` 留下的行靠 **pid + 起始时刻**判死并明说易主；后到的进程**只读 + 明说**（动作 409 点名 pid，读路由照常）；认领的 token 就是票 05 的 generation。落地记录见下 |
| 05 | 会话 feed：尾页、增量、补页 | 01 | **一条 source 三个动词**（`tail` / `append` / `prepend`）；序号可从记录重放；`since` 与 generation；`rebuild` / `sofar` 的 live 语义读内存；放掉时的终态 |
| 06 | 副本：窗口、补页、刷新 | 03, 05 | 窗口 `{entries, baseSeq, hasMore, revision}`；「显示更早」那颗按钮；补一页 vs 重开的两种处置；刷新走内存；打字 / 滚动的锚定 |
| 07 | 收口：两条铁律、状态表、文档、报数 | 02–06 | `overview.md` 铁律 1 与 3 的新措辞 + 状态表（+ 窗口那一行）；`edge.md` / `client.md` / `kernel.md` / `skills-and-instructions.md` 的推导；`CONTEXT.md` 术语；两套全量 + 走查证据；落地记录 |

## 状态

**2026-09-20 立票，同日按 ADR 0003 重切为七张票。** 票 01、02、03、04 已落地（01/02 的文件留在
`issues/` 里当落地记录，票 07 收口时再把它们折进这里）；05–07 `ready-for-agent`，等前置。

## 已验证到什么程度

**票 01**（`src/harness/edge/sessions.clj` + `test/harness/edge/sessions_test.clj`，11 个用例）
与**票 02**（`src/harness/edge/record.clj` + `test/harness/edge/record_test.clj`，9 个用例 51 条断言；
`http.clj` 的接线 + `http_test.clj` 的一个用例；UI 的 `lib/record-health.ts` /
`components/record-notice.tsx` / `test/suites/record.tsx`）已落地。

后端全量：**1003 tests / 12180 assertions / 0 failures / 0 errors**（2026-09-21，
`.worktrees/sessions-live-on-the-server`）。UI：**54 cases 全绿**，`tsc --noEmit` 与 `vite build` 干净；
真浏览器走查 **GREEN**（`node scripts/dev.mjs --scripted .scratch/sessions-live-on-the-server/evidence/go.json`
+ `evidence/walkthrough.mjs`：一个会话第一轮正常落盘，第二轮之前把那个 jsonl 改成只读，跑完之后
**不刷新**页面上多出一条常驻的条，`sofar` 同时报 `state=degraded`，记录行数 14 → 14 一个字节没动；
图与记录在 `evidence/`）。

**这个分母和票 01 记的不是同一个，原因值得留着**：`test/harness/test_runner.clj` 的
`test-namespaces` 是一张**字面清单**，票 01 的 `sessions-test` 与自己新写的 `record-test`
都没有进去——票 01 那句「982 / 12083」因此**从未包含 sessions-test**（一个从不运行的命名空间
不可能是红的）。两张都已登记，上面这个数才是真的跑了它们的数。

**「界面说出来」验到哪一步**：降级态在两条读路由上的形状、以及它渲染成一句话（两种语言、复数形式）
都有用例；`app.tsx` 里的**接线与位置**（读到的 `:record` 交给这根条、摆在视图切换下面）没有自动用例
——那个文件在 vitest 里渲染不了（它要走 `lib/i18n.ts` 与 assistant runtime），靠类型门 + 真浏览器走查，
而**走查正是在那里抓到一个真洞**：自己在驱动这一轮的页面原本收不到跑中的降级（见票 02 的落地记录），
修完之后走查 GREEN。这正是 `.scratch/session-title-blank/` 那次的教训形状，所以那句话被提成了一个
能渲染的组件。

## 票 03 落地记录（2026-09-22）

**交付物**：`edge/sessions.clj`（`append!` / `settle!`）、`edge/ag_ui.clj`（`context-entry` 与新的
`inbound`）、`edge/replay.clj`（追加折叠与 `:added`）、`edge/http.clj`（四道路口 + `run-agent!`）、
`cap/project.clj`（`session-exists?` / `register-session!` / `bind!`）、`edge/stats.clj` 与
`edge/trajectory.clj`（读 `:added`）；UI 侧的 `src/lib/agent.ts`、`src/lib/projects.ts`、
`src/app.tsx`、`src/components/sidebar.tsx`。票面文件已按 `docs/agents/issue-tracker.md` 删掉
（它的结论在这里）。

### 线上形状（票面判断 1 要的「选一个」）

动作的载荷走**一个新字段 `append`**，不叫 `messages`：这样「客户端还在送旧的那个形状」是一件
**能具名拒绝**的事——400，`:field "messages"`，句子里点名 `append`——而不是被悄悄读成新的意思。
于是 run 的请求体是 `{threadId, append, tools, resume?}`：

- **`append`**：这次动作**新加进对话**的条目（AG-UI 消息形状，带 id）。服务端按 id 去重
  （`sessions/append!` 用 `swap-vals!` 判「谁真的进去了」），所以客户端重发同一条是幂等的。
- **`messages`** ⇒ 400（具名）。**`runId`** 不再被读：门里铸 UUID，它进记录（`input` 行的 `:runId`）
  和帧，所以「同一场同一轮重建两次，卡的 id 相同」靠的是记录，不是「再铸一枚一模一样的」。
  客户端还送 `runId` 也不报错，只是不看。
- **`provider`**：`current-provider` 只按 `thread-id` 取，请求里那层不再被 consult（但请求体原样
  进 `input` 行，事后能追问）。
- **`context`**：只在 **出生那一轮**（会话还是空的）被读，构成一条 id 为 `session-context` 的 user
  消息，跟着 `append` 一起进会话——此后它就在历史里，不再进输入面。
- **不认识的 `threadId`** ⇒ 404，句子点名那个 id 并指路 `POST /api/sessions`。**不静默建**
  （这一条退掉了 ADR 0001 决策 3 那个补丁：挂载铸一枚 id ⇒ 每刷新一次多一条空会话）。
- **`resume`** 行为不动（`resume-decisions`，认不出就拒），只是它现在与 `append` 并列在同一条动作里。

### 客户端那半

`ui/src/lib/agent.ts` 的 `HarnessAgent extends HttpAgent`，只覆盖 `requestInit`：剥掉 `messages` 与
`runId`，把 `appendOf(messages)`（**最后一条非 user 消息之后的那段 user 消息**）塞进 `append`。
页面不再铸任何**会话** id：新任务 = `POST /api/sessions`（body 不具名），项目里的新会话 =
`POST /api/project {dir}`（一次调用里铸 + bind，所以不会留下没绑上的空会话）；`sidebar.tsx` 里
四处 `crypto.randomUUID()` 都换成了这两个动词，`app.tsx` 的 `onListed` 在「没有可恢复的会话」时
问服务端要一枚（要不到就把服务端那句话画在对话区，`data-slot="no-session"`）。

### 实现时才发现、写票时没写到的

1. **`POST /api/sessions` 早就有**（脚本与测试在用），把它变成「页面铸 id 的动词」之后，
   `project/register-session!` 得是 `INSERT … ON CONFLICT DO NOTHING`——具名要两次同一枚 id 不是错，
   但**不能**铸出第二行。
2. **记录的 `input` 行多了一个 `:added`**：请求体原样 + 「这次真的进去了什么」。两者不同出现在
   重发（什么都没进）与出生（context 进去了，而客户端没送过它）。`stats` / `trajectory` / `rebuild`
   都改读 `:added`（老记录回落到 `:messages`），`trajectory/one-run` 的 `:history` = `added` + 返回的。
3. **会话在「帧发出去之前」落定**：终帧先 `sessions/settle!` 再发给客户端，否则一个快的客户端在
   收到 RUN_FINISHED 之后立刻发下一步动作，会在「run 结束了」与「它的话进了对话」之间赛跑。
   run 崩掉那条路也 settle（已经发出去的半句是模型说过的话，丢掉它会让下一轮从一段没有半点
   痕迹的对话继续）。
4. **UI 的两套 E2E 都得换客户端**：`ui/test/e2e.ts` 里那些 `new HttpAgent(...)` 造出来的 body 现在会
   被服务端拒（它们送 `messages`），所以 `postRun` 改成送 `append`、并加了 `ensureSession` /
   `agentFor`；`suites/turn.ts`、`client.ts`、`approval.ts`、`concurrent.ts` 改用它。
5. **模型模态守门改看 `:append`**：`guard-input-modalities!` 以前检查「客户端递上来的整份」，
   现在检查这次动作自己的条目——三轮前的一张图不该让这一轮被拒。

### 边界（写给票 04/05/06）

- **`context` 出生时只有一个来源，而今天没人往里放东西**：服务端会在出生那一轮读请求里的
  `context`，页面却始终没有构造过它，所以出生 context 现在是空的。真要放，入口是出生那一轮。
- **页面手里那份 `agent.messages` 还在长**（运行时的副本），只是不再送出去；窗口（票 06）才是给它
  上界的地方。
- **`(sessions/messages thread-id)` 仍是全量读**（票 01 的修正项 1），票 05 换成窗口。
- **`sessions/runs` 与 `http/live-runs` 仍是两份**（票 01 的修正项 3），票 05 合并。

### 验证

**后端全量：1011 tests / 12258 assertions / 0 failures / 0 errors**（2026-09-21，本 worktree，
exit 0）。新用例落在 `edge/http_test.clj`：不认识的 id 具名 404、带 `messages` 的 body 具名 400、
请求里的 `provider` 不被读、run id 在门里铸且**记录**是命名它的地方、出生 context 只进一次、
项目里的新会话由服务端铸并绑上、同一会话的第二条 run 仍被拒。`ag_ui_test` / `replay_test` /
`stats_test` / `trajectory_test` 跟着 `:added` 改（老记录回落 `:messages`）；`test_support.clj`
多了 `support/start-session!`——**五个驱动 run 的命名空间原来靠运行边的静默登记**，那条路没了之后
每个 fixture 得自己先把会话建出来。

**UI：56 cases 全绿**（54 → 56），`tsc --noEmit` 与 `vite build` 干净。两条新用例在
`ui/test/suites/client.ts`：一条读**线上的 body**（用 `HttpAgent` 自己的 `fetch` 缝抓下来）——
没有 `messages`、没有 `runId`，第二轮 `append` 只有第二条问题；另一条证明服务端铸的 id 是页面下一步
能用的（列得出来、跑得起来）。`suites/turn.ts` / `client.ts` / `approval.ts` / `concurrent.ts`
都改用页面自己的 `HarnessAgent`（`test/e2e.ts` 的 `agentFor` / `ensureSession`），因为旧写法送
`messages`，现在会被服务端拒——**这条本身就是那次改动最好的回归门**。

**真浏览器走查 GREEN**（`evidence/03-walkthrough.mjs` + `03-go.json` + `03-action-body.png`）：
空 localStorage 的新页面 → `POST /api/sessions` body 是 `{}` → 服务端答一枚 id；三轮 body
逐条打印，都能看见 `{threadId, append:[这一条], tools}`，没有 `messages` / `runId`；刷新回同一场且
**没有再问一次** id；从页面里敲一个不认识的 id，得到 404 和那句点名的话；`GET /api/threads` 里那个 id
只出现一次，记录里三条 `input` 各带服务端铸的 `runId`。

## 票 04 落地记录（2026-09-22）

**认领是一行库里的 owner，不是锁文件。** 选择理由写在新模块的 docstring 里，一句话版：库已经是
「同一个 home 的两个进程唯一会达成一致的地方」（`with-transaction` 用 `BEGIN IMMEDIATE` 开，
读-改-写不会输给抢跑的进程），而认领正好是库擅长的那种东西——**可重写的一行状态**，不是记录。
锁文件要自带目录、原子创建和它自己的「放哪儿」，而 pid 活性那一问它答得和这里一模一样，
所以它只多一套机制，不多一个答案。

### 接口

- **表**：`session_claims (thread_id PK, instance, token, pid, started_at, since)`，迁移链上多一步
  `session-claims`（append-only，`test/harness/infra/db_test.clj` 的「store 里有哪些表」守卫同步更新）。
- **`harness.cap.claims`**：`holder`（**活着的**持有者，陈旧行答 nil）、`mine?`、
  `take!`（活着的别人 ⇒ 抛 `:session-claimed`，异常里带着那一行；陈旧行 ⇒ 接手并写一行 WARN）、
  `release!`（**按 token**）、`release-all!`（按 instance，退出钩子用）、`hand-over!`（下面那条竞态用）、
  `held`（给读者看全部行）。`take!` 自己装退出钩子（幂等），所以**不靠「谁启动了 server」**——
  一个只认领不开 http 的进程也要正常退出时交还。
- **接线**：`sessions/ensure-session` 在**建表那一步**认领（认领属于会话的寿命，不属于 run）；
  `sessions/drop!` 与 `sweep!` 交还（按**条目里那个 token**，不按 thread-id）；`http/handle-run` 在
  门口问一次 `holder`，是别人的 ⇒ `refuse-served-elsewhere!`（409，body 里有 `:holder {:pid :since
  :instance}`，句子说得出「停掉那个进程或等它退出」）；`http/project-post` 同一条规则——因为**改绑会
  移动日志文件**，那是在别的进程的写者脚下抽地板。

### 三个决定，以及为什么不是另一种

1. **owner = instance + pid + 起始时刻，三个事实各答一问**：`instance` 是进程自己铸一次的随机 id，
   「这行是不是我的」是字符串比较、不碰 OS；`pid` + `started_at` 才是后到进程问 OS 的东西。
   **只要 pid 会犯错**：pid 会被复用，一个复用的号会让一场对话被一个陌生人钉住——起始时刻就是判这个的。
   没有心跳：心跳要每个进程一个定时器，而 OS 直接答同一个问题；心跳唯一能多买到的是「发现卡住的进程」，
   而卡住的进程**仍然是**主人，抢它的认领正是这一票要防的两份权威。
2. **粒度是进程、寿命是会话**（判断 3/4）：run 结束不让认领（否则下一轮要重新认领，而重建是冷的）；
   空闲 30s 放掉会话时**一起放**，否则空闲会话会永远占着一个进程。两票的接口就在 `sweep!` 里对齐。
3. **后到的是「只读 + 明说」**（判断 5/9）：看（列表、`rebuild`、`sofar`、统计、轨迹）照常——那些读的是
   文件；动作（run、改绑）409 并点名 pid。**非 owner 的页面没有增量，就是降级**（判断 9 的选定）：
   代理要引入一条跨进程长连接、它的寿命、重连与背压，而「只读」要买的只是「看得见历史」。

### 两个竞态，写下来免得后人重新踩

- **放掉会话是「先摘表、后交还」，两者之间那一场可能已经重生**：所以 `take!` 每次**换一个 token**，
  `release!` 按 token 删。按 thread-id 删会删掉刚重生的新认领，留下一个没有认领却被人服务的会话——
  比「删不掉」坏得多（两份权威）。
- **同一进程里两次出生抢一张表**（运行边的门口检查与 `register-run!` 之间那个众所周知的窗口，见
  `handle-run` 的 docstring）：两次 `take!` 都会铸 token，赢下 `swap-vals!` 的那个条目的 token 可能不是
  行里那个。**输的一方**在两个 token 都看得见，所以由它把行交过去（`hand-over!`）；不做这件事的话，
  那一行会一直挂到进程退出为止，期间别的进程**连一场这个进程早就不服务的对话也拿不到**。

### 与票 05 的接口（判断 8）

**认领的 token 就是 generation。** 会话表条目里带着 `:claim`（`(sessions/live)` 也读得出来），
`take!` / `hand-over!` 换 token、`release!` 删行——所以「这一场易主或放掉」和「副本窗口作废」是
**同一个时刻的同一个事实**，票 05 只要拿窗口建立时的 token 与 `holder` 比一次就知道该发终态。
本票不造 feed（票 05 的活），只把这条缝对齐并用用例钉住：`sessions_test` 里「放掉会话 ⇒ 行没了」，
`claims_test` 里「正常退出 ⇒ 行没了」「被杀 ⇒ 行留着且下一次接手会明说」。

### 验证

**后端全量：1028 tests / 12355 assertions / 0 failures / 0 errors**（2026-09-22，本 worktree，exit 0）。
新用例三处，各有各的理由：

- `test/harness/cap/claims_test.clj`（10 个用例 59 条断言）：行的三个事实、重复 `take!` 换 token、
  活着的陌生进程被具名拒绝、死 pid 与**复用 pid**两种陈旧、按 token 释放、`hand-over!`、`release-all!`
  不碰别人的行、退出钩子只装一次；再加**两个真 JVM**：第二个进程（`sessions/touch!` 走真正的出生路径）
  活着时本进程被点名拒绝 → 正常退出 ⇒ 行没了、本进程接手 → 再起一个 **`destroyForcibly`** ⇒ 行留着、
  `holder` 答 nil、接手时日志里有一行 `taken-over`。这一条是整票唯一一件进程内测不了的事
  （`alive?` 问的是 OS，退出钩子只有真的退出才跑）。
- `test/harness/edge/sessions_test.clj`（+4 个用例）：出生即认领且条目带着 token；`drop!` 与**空闲扫除**
  都交还；别人正服务的会话在这里**生不出来**、被拒之后表里没有残留条目；陈旧行被接手。
- `test/harness/edge/http_test.clj`（+3 个用例）：门里 409 且 body 点名 pid/`since`、日志**一个字节没长**
  （等文件稳定再比——记录是异步的，`pending?` 空了之后行还可能在落盘路上，实测 20KB 的
  `model/start` 晚 3ms 才落）；读路由（列表、`rebuild`）照常 200；没人服务时同一条 run 立刻能跑；
  复用 pid 的陈旧行不拒任何东西；改绑别的进程正服务的会话 ⇒ 409 且绑定一动不动。
- `test/harness/infra/db_test.clj` 与 `test/harness/cap/hashline/store_test.clj` 的表清单/列名守卫跟着
  加了 `session_claims`（这两条守卫就是设计来逼人写清「为什么它是状态不是记录」的）。

**判据没变的那一半**：`running?` 的既有用例（同会话第二条 run 的拒绝）全绿——本票补的是跨进程那一半，
没有替换它（判断 2）。**UI 一行没动**：非 owner 的页面今天拿到的就是「降级」——它读的是 `rebuild` /
`sofar`，没有增量；真要发动作会拿到那句 409，落进既有的错误卡。

### 边界（写给票 05/06/07）

- **feed 的终态还没人发**（票 05）：本票给的是缝（token = generation + 「行没了」这个事实），
  终态那一帧是 feed 自己的事。
- **`POST /api/project` 里只有「具名改绑」被挡**：`dir` 必填，所以走这条路一定会移动日志；
  「解除绑定」这条 HTTP 路今天不存在，等它出现时同样要问认领。
- **`sessions` 表的**其余**写入没有新增第二份**（判断 7）：锚点、待办仍然只存在库里按 thread-id 分家，
  本票在进程内只多了一个 `:claim` 字符串（token），没有多出任何对话内容。
