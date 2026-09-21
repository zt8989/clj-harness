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
| 03 | 输入面：`messages` 退役 | 01, `session-after-refresh` 票 05（跨特征） | 五样逐条落（表在 ADR 0002 决策 9）；动作是 feed 的写侧；前端 adapter 的**写侧**改掉 |
| 04 | 认领：一个 thread 归一个进程 | 01 | 锁文件／库里一行 owner；后到的只读或拒绝；**认领易主 ⇒ generation 作废**；非 owner 的只读页面有没有增量 |
| 05 | 会话 feed：尾页、增量、补页 | 01 | **一条 source 三个动词**（`tail` / `append` / `prepend`）；序号可从记录重放；`since` 与 generation；`rebuild` / `sofar` 的 live 语义读内存；放掉时的终态 |
| 06 | 副本：窗口、补页、刷新 | 03, 05 | 窗口 `{entries, baseSeq, hasMore, revision}`；「显示更早」那颗按钮；补一页 vs 重开的两种处置；刷新走内存；打字 / 滚动的锚定 |
| 07 | 收口：两条铁律、状态表、文档、报数 | 02–06 | `overview.md` 铁律 1 与 3 的新措辞 + 状态表（+ 窗口那一行）；`edge.md` / `client.md` / `kernel.md` / `skills-and-instructions.md` 的推导；`CONTEXT.md` 术语；两套全量 + 走查证据；落地记录 |

## 状态

**2026-09-20 立票，同日按 ADR 0003 重切为七张票。** 票 01、02 已落地；03–07 `ready-for-agent`，等前置。

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
