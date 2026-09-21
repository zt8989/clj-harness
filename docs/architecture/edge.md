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
不走 ring 响应（见 `runner`），所以那条路由把同一个头交给 `runner`。

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
（`name` 是 `injected-context`，值里是那条消息、id 是确定性的）。**这一族是唯一客户端不回发的**：适配器把
`CUSTOM` 落成一个 `data` part，而回发转换只带 text / reasoning / tool-call——于是注入物看得见、又**进不了**
下一轮的请求（见 [client](client.md#注入物在会话栏里的一张卡)）。上面那五种只落审计行的事件照旧一个帧都不发。

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
| `/api/threads/<stem>/rebuild` | POST | 重建对话交还客户端；日志若停在半途，先合上**每一条**没终结的 run（按 run id 认；各补 `TOOL_CALL_RESULT` + `RUN_ERROR`）再重建 | `session/rebuilt`，合上过则每一轮先有一行 `session/closed-off` |
| `/api/threads/<stem>/sofar` | GET | **至今为止的对话**：客户端**轮询**用的只读读法——已记下的消息 + 三个状态（`running` / `parked` / `settled`）。在跑时返回半轮（含没有结果的调用），**不写一个字**；被切断（没有终帧且本进程没在跑它）**按名字拒绝**并指向 rebuild。与 `rebuild` 的分界：那条是「交给我、我接手」（会合上、会写），这条是「给我看看」 | 无（只读） |
| `/api/threads/<stem>/archive` | POST | 归档 / 取消归档（一个路由两个方向，body 说方向） | 无（日志必须一字节不动） |
| `/api/threads/<stem>/stats` | GET | **会话统计**：这条会话的记录折出来的几个数（轮 / 模型调用 / 用量 / 缓存命中 / 输出速度），composer 下面那条状态条读它 | 无（只读） |
| `/api/projects` | GET | 侧边栏的数据，**两块一次给全**：`{projects: [每个项目 + 它的会话], tasks: [未绑定的会话，平铺]}`。任务 = 库里没有项目**且不记得任何目录**的会话。**每一行都只由库回答**：`firstUserText`（`sessions.title`，第一次收到消息的那次 run 写的、**只写一次**）、`lastSentAt`（`sessions.last_sent_at`，**每一次 run 的 input 到达时重写**）、`archived`、归属；排序按 `lastSentAt` 降序、NULL 沉底。唯一不是库的是 `running`（进程内的 live-runs 注册表）。这里**不再 stat 任何日志**：体积与 mtime 都退场了，也不再为任务走那棵树——刷新从此是一次 SELECT 加一次注册表查（`.scratch/store-backed-sidebar/spec.md`） | 无 |
| `/api/projects` | POST | 让一个目录成为项目（find-or-create） | 无 |
| `/api/sessions` | POST | 让一条会话**存在**（`{threadId}`，find-or-create）：库里没有就插一行未绑定、无记忆的会话；已经有就原样不动（**不会解绑**）。「新建任务」与 `POST /api/agent` 第一次收到陌生 thread id 时各调它一次，所以「一条会话什么时候成为这个家的一条会话」只有一个答案 | 无（只写库里一行，不开任何文件） |
| `/api/projects/<canonical-path>/remove` | POST | 移除项目（= 解绑它的会话，不删日志） | 无 |
| `/api/mcp` | GET | MCP 账本：服务器、状态、工具清单 | 无（只读） |
| `/api/mcp` | POST | 本会话启停一个 MCP 服务器 | `mcp/server`（带 `disabled`，runId null） |
| `/api/elicitation` | GET | 某个悬置的问题问的是什么、要填什么 | 无（只读） |

规矩三条：

- **审计行跟着「日志」走，不跟着「写入」走。** 会动日志的路由留痕（绑定搬日志、重建读日志）；
  **只改库里一行、或只改一份配置的路由一行都不写**——归档 / 取消归档、添加项目、移除项目都属此类，
  `config.edn` 的四条写入路由（`/api/providers` 两条、`/api/providers/models`、`/api/defaults`）同理：
  写配置既不搬日志也不读日志，所以它们与「加一个项目」是同一类。运行时的「我到底被谁服务」
  由既有的 `provider/init` 行回答，够用。
  归档这条尤其是有意的：它必须让 jsonl **逐字节、逐 mtime 不动**，写一行审计就会毁掉
  「归档不是删除」的那条证明。所有 GET 都是只读，同样一行不写；`/api/settings` 是其中最严格的一个
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
`stats` / `trajectory` / `sofar` 是 GET（三个都只读日志，见下）。

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

**`stats` 折的是审计那一半，不是对话那一半**（`harness.edge.stats`）：轮数来自 `input` 里**新的用户消息 id**，
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
它读 `input` + `message` + `tools/*`，回答「**模型每一轮到底看到了什么**」——system 消息的字节、
拼在它旁边的指令文件与技能清单、每条用户消息、每次工具调用的参数与结果，以及每一轮发出去的工具表。
它与 `stats` 是同一份文件的两个读者：`stats` 数数（不读一条消息），它看内容（不数一个数）。
两半的边界是**轮的判据**：两处都调**同一个**「这个 input 带来哪些用户消息 id」的实现
（`stats/user-ids`），所以数出来的轮与分出来的组不会各说各话。
**它按次序判轮、不认 `input` 行**：悬置恢复会写第二个 `input`（同一个 runId、没有新用户消息），
那是同一轮的续，不是新的一轮。

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

一个线程一个文件，写在**它项目的 workspace** 里。每行 `{ts, runId, kind, payload}`：

| kind | 何时 |
|---|---|
| `input` | 收到的 RunAgentInput，原样 |
| `event` | 发出的每个 AG-UI 帧 |
| `message` | LLM 真实看到/返回的 provider 形状消息，**逐字**。**submitted 侧 = 第一次模型调用真正收到的那一份**（开场块、技能清单、`/<名字>` 的技能正文都在里面），returned 侧 = 内核在那之后追加的；两半按**条数**切开，所以那一步注入必须发生在记 submitted 之前 |
| `tools/pre-execute` / `execute` / `post-execute` | 工具生命周期三相，按 `toolCallId` 键控，**不上 wire** |
| `model/start` | 一次**模型调用**开始：`:model` / `:base-url` / `:reasoning-effort` / `:context-window`（目录声明了才记，前三个同）与 `:tools`（**照发出的那张工具表**，没有表就不写这个键），**不上 wire** |
| `model/end` | 同一次调用结束：`:usage` / `:finish-reason` / `:model`，**厂商的键名逐字**；这次调用什么都没报时载荷是空对象，**不上 wire** |
| `approval/decided` | 人对一个 park 调用的答复 |
| `provider/init` | 每 thread 恰好一行，首次 run；含**选择**（三个旋钮）、**来源**（`default` / `request` / `inline`）与**解析结果** `:resolved` |
| `provider/changed` | 会话中 provider 档变更：`:before` / `:after`（本次按下的旋钮）、`:override`（按完之后 session 这一档的完整形状）、`:trigger`、`:resolved` |
| `project/bound` | 绑定变更，before → after（可读成目录时间线） |
| `session/rebuilt` | 重建动作，落**被重建的那份日志**上 |
| `hook/<Point>` | 一次 hook 触发（`hook/PostToolUse`、`hook/InstructionsLoaded`…） |
| `mcp/server` | 一个 MCP 服务器的连接结果、失败、重连或启停（**只有变化才落行**，落在 `:run/done`） |

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
- **重建对话的代码只认 `input` / `event` 两种行**；其余是审计轨迹，不是对话的一部分。
  **审计轨迹有自己的读侧**：`harness.edge.stats` 折 `input` 与 `model/*` 出这条会话的几个数
  （`GET /api/threads/<stem>/stats`，见下）。两种读侧读的是同一条日志的两半，谁也不读对方的那半——
  「只认两种行」是 `harness.edge.replay` / `harness.kernel.frames` 的规矩，不是所有读者的规矩。

## 入站翻译：parts 与图片

入站消息的 `content` 可以是字符串，也可以是 parts，而两个协议对 parts 的拼法不同。
**翻译发生在 `harness.edge.ag-ui/inbound`**，不是 `llm`——因为 `message` 行的契约是「LLM 真实看到的，逐字」，
到协议层才翻会让那条日志撒谎。

它也是**开场块进入消息向量的那一处**：4-arity 收下已渲染好的块，拼在**客户端消息之后**——注入物的位置是
**system → 提问 → context → skill context**（见 [skills-and-instructions](skills-and-instructions.md)）。
它收到的 system 文本也是**已经组装好的**（`harness.cap.system-prompt/assemble` 的结果，见 [hooks](hooks.md)）。
它自己不读任何文件、不跑任何 hook（两样都是递进来的），所以这个命名空间仍是个转换器；空块时它返回
**原向量本身**，而不是一个等价的副本——那是「什么都没配的会话与从前逐字节相同」这条回归保证的形状。
见 [skills-and-instructions](skills-and-instructions.md#看得见但仍然不是会话的一部分)。

**会话自己的注入不在 `inbound` 里，在它之后**：`/<名字>` 要的技能正文由 `harness.cap.project/before-llm`
折进来，而 `harness.edge.http/run-agent!` 在**记 `message` 行之前**先施加一次——submitted 侧因此就是模型
真收到的那一份（内核每次模型调用前还会再施加，幂等；内核自己插一条就会把按条数切的两半顶偏）。
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
