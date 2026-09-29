# 边：`harness.edge.http`

一个 http-kit 服务器，**一个前缀**（`/api`）下两条边共用一个 handler：流式的 **AG-UI 边**
（`POST /api/agent`）与普通的 JSON **管理边**（`/api/*` 的其余部分）。两者是**答案的形状**不同
（SSE / JSON），不是路径不同——run 端点从前在服务器**根上**，路由表认不出的任何路径都落到它，
于是一个打错的管理路径会被当成一次没有 `RunAgentInput` 的 run；现在它是一条**明写的路由**，
`/api` 之外认不出的路径答 404（`no such route: ...`）——唯一在路由表之外、又不算「认不出」的
是根上那一页：`ui/dist` 里的构建产物由 `harness.edge.ui` 发出（见「根上那一页」）。

CORS 按**请求自己带来的 `Origin`** 判，不是按启动时定下的一个值：本机的页面一律放行
（`localhost` / `127.0.0.1` / `[::1]`，**主机名整串匹配、不比后缀**，端口不参与判断），并把它
**原样答回去**；别的 origin **一个头都不给**——浏览器自己会拦，替它编一个 origin 只是撒谎。
不带 `Origin` 的请求（curl、套件、同源部署）答的是 `ui-origin`，也就是 `--ui-origin` 命名的那个
（`start!` 收 `:ui-origin`）；那个选项现在只剩一种用处：**UI 不在本机**时把它的 origin 明说，
本机的页面不需要谁说——所以 dev 脚本已经一个端口都不用报了。

**决定只有一处**：`handler` 把这三个头并到路由交回来的响应上（包括那条 500 的兜底路），
`api-response` 因此完全不碰 CORS 头。唯一的例外是 SSE：它的状态与头**骑在第一帧上**、
不走 ring 响应（见 `runner` 与 `stream-feed!`），所以那两条路由把同一个头交给各自的流。

而它现在是 **dev 的主路而不是备用路径**——页面直连这个进程，浏览器发的就是跨域请求、有 preflight；
`ui/vite.config.js` 那条 `/api` 前缀规则还在（目标来自 `HARNESS_BACKEND_URL`），但 dev 循环不再经过它。
**理由是一次实测**：vite 8.3.0 自带的 http-proxy-3 1.23.3 转发 SSE 时偶发丢掉**最后一个 chunk**——
帧一个不少、终止块不来，浏览器的 fetch 永不落地，于是客户端永远停在「运行中」。数字与代价写在
`scripts/dev.mjs` 的头注释和 `README.md`。

所以端口不再是「同时改两处契约」的那件事——本进程绑哪个端口由 `--port` 决定（`0` = 随 OS 挑，
绑到的那个会被打印并记进日志）。

## AG-UI 边

`POST /api/agent` 收一个 `RunAgentInput`，**只起跑并回一个 ack（`{threadId, runId}`）**；这一轮的
AG-UI 帧从**页面级的下行** `events.mux` 到达（ADR 0004），发起的页面与看客读的是同一条流。
（2026-09-23 之前这里是「以 SSE 回帧」——那条响应体连同 `GET …/feed`、`GET …/follow` 两条流
已经一起删掉，见 `.scratch/events-mux-and-host/spec.md` 票 05。）

**每次 run 一个 converter、一个 emitter。** converter（`ag_ui/outbound`）持有「哪条消息开着」的状态机，
逐事件重建它会把每条消息 id 重置、重复发 START 帧——AG-UI 客户端视为致命。

**帧的顺序就是模型自己的顺序**（2026-09-22 owner 拍定：*按 llm 顺序渲染*）。常见形状是
「思考 → 答案 → 工具调用」，但**厂商可以在答案开始之后又回到思考**（真会话实测：`reasoning_content`
→ 答案第一个 token → 同一段思考的尾巴 → 答案接着写）。所以 `REASONING_*` 那一组**不由答案的第一个
token 关闭**：它一直开着，晚到的 delta 落进**同一条** reasoning 消息，直到**这一次模型调用结束**
（`:model/end`）才收。提前关的代价是晚到的那段变成**第二条** reasoning 消息——页面上就是答案下面多出一行
`思考`（那条线上修过一次，见 `.scratch/thinking-row-tail` 复议三与 `.scratch/reasoning-order`）。
跨着答案开着的代价如实记下：那一行在整个回答期间都还是「正在想」（微光 + 实时窗），答案开始不等于思考结束。

**发出去的帧分三族**：`RUN_*`（一次 run 的起与终，含 `RUN_ERROR`）、`TEXT_MESSAGE_*` / `REASONING_*` /
`TOOL_CALL_*`（对话本身）、以及 **`CUSTOM`**——AG-UI 自己的扩展点，本仓拿它发**两件**人该看见、模型绝不能
被喂回去的事：**注入物**（`name` 是 `injected-context`，值里是那条消息、id 是确定性的）与**一次压缩**
（`name` 是 `compacted-context`，值里是那段摘要、它折了多少、估算多大，id 取压缩自己那个 UUID）。
**这一族是唯一不进那场对话的**：适配器把
`CUSTOM` 落成一个 `data` part，而交给下一轮的会话那一份没有它（`sessions/messages` 把 `data` part 摘掉）
——于是两者都看得见、又**进不了**模型的向量（见 [client](client.md#注入物在会话栏里的一张卡) 与
[client](client.md#压缩在会话栏里的那张卡)）。压缩那一张**只讲屏幕**：模型读到摘要走的是记录上那条
`context/compacted` 事实的投影（`replay/model-nodes`），与这一帧无关。上面那五种只落审计行的事件照旧一个帧都不发。

**线上一帧不少，记录不是一帧不落**：`REASONING_START` / `REASONING_MESSAGE_START` / `REASONING_MESSAGE_CONTENT` /
`REASONING_MESSAGE_END` / `REASONING_END` 这五族**不写进记录**——同一段文字本来就在 run 自己那条 `message` 行
（信封 `:source "model"`）的 `reasoning_content` 上，折法（`replay/reasoning-row?` / `attach-reasoning`）从那里取回来，
折出来仍是那条 `role "reasoning"` 的消息，形状、位置、id 都不变（ADR [0009](../adr/0009-the-record-holds-a-thought-once.md)）。
理由是字节：五族占一份真记录 80% 的字节、92% 的行。

**出生那一轮把对话本身交给客户端**（`.scratch/session-opening`，2026-09-21 owner 拍定）：指令文件与技能
清单在会话出生时写进对话本身，**卡片随那条 entry 走**（`harness.edge.ag_ui/opening-entries` 给每条 message
同时带 `data` part 与 `text` part）。出生那一轮是**唯一**没有窗口、也没人跟 feed 的一轮——自己开出这一页
的客户端既没有窗口也不跟 feed——所以那一轮随 `RUN_STARTED` 之后发一帧 **`MESSAGES_SNAPSHOT`**
（`ag_ui/conversation-snapshot`），内容是**这一轮写进对话的那些 entry**，投影成 AG-UI 能收的
消息形状：`id`、`role`、`content` 是**文本**。不流这一下，那一页就只有提问、没有开场。

**为什么不是「每个条目发一张 CUSTOM 卡」**（先是那么写的，实测被打回）：AG-UI 的 `CUSTOM` 帧是个
**part**，适配器把它挂到**正在流的那条消息**上，帧自己的 `messageId` 在入口处就被丢了——客户端手里没有
那条消息时，卡就落到答案底下、而不是记录把它放的那一列。`MESSAGES_SNAPSHOT` 是 AG-UI 为「这就是那段
对话」准备的帧，消息、id 一起走，落位由消息自己决定。

**投影是必须的**（`ag_ui/wire-message`）：`@ag-ui/client` 对**它解析的每一帧**做 schema 校验，而它的消息
schema 要 `content` 是文本或输入块——本仓的 entry 带的是 part 向量，一个 `data` part 会当场把这一轮打死
（实测：界面上一条 Zod 报错）。所以快照只带 `id`/`role`/文本：**卡由读者按 id 和文本自己画**
（`ui/src/lib/injections.ts`），而文本正是卡里那份字节——服务端两样都是从同一个 block 建的。
**客户端自己发的消息不用投影**，它本来就是客户端那次转换的产物、也就是校验它的那份 schema 认可的形状。

`CUSTOM` 帧剩下的那一半仍是**这一轮自己派生出来的**注入——人的 `/name` 要的技能正文、后台作业的结尾——它们的 id 是
`<run>-pre<i>`（`pre` = 第一次调用**之前**就折进去的），身份是「这一轮开始时手里就有的」。
内核自己中途拼进去的那些（`:context/injected`，工具调用的产物）用 **`<run>-ctx<n>`**——**两族的拼写必须不同**：
两边都从 0 数，而帧的 id 正是记录把卡折成消息时用的名字（`apply-frames`，再经 `replay/append-new` /
`sessions/append!` 先到先得），撞了就是**两张卡折成一张**、重建后少一条注入。

### bind 的 hook sink

这是**唯一**同时知道「这是哪个线程」和「审计行写哪」的地方，所以 run 作用域的 hook sink 在这里绑定：

```clojure
(binding [hook/*sink* {:thread-id .. :audit (fn [payload] (log! ..)) :run-id ..}] ...)
```

**这个 binding 包住 set-up，不只是包住 run。** set-up 里有**两**件事都靠它才活着：折叠会话的指令文件
（`InstructionsLoaded`），以及组装 system 文本（`SystemPrompt`——`harness.cap.system-prompt/assemble`
就在这里被调，它随后被交给 `ag_ui/inbound`）。两者都发生在第一条消息组装**之前**——binding 摆在
set-up 之后，这两个点都会拿到 nil sink、永远静默。这是「点声明了却永不触发」在接线层面唯一的一次近失，
记在 [hooks](hooks.md#27-个点全部是数据)。

**没绑 = hook 不触发**，这是刻意的默认值：离线工具、replay、直接驱动内核的测试都没有审计写入者，
而一个没人记录的 hook 判定比没有 hook 更糟——它会**静默地**改变一次 run。

## 路由表

**表里认不出的路径一律 404**（`no such route: <路径>`），不再落到 run 上——见文件头那段。
**先问页面、再答这条 404**：`GET`/`HEAD` 且不在 `/api` 下的请求会先落到 `harness.edge.ui`，
`ui/dist` 里有这个文件就发它；没有才由这条 404 收尾（见下一节）。

| 路由 | 动词 | 干什么 | 落审计行 |
|---|---|---|---|
| `/api/agent` | POST | **AG-UI run：起跑并回 ack（`{threadId, runId}`）；帧走 `events.mux`** | 下面那些 |
| `/api/model` | GET | 本会话服务的模型收什么、出什么、多大 | 无（只读） |
| `/api/model` | POST | 换本会话的 provider / model / 思考档（`clear` 退回配置档） | `provider/session-changed` |
| `/api/choices` | GET | 三个选择器可以摆出来的东西：现状、厂商与 model（每个厂商带**有没有密钥**这一件事，不带值）、可选的思考档 | 无（只读） |
| `/api/skills` | GET | **技能列表**：本会话的根分组（每组带层与根路径），每行带名字、描述、能不能用与原因 | 无（只读） |
| `/api/settings` | GET | 只读的生效配置：三个旋钮与**各来自哪一档**、家目录路径与它是哪条规则给的、哪几份文件在、有没有 key（只有有没有、来源与**凭据名**） | 无（只读） |
| `/api/language` | GET | 这个家说的语言（`en` / `zh`），由 `harness.infra.language` 按 `config.edn` 的 `:ui :language` → 系统语言 → 终端语言 → 英语解析。**不带 threadId**：语言是这个家的事实，不是会话的 | 无（只读） |
| `/api/language` | POST | 选这个家说的语言：写 `config.edn` 的 `:ui :language`（先校验整份配置、再原子写、留 `.bak`），回传**解析后**的值；不认的值是 400 加服务端那句话，一个字节不写 | 无 |
| `/api/security` | GET | 这一家点名的**敏感路径**：写下来的原样（`~/.ssh/`，不展开）、是这一家写的还是内置的、以及内置清单本身（好让面板能「恢复默认」）。**不带 threadId**：这是这个家的事实，不是会话的 | 无（只读） |
| `/api/security` | POST | 换**整份**敏感路径清单：先校验整份配置、再原子写 `config.edn` 的 `:security`（留 `.bak`），回传**读回来**的清单；`[]` 是「什么都不守」，不认的值是 400 加服务端那句话，一个字节不写 | 无 |
| `/api/providers` | GET | 目录现成一份给设置表单：每条带**来源**（内置 / 你的 / 你的补丁）、endpoint、它声明的 model、凭据名与密钥事实，另带可选协议、思考档与 `:default` 现状 | 无（只读） |
| `/api/providers` | POST | **新建或改写一条** provider：先校验整份新配置，再原子落盘（密钥写进 `.env` 的一行） | 无（见下） |
| `/api/providers/<id>/remove` | POST | 从 `:providers` 里去掉一条；`:default` 正指着它就先拒（那会把家变成每轮都跑不起来） | 无 |
| `/api/providers/models` | POST | 问厂商要它的 model 列表——**本特征唯一出网的路由**，密钥可由表单带；测试缝 `providers/*list-models*` | 无 |
| `/api/defaults` | POST | 设**默认档**（`:default` 那三个旋钮）：缺席 = 不动那一项，`null` = 清掉那个键；先解析后写 | 无 |
| `/api/git` | GET | 本会话目录作为工作树：当前分支、本地分支、脏改动条数 | 无（只读） |
| `/api/git` | POST | 把本会话目录切到某个分支（脏树与占用由 git 自己拒绝，原话回传） | `git/branch` |
| `/api/project` | GET | 绑定目录（未绑定答 `null`） | 无 |
| `/api/project` | POST | 绑定 / 换绑 / 解绑（`dir: null`，upsert，`{threadId, dir}`）。**它同时把那条会话的日志搬过去**（一个会话一份文件，见 [home-and-storage](home-and-storage.md#一个会话一份文件)），所以这一段与写日志的那条路**同一把锁**：读旧绑定、写库、搬文件都在 `log-lock` 里，而写记录的那条路（`log!`）也在同一把锁里决定「这条记录写哪个文件」。没有这一点，一次落在 run 中途的 bind（**发送才建会话**之后这是常态）会和写手抢同一个重命名：轻则一句假的拒绝，重则一次会话被劈成两份 | `project/bound`（runId null） |
| `/api/project/pick` | POST | 开 OS 原生目录对话框，**不绑任何东西** | 无 |
| `/api/threads` | GET | 日志树的原始清单（诊断用） | 无 |
| `/api/threads/<stem>/rebuild` | POST | 重建对话交还客户端；日志若停在半途，先合上**每一条**没终结的 run（按 run id 认；各补 `TOOL_CALL_RESULT` + `RUN_ERROR`）再重建。服务端持有这场会话时**从内存答**，而且**不修不合**——合上是对**死掉的**会话的收尾 | `session/rebuilt`，合上过则每一轮先有一行 `session/closed-off` |
| `/api/threads/<stem>/fork-points` | GET | **这一场能在哪些行 fork**：每个 `step/end`，最老的在前，各带 `:seq`（记录行号）、`:at`（时刻）、`:tools`（那一步调了什么）与 `:compactionId`（**紧跟其后**发生的那次压缩，没有就是 null）——压缩跑在两步之间，所以「压缩前的那一刀」就是它前面那个 `step/end`。尾部截到 200 条，`:total` 说一共有多少 | 无（只读） |
| `/api/threads/<stem>/fork` | POST | **从某一步的结束另开一场会话**：body 里 `stepSeq`（`fork-points` 里的行号，切在它**之后**、**保留**这一行）；**不给**就取最后一个 `step/end`。新 thread-id、新记录文件（截点之前的行逐字复制），被截断的 run 补上终局；继承项目绑定，标题写成 `[fork] 原标题`，原会话**一个字节不动**。有 run 在跑 → 409；未知会话 → 404；给的行不是 `step/end` → 400 | `session/forked`（新会话），截断过则先有 `session/closed-off` |
| `/api/threads/<stem>/sofar` | GET | **记录到哪了**：已记下的消息 + 三个状态（`running` / `parked` / `settled`）。在跑时返回半轮（含没有结果的调用），**不写一个字**；被切断（没有终帧且本进程没在跑它）**按名字拒绝**并指向 rebuild。服务端持有这场会话时读**内存**，但**有 run 正在跑时仍读记录**——那一刻「到哪里了」的答案在文件里。它**不是客户端的轮询**：有窗口的页面由下行（`events.mux`）报，只有**没有窗口**的那几扇门在自己驱动的一轮结束后读它一次。与 `rebuild` 的分界：那条是「交给我、我接手」（会合上、会写），这条是「给我看看」 | 无（只读） |
| `/api/events.mux` | GET | **下行那条流**（WebSocket，ADR 0004）：一页一条，按 `?subscriber=<token>&sessions=<json>` 声明持有哪几场、各自从哪个游标开始；此后**每场被订阅的会话每落盘一批推一帧**（帧带 `threadId`），窗口结束一帧 `end`。**只推这条连接订阅的会话**——没订阅的会话一条都不推；token 随连接生、随连接死，服务端不记连接之外的订阅。run 的帧与子 agent 的帧也从这里下行 | 无（只读） |
| `/api/threads/<stem>/frames` | GET | **子 agent 重放的半边**（JSON）：这场会话的帧按运行时要读的顺序（`RUN_STARTED` 起头、`MESSAGES_SNAPSHOT` 随后、记录的帧按序）加一个 `:running`。面板读它、再从 `events.mux` 取实时尾巴，两边靠帧自己的 `:seq` 对齐（ticket 04） | 无（只读） |
| `/api/events.mux/subscribe` | POST | **改一条活着的下行的订阅集合**（socket 只下行，发不了订阅）：`{subscriber, subscribe: [{threadId, since, generation}], unsubscribe: [threadId]}`。连接已关闭或从未开 ⇒ 404 | 无（只读） |
| `/api/threads/<stem>/page` | GET | **窗口那一页**：没有 `beforeSeq` 是尾页，有它是读者手上最老那条**之前**的一页（一次一页）。活着的会话读内存（**有 run 正在跑时读记录**，见下），不活着的读记录——向前翻页是一次读，不需要是服务这场会话的那个进程 | 无（只读） |
| `/api/threads/<stem>/trajectory` | GET | **模型每一轮看到了什么**：system 消息的字节、拼在它旁边的指令文件与技能清单、每条用户消息、每次工具调用的参数与结果、每轮发出去的工具表；折自记录（见下）。**NDJSON 流**：首行是头（`:threadId` / `:incomplete` / `:behind`），其后一轮一行，`fold-trajectory` 折完一轮就吐一轮。**这场会话有人订阅（一条活着的下行）时它保持连接并接着推**，没人订阅就给完折好的那一份、结束——见下面「门铃归连接所有」 | 无（只读） |
| `/api/threads/<stem>/archive` | POST | 归档 / 取消归档（一个路由两个方向，body 说方向） | 无（日志必须一字节不动） |
| `/api/threads/<stem>/stats` | GET | **会话统计**：这条会话的几个数（轮 / 模型调用 / 用量 / 缓存命中 / 输出速度 + 上下文圈）。**三个来源按序**：进程内持有该会话时读它的活折；否则读 `sessions.numbers`（**一次 SELECT**，快照带 `:numbersAt` 说明它是哪个时刻写的）；都没有才折记录。`?fold=1` 强制折记录（修复/对照用的门）。带 `:behind`（= 还有几批没落盘，为 0 时不出现） | 无（只读，且**从不写**） |
| `/api/threads/<stem>/jobs` | GET | **本进程为这一场跑着的后台作业**：id、命令、状态、起点、记录的路径。读的是**进程内的作业注册表**，不是日志——没有作业、或作业随上一个进程死掉，都是 `:jobs []`（**不 404**）；状态就是记录末行（`[running]` / `[exit N]` / `[stopped]`，与 `job_output` 同一处出处） | 无（只读） |
| `/api/threads/<stem>/jobs` | POST | **人从面板停掉一条**：body `{job}`。停的是同一处（`cap.jobs/stop!`），但发起人是**人**——不认领「告知」，改在条目标 `:stopped-by`，于是下一通调用前多一条 `by="user"` 的注入。未知 id 是既有的 `unknown-job`（404）、坏 body 400；不带审批（照 `cancel`） | 无 |
| `/api/projects` | GET | 侧边栏的数据，**两块一次给全**：`{projects: [每个项目 + 它的会话], tasks: [未绑定的会话，平铺]}`。任务 = 库里没有项目**且不记得任何目录**的会话。**每一行都只由库回答**：`firstUserText`（`sessions.title`，第一次收到消息的那次 run 写的、**只写一次**）、`lastSentAt`（`sessions.last_sent_at`，**每一次 run 的动作到达时重写**）、`archived`、归属；排序按 `lastSentAt` 降序、NULL 沉底。唯一不是库的是 `running`（进程内的 live-runs 注册表）。这里**不再 stat 任何日志**：体积与 mtime 都退场了，也不再为任务走那棵树——刷新从此是一次 SELECT 加一次注册表查（`.scratch/store-backed-sidebar/spec.md`） | 无 |
| `/api/projects` | POST | 让一个目录成为项目（find-or-create） | 无 |
| `/api/sessions` | POST | 让一条会话**存在**（`{threadId}`，find-or-create）：库里没有就插一行未绑定、无记忆的会话；已经有就原样不动（**不会解绑**）。**这是「一条会话什么时候成为这个家的一条会话」唯一的答案**：页面自己铸的那枚 id 在第一句真正发出去之前由它登记一次（任务走这一条，项目会话走 `/api/project`——同样认调用方给的 id、同样幂等），这正是「点击新增不立刻会话，发送才新建」要的那一次；而 run 那条边对陌生 id 是 **404**、不再静默创建（`refuse-unknown-session!`，`.scratch/sessions-live-on-the-server` 票 03），所以登记必须发生在这次 run 之前 | 无（只写库里一行，不开任何文件） |
| `/api/projects/<canonical-path>/remove` | POST | 移除项目（= 解绑它的会话，不删日志） | 无 |
| `/api/mcp` | GET | MCP 账本：服务器、状态、工具清单 | 无（只读） |
| `/api/mcp` | POST | 本会话启停一个 MCP 服务器 | `mcp/server`（带 `disabled`，runId null） |
| `/api/elicitation` | GET | 某个悬置的问题问的是什么、要填什么，**以及谁在问**：`server` 是外部服务器（`cap.mcp` 转的），`askedBy` 是本仓工具自己问的（`ask`）。**两个键都不在场就是没人署名**——缺的键不出现，不是 null | 无（只读） |

规矩三条：

- **审计行跟着「日志」走，不跟着「写入」走。** 会动日志的路由留痕（绑定搬日志、重建读日志）；
  **只改库里一行、或只改一份配置的路由一行都不写**——归档 / 取消归档、添加项目、移除项目都属此类，
  `config.edn` 的四条写入路由（`/api/providers` 两条、`/api/providers/models`、`/api/defaults`）同理：
  写配置既不搬日志也不读日志，所以它们与「加一个项目」是同一类。运行时的「我到底被谁服务」
  由既有的 `provider/init` 行回答，够用。
  归档这条尤其是有意的：它必须让 jsonl **逐字节、逐 mtime 不动**，写一行审计就会毁掉
  「归档不是删除」的那条证明。所有 GET 都不落审计行——窗口那两条会让会话出生并认领它（推送意味着
  持有），可它们同样不碰日志；`/api/settings` 是其中最严格的一个
  ——它连自己问的那个会话都不动。
- **校验失败不留痕**，而且发生在任何写入之前——一条被拒的绑定不该在磁盘上留下半个痕迹，
  一条被拒的 provider 写法同样：一句服务端原话，`config.edn` 逐字节不动。
- **审计行的 `runId` 为 `null`** 表示这件事发生在任何 run 之外（绑定、重建）。

路径匹配是两段式：先是精确串匹配（上表前几行），然后是**带动词的通用形状**
（`/api/<collection>/<stem>/<verb>`，跟着一张 verb → handler 的表；三个 collection 的动词都是**闭集**）。

**`/api/providers/models` 是精确路由而不是动词，而且这是承重的**：它在 collection 之后只有一段，
那个通用形状要求两段，所以它永远匹配不到——掉进兜底就是 run 端点，也就是本文上面记着的那个
「body 根本不存在的 500」。精确匹配先于形状被试，这就是它必须写成精确路由的原因。

那个 stem 是各 collection 给行起的名字：**thread 用会话 id**（它同时是日志的文件名 stem），
**project 用目录的 canonical 路径**（不是那个整数 id——canonical 路径才是这个边里项目在各处的身份），
**provider 用它的 id**（它在 `config.edn` 里就是那个键，也是凭据名的来源）。
**这个形状上不该被服务的动词**由这里答 405，而不是掉进 run 端点——那正是它从前会变成一个
「body 根本不存在的 500」的原因。**方法说有没有副作用**：`rebuild` 与 `archive` 是 POST，
`stats` / `trajectory` / `sofar` / `feed` / `page` 是 GET——前三个只读日志，后两个是窗口那两条（见下）。
`jobs` 一个动词**两种方法**：GET 列本进程为这一场跑着的作业（只读注册表，不是日志），POST 停一条
（发起人是人）——方法说有没有副作用，这一条两种都有。

**一个叫 `models` 的 provider 与那条精确路由不冲突**：新建与改写走 collection（`/api/providers`），
删除走 verb 形状（`/api/providers/<id>/remove`），所以那条路径永远只可能是探询。
为它留一个保留字是一条没有失败可防的规矩。

`*directory-chooser*` 是测试缝：真实对话框要等人，测试里换 stub。
用 `alter-var-root` 而不是 `binding`，因为服务在**另一个线程**上跑（见 [client](client.md)）。
它底下还有一个缝 `*dialog-launcher*`——**画窗的那个进程**（argv 进、`{:out :exit}` 出），
两个缝是两层而不是一层：`chooser` 是「问人」这个动作，`launcher` 是「起进程」这件事，
所以 macOS 与 Windows 两支各自的判断可以被测（含起不来进程的那条路），而不需要真有一个人在窗前。

**选择器的答案是三态，而且这不是洁癖**：`:picked`（带路径）/ `:cancelled`（人关了窗）
/ `:unavailable`（这台机器上没有窗可开）。从前只有两态——打不开也答 `nil`，而 `nil` 在契约里
是「取消」，于是 Windows 上（那时只有 macOS 一支 osascript）点按钮**什么都不发生**，连一句错都没有。
`unavailable` 走 **501 + `:error`**（一句给 UI 显示的人话），客户端据此把手输路径那一行放出来；
`cancelled` 依旧是 200 + `{:dir nil}` 且**不留任何提示**——人关了窗不需要被通知，这条从今天起也没变。

**按平台分派**：macOS 走 `osascript`，Windows 走 PowerShell（`-NoProfile -STA` 里的 WinForms
`FolderBrowserDialog`），**其余平台在分派表里明说没有**，而不是落到默认支里用别人的命令失败。
外部进程那一半只有一件事要自己扛——**编码**：字节一律按 UTF-8 显式解码，PowerShell 侧显式设
`OutputEncoding` 为无 BOM 的 UTF-8，回来再剥一次 BOM（`clojure.string` 把字符串 match 当文本不当正则，
所以写在 pattern 里的 `^` 是字面量）。中文目录名、带空格目录名、`C:\` 这种带尾分隔符的
都要原样回来。

**归档为什么一个路由两个方向**：它是同一列的一次写入，两个方向只差一个布尔，两条路由就是两处会漂移的
机会。路径说动作，body 说方向。**重建与归档的差别也值得知道**：重建必须**找到**日志（它从日志里重建对话），
归档**不开文件**——那句话是会话的属性不是文件的属性，所以日志被手工挪走或删掉的会话照样能归档。

**`stats` 折的是审计那一半，不是对话那一半**（`harness.edge.stats`）：轮数来自客户端 `message` 行里**新的用户消息 id**，
步数来自 `model/start` 的行数，用量、缓存命中与输出速度来自那两行 `model/*`（速率的分母是每一对
`start`→`end` 的 `:ts` 差）。它**不读** `event` / `message` 行，`replay` 也**不读** `model/*`——
两种读侧各读一半，谁也不冒充另一半。
**折不出来的东西是缺席的，不是 0**：一份早于这两行的老日志只有轮数；一次没报用量的调用不进任何分母；
缓存字段没人报就没有那一格。客户端拿到的就是这个形状，它**不补**（composer 下面那条状态条读它，
见 [client](client.md)）。
**它容忍半行**：日志的最后一行可能正在被写，那行丢掉、其余照折——条子最常被问的时刻正是会话跑着的时候。
（`replay` 相反：重建宁可整个拒绝，因为少一截的对话比没有更坏。）
**它不写任何东西**，所以同一个 stem 问多少次都只是再读一遍——**读也不写库**，这条规矩比这张表老
（`docs/rules/panel-data.md`）：那些数由**运行自己**在 `model/end` 与 `:run/done` 两个写点上落进
`sessions.numbers`（`.scratch/session-numbers-in-the-store`，票 01），读侧只是把行读出来。
**所以「首次一次 SELECT」是这份统计的常规成本**：页面上打开一场别处正在服务的会话，不再走一遍日志。
**快照是「上次已知」不是「现在」**，答案带着 `:numbersAt`；`?fold=1` 是那扇「我不信这行，给我记录自己的
答案」的门（也不写回）。**`:incomplete` 只有折那一侧答得出来**（它是「这次读到的最后一帧是不是终局」），
快照没有它——两个读者各自说得出什么，就说得出什么。
「这份日志完了没有」是**两个读者共用的一条规则**（`stats/incomplete?`），不是各算一遍。

**同一个载荷里还带着 `context` 那一节**（`harness.edge.context`，见 [client](client.md) 里那颗圈）：
最近的**一次报过用量的调用**把模型的上下文窗口填到了多少（`:usedTokens` / `:windowTokens` / `:percent`），
以及填进去的**三样各占多少**（`:parts`：`system` / `tools` / `conversation`）。**没有第五个动词**：
一次读盘、两个折（`stats/records->stats` 与 `context/records->context`），一个载荷——圆圈与状态条
因此是同一个瞬间的两个说法。
**分子与分母来自同一次调用**：分母是那一次调用自己 `model/start` 行上的 `:context-window`（日志早于
这个键时才回退读 `provider/init` / `provider/changed` 的 `:resolved`），**不回目录现解**——换过 model
之后现解出来的会是新 model 的窗口，而分子是旧那一次的。
**三个篮子是摊出来的估算，分子分母不是**：厂商只报总数，所以三块按记录里各部分的字符数去摊那个总数
（同一口径计数），它们因此**恰好加起来**等于 `:usedTokens`——条子画不满就是在说假话。`prompt_tokens`
没报 `prompt_tokens` 的调用被跳过（不把上一次的数抹掉）、谁都没报过就没有 `:usedTokens`；**最后那次调用所在的 run**
还没写完**（没有终帧、或返回侧还没落盘）时只缺 `:parts`：厂商的数已经在记录里了，不拿半份消息凑一个三分。

**`trajectory` 折的是另外两半**（`harness.edge.trajectory`，`GET /api/threads/<stem>/trajectory`）：
它读 `message` + `event`（`tools/*`、`system-prompt` 都在其中），回答「**模型每一轮到底看到了什么**」——system 消息的字节
（**从 `:source "system-prompt"` 的 `message` 行里取**：全文一场会话只写一次，只有 hash 的那几轮靠往前带，见
[overview](overview.md#状态放在哪) 那张表）、
拼在它旁边的指令文件与技能清单、每条用户消息、每次工具调用的参数与结果，以及每一轮发出去的工具表。
它与 `stats` 是同一份文件的两个读者：`stats` 数数（不读一条消息），它看内容（不数一个数）。
两半的边界是**轮的判据**：两处都调**同一个**「这段记录带来哪些用户消息 id」的实现
（`stats/user-ids`），所以数出来的轮与分出来的组不会各说各话。
**它按段判轮，不认某种行**：悬置恢复会在同一个 runId 下再写一条 `system-prompt` 行（没有新的用户消息），
那是同一轮的续，不是新的一轮（`trajectory/run-segments` 的第三种开段情形）。
**跑着的那一段读 `tools/*` 那几行**（2026-09-27）：一轮的**返回侧 `message` 行是 `:run/done` 之后才写的**
（`log!` 一次把它们全交出去），所以只折 `message` 的读者**在一轮正在跑的时候画不出任何一次工具调用**——
看到提问和注入块，看不到它在干什么。而 `tools/pre-execute` 那几行**是当场写的**。所以轨迹给**仍然开着的那一段**
多折一次：**已到达、但这一段的返回侧还没有回答它的**调用画成一条 `tool` 条目（`trajectory/pending-tool-items`），
名字取审计行自己的 `:toolName`，结果**缺席**（不是空串）——返回侧落盘后，条目换成 `message` 行折出来的那条
（名字、参数、结果俱全），**一条调用始终只有一行**。**已经关掉的那一段不这么折**：它未回答的调用是**同一轮的
后一段**回答的（悬置与恢复是两段、一条工具消息），两段都画就是一次调用画两遍，还会把恢复的裁决读进悬置那半里。

### 窗口：增量在下行，补页在 page
**一场会话可以长到不该整份发出去**（ADR 0003），所以有一页一页的读：
`GET /api/threads/<stem>/page[?beforeSeq=N]` 一次一页（尾页或读者手上最老那条之前的一页），
**增量不再走一条自己的 SSE**——它随页面级的下行（`events.mux`）到达，帧带 `threadId`。三个方向——
尾页 `tail`、增量 `since`、补页 `before`——由 `harness.edge.sessions` 里**同一组纯函数**算出来，
所以 `baseSeq` 与 `hasMore` 只有一处说了算。

**窗口切在「批」的边界上，不切条目。** 一次动作（或一轮 run）的条目落在同一条 jsonl 行里，于是共享
一个 `:seq`；页因此既不重叠也不漏半批——`before` 严格切在读者手上最老那条之前，读者没拿到的条目必然
整批在前。一批比一页大就**整批拿走**（一轮不可拆）。`page-size` = **50，是一个判断**（照抄参考实现
的数）；把它写下来，「开一场会话的成本是一个上界、而不是它的长度」才是一句能兑现的话。

**有 run 正在跑时，窗口读记录，不读内存。** `settle!` 是**一轮结束时**才把这一轮的帧折进会话表的
（「半截答案不算一轮」，见 kernel.md），所以**没赶上收帧的页面**——刷新、新标签页、另一个进程——在
跑着的时候问表，只会看到提问和出生那几条，助手那一轮整片空白。而帧在写下那一刻就在记录里了
（`log!` 一帧一行），未收尾的那一组 `replay/entries` 也会 flush，所以「到此刻为止」的答案是**文件
答得出来的**。于是三条窗口路在**本进程正跑着这场会话**时改读记录（`read-entries` / `window-page`），
不新增任何存储：表仍是「这一轮结束了」之后的权威，两边**条目同 id**，读者按 id 去重，所以切换那一刻
不多一条、不跳一下。读的是 `replay/read-records`——**最后一行可能是半行**（写的人在追加），丢掉它才
是「已经到达的」的诚实答案；其余行仍严格读。本进程不持有这场会话、或它没有 run 在跑时，照旧读表。

**一张卡的名字要能在新进程里兑现。** park 只活在造它的进程里（kernel.md 的审批一节），而重建出来的
对话仍带着那个 interrupt id——于是换进程之后的卡片**两样都没了**：题面问不到（`GET /api/elicitation`
404，表都画不出来）、答案无处可去（resume 按未知名拒绝），人对着一个填不动的表单（每次重启都要新开
会话）。所以**两条读路在把对话交出去之前把 park 造回来**（`harness.edge.sessions/revive-parks!`，
`read-entries` 与 `window-page` 各一次）：id 取**对话自己写下的那个**、调用取同一条消息上的
`toolCallId`，park 的内容由 `harness.kernel.tools/repark!` 从调用的参数现推——推不出来的（服务器的
elicitation、这场会话已经不服务的工具）照旧没有，也就照旧被拒。**这一笔只写注册表**：记录一个字不动、
不取 claim、不建会话，id 已经 park 着的一律跳过（翻页因此不会把人在答到一半的问题重新 park，也不会
盖掉已经落下的决定）。

**而这件事的前提是那条消息上真写着 id。** `RUN_FINISHED` 的 interrupt 由 `apply-frames` 折到最后一条
assistant 的 `metadata.custom.agui.interrupts` 上，**但它只看得到自己那一个帧组**：一轮的帧会被它自己的
`message` 行切成两组（`entries-step` 在每条 message 行之前收组），而新一轮的写法正是**模型那条
`kernel-message` 行夹在工具调用帧与终帧之间**（2026-09-29 实测：会话 24b97ff5 的两个 park 都是这个
形状）——终帧那一组里一条 assistant 都没有，卡于是**整张从重建出来的对话里消失**，刷新之后连 404 都
没有。`replay/flush-group` 因此照**这一轮最后那条 assistant**（`:model-ids`，与它给模型行配对用的是
同一份读数）补挂一次：帧没被切开的记录写进去的还是原来那个值，被切开的那个补回来。

**五种帧，说的是「读者手里是什么」而不是「哪条路答的」**：`window`（feed 的开场：尾页）、
`append`（读者游标之后的条目——feed 的后续帧，或带 `since` 连上时的开场）、`page`（读者最老那条
之前的一页）、`tail`（没有游标的读者要的最新一页，`GET …/page`）、`end`（窗口结束：会话被放掉或
被接管）。除 `end` 外每一帧都带 `baseSeq` / `hasMore` / `cursor` / `generation` / `state`，
以及记录写不进去时的 `:record`（ADR 0002 决策 6）；`end` 带一个 `reason`。

**序号是记录自己的行号**，由写者在落盘时报出、不由谁预测，所以刷新与换进程之后同一个号还是同一个号。
也正因为号要到落盘才有，读者手里可以有一条**还没有号**的条目——**游标因此只前进到「已经落盘的最后一
个号」**，同一批条目可能在两帧里各出现一次，副本按消息 id 去重（它本来就按 id 认账）。

**两条拒绝发生在推任何字节之前**，而且是同一件事——读者手里的号属于一条已经不存在的窗口：
generation 不是这条窗口的（会话被放掉 / 被接管 / 换了进程），或者 `since` 比尾页还老（要「增量」
就等于要整场会话，那正是窗口要拒绝的成本）。两条都答 **409，body 带当前的 `generation` 与
`baseSeq`**，读者的下一步因此是同一步，而且做得到：丢掉手里的，重开尾页。

**一条 feed 一条连接，连接就是游标。** 服务端**不记谁在订阅**（ADR 0003 决策 7）：`watch!` 是一个
**只报事实、不报状态**的门铃，连接每次读都自己带 `since`，所以十几个门铃折成一次重读、漏一个门铃
只是晚一次读。**feed 就是推的**——旧说法里真正要保住的是「不按客户端记推送状态」，不是「服务端不
推送」。**连上也是一条动作**：feed 出生这场会话（第一次问就是出生）并认领它，因为推送意味着持有——
被另一个活进程服务的会话在这里是 409 点名，而不是半服务。连接同时是一根**钉子**：有窗口连着的会话
不被空闲扫除，否则一个开着超过半分钟的页面会被反复「重开」（连接断了由 http-kit 的关闭回调松开）。

**门铃归连接所有，而且这件事是问得出来的。** `watch!` 登记一个门铃时带着它的**主人**——一个 0 元的
「你还在吗」——主人说没了就不算读者：清扫那一拍**先 `prune-watches!` 再决定**放不放，于是没有一条
没人能再敲响的门铃能被当成读者留在内存里（`.scratch/memory-hygiene/` 票 02 实测：2 条活连接 / 5 条
订阅，却挂着 199 个门铃、钉住 32 场会话——`detach!` 那一行读的是整张表而不是那一行，所以它一个都
没松开；而 trajectory 那条流的读者，服务端根本看不见）。**一条普通流式响应的 socket 不能当主人**：
http-kit 对它**不报关闭**，读者走了以后 `open?` 还是 true、写还「成功」（实测）。所以 trajectory
那条长响应把**这一页的下行订阅**当主人（`harness.edge.mux/watching?`）：有人订阅这场会话它才继续
推，没人订阅就拿到折好的那一份、流结束、不留门铃——读者是 curl 也一样。

**记录在长也算「会话变了」。** run 进行中窗口读的是**记录**（内存要到终帧才把那半轮折进来），而记录
是**一行一行**长的：每一帧写下去，能答给读者的东西就多一段。所以**唯一那条写路径**（`http/log!`）每
写一行就在会话上留一个标记（`sessions/record-grew!`），真正的门铃在一个**每 100ms 的 tick** 上
（`harness.kernel.session/growth-interval-ms`）——**一次 tick 里的所有标记折成一个 ring**，因为一个
ring 的代价是读者把整场会话重读一遍，而那个标记是在 run 自己的帧循环里留的：按行 ring 会让一场长会话的
run 等在自己的日志后面。没人在看的会话不留门铃，它的读者下次连上时拿尾页。

**活着的会话从内存读，死掉的从记录读。** `rebuild` 与窗口那两条在服务端持有这场会话时读**内存**
（内存是权威，记录允许落后）；`sofar` 有一个例外——**有 run 正在跑时它读记录**，因为那一刻内存里
可能还缺正在写的那些帧，而它答的是「记录到哪里了」。`stats` / `trajectory` / 上下文圈这三条折的是
记录的聚合与内容，它们向**会话**要记录的字（票 05–07），不再自己开文件，所以它们带 `:behind`：
落后几批由这个数说出来，读的人自己决定要不要等（今天没有人因此等：状态条画的就是记录折出来的
那几个数）。


## 会话：机制在核心里，适配在这里

**`harness.edge.sessions` 是本文件里唯一不含机制的边命名空间**：会话的状态、两道订阅缝、窗口
算术、run / stop 的钉子全在 `harness.kernel.session`（ADR
[0005](../adr/0005-sessions-own-the-record-stream.md)、[layers](layers.md#一个机制在核心里适配在边上会话)），
这里只剩「记录是什么」这一层适配——**装缝 + 再导出**，没有一个自己的 atom。

**它装五道缝**（`sessions/install!`，组合根 `start!` 调）：`:build` 是会话出生那**一次走查**
（`replay/fold-sofar`：对话、状态、压缩/剪枝事实，加上每个登记过的折子，一条流折完）；
`:model-messages` 是能交给 provider 的那份对话（`replay/model-view` 脱卡 + `compacted-messages` 折
压缩与剪枝）；`:read` / `:fold` 是记录的字怎么定位、怎么读、怎么折（`replay/locate` /
`read-records` / `fold-records`）；`:claim` 是 `harness.cap.claims`。

**两道订阅缝由机制定义、消费者只登记**：读流 `register-fold!`（`(fn [acc ctx [line-index row]] acc)`，
装会话时按行喂，结果落在会话行上，`fold-value` 取）；写流 `register-step!`（唯一写入口
`http/log!` 每写一行叫一次 `row-written!`，订阅者原地推进）。**`log!` 不认识任何一个具体消费者**
——它只把行交给会话。压力表（`harness.edge.pressure`）是第一个订阅者，它的 `install!` 登记表针的
折子与 step。

**铁律：run 进行中内核不读自己的记录。** 会话一生只读一次（出生那次 `:build`），run 里只有订阅者
的实时 step；所以 `log-pressure` 只要一个 thread-id，它连文件都没有可读的。

## 根上那一页：`harness.edge.ui`

**后端自己发页面**，这是这一节存在的原因：`clojure -M:run` 起的那一个进程既能答 `/api`，也能把
`ui/dist`（`npm run build` 的产物）当静态资源发出去，于是「一个进程、一个地址」是一种能跑的模式，
而不只是部署才有的形状。页面的地址本来就默认是**它自己的 origin**（`ui/src/lib/threads.ts` 里
`VITE_AGENT_URL ?? "/"`），所以这么发出来的页面**不带我们的地址、也不发跨域请求**。

它**不是 dev server**：没有 TSX、没有 Tailwind 扫描、没有 HMR——改过 `ui/src` 要重新 `npm run build`。
`scripts/dev.mjs` 那条 vite 的路一字节没变，两条路各管各的。

目录来自 `:ui-dist`（命令行 `--ui-dist DIR`），不给就是**当前工作目录**下的 `ui/dist`——`clojure -M:run`
只在仓库根上解析得到 `:run` 这个别名，所以这个默认值就是它该在的地方。目录里没有 `index.html`
就叫「没有页面」，不是错误：那时这个进程照旧只服务 `/api`。启动横幅第二行会说清是哪一种。

三条规矩，每条都是拒绝：

- **只答 `GET` / `HEAD`**：`POST` 到一个文件路径不是那个文件。
- **`/api` 下一个字节都不碰**：管理边拥有那个前缀，打错的端点必须还是指名道姓的 JSON 404，
  不能被一个文件顶掉，更不能让调用方拿到一页 HTML 去当 JSON 解析。
- **不回落 `index.html`**：这个应用**没有客户端路由**（`ui/src` 里没人读 `window.location`），
  所以一个指不到文件的名字就是什么都没有——拿壳去答会把每个笔误变成 200。要加回落，先加路由。

路径在 `getCanonicalFile` 之后按**路径分量**（`Path.startsWith`）判是否还在根内，所以 `..`、
URL 编码过的 `%2e%2e`、以及指向树外的符号链接都在**这里**被拒（不是被发出去）；字符串前缀匹配
会把 `/root/../elsewhere` 和 `/rootfoo` 一起放行。`/assets/*` 是 vite 的**带哈希**产物，答
`immutable` + 一年；别的（壳）答 `no-cache`——壳的名字跨构建不变，而指新哈希的正是它。

**页面是压着出去的。** 客户端在 `Accept-Encoding` 里说了收 gzip，`harness.edge.ui` 就现折
（`gzipped`），没说就一个字节不动——答一个没要过 gzip 的客户端 gzip 是**坏文件**，不是小文件。
实测（2026-09-29，本仓自己的 `ui/dist`）：1.52 MB 的 bundle → 428 KB、102 KB 的样式表 → 16 KB、
1.6 KB 的壳 → 835 B，整页 1.62 MB → 445 KB；代价是每次请求一次约 160 ms 的折（浏览器此后按
`immutable` 存一年，所以一页只付一次）。两条边界：只有**折得动**的类型才折——大小决定不了这件事，
PNG / woff2 / `.gz` 本来就被压过，再折只会**更大**（实测 1 KB 随机字节 → 1047），所以按类型判；
以及只有过了**下限**的文件才折（gzip 自己的头尾就 ~20 字节，实测 24 字节的体折完是 44）。
于是同一个 URL 可能有两种体，折得动的类型一律带 `Vary: Accept-Encoding`。折好的那份**不留在
进程里**，与上面「什么都不缓存」同一条规矩：改过 `ui/src` 重新构建，下一次请求就是新的。

**没有构建时那句 404 是特指的**：`/` 与 `/index.html` 答一段 `text/plain`，写明它找过的目录与
填它的命令（`cd ui && npm run build`），因为 `no such route: /` 是一句**关于错的东西**的真话。
别的未知路径照旧答 JSON 那条 404——这样「表里不认的路径答什么」就不取决于这台机器上碰巧有没有
构建产物，测试也才敢断言它。

## jsonl 审计行

一个线程一个文件，写在**它项目的 workspace** 里。

**一行只有两种**（`.scratch/jsonl-two-kinds`，2026-09-21 拍定）：`{type, payload, ts, runId}`，`type` 是

- **`message`** —— **送给大模型的那个 messages 数组里的一个元素**（2026-09-21，主人更正：`message` 就是那个
  数组的超集）。所以人和 LLM 的话是 `message`，**注入物也是**（开场块、人的 `/name` 要的技能正文、作业结尾各是数组里的
  一条），而 system 消息同样是——它就在那个数组的第一位。payload 就是交给厂商/厂商返回的那个 map，
  **逐字**（信封上的键一个都不进 payload）；一条条目的**身份永远在信封上**（`id`），不进 payload；
- **`event`** —— **其余一切事实**。线上发过的帧（`RUN_*` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` /
  `CUSTOM injected-context` / `CUSTOM compacted-context`）的 payload **就是那一帧**；harness 自己知道的事实（下面表里的那些）
  包成一个 **CUSTOM 帧**，`name` 是那种事实的名字，payload 是它当时知道的东西。

判据是这一句：**同一段记录，当时线上发过什么帧，重建就得到什么帧——除了推理那五族**（它们不落行，同一段思考靠模型那条
`message` 行的 `reasoning_content` 折回来，ADR [0009](../adr/0009-the-record-holds-a-thought-once.md)）。读者**严格**：顶层出现
`kind`
（旧契约）、缺 `payload`、`type` 不是这两个之一、不是对象、半行 JSON —— 都**按行号抛异常**，
理由写在 `:reason` 里；旧记录打开时报的是"这份记录是旧契约，请开一场新的会话"，不是"读不出来"。

**事实的名字就是下面这张表的第一列**（它同时是那一行 `payload.name` 的值，也是 `replay/kind` 的答案）：
**一次调用的请求侧落在它自己的信封里**（`.scratch/record-envelopes` 票 04，2026-09-28）：这一轮**为某一次调用
写下、且在调用前就拼好的那几行**——system prompt、这次动作带的条目（客户端的提问、指令更新）、run 自己派生的
注入、以及「请求将发出时有多满」那条压力读数——写在 `model/start` **之后**、`model/end` **之前**。所以读到一对
`model/*` 就知道这次调用手里有什么，不必自己攒；而 **`message` 行之间的数组顺序照旧**（prompt 仍是第一条）。

**做法**：边照旧在调用前把它们拼好，但**攒着不写**，等到这次调用的 `:model/start` 到达（信封开的那一刻）一次
写下。攒住的是**写**而不是想法，因为 `log!` 当场答行号、`land-at!` 拿那个行号给条目编号——编号因此仍然指着行
真正所在的地方。**代价说一次**：请求不再「发出前就在盘上」，它落在它所服务的那次调用开始的那一刻（几十毫秒之后）。

**没有调用也一行不丢**：run 收尾时（没有 provider、进门就被拒）照样把它写下去——人的提问必须在记录上，哪怕这一轮
根本没跑起来。**旧记录照旧**：信封之前的形状（请求写在 run 自己的行之前）与新形状折出**同样的轮、同样的条目**
（`harness.edge.trajectory-test` 有一条用例把两者并排比）。


| 事实（CUSTOM 帧的 `name`） | 何时 |
|---|---|
| `tools/pre-execute` / `execute` / `post-execute` | 工具生命周期三相，按 `toolCallId` 键控，**不上 wire** |
| `model/start` | 一次**模型调用**开始：`:model` / `:base-url` / `:reasoning-effort` / `:context-window`（目录声明了才记，前三个同），加工具表的**签名**：`:tools-names-hash`（工具**名字**集合的 SHA-256——改描述不动它，加删工具才动）/ `:tools-count` / `:tools-bytes`（`context/size-of` 的字符数，给上下文圈画数）。**整张工具表不在这一行**（票 04：runtime 配置，一轮里一字不差重复几百遍，曾占整份日志四成）——它落在 system 那条 `message` 行的**信封**上（`:tools`，整张表，见下）。表为空时不写这三个键。**两处都在**：照旧进记录，**并且上会话那条下行**（ADR 0006 决策 4），线上的载荷就是这一行的载荷 |
| `model/end` | 同一次调用结束：`:usage` / `:finish-reason` / `:model`，**厂商的键名逐字**；这次调用什么都没报时载荷是空对象。**两处都在**（同上），而线上的那一份多一层 **`numbers`**：到这一刻的 `steps` / `usage` / `cacheHitPercent` / `outputTokensPerSecond` / `context`——它是**会话自己那几份折叠**当时的答案（`stats-get` 答的就是它们），所以线上不是第二份真相，是同一个答案早一点到 |
| `turn/start` / `turn/end` | 一轮的两端。**只上会话那条下行，不进记录**：轮的边界在记录里由「没见过的 `:source "client"` user 行」算得出来（`harness.edge.stats/user-ids`），再写一行就是同一件事的第二份。`turn/start` 在那条 user 行**写入之前**发（行号就是它将要拿到的那一行）；`turn/end` 在**返回尾巴落地之后**发，带 `{turnId, calls, messages, seqFrom, seqTo}`——`calls` / `messages` 是 `harness.edge.turn` 那份**按轮**的折叠（客户端 `lib/turns.ts` 的 `turnCounts` 是同一套读数）。**parked 的一轮不收口**：`run/interrupt` 不是终局，带着人答复回来的那个 run 关的是**同一轮**（ADR 0006 决策 3） |
| `step/start` / `step/end` | **一步**的两端：一次模型请求，加上**它调的那些工具**（ADR 0011）。**两处都在**：各写一行记录，**并且上会话那条下行**——这一族的 `:seq` 是**它自己那一行**的行号（不像 `turn/*` 是借来的，因为它本来就在记录里）。`step/start` 在请求发出之前发（**停止检查之后**：一个被停的 run 不该开一步它收不了的步）；`step/end` 在这一步那些调用**都有了结局之后**发，带 `{:tools [{:id :name} …]}`——每个调用后来怎么了在**它自己**的 `tools/*` 行上（同一批 id），不在这里说第二遍。一次工具也没调的步，`step/end` 紧跟在 `model/end` 之后。收口有四条路，都收：工具都答了 / 停止（被切掉的调用先拿 cut-off 答案）/ **悬置** / 失败（内核那个 `catch`）。**悬置关的是同一步**：回答人的那次请求是**下一步**——步不跨 run，轮才跨。这条也不是 AG-UI 帧（`harness.edge.ag-ui/step` 对它是空操作） |
| `approval/decided` | 人对一个 park 调用的答复 |
| `provider/init` | 每 thread 恰好一行，首次 run；含**选择**（三个旋钮）、**来源**（`default` / `request` / `inline`）与**解析结果** `:resolved` |
| `provider/changed` | 会话中 provider 档变更：`:before` / `:after`（本次按下的旋钮）、`:override`（按完之后 session 这一档的完整形状）、`:trigger`、`:resolved` |
| `project/bound` | 绑定变更，before → after（可读成目录时间线） |
| `session/rebuilt` | 重建动作，落**被重建的那份日志**上 |
| `session/closed-off` | 一次 mid-run 收尾：`:run-id` / `:last-frame` / `:frames`（补了哪几帧）。rebuild 与 fork 截断都用它；fork 时多一个 `:via "fork"` |
| `session/forked` | 一次 fork 动作，落在**新会话**的日志上：`:from`（原会话）、`:compactionId`（切在哪一次压缩**之前**） |
| `hook/<Point>` | 一次 hook 触发（`hook/PostToolUse`、`hook/InstructionsLoaded`…） |
| `provider/session-changed` | 会话档位随会话绑定变化（`:resolved` 落新的一档） |
| `git/branch` | 一次 git 分支探测的结果 |
| `mcp/server` | 一个 MCP 服务器的连接结果、失败、重连或启停（**只有变化才落行**，落在 `:run/done`） |

`message` 行的契约（`.scratch/jsonl-two-kinds` 票 02，2026-09-21）：

- **一条 `message` 行 = 数组里的一个元素**，payload 是**厂商读到的那一份**（AG-UI 的 `image` 在记录里就是
  `image_url`，卡已经去掉，reasoning 折进它后面那条 assistant）——`ag/provider-messages` 就是写侧用的那一翻。
  翻出来是空的条目（单独一条 `reasoning`）**不写行**。
- **信封上的 `:source` 说这条是谁放进数组的**：`client` / `injection` / `opening` / `skill` / `job`
  （以上是数组进来的一侧），`system-prompt` / `model` / `tool` / `skill` / `job`（返回的一侧）。
  屏幕上的卡因此能直接说出自己是哪一族，不必去猜标签。
- **`:id` 是条目的身份**（信封，不进 payload），与帧的 `messageId` 同一套命名：开场条目
  `session-opening-<i>`、出生 context `session-context`、客户端的 `u1`、助手的 `msg-*`。
- **一次动作写了哪几条 = 那些 `message` 行**，顺序就是它们进数组的顺序；`input` 行不再存在，
  它原先答的「身份 / 边界 / 出生 context 与绑定」分别由行信封、行序 + `RUN_*` 帧、`event` 行回答。
- **`message` 行按数组顺序写**：一次 run 的第一条 `message` 行**一定是** `role=system`（它就是数组的第
  一个元素），然后是这个人自己的话（`client` / `injection` / `opening`），再是这个 run 的产物（`model` /
  `tool`）。`provider/init` 是 `event` 行，写在前面，所以读者仍然先遇到「这场会话由谁服务」。
- **system 消息是 `message` 行**：每场会话的第一条带全文与 `:hash`，之后每条 run 都写自己那条（组装每
  run 现算）——`hash` 是这一轮真正交给模型那串字节的 SHA-256，回答「这轮和上轮读的是不是同一句」
  （prefill / prompt cache 靠那条前缀稳定）。信封上另有 `:hooks-names-hash`（这一轮参与组装的 hook
  **身份**集合的 SHA-256，票 04，压力表判「前缀断没断」的那半格；`prompt.md` 不参与签名）与 `:tools`
  （**整张工具表**，名字 + 描述 + parameters）——表**不进正文**（放了模型就读第二遍、白付 token），在
  信封上：`replay/payload` 把信封挡在消息外，所以**记录里回读得到、模型读不到**。
- **送出去的指令是哪一份，记录说什么就是什么**（`.scratch/instruction-updates`）：`:replace` 时 `message[0]` 就是
  这一轮的新全文；`:in-place` 时 `message[0]` 是**上一轮那份**（一个字节不动），新全文作一条 `role=developer`
  的 `message` 行，插在这个 run 的 `client` 行之后、新提问之前，信封 `:source` 是 `"instruction-update"`。
  它**不是会话条目**（`replay/entries` 与 `trajectory/entry-row?` 都不收），但**在模型读到的那个数组里**，
  所以它折进 pressure 的「这个 run 自己的注入」、在 context 圈里算进 conversation 那一桶。system 那条行的
  信封因此还带 `:instruction-updates`（这一轮实际用哪一档）——`:sig`（hooks 的 hash）分不出「同档下 hook 变了」
  与「跨档切换」，交付方式这一格才分得出（票 06）。
- **被主动放弃的一件事实**：`input` 行的 payload 里还带着当时的**请求体**（`:provider` / `:model` /
  `:tools` / `:context`，即"客户端要的是什么"）。行删掉后这份事实**没有新家**：记录只答"这次跑的是哪一档"
  （`provider/init` / `provider/changed` 的 `:resolved`）。
- **两种方言，出口一种**：条目行是厂商形状，帧折出来的消息是 AG-UI 拼法，`replay/history` 与活着的会话
  （`sessions/model-view` → `ag/inbound`）都要一份厂商向量——`ag/provider-messages` 因此是**幂等**的：
  折进来的第一步 `ag-ui/absorbed` 先摘掉**只有 wire 才留的字段**（`ag-ui-only`：条目的 `:id`、`metadata`
  这些），剪掉之后 `provider-shaped?` 只按「拼法」判——部件的类型在厂商自己的表（`provider-parts`）里、
  没有 camelCase 工具字段——是就原样放过。`:id` 不再算一种拼法，是因为 2026-09-21 的一次事故：
  `replay/entries` 会把条目的 `:id` 盖回消息上，而 `:id` 被当成「还有 AG-UI 拼法」时，记录里那条已经翻好的
  `image_url` 会被**再翻一次**，第二次翻译按名字拒绝它——**会话里有过一张图，就再也发不出下一句**。

几条支撑性的事实：

- **append 由一把锁串起来。** 大多数写入来自 run 的单个消费线程，但 hook 在它自己的点上触发
  （`PostToolUse` 跑在那次调用的线程上），两条线可能同时在飞。半行不是更短的记录，是一个坏掉的文件。
- **`provider/init` 记的是「解析结果」，不是事后重算的结果。** 目录会变（base-url 改了、model 表更新），
  拿今天的目录去重算旧日志，读出来的就是今天的答案而不是那天的——两个数字（上下文窗口、最大输出）
  也在同一条理由里：内置表以后改了，旧日志仍说得出「当时这个模型声称多大窗口」。
- **两行都记「选择」而不是「端点」**：`:before` / `:after` 是本次按下的那一刀，`:override` 才是按完之后
  整个会话档的样子——回放者拿到 `:override` 就能还原「按完 session 长什么样」。
  被人工**否决**的变更**不落此行**，所以「有没有这一行」就是批准与否的判据。
- **api-key 只以 `:api-key :stripped` 出现**——是「被剥掉了」这个事实，永不出现值。
- **`model/start` 与 `model/end` 按次序配对**：一个 run 里第 n 条 `model/start` 就是第 n 次调用，
  序号**不记**——记一份就是同一件事实的第二份，两份必然会漂。时长由两条行自己的 `:ts` 差出来，
  也不新记时间戳字段。**承载这次调用的参数与用量的是这两行**，不是帧：客户端在对话里一个字都看不到它们。
- **重建对话的代码只认 `message` / `event` 两种行**（`input` 行在 `.scratch/jsonl-two-kinds` 票 02 里删掉了）；
  其余是审计轨迹，不是对话的一部分。
  **审计轨迹有自己的读侧**：`harness.edge.stats` 折 `message` 与 `model/*` 出这条会话的几个数
  （`GET /api/threads/<stem>/stats`，见下）。两种读侧读的是同一条日志的两半，谁也不读对方的那半——
  「只认两种行」是 `harness.edge.replay` / `harness.kernel.frames` 的规矩，不是所有读者的规矩。

**模型面只有一份，锚点比的是签名。** 「模型看的」（能直接交给 provider 的那个数组）是记录的一个**纯投影**：
`harness.edge.replay/model-message` / `model-view` 一处实现（客户端面 `entries` 上的卡在这里脱掉），
**压缩**（`harness.edge.compaction`）与**压力表**（`harness.edge.pressure`）都消费它，谁都不另写一份——
2026-09-24 那次压缩 422 就是把客户端面当模型面交了出去（见 [ADR 0004](../adr/0004-a-call-keeps-the-tables-signature-not-the-table.md)）。
压力表的**锚点**（拿厂商上次报的 `prompt_tokens` 当基准、只估增量）也只在**信封没变**时采用，而「变没变」由**签名**
回答：工具表的**名字集合**（`model/start` 上的 `:tools-names-hash`）+ 路由 + hook 的**身份集合**（system 行信封上的
`:hooks-names-hash`）。system 是每轮现装的，所以比的是签名，不是那段文本。

**读记录是流式的（票 06）。** `harness.edge.replay/read-lines` 一行一行读（UTF-8 显式，读完即关文件），
`lines->records` 是懒的，`fold-records` 把 reader 关在自己里面——**折的人不物化整份**。`read-records` 仍返回
vector，但也是建在流上：一行坏在中间照样按行号硬失败，最后那一行写了一半就丢掉（旧契约见上）。

**压力表不再每轮读整份（票 03）。** run 开头那次测量（`context/pressure` 那行，以及 check 阈值要不要压缩）读的
是**表针**（`harness.edge.pressure` 的 band），不是记录：`harness.edge.http/log!`——每行都走的那条路——
把每一行顺手喂给 `pressure/meter-row!`，band 就地更新（只认四种行：真 run 的 `model/start`、报 usage 的
`model/end`、system 行、run 自己的注入）。**一个进程里第一次问某个会话**才会折一次记录来装 band
（`seed-band!`），此后都是 O(1)。读数与离线折出来的答案**同答案**：`records->pressure` 自己就是
`meter-of-records` + `state->pressure`，两条路跑的是**同一套算术**（ADR 0002 决定 2 那条「历史初次从记录重建、
之后在内存里操作」在这里兑现）。压缩那一步仍然要读记录（免费的 prune 与 lock 检查都要它），但它**只在
band 已经报过阈值之后**才读——没过阈值的 run 一次盘都不碰。

## 入站翻译：parts 与图片

入站消息的 `content` 可以是字符串，也可以是 parts，而两个协议对 parts 的拼法不同。
**翻译发生在 `harness.edge.ag-ui/inbound`**，不是 `llm`——因为 `message` 行的契约是「LLM 真实看到的，逐字」，
到协议层才翻会让那条日志撒谎。

**开场块不从这里进消息向量**（`.scratch/session-opening` 的修正）：它们在会话出生时由
`edge/http.clj` 写进对话的 `:added`、位置在那条提问**之后**，所以
**system → 提问 → 开场块（指令文件 → 技能清单），末尾再接着人的 `/name` 要的正文**（见
[skills-and-instructions](skills-and-instructions.md)）。四元里那个 `context` 是「会话出生的那一条」的旧口子：
生产调用点今天一律传 nil（出生的那条已经在对话里了）；真传的时候它落在对话之后。
它收到的 system 文本是**已经组装好的**（`harness.cap.system-prompt/assemble` 的结果，见 [hooks](hooks.md)）。
它自己不读任何文件、不跑任何 hook（两样都是递进来的），所以这个命名空间仍是个转换器；空块时它返回
**原向量本身**，而不是一个等价的副本——那是「什么都没配的会话与从前逐字节相同」这条回归保证的形状。
见 [skills-and-instructions](skills-and-instructions.md#看得见但仍然不是会话的一部分)。

**会话自己的注入不在 `inbound` 里，在它之后**：`/<名字>` 要的技能正文由 `harness.cap.project/before-llm`
折进来，而 `harness.edge.http/run-agent!` 在**记 `message` 行之前**先施加一次——submitted 侧因此就是模型
真收到的那一份（内核每次模型调用前还会再施加，幂等；边在记之前不施加一次，submitted 侧就不是模型真收到的那一份）。
**这一次施加也取 diff**，和内核在每次调用前取的是同一个：两侧各为自己新加的那些消息发一张卡，
否则「每一轮开头就带着的注入」（上一轮 `/name` 留下的正文、两次 run 之间结束的作业通知）在会话栏里没有
任何东西替它说话——它们每一轮都被重新折进来，而折进来的那一处不是内核。

```
AG-UI 入站                                        出网（OpenAI 兼容 chat-completions）
{:type "text" :text "…"}                        → {:type "text" :text "…"}          同形，原样
{:type "image" :source {:type "url"  :value u}}
                                                → {:type "image_url" :image_url {:url u}}
{:type "image" :source {:type "data" :value b64 :mimeType "image/png"}}
                                                → {:type "image_url" :image_url
                                                   {:url "data:image/png;base64,<b64>"}}
```

**认不出的 part 类型（如 `:document`）指名报错**——既不静默丢弃，也不原样发出
（原样发出等于把问题推给厂商那个什么都不指名的 400）。第二个协议出现时，这里是拆分接缝。

**模态守卫**：模型声明 `:input #{:text}` 而入站消息带图片 → 在**调用厂商之前**以 RUN_ERROR 终止，
错误里点名 model id 与越界模态（厂商自己的答复是请求已发出之后的一个 400，body 里什么都不指名）。

- **未声明即不拦**：inline provider 没写 `:input` 就是什么都没承诺，替它猜会让每个直接描述 endpoint 的
  部署开始失败于一条没人写下来的规则。
- **性质是流程纪律，不是安全边界**：给一个纯文本模型写 `:input #{:text :image}` 照样打得出去。
  这道闸省下的是一次白跑的请求与一个看不懂的错误，不是防住谁（与项目围栏同一定性）。

## 不在生产路由里的东西

**服务端不为测试长路由。** `npm test` 要控制模型说什么，用的是**文件**：`dev/harness/e2e_server.clj`
在遇到**新的 threadId** 时重读脚本文件。生产边一个测试专用路由都没有。

## 记录是一条流：一个写队列、两个生产者、两种读法

**队列的主人是 `harness.infra.stream`，不是这条边**：「字节在盘上，或者这一行被攒住、在它落地之前不再写
任何东西」是关于 IO 的承诺。**同步答号**（`push!` 的返回值就是 `seq`，也就是文件里的行序），写不进去时
**按线程攒住**、`retry!` 重投、**不许留洞**；`go` 块里不许调它。（ADR [0012](../adr/0012-the-record-is-a-stream.md)）

**两个生产者，一个号源**：内核 push 它自己的消息（见 [kernel](kernel.md#内核写它自己的消息drained-栅栏)），
这条边 push 请求批、线上帧与事实。**每一行说它从哪来**：信封上的 `:producer` 六值
（`record` / `kernel-event` / `kernel-message` / `request` / `frame` / `fact`），读者因此不必按位置猜。

**两种读法挂在同一条流上**：**推流** = `listen!`（属进程而不属某条会话的用 `listen-every!`，
**必须在加载时挂**：挂晚了的会话看起来就是「记录从没长过」）；**拉流** = `consume!` / `after`
（游标就是行号；`after` 是有界短环，长缺口读文件——文件存在的理由）。**写入者不认识任何读者**：
`log!` 只写，会话的 live step 与窗口的 mark 是挂在流上的监听器。
