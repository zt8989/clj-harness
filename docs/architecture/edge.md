# 边：`harness.edge.http`

一个 http-kit 服务器，两条边共用一个 handler：流式的 **AG-UI 边**（`POST /`）与
普通的 JSON **管理边**（`/api/*`）。CORS 只放行 `http://localhost:5173`（那是契约，不是偏好）。

## AG-UI 边

`POST /` 收一个 `RunAgentInput`，以 SSE 回帧。两条 http-kit 的规矩必须同时成立：

- **status 与 headers 骑在第一次 `send!` 上**，不能先单独发一次 header；
- **最后一帧带 `close-after-send?`**——单独走一条 close 路径会丢掉缓冲里没冲出去的body。

body 是 **UTF-8 字节**（本机 JVM 默认 GBK，交字符串给 http-kit 等于对非 ASCII 掷硬币）。

**每次 run 一个 converter、一个 emitter。** converter（`ag_ui/outbound`）持有「哪条消息开着」的状态机，
逐事件重建它会把每条消息 id 重置、重复发 START 帧——AG-UI 客户端视为致命。

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

## 管理边：路由表

| 路由 | 动词 | 干什么 | 落审计行 |
|---|---|---|---|
| `/` | POST | AG-UI run（流式） | 下面那些 |
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
| `/api/project` | POST | 绑定 / 换绑 / 解绑（`dir: null`） | `project/bound` |
| `/api/project/pick` | POST | 开 OS 原生目录对话框，**不绑任何东西** | 无 |
| `/api/threads` | GET | 日志树的原始清单（诊断用） | 无 |
| `/api/threads/<stem>/rebuild` | POST | 重建对话交还客户端 | `session/rebuilt` |
| `/api/threads/<stem>/archive` | POST | 归档 / 取消归档（一个路由两个方向，body 说方向） | 无（日志必须一字节不动） |
| `/api/threads/<stem>/stats` | GET | **会话统计**：这条会话的记录折出来的几个数（轮 / 模型调用 / 用量 / 缓存命中 / 输出速度），composer 下面那条状态条读它 | 无（只读） |
| `/api/projects` | GET | 侧边栏的数据：每个项目 + 它的会话 | 无 |
| `/api/projects` | POST | 让一个目录成为项目（find-or-create） | 无 |
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
`stats` 是这条形状上唯一的 GET（它只读日志，见下）。

**一个叫 `models` 的 provider 与那条精确路由不冲突**：新建与改写走 collection（`/api/providers`），
删除走 verb 形状（`/api/providers/<id>/remove`），所以那条路径永远只可能是探询。
为它留一个保留字是一条没有失败可防的规矩。

`*directory-chooser*` 是测试缝：真实对话框要等人，测试里换 stub。
用 `alter-var-root` 而不是 `binding`，因为服务在**另一个线程**上跑（见 [client](client.md)）。

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

## jsonl 审计行

一个线程一个文件，写在**它项目的 workspace** 里。每行 `{ts, runId, kind, payload}`：

| kind | 何时 |
|---|---|
| `input` | 收到的 RunAgentInput，原样 |
| `event` | 发出的每个 AG-UI 帧 |
| `message` | LLM 真实看到/返回的 provider 形状消息，**逐字**（含开场块：指令文件与技能清单都在里面） |
| `tools/pre-execute` / `execute` / `post-execute` | 工具生命周期三相，按 `toolCallId` 键控，**不上 wire** |
| `model/start` | 一次**模型调用**开始：`:model` / `:base-url` / `:reasoning-effort`（有才记），**不上 wire** |
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

它也是**开场块进入消息向量的那一处**：4-arity 收下已渲染好的块，拼在 system 消息之后、客户端消息之前。
它收到的 system 文本也是**已经组装好的**（`harness.cap.system-prompt/assemble` 的结果，见 [hooks](hooks.md)）。
它自己不读任何文件、不跑任何 hook（两样都是递进来的），所以这个命名空间仍是个转换器；空块时它返回
**原向量本身**，而不是一个等价的副本——那是「什么都没配的会话与从前逐字节相同」这条回归保证的形状。
见 [skills-and-instructions](skills-and-instructions.md#前端零改动wire-零改动)。

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
