# 内核：event / loop / llm / tools

这四个命名空间是内核的全部。`event` 定义词汇，`llm` 说话，`tools` 干活，`loop` 把它们串起来。

## 事件：14 种，就这些

`harness.kernel.event` 是内核唯一的输出面。**AG-UI 的帧全部由 `harness.edge.ag-ui` 从这些事件派生**，
内核自己不知道 AG-UI 存在。

| 事件 | 含义 |
|---|---|
| `:run/start` | 一轮 run 开始 |
| `:text/delta` | 助手文本的一个增量 |
| `:reasoning/delta` | 推理内容的一个增量 |
| `:context/injected` | 前置步骤刚放进历史的一条消息（指令块、技能正文、作业通知）——**模型被交给了它、它自己没要**。边把它发成 **`CUSTOM` 帧**，客户端画成一张卡，但**那不是对话的一部分**（只给屏幕看，见 [edge](edge.md)） |
| `:tool/call` | 一次工具调用，参数是**累积完成的**文本（不是碎片） |
| `:tool/result` | 一次工具调用的结果 |
| `:tool/pre-execute` | 一次调用进入执行缝（**不上 wire**，只落审计行） |
| `:tool/execute` | 一次调用离开执行（同上） |
| `:tool/post-execute` | 一次调用的生命周期闭合（同上） |
| `:model/start` | 一次**模型调用**开始：这次调用的身份（model / base-url / 思考档）与**照发出的那张工具表**——`loop` resolve 一次，同一份既进请求体又进这条（**不上 wire**） |
| `:model/end` | 一次模型调用结束，带**厂商回的话**（usage / finish_reason / 回声的 model；**不上 wire**） |
| `:run/end` | 正常收尾 |
| `:run/interrupt` | **第二种终态**：有调用 park 等人，本次 run 到此为止 |
| `:run/error` | 出错收尾 |

`:run/end` 与 `:run/interrupt` **互斥**，一次 run 恰好发其一——客户端因此永远看得到一个终结。

五个事件**没有帧**（三条工具生命周期 + 两条模型调用边界）：它们落 jsonl 审计行，
读它们的是记录的读侧（[edge](edge.md) 那一侧），不是对话。加一帧去装一个统计量就是改协议。

## 循环：`harness.kernel.loop`

```
drive! :
  emit :run/start
  [有 resume 就先 replay! —— 把人的决定重放进缝]
  loop:
    施加会话的前置步骤（cap.project/before-llm = 技能正文 + 作业结束的通知；见下）
      └ 一次 `swap-vals!` 拿到前后两份历史，尾巴上新加的每条发一个 `:context/injected`
    emit :model/start → llm/stream!  流式一轮（事件边流边发）→ emit :model/end
      ├ 厂商报的用量、结束原因、回声的 model 挂在 end 上，逐字进记录，不重命名
      └ 中途抛也发 end（载荷空）：没有终点的那一段分不出「还在跑」与「跑死了」
    tool_calls 非空 → 并发跑，收齐结果，追加 tool 消息，再来一轮
    tool_calls 为空 → 结束
  有 parked → emit :run/interrupt，否则 emit :run/end
  然后 emit :run/done（携带最终 history 与「本 run 追加了哪几条」），通道关闭
```

**内核拿到的那份历史不是它自己拼的，也不从请求里来。** 交给 `drive!` 的是一份**已经组装好的**向量：
组装好的 system 文本在前，接这场**会话的历史**（服务端内存是权威，`jsonl` 是恢复源）+ 这次动作带的
`append`，后面再接开场块（指令文件 + 技能清单）与会话自己的注入（见下）。**一轮 run 的输入是动作**：
`append` 只带这次新加的条目，AG-UI 的 `messages` 退役了——再送它不是被悄悄读成 append，而是**具名 400**
（那会把对话翻倍，正是本特征要终结的那种坏法）。内核不读会话表、不读请求：它只在这一份上跑。

**而它追加的那些消息是这场对话的另一半，而且由它自己点名。** `:run/done` 除了最终 `:history` 还带
`:added`（本 run 放进历史的每一条，按放入次序），边把**它**逐条落成 `message` 行。**不是「比交给它的那份
多出来的那一段」**：resume 那一轮的答案插在它回答的那条 assistant 消息正后面（见下面的 parked 一条），
于是「多出来的那一段」里会混进一条**客户端交进来的**消息，而真该记的那条不在里面——数出来的答案与事实
差一条，正是 `.scratch/session-opening` 票 02 的由来。会话本身在**终帧**那一刻就已经折进去了（`sessions/settle!`，在
`:run/done` 之前——所以一个快的客户端收到 RUN_FINISHED 之后立刻发下一步动作，不会在「run 结束了」与
「它的话进了对话」之间赛跑）。下一次 run 从会话里取，不从浏览器记得的东西里取。

**一次模型调用恰好一对 `:model/start` / `:model/end`**，按**次序**配对（一个 run 里第 n 条 start 就是
第 n 次调用）：序号不记进记录，那是同一件事实的第二份（见 [edge](edge.md) 的行表）。
把两条包起来的是 `harness.kernel.loop/model-call!`，它是「失败也要闭合」这唯一一件事的落点。

**会话自己的东西在每次 `llm/stream!` 之前重算一次**，就在这一行，共两半：

- **技能正文**：模型调用 `skill` 是为了**现在**照着做，等下一轮等于白调；而它是**派生**的（从会话
  自己扫出加载过的技能，见
  [skills-and-instructions](skills-and-instructions.md#技能正文是派生的不是累积的)），
  所以每轮施加不需要任何簿记。
- **后台作业的结束**（`harness.cap.jobs/before-llm`）：一条没人等的命令结束了，它的结局就作为一条
  `<job-ended id="…" path="…">[exit N]</job-ended>` 的尾随 user 消息摆在下一次调用面前——**三样事实
  （哪条作业、记录在哪、怎么结束的），与记录多大无关**；**这不是推送**（不唤醒、不新起 run），
  而且**只说一次**。它与技能正文的唯一不同是幂等的来源：技能正文靠**会话里的那对 `skill` tool call
  与它的加载确认**（模型自己说的那句话就在这场对话里，服务端一直持有它），通知**没有那个锚**——它就是
  那条每轮现算的派生消息，而模型看到的历史里没有它（卡片只给屏幕看，见
  [skills-and-instructions](skills-and-instructions.md#看得见但仍然不是会话的一部分)），所以「说过了」记在
  **作业注册表**里（进程内、按会话、与作业同寿命）。模型自己 `job_output` / `job_kill` 拿到过结局的
  作业**不再通知**——已经读过的东西不是新闻。

两半都装在 `cap.project/before-llm` 里（一处组装，见 [architecture](../architecture.md) 的 `cap.project`
一行）；一并施加的后果是**注入物照旧进 jsonl 的 `message` 行**，而新加的每一条都会**说出去**：它变成一个
`:context/injected` 事件，边把它转成 `CUSTOM` 帧（客户端画成一张卡，而**卡不进对话**——见
[edge](edge.md) 与 [client](client.md#注入物在会话栏里的一张卡)）。**边也有一半**：一轮**开头**就带着的那几条
（指令文件、清单，以及上一轮留下的技能正文、两次 run 之间结束的作业通知）是边先施加的，diff 也由它取、卡
也由它发——内核走到这一步时它们已经在了，一句话都不会说。

几个不显然的地方：

- **同一轮的工具调用并发跑**，各自一个 `async/thread`；每个 `:tool/result` 在它自己的工具跑完时立刻发出
  （结果按**完成顺序**流动），但写进历史的 tool 消息按**调用顺序**——每个 `tool_call_id` 恰好被回答一次。
- **每条调用一个缓冲通道，且永不关闭**：`alts!!` 把「已关闭且为空」的通道当成就绪返回 nil，
  真关掉会让一次排空吞下一个幻影 nil，把真实结果晾在那儿。
- **parked 的调用不回答**：不发 `:tool/result`、不写 tool 消息。它还没被回答，
  它的工具消息落在 resume 那一轮，并且**插在那条点名它的 assistant 消息正后面**（同一轮的那几条按**调用
  顺序**排在彼此之后，即插在已经坐在那里的答案后面）——厂商是按**相邻**判定
  一条调用有没有被回答（`harness.kernel.llm/unanswered-tool-calls`），而交给这场 run 的历史里那条消息
  后面**可能已经坐了别的东西**（边在交出去之前施加的派生注入、这次动作带的 `append`）。插在末尾等于没回答：
  这场 run 会把同一个问题再问一遍，人再批一次就会把工具再跑一次。找不到那条 assistant 消息时不猜一个相邻，
  退回末尾并在 `:unplaced` 里说一声（边为它写一行 WARN）。
- **`:run/done` 不是 wire 帧**，它是「本 run 追加了哪些消息」的返回面（`{:history … :added … :unplaced …}`），
  边把 `:added` 落成 `message` 行。
- **没有迭代上限**，这是设计。
- `replay!` 遇到本进程没 park 过的 interrupt **直接抛**——猜一个批准是这里最坏的失败模式。

## provider 层：`harness.kernel.llm`

一个 multimethod，按 `:protocol` 分派；生产上只有 `:openai-completions`，测试里有一个 `:fake` 脚本替身。

请求体只带 `{:model :messages :tools :stream true}`（有 reasoning-effort 就加 `reasoning_effort`）。
`:tools` 来自 `tools/specs`（**每个 LLM 请求都现取**——这条以后会变成 MCP 连接必须缓存的理由）。

两件被反复踩出来的事：

- **`reasoning_content` 必须原样留在历史里。** 带 `tools` 的请求，后续每一轮都要回传它，
  否则 DeepSeek 直接 400。所以 `loop` 把 assistant 消息**逐字**追加，从不重建。
  **推理字段两个拼法都认**：`llm/consume-sse` 同时读 `reasoning_content`（DeepSeek）与 `reasoning`
  （OpenRouter 代理），回来时按 OpenAI 形状写成 `reasoning_content`。
- **`line-seq` 是惰性的**，必须在 `with-open` 里强制求值，否则响应体泄漏、调用方死等一个没人读的流。

`:reasoning-effort` 只有某一档真的选了才出现（`reasoning_effort` 仅部分厂商需要，不给就是厂商默认）。

### prompt 的载体：冻结的是**开头**

`prompt.md` 的载体在这里（`harness.kernel.llm/prompt` / `reset-prompt!`），因为「冻结」这件事的理由就是
provider 的前缀缓存——它是 provider 的约束，放在 provider 层。

**但它是 system 消息的开头，不是整个 system 消息。** 一条 system 消息的全文由
`harness.cap.system-prompt/assemble` 在每次 run 组装：`prompt.md` 的字节（冻结）之后，接上
`SystemPrompt` 点每条匹配声明追加的文本，顺序由来源档位定（内建两条 → 文件 → 会话），块间一个空行。
追加的文本**原样**进 prompt，引擎不包装——块自己带 `<project>` 这类标签。

**冻结的边界就是「与任何会话无关的话」这条线**：身份、secrets 纪律、以及「其余自己读」是
**承诺**，写死在文件里；绑定的目录、**这台机器长什么样**（平台、命令交给哪个 shell、有哪些
命令行增强工具）是**事实**，现算。理由不是洁癖——事实能在会话中途变（`project/bind!`、
`session-disable!`），冻下来的那句就会说一件已经不成立的事。代价是事实动了要付一次冷前缀，那是它该有的样子。

两半各有主人：**system 半是 `harness.cap.system-prompt`，user 半是 `harness.cap.preamble`**
（`harness.cap.project` 已经 require 了 `preamble`，而 system 半要 `tools` / `project` / `hooks.dispatch`，
并进去就是 require 环）。两半不可能交错：不同的 message role。

`harness.kernel.llm/prompt` 对**没有 hook sink 的调用方**（离线工具、replay、直接驱动内核的测试）返回的就是
那份开头，逐字节——见 [edge](edge.md) 与 [hooks](hooks.md) 的 no-op 一节。

## 工具表与执行缝：`harness.kernel.tools`

工具定义：

```clojure
{:description string :parameters JSON-Schema :required [kw..] :run (fn [args] string)}
;; 另有可选标记：:fence-paths（受围栏约束的文件工具）、:requires-approval（调用即 park）
```

**表与读表的缝住在一起**，因为它们是同一件事的两半：缝决定一次调用意味着什么，表说有什么可调。

### 基座：两份工具表，按会话的编辑模式二选一

基座**不是一个固定清单**：文件编辑有两套实现，一次只会有一套装在本会话的工具表里，由
`harness.edn` 的 `:editing {:mode …}` 决定（见 [home-and-storage](home-and-storage.md#配置根一个根三层优先级)）。

| 模式 | 文件工具 | 两种模式都服务 | 其余 |
|---|---|---|---|
| `:hashline`（**默认**） | `read` `replace` `insert` `anchor_grep` `undo_last_replace`（都带 `:fence-paths`） | `glob` `todo_write` `web_fetch` `web_search` | `bash` `job_output` `job_kill` `eval` `session-configure` `skill` `write` |
| `:str-replace` | `read` `write` `edit`（都带 `:fence-paths`） | 同上 | 同上 |

**中间一列是「与编辑无关」的四个**：`glob` 列的是**路径**，而路径没有锚点可言（所以它在
`harness.cap.glob`，不在 `harness.cap.hashline.*` 底下）；`todo_write` 碰的是**本会话的清单**，不是文件系统
（它落库，见 [home-and-storage](home-and-storage.md#任务清单的表)）；两个 `web_*` 碰的是**网**。
**最后一列里与作业有关的那两个同属这一族**（它们碰的是一条**正在跑的命令**，不是文件，所以同样不登记在
`harness.cap.editing/families` 里）。它们都属于「没有编辑家族」那一类——`harness.cap.editing/families`
**一个字都没改**，因为那张表登记的是
「与编辑有关的名字」，没登记的名字两种模式都服务。

**命令的记录落在配置家**（`<root>/jobs/<会话>/句柄-进程戳.log`），不在会话的 jsonl 那棵树里：一份文件，
`bash` / `read` / `grep` 都能读，而配置家是围栏的自由路径（见 [projects](projects.md)）。**两种情形各写一份**：
后台作业从一开始就写，前台 `bash` 只在答案超过 `answer-budget-bytes` 时把整份落下来（答案本身只带尾部、
省略了多少字节、以及这个路径）。**而它活过写它的那个进程**：JVM 退出只收走作业（句柄、进程、注册表），
文件留在盘上——名字里那截进程戳就是「哪一次运行写的」，同一个会话的下一次运行因此不会写到上一次的记录上；
整棵树按字节封顶（`record-tree-budget-bytes`），这个进程第一次写记录前扫一遍、从最旧的一份开始删。

**`job_output` 读的就是那份文件，而它的 `wait` 是唯一会阻塞的后台动作**：状态行是记录自己的末行
（`[exit N]` / `[stopped]` / 没写就是 `[running]`），`offset` 是记录自己的行号，`wait: true` 挂到
**记录写完末行**那一刻（`write-last-line!` 里 `deliver`，不轮询文件）。`timeout` 到了就答 `[running]`——
**那是「我这次等多久」，不是作业的时限**；作业照样没有时限。

**命令自己把输出重定向走时（`… > 文件`），后台模式的答案里只指名说一声，绝不拒绝**：判据是尽力而为的
（引号、变量、`$(mktemp)` 都可能漏），拿一个尽力而为的判定去拦一条可能正当的命令（`> report.csv`
是真正的活）是拿真事换姿态。漏了，只是不提醒。

`session-configure` 带 `:requires-approval`，其余不带。两个 `web_*` **刻意也不带**：
`bash` 今天就能 `curl` 任何地址且不带审批，给它们挂个 park 是**装样子**（`tool-toggles` 自己写过那句
「关闭不是禁止」），要这道坎的会话自己装规则（`session-require-approval!`，或一条 hook）。
`read` 与 `write` 在两种模式下**同名**，
靠 `:describe` 换脸：参数与说明随模式变，名字不变——同一个名字在两种模式下是两件不同的事，
比两个名字各自只在一半时间里存在更好读。

文件工具的相对路径经 `project/resolve-path` 重根到会话的项目目录，**回报的是已解析路径**。
`bash` 的 cwd 是绑定的目录；**命令内容永不判定**（这是明示接受的逃逸面）。
`eval` 在常驻的 `harness.user` 命名空间里执行，`def` 跨调用保留。
`session-configure` 改本会话的 provider/model/reasoning-effort，**先解析后写**——改不动的配置不会被写进会话。
`skill` 只按名字查表（表由目录列举产生，所以名字永远变不成路径），**不标审批**：读一份指令不是副作用，
而正文里让人做的事各自过各自那道缝。它唯一的效果是把那份正文带进对话，施加点在循环里那一步
（见 [skills-and-instructions](skills-and-instructions.md#skill-工具)）。
`todo_write` 送的是**完整清单**（不是增量，空数组即清空），一次调用整份替换本会话的清单；
**一条消息里只许一次**——两次「整份替换」之间不存在合并，而一个回合的工具调用是并发跑的，
所以那样的消息**两条都不落盘**（判据是 `harness.kernel.tools/sole-call-of-its-name?`，它读的是 run loop
交给 `register-turn!` 的整个回合）。
`web_fetch` 取回的是一页的**正文**（`<script>` / `<style>` 连同内容丢掉、块级标签换行、实体解码），
它是**有损的文本抽取器而不是渲染器**，所以 JS 渲染的页面会如实回一句「没有可读正文」；
`web_search` 有**三个厂商的线**（`harness.cap.web.search`：Brave / Exa / Tavily，各自一对请求与响应形状），
**哪个键在就哪个答**（顺序 Brave → Exa → Tavily，就是这张表里的顺序），
键与 provider 的键走同一个 `harness.infra.home/env-value`。

### 会话 overlay：两条正交轴

```
thread-id → {:added {name tool}   ; presence：本会话贡献的定义
             :disabled #{name}}   ; availability：本会话关掉的，定义不动
```

- **关闭不是隐藏**：被关的工具**仍在工具表里**（模型仍看得见），调用被缝以 `:disabled` 拒掉。
  藏起来会让「没有这个工具」和「这个工具关着」变成同一个观察，而前者是谎话。
- **没有 `:removed`**：任何东西都不许从工具表里消失。
- **编辑模式是这条规则的唯一例外**（2026-09-15 推翻 tool-toggles 的裁定）：`:hashline` 下 `edit`
  **不在表里**，`:str-replace` 下锚点那四个**不在表里**。代价是模型可能把「被策略关掉」读成「这能力
  不存在」，所以豁免附了一条对等义务——**调用一个本会话不服务的名字要指名拒绝并说清替代品**：
  「本会话按 old_string 编辑，用 replace；要切换写 `:editing {:mode :hashline}`」。一句话同时给出
  能力去哪了、怎么拿回来，模型既不会以为能力不存在，也不必自己摸。豁免只归编辑模式解析器所有，
  `session-disable!` 那条轴一个字没动。
- 关掉是**策略开关，不是安全边界**——关掉 `write` 不阻止 `bash` 写文件。
- 全部进程内、按 thread、重启即失。

### 执行缝：一次调用，三相，三个出口

```clojure
(tools/run! call thread-id on-phase)
;; call 是 PROVIDER 形状：{:id .. :type "function" :function {:name .. :arguments json-string}}
;; 永不抛、永不返回 nil —— 工具失败是给模型的信息，不是 run 的失败
```

`on-phase` 收到三相事件（pre / execute / post）。**生命周期总是闭合**：连没通过 pre 的调用
（未知工具、被关、缺参数）也有自己的 `:tool/post-execute`。

判定次序（`cond`，先匹配者胜）：

| 序 | 判定 | 出口 | pre-execute 的 outcome |
|---|---|---|---|
| 1 | 本会话关掉了它 | 阻断（硬拒） | `:disabled` |
| 2 | 本会话的编辑模式不服务它 | 阻断（硬拒） | `:unserved` |
| 3 | 缺必填参数 | 阻断 | `:missing-args` |
| 4 | 审批规则命中 | 悬置**或**按已有决定穿越 | `:needs-approval` / `:approved` / `:vetoed` |
| 5 | `PreToolUse` 门禁 | 放行 / 阻断 | `:pass` / `:hook-blocked` |
| — | 工具不在表里 | 阻断 | `:unknown-tool` |

**为什么门禁最后**：前四条是 harness 自己的判断；一个根本跑不起来的调用拿去问用户的规则，
既浪费一次 spawn，也把模型读到的理由搅浑。

**`:disabled` 与 `:unserved` 不同，两者都答时 `:disabled` 先出**：一个说「你关掉了它」，一个说
「本会话编辑用的是另一套」。两个都成立时两条都要说（先关掉、再说替代品），否则模型会去把自己关掉的
工具打开，然后发现它**仍然**不会跑。

### 悬置：先问规则，再问人

一条必须悬置的调用问两次：

1. **已经有人答了吗？**（`take-decision!` 取 resume 带回来的裁决，取过即标记已消费——
   重放同一个 interrupt 不能把调用执行两次。）
2. 没有就问 **`PermissionRequest`**：让一条**规则**回答本该打断人的事。
   它的效力与人的答案相同（`approve` 就执行，`deny` 就用它的理由回答这次调用）；
   **声明了但没给答案，就照旧 park 等人**。

所以一个没声明任何 hook 的会话，行为与 hook 存在之前逐字节相同。

悬置的**原因**算一次、随 parked 记录走（`:tool-declares` / `:session-asks` / `:out-of-bounds`），
因为人（和读日志的人）需要知道这是工具自己声明的、本会话要求的、还是撞了项目围栏。

审批状态全在进程内存（`parked-registry`：interrupt-id → 记录），重启即失；
拿一个本进程没 park 过的 interruptId 来 resume 会被**明确拒绝**，不猜。

**不做超时，也不做跨进程持久化**：人一直不响应，这个 thread 就一直待决——这是可接受的语义，
不是缺陷（interrupt 也不填 `expiresAt`，延续本仓「不写 sleep、不重试」的纪律）。
**这一条讲的只是审批**：命令自己的**时限**是另一件事，已经有了——`bash` 的 `timeout`（毫秒，
默认 120000，到点连子孙一起停掉），而**后台作业反过来没有时限**（`job_output` 的 `timeout` 也不是它：
那是「这一次我等你多久」）。「人一直不响应就一直等」是有意的
无限等待，「一条命令不许无限跑」是命令的边界；两条不要读成同一条纪律。
两个开启悬置的来源都不删：工具自带 `:requires-approval` 与 `session-require-approval!`，
它们是「给这个工具装一条悬置型判定」的两种来源，与 `hooks.edn` 里写的门禁同一族。
**默认全放行**：没有任何工具被标记时，帧序列与这套能力存在之前逐字节相同。

**wire 上的形状**（客户端那一侧）：

```
内核 :run/interrupt
  → ag_ui 映射为 RUN_FINISHED + outcome{type:"interrupt",
      interrupts:[{id, reason:"tool-approval", message, toolCallId}]}
  → 客户端从 outcome.interrupts 落 pendingInterrupts
  → 下一次 run 的 RunAgentInput.resume:[{interruptId, status, payload?}]
      status "resolved" ⇒ 批准   "cancelled" ⇒ 否决   （同一个 POST 端点，不做第二个）
```

interrupt 的键是**严格校验**的（AG-UI 的 zod 多一个键就失败），所以内核只往外带四件事实；
给**人**看的那句话是 `message`，不是 `reason`。一次 run 里有多条开着的 interrupt 时，
客户端必须一次把它们**全部** resume——「只回答一部分」会被运行时按名拒绝。

`*thread-id*` 在工具体外面被绑定，所以工具内部的代码（`eval` 尤其是）能问到自己属于哪个会话。

### 文件编辑：锚点那一套（`harness.cap.editing` + `harness.cap.hashline.*`）

**按锚点编辑是这个 harness 的默认编辑方式**（2026-09-15 起）：`read` 每行回成 `锚点│内容`，
`replace` / `insert` 用那个锚点定位，而不是让模型重打一遍要改的文本。

**锚点是什么。** 一行的 4 字符名字（`a3f9`），由**分配**得来——从一张随仓携带的 1353139 个条目的表里
取下一个没用过的（表是 vendored 资产，MIT，署名见 `NOTICE`）——**不是从内容算出来的**。这一点是有意的：
内容相同的两行**绝不**共用一个锚点，所以「改第二处 `return null`」这种话不需要加长上下文来消歧。
一行不动，它的锚点一直是它；锚点**只属于铸造它的会话**，另一个会话读同一个文件拿到的是另一套。
每行还带一个校验和（规范化后的 SHA-256 前 16 位，规范化 = 去 `\r`、去行尾空白、超 500 字节截断）。

**三个词决定了它的行为：**

- **已展示**（served）：本会话**真的把这一行连同锚点印出来过**。它与「归谁所有」是两件事——`read`
  分页时给没返回的页也铸了锚点，那些行归你所有但你没见过，**编辑它们会被拒**。防的正是「凭记忆改一行
  自己没看过的代码」。`read` 与 `anchor_grep` 印出来的算，拒绝里回带的那几行也算。
- **漂移**（drift）：读到某一版之后文件在磁盘上变过了（判据是逐行校验和，不是时间戳）。这时**不静默
  重定位**，而是拒绝并把该区间**当前**的锚点一起交出来。
- **拒绝即交付**：编辑被拒时回的不只是一句错，而是它当时在说那几行 + 它们**现在**的锚点，并把这些行
  登记为已展示。于是重试是一次新的编辑调用，**不是一次重读**——这是整套东西的脊柱。

**改完的答复是 diff，不是一句 "edited"**：`+锚点│行` / ` 锚点│行` / `-    │行` 三态，上下文行数由
`:diff-context-lines` 决定，被删掉那行的锚点位留空（免得有人复制一个已经死掉的名字）。模型照着 `+`
与空格行的锚点就能下下一笔，不必重读文件。

**一次消息里对同一个文件的多次编辑是一次提交。** 一个回合的工具调用是**并发**跑的
（`harness.kernel.loop`），两笔各自针对同一基态的编辑各自都成立、合起来丢数据——所以它们按目标路径分组、
区间必须两两不相交、全部针对消息开始前的状态校验、最后一次调用给出合并后的 diff、**一次写一次撤销**，
不成立就整批拒绝（其余调用得到的答复是「已并入」）。不这么做的失败模式是**静默数据丢失**。

**`write` 是锚点的边界**：写完之后该文件所有锚点释放、撤销记录清空（内容已经与模型看到的不是一回事），
并且**拒绝把自己印出来的锚点行回写进文件**。`undo_last_replace` 读的撤销记录只保留最近一次，
把文件**和锚点**一起退回去（只还原文本会让库里那套锚点描述一个已经不存在的状态）。

`anchor_grep` 走 `rg --json`，命中行直接带锚点（行号仍然印，但它**不是拿来编辑的**）。
危险正则在跑之前就被拒（反向引用、量词化的组、量词化的选择分支、大 `{n}`、
嵌套量词），出路是 `literal: true`。

**跑 `rg` 这件事本身在 `harness.infra.rg` 里，因为它现在有两个用户**：二进制名、超时、以及
「`rg` 不在 PATH 上」那句点名失败（判据是**退出码 127**，不是 `No such file or directory` 那句
字符串——后者也是 `rg` 对**不存在的搜索根**说的话，按它判断会把一个拼错的路径报成「没装 ripgrep」）。
`--json` 的解析留在 `anchor_grep` 自己手里：一次命中是一条**行**，而行是要给它铸锚点的那个东西。
`glob` 用同一份管道，读的是**文件名**而不是行：它的答案是 **rg 两次列举的交集**——
`rg --glob` 的优先级**高于** `.gitignore`（它自己的帮助这么写），所以把模型的模式直接交给它，
`**/*` 会把 `node_modules` 整个列出来；交集说的是「在**这个树里**按模式找」，
而不是「按模式盖在这个树上面」。

**存储**（表结构与那三次迁移见 [home-and-storage](home-and-storage.md#sqlitehome-的元数据层)）：
落盘持久化是刻意的——会话长命（jsonl 日志 + 重建），重启后日志里的锚点还该能用。
**铸造是会话级的**，所以那一段临界区要的是一把**会话锁**：`read` 两个文件在同一个回合里并发跑过，
两次铸造从同一个探针位置起步，给不同的行铸出**同一批锚点**，第二次 claim 撞上主键。`with-path-lock`
盖不住这件事（两个不同文件本来就该是两把锁），所以 `store/with-session-lock` 永远是最外层。
