# 一次请求的完整路径

一个 run 从浏览器到模型再回来，中间经过哪些地方。这一篇是地图，细节在各自的章节里。

```
浏览器 (ui/src/app.tsx)
  │  POST /api/agent  { threadId, append, context, resume? }         AG-UI RunAgentInput
  ▼
harness.edge.http/handle-run ──► as-channel，SSE 回包（首帧带 status+headers）
  │
  ├─ providers/current-provider    三档解析（config → 会话 → 本次请求），挂上 api-key
  ├─ system-prompt/assemble        组装 system 文本：prompt.md 的冻结开头 + 各 SystemPrompt 声明追加的文本
  │                                （内建两条：工程目录 / 这台机器）
  ├─ preamble/gather               开场块：指令文件（每个折叠一次 InstructionsLoaded）+ 技能清单
  ├─ ag_ui/inbound                 **这场会话的历史（服务端内存）+ 这次动作带的 `append`** → provider 形状；
  │                                出生那一轮，开场块（指令文件 → 技能清单）已经写在 `append` 里：
  │                                提问在前、材料紧跟其后（见 skills-and-instructions）
  ├─ resume-decisions              客户端的 resume → 内核要重放的决定（未知 interrupt ⇒ 直接失败）
  │
  ├─ binding hook/*sink*           run 作用域的 hook sink（线程 + 审计写入者），**包住 set-up**，见 edge
  │   ├─ SessionStart（仅本会话第一次 run）
  │   ├─ log! "provider/init"      首次 run 落一行
  │   ├─ log! "approval/decided"   本次 resume 带的决定
  │   ├─ log! "provider/changed"   上一轮工具改过的 provider 档（outbox 排空）
  │   └─ log! "message" × n        每一条进数组的消息各一行：场的 prompt、这次动作带来的每个条目
  │                                （信封 `source` 说谁放的，`id` 是它的身份），payload 是厂商读到的那一份
  │
  ├─ loop/run-chan                 内核跑起来了；下面全是「事件 → 帧 + 审计行」
  │   │
  │   │  ┌─ 循环 ────────────────────────────────────────────────┐
  │   │  │ skills/derived-injections  人的 `/name` 要的正文（幂等，可重算）；新加的每条发 `:context/injected`
  │   │  │ llm/stream!      流式一轮；tool_calls 累积在 assistant 消息里
  │   │  │ tools/run! × n   本轮每个工具调用并发跑（各自一个线程）
  │   │  │                  pre / execute / post 三相事件骑同一条通道出去
  │   │  └──────────────────────────────────────────────────────┘
  │   │
  │   ├─ :run/end      ──► RUN_FINISHED                     （正常收尾，Stop 观察者触发）
  │   ├─ :run/interrupt ─► RUN_FINISHED + outcome.interrupts（有调用 park，等人）
  │   └─ :run/error   ──► RUN_ERROR
  │
  ├─ 每个内核事件：
  │   ├─ convert（ag_ui/outbound）→ AG-UI 帧 → emit → SSE + log! "event"
  │   ├─ :tool/* 三相 → 不上 wire，只落 tools/pre-execute | execute | post-execute
  │   └─ :run/done   → 不转换；本 run 追加的消息进**会话**（内存是权威），并逐条落 log! "message"
  ▼
浏览器（@ag-ui/client 收帧 → assistant-ui 渲染；它手里是**一段窗口**，不是整个会话）
```

## 三条铁律

这三条是理解整个仓库的前提，每一处设计都能追到它们之一。

1. **run 中不读自己的日志（内存里就有）。日志只 append、异步写、允许落后，且永远是会话的有序前缀。
   日志是恢复源；运行时的真相在内存。** 一场会话活在服务端内存里（`thread-id → 会话`），run 的输入
   是内存里那份历史加上这次动作带的东西；进程起来时从记录重建**一次**，此后读文件只发生在 run 外
   （显式重建、作者工具 `evals`）。**落后是允许的，而且落后是一份更短的会话、不是一份错的**：
   一行一个 JSON、一个写者、按发生顺序，所以它永远是那条会话的**有序前缀**。
   写日志的地方仍然只有边（`harness.edge.http/log!`），但它是**异步**的：一帧入队就写，单消费者逐行
   append——异步换的是「IO 不在响应路径上」，不是「少写」。**写不进去不许静默**：那个会话进**降级态**，
   `sofar` / `rebuild` 带上 `:record`，界面上是一根常驻的条（见 [edge](edge.md) 与
   `.scratch/sessions-live-on-the-server/spec.md` 票 02 的落地记录）。
   （旧措辞那句推论「写不进去就发不出去」随同步写一起作废了，别再写它。）
2. **system 消息只有一条，它的开头冻结，hook 追加其后。** `prompt.md` 首调读入即冻
   （`harness.kernel.llm/prompt`），因为 provider 的前缀缓存（prefill）靠的是逐字节稳定的前缀；
   改它要显式 `(llm/reset-prompt!)` 或重启。**冻结的是开头**：一条 system 消息的其余部分由
   `harness.cap.system-prompt/assemble` 在每次 run 组装时补上——`SystemPrompt` 点上每一条声明
   （内建两条 + 文件里的 + 会话加的）追加自己的文本，按来源档位排。
   推论：**本会话的事实一律不进那个冻结文件**——绑定的目录、这台机器长什么样都是现算的，
   写进文件的句子从写下的那一刻就在过时，而 `<project>` / `<env>` 块不会。
   另一半推论不变：per-run 的 context、指令文件、技能清单仍是 user 消息，**不进 system 消息**
   （见 [skills-and-instructions](skills-and-instructions.md)）。两半各有主人、且不可能交错
   （不同的 message role），所以「顺序只有一个决定处」在每一半内部照样成立。
   **送达有两种**（2026-09-24，`.scratch/instruction-updates`）：组装出来的这一份变了就送，怎么送由**端点的
   能力位**说——`:replace`（缺省）换掉 `message[0]`，那是一次**显式的**冷前缀；`:in-place` 让 `message[0]`
   **一个字节都不动**，把新的指令全文作一条 `developer` 消息放在新提问之前（共享前缀 = system + 客户端每轮重述
   的历史，因此整段留得住；尾巴本来每轮就要重算）。判据是**两份名字集合的 hash**（hooks 与 tools），不是组装后
   的文本、也不是数量：加/删一条 hook 或一个工具算变了，改一句描述不算。
3. **会话归服务端；浏览器是只读副本，只发动作。** 一场会话的权威在服务端内存里（记录是它的恢复源），
   浏览器手里是**一段窗口**：打开拉尾页、增量走一条 feed、更早的按需补页、刷新再拉尾页
   （见 [edge](edge.md) 与 [client](client.md)）。发送消息、换模型、审批回答、停止、归档都是**动作**——
   副本画什么由服务端给的帧决定，它不撰写历史。
   **这一条反过来的是「谁持有会话」，不是「服务端不推送」**：feed 就是推的。旧说法里真正要保住的那件事
   换个说法留着——**服务端不记谁在订阅**（ADR 0003 决策 7）：游标随连接走（`since=<读者的游标>`），
   服务端从会话里取 N 之后发出去，除了**活着的连接**本身，进程里没有任何「谁订了什么」的表。
   所以窗口的 `baseSeq` / `hasMore` / generation 住在**连接与副本**里，不是服务端的状态。

## 一个 run 有三个出口，不是一个

模型发起的工具调用在**执行缝**（`harness.kernel.tools/run!`）里被判定为三种结局之一，次序写死：

- **放行**：跑。没有任何东西拦它。
- **阻断**：不跑，理由作为**这次调用的工具结果**回喂模型，run 继续（否决、`PreToolUse` 退出 2、
  被会话关掉的工具）。
- **悬置**：不跑**且先问**——run 以 interrupt 收尾，这次调用在有人回答之前属于人；
  回答经下一次 run 的 `resume` 回来。

次序：**关掉的工具 → 缺参数 → 审批规则 → `PreToolUse` 门禁**。前三条是 harness 自己判断，
所以门禁排在最后：一个根本跑不起来的调用不该拿去问用户的规则。详见 [kernel](kernel.md) 的「执行缝」一节。

## 状态存在哪

一份状态只有一个家，这是最近几个特征反复强化的边界：

| 状态 | 在哪 | 为什么 |
|---|---|---|
| 会话消息 | **服务端内存**（会话表；`jsonl` 是恢复源） | 铁律 3：会话归服务端，一轮 run 的输入是**动作**（`append`），历史由服务端自己交给自己 |
| **窗口**（`entries` / `baseSeq` / `hasMore` / `cursor`，以及 generation） | **连接与浏览器**（一条 feed 一条连接；服务端不记谁订了什么） | 铁律 3 的另一半：游标随连接走，进程除了活着的连接不持有任何订阅状态 |
| 项目 / 会话归属 / 归档 / 认领（`session_claims`）/ 任务清单（`todos`） | **sqlite**（`harness.infra.db`） | 会被**改写**的状态 |
| 行锚点、已展示集合、撤销记录 | **sqlite**（`harness.infra.db`，四张 `hashline_*` 表） | 会被**改写**的状态；且会话长命，重启后日志里的锚点还得能用 |
| 文件编辑模式（`harness.edn` 的 `:editing`） | **文件**（每次调用现读） | 手编、改了不重启；按会话解析，两种模式各有完整用例 |
| 已执行的对话记录 | **jsonl 文件**（只追加） | 只追加的记录：`message` / `event` 两类行，其中**不含推理帧**——同一段思考在模型那条 `message` 行的 `reasoning_content` 上（[ADR 0009](../adr/0009-the-record-holds-a-thought-once.md)） |
| 配置 | **文件**（每轮现读） | 手编、改了不重启 |
| 开场块（指令文件、技能清单） | **不存**：每轮现读现拼 | 配置与技能根是真相源，缓存一份就会「改了没生效」 |
| system 消息（全文） | **不存整份**：每轮现算，但**记一份**（2026-09-21）：每场会话第一条 `message` 行（信封 `source: "system-prompt"`）带**一次全文**、每轮带**那一轮的 hash**。另有一份**进程内存**（`harness.cap.instruction-updates`，按会话）：**这一轮到底送出去的是哪份文本**、以及 `:in-place` 下累积的更新链 | 同上，而且更强：工具集合、绑定、provider 都会在会话中途变，冻结一份就是一句会过期的话。所以真相仍是现算的组装，记录里那一份是**那次 run 到底读到什么**的见证——hash 每轮一条，正是为了「这轮和上轮是不是同一句」能对得出来（prefill / prompt cache 靠的就是那条前缀稳定）。进程内存那一份不是第二份真相，是**「我上一轮说过什么」**这句问话唯一的家：它只用来决定这一轮要不要重建、以及 `:in-place` 时 `message[0]` 该冻在哪一版；重启即失，重新组装一次、报「没有变化」 |
| 人的 `/name` 要的技能正文 | **不存**：每轮从会话自身重算 | 注入是**每轮现算的派生文本**，不是那场对话说过的话；模型 `skill` 那条路不是注入（结果就是正文，随那次调用存在对话里）（见 [skills-and-instructions](skills-and-instructions.md#技能正文的派生注入只剩人的那一半)） |
| **上下文占用**（这次调用的 prompt 占窗口多少，以及三样各占多少） | **不存**：每次从记录折 | 与下一行同一条：分子是厂商报的数、分母是那次调用自己行上的声明，都不是这个进程能攒下来的东西（见 [client](client.md#上下文占用model-左边那颗圈)） |
| **会话统计**（轮 / 模型调用 / 用量 / 缓存命中 / 输出速度） | **不存**：每次从记录折 | 它是**记录的读法**，不是状态——记一份就是同一件事实的第二份，两份必然会漂。客户端也不算它：缓存命中它无从知道，用量估出来就是编（见 [edge](edge.md#管理边路由表)） |
| 待决审批、会话 overlay、hook 连接 | **进程内存** | 重启即失是特性不是缺陷 |
| **后台作业**（句柄、状态） | **进程内存**（`harness.cap.jobs`，按会话分家，退出时收尾钩子只停进程、清注册表） | 它是一条**正在跑的命令**，不是一条事实：命令随进程死，注册表里的 pid 记一份在盘上只会留下一个再也对不上的 pid。run 结束**不**收它（收了就等于后台执行没用）。作业结束了**条目仍在**（答得出末行），让 `job_output`、`job_list` 与第二次 `job_kill` 有东西可答 |
| **命令的记录**（`bash` 说过什么） | **文件**（`<配置家>/jobs/<会话>/<句柄>-<进程戳>.log`），**活过写它的进程** | 它不是状态：一份文件从头到尾只被追加，谁也不会去改写它，所以它不进库。**跨重启还在**是留它的全部理由（昨天那次的现场今天还读得到），进程戳进文件名则是为了下一次运行别写到上一次的头上；整棵树按字节封顶，超了从最旧的一份开始删 |

判据不是「改得勤不勤」，是**能不能被改写**：归档标记一年改一次也是状态，它必须进库；
一条消息永远不会被改写，所以它永远不进库。详见 [home-and-storage](home-and-storage.md#库与文件的边界)。
