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

`POST /api/agent` 收一个 `RunAgentInput`，以 SSE 回帧。两条 http-kit 的规矩必须同时成立：

- **status 与 headers 骑在第一次 `send!` 上**，不能先单独发一次 header；
- **最后一帧带 `close-after-send?`**——单独走一条 close 路径会丢掉缓冲里没冲出去的body。

body 是 **UTF-8 字节**（本机 JVM 默认 GBK，交字符串给 http-kit 等于对非 ASCII 掷硬币）。

**每次 run 一个 converter、一个 emitter。** converter（`ag_ui/outbound`）持有「哪条消息开着」的状态机，
逐事件重建它会把每条消息 id 重置、重复发 START 帧——AG-UI 客户端视为致命。

**发出去的帧分三族**：`RUN_*`（一次 run 的起与终，含 `RUN_ERROR`）、`TEXT_MESSAGE_*` / `REASONING_*` /
`TOOL_CALL_*`（对话本身）、以及 **`CUSTOM`**——AG-UI 自己的扩展点，本仓拿它发一种东西：**注入物**
（`name` 是 `injected-context`，值里是那条消息、id 是确定性的）。**这一族是唯一不进那场对话的**：适配器把
`CUSTOM` 落成一个 `data` part，而交给下一轮的会话那一份没有它（`sessions/messages` 把 `data` part 摘掉）
——于是注入物看得见、又**进不了**模型的向量（见 [client](client.md#注入物在会话栏里的一张卡)）。上面那五种只落审计行的事件照旧一个帧都不发。

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

`CUSTOM` 帧剩下的那一半仍是**这一轮自己派生出来的**注入——技能正文、后台作业的结尾——它们的 id 是
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
| `/api/agent` | POST | **AG-UI run（流式）** | 下面那些 |
| `/api/model` | GET | 本会话服务的模型收什么、出什么、多大 | 无（只读） |
| `/api/model` | POST | 换本会话的 provider / model / 思考档（`clear` 退回配置档） | `provider/session-changed` |
| `/api/choices` | GET | 三个选择器可以摆出来的东西：现状、厂商与 model、可选的思考档 | 无（只读） |
| `/api/skills` | GET | **技能列表**：本会话的根分组（每组带层与根路径），每行带名字、描述、能不能用与原因 | 无（只读） |
| `/api/settings` | GET | 只读的生效配置：三个旋钮与**各来自哪一档**、家目录路径与它是哪条规则给的、哪几份文件在、有没有 key（只有有没有、来源与**凭据名**） | 无（只读） |
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
| `/api/threads/<stem>/sofar` | GET | **记录到哪了**：已记下的消息 + 三个状态（`running` / `parked` / `settled`）。在跑时返回半轮（含没有结果的调用），**不写一个字**；被切断（没有终帧且本进程没在跑它）**按名字拒绝**并指向 rebuild。服务端持有这场会话时读**内存**，但**有 run 正在跑时仍读记录**——那一刻「到哪里了」的答案在文件里。它**不是客户端的轮询**：有窗口的页面由 feed 报（见下），只有**没有窗口**的那几扇门在自己驱动的一轮结束后读它一次。与 `rebuild` 的分界：那条是「交给我、我接手」（会合上、会写），这条是「给我看看」 | 无（只读） |
| `/api/threads/<stem>/feed` | GET | **窗口那条流**（SSE）：首帧是尾页（带 `since` 连上时是它之后的增量），此后**每落盘一批推一帧**，窗口结束时一帧 `end`。**有 run 正在跑时条目也从记录读**（见下），所以刷新/新开一个窗口看得到那一轮已经写下的帧。`since` 落在窗口外或 generation 不对 ⇒ **在推任何字节之前** 409，body 带当前值；会话被另一个活进程服务 ⇒ 409 点名（推送意味着持有） | 无（只读） |
| `/api/threads/<stem>/page` | GET | **窗口那一页**：没有 `beforeSeq` 是尾页，有它是读者手上最老那条**之前**的一页（一次一页）。活着的会话读内存（**有 run 正在跑时读记录**，见下），不活着的读记录——向前翻页是一次读，不需要是服务这场会话的那个进程 | 无（只读） |
| `/api/threads/<stem>/trajectory` | GET | **模型每一轮看到了什么**：system 消息的字节、拼在它旁边的指令文件与技能清单、每条用户消息、每次工具调用的参数与结果、每轮发出去的工具表；折自记录（见下）。带 `:behind` | 无（只读） |
| `/api/threads/<stem>/archive` | POST | 归档 / 取消归档（一个路由两个方向，body 说方向） | 无（日志必须一字节不动） |
| `/api/threads/<stem>/stats` | GET | **会话统计**：这条会话的记录折出来的几个数（轮 / 模型调用 / 用量 / 缓存命中 / 输出速度），composer 下面那条状态条读它。带 `:behind`（= 还有几批没落盘，为 0 时不出现） | 无（只读） |
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
`stats` / `trajectory` / `sofar` / `feed` / `page` 是 GET——前三个只读日志，后两个是窗口那两条
（见下）。

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
**它不写任何东西**，所以它是这条形状上唯一一个 GET；同一个 stem 问多少次都只是再读一遍日志。
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

### 窗口：`feed` 与 `page`

**一场会话可以长到不该整份发出去**（ADR 0003），所以它多两条路，读的是**同一段窗口**的两个方向：
`GET /api/threads/<stem>/page[?beforeSeq=N]` 一次一页，`GET /api/threads/<stem>/feed[?since=N&generation=G]`
一条流。**推与拉是同一个窗口的两个方向，不是两个接口**：三个动词——尾页 `tail`、增量 `since`、
补页 `before`——由 `harness.edge.sessions` 里**同一组纯函数**算出来，所以 `baseSeq` 与 `hasMore`
只有一处说了算。

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

**活着的会话从内存读，死掉的从记录读。** `rebuild` 与窗口那两条在服务端持有这场会话时读**内存**
（内存是权威，记录允许落后）；`sofar` 有一个例外——**有 run 正在跑时它读记录**，因为那一刻内存里
可能还缺正在写的那些帧，而它答的是「记录到哪里了」。`stats` 与 `trajectory` 始终读记录（它们折的
是记录的聚合与内容），所以它们带 `:behind`：落后几批由这个数说出来，读的人自己决定要不要等
（今天没有人因此等：状态条画的就是记录折出来的那几个数）。

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

**没有构建时那句 404 是特指的**：`/` 与 `/index.html` 答一段 `text/plain`，写明它找过的目录与
填它的命令（`cd ui && npm run build`），因为 `no such route: /` 是一句**关于错的东西**的真话。
别的未知路径照旧答 JSON 那条 404——这样「表里不认的路径答什么」就不取决于这台机器上碰巧有没有
构建产物，测试也才敢断言它。

## jsonl 审计行

一个线程一个文件，写在**它项目的 workspace** 里。

**一行只有两种**（`.scratch/jsonl-two-kinds`，2026-09-21 拍定）：`{type, payload, ts, runId}`，`type` 是

- **`message`** —— **送给大模型的那个 messages 数组里的一个元素**（2026-09-21，主人更正：`message` 就是那个
  数组的超集）。所以人和 LLM 的话是 `message`，**注入物也是**（开场块、技能正文、作业结尾各是数组里的
  一条），而 system 消息同样是——它就在那个数组的第一位。payload 就是交给厂商/厂商返回的那个 map，
  **逐字**（信封上的键一个都不进 payload）；一条条目的**身份永远在信封上**（`id`），不进 payload；
- **`event`** —— **其余一切事实**。线上发过的帧（`RUN_*` / `TEXT_MESSAGE_*` / `TOOL_CALL_*` /
  `CUSTOM injected-context`）的 payload **就是那一帧**；harness 自己知道的事实（下面表里的那些）
  包成一个 **CUSTOM 帧**，`name` 是那种事实的名字，payload 是它当时知道的东西。

判据是这一句：**同一段记录，当时线上发过什么帧，重建就得到什么帧。** 读者**严格**：顶层出现 `kind`
（旧契约）、缺 `payload`、`type` 不是这两个之一、不是对象、半行 JSON —— 都**按行号抛异常**，
理由写在 `:reason` 里；旧记录打开时报的是"这份记录是旧契约，请开一场新的会话"，不是"读不出来"。

**事实的名字就是下面这张表的第一列**（它同时是那一行 `payload.name` 的值，也是 `replay/kind` 的答案）：

| 事实（CUSTOM 帧的 `name`） | 何时 |
|---|---|
| `tools/pre-execute` / `execute` / `post-execute` | 工具生命周期三相，按 `toolCallId` 键控，**不上 wire** |
| `model/start` | 一次**模型调用**开始：`:model` / `:base-url` / `:reasoning-effort` / `:context-window`（目录声明了才记，前三个同）与 `:tools`（**照发出的那张工具表**，没有表就不写这个键），**不上 wire** |
| `model/end` | 同一次调用结束：`:usage` / `:finish-reason` / `:model`，**厂商的键名逐字**；这次调用什么都没报时载荷是空对象，**不上 wire** |
| `approval/decided` | 人对一个 park 调用的答复 |
| `provider/init` | 每 thread 恰好一行，首次 run；含**选择**（三个旋钮）、**来源**（`default` / `request` / `inline`）与**解析结果** `:resolved` |
| `provider/changed` | 会话中 provider 档变更：`:before` / `:after`（本次按下的旋钮）、`:override`（按完之后 session 这一档的完整形状）、`:trigger`、`:resolved` |
| `project/bound` | 绑定变更，before → after（可读成目录时间线） |
| `session/rebuilt` | 重建动作，落**被重建的那份日志**上 |
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
- **system 消息是 `message` 行**：每场会话的第一条带全文与 `:hash`，hook 改动后第一条再带一次全文，
  其余每轮一条只带 `:hash`——`hash` 是这一轮真正交给模型那串字节的 SHA-256，回答「这轮和上轮读的是不是
  同一句」（prefill / prompt cache 靠那条前缀稳定）。
- **被主动放弃的一件事实**：`input` 行的 payload 里还带着当时的**请求体**（`:provider` / `:model` /
  `:tools` / `:context`，即"客户端要的是什么"）。行删掉后这份事实**没有新家**：记录只答"这次跑的是哪一档"
  （`provider/init` / `provider/changed` 的 `:resolved`）。
- **两种方言，出口一种**：条目行是厂商形状，帧折出来的消息是 AG-UI 拼法，`replay/history` 要一份厂商
  向量——`ag/provider-messages` 因此是**幂等**的（`provider-shaped?` 认出已是厂商消息就放过），折出来的
  消息带的身份 `:id` 由 `ag/strip-identity` 在 `history` 里去掉。

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

## 入站翻译：parts 与图片

入站消息的 `content` 可以是字符串，也可以是 parts，而两个协议对 parts 的拼法不同。
**翻译发生在 `harness.edge.ag-ui/inbound`**，不是 `llm`——因为 `message` 行的契约是「LLM 真实看到的，逐字」，
到协议层才翻会让那条日志撒谎。

**开场块不从这里进消息向量**（`.scratch/session-opening` 的修正）：它们在会话出生时由
`edge/http.clj` 写进对话的 `:added`、位置在那条提问**之后**，所以
**system → 提问 → 开场块（指令文件 → 技能清单 → 技能正文）**（见
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
否则「每一轮开头就带着的注入」（上一轮加载的技能正文、两次 run 之间结束的作业通知）在会话栏里没有
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
