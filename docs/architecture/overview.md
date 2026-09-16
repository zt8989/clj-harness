# 一次请求的完整路径

一个 run 从浏览器到模型再回来，中间经过哪些地方。这一篇是地图，细节在各自的章节里。

```
浏览器 (ui/src/app.tsx)
  │  POST /  { threadId, runId, messages, context, resume? }        AG-UI RunAgentInput
  ▼
harness.edge.http/handle-run ──► as-channel，SSE 回包（首帧带 status+headers）
  │
  ├─ log!  "input"        收到的 RunAgentInput 原样
  │
  ├─ providers/current-provider    三档解析（config → 会话 → 本次请求），挂上 api-key
  ├─ system-prompt/assemble        组装 system 文本：prompt.md 的冻结开头 + 各 SystemPrompt 声明追加的文本
  │                                （内建三条：工具集合 / 工程目录 / provider 档）
  ├─ preamble/gather + messages     开场块：指令文件（每个折叠一次 InstructionsLoaded）+ 技能清单
  ├─ ag_ui/inbound                 客户端的消息 → provider 形状；开场块拼在 system 之后，context 变尾部 user 消息
  ├─ resume-decisions              客户端的 resume → 内核要重放的决定（未知 interrupt ⇒ 直接失败）
  │
  ├─ binding hook/*sink*           run 作用域的 hook sink（线程 + 审计写入者），**包住 set-up**，见 edge
  │   ├─ SessionStart（仅本会话第一次 run）
  │   ├─ log! "provider/init"      首次 run 落一行
  │   ├─ log! "approval/decided"   本次 resume 带的决定
  │   ├─ log! "provider/changed"   上一轮工具改过的 provider 档（outbox 排空）
  │   └─ log! "message" × n        组装的 system 全文（冻结开头 + 各 hook 追加）+ 开场块 + 每条入站消息，逐字
  │
  ├─ loop/run-chan                 内核跑起来了；下面全是「事件 → 帧 + 审计行」
  │   │
  │   │  ┌─ 循环 ────────────────────────────────────────────────┐
  │   │  │ skills/derived-injections  已加载技能的正文（幂等，可重算）
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
  │   └─ :run/done   → 不转换；把本 run 追加的消息尾逐条落 log! "message"
  ▼
浏览器（@ag-ui/client 收帧 → assistant-ui 渲染）
```

## 三条铁律

这三条是理解整个仓库的前提，每一处设计都能追到它们之一。

1. **jsonl 只 append，内核 run 中永不读自己的日志。** 日志是**记录**，不是真相源，也不是输入。
   唯一的读取者是 run 外的显式管理动作（重建）与作者工具（`evals`）。
   推论：写日志的地方只有边（`harness.edge.http/log!`），而且是流式响应里同步写的——
   写不进去就发不出去，不存在「帧到了日志没到」。
2. **system 消息只有一条，它的开头冻结，hook 追加其后。** `prompt.md` 首调读入即冻
   （`harness.kernel.llm/prompt`），因为 provider 的前缀缓存（prefill）靠的是逐字节稳定的前缀；
   改它要显式 `(llm/reset-prompt!)` 或重启。**冻结的是开头**：一条 system 消息的其余部分由
   `harness.cap.system-prompt/assemble` 在每次 run 组装时补上——`SystemPrompt` 点上每一条声明
   （内建三条 + 文件里的 + 会话加的）追加自己的文本，按来源档位排。
   推论：**本会话的事实一律不进那个冻结文件**——工具集合、绑定的目录、生效的 provider 都是现算的，
   所以 `prompt.md` 里那句工具枚举会过时，而 `<tools>` 块不会。
   另一半推论不变：per-run 的 context、指令文件、技能清单仍是 user 消息，**不进 system 消息**
   （见 [skills-and-instructions](skills-and-instructions.md)）。两半各有主人、且不可能交错
   （不同的 message role），所以「顺序只有一个决定处」在每一半内部照样成立。
3. **客户端持有会话。** 服务端不建会话状态权威：每轮从请求里现收全部历史，算完把新消息交回去。
   threadId 的主人在 React state 里，服务端只按它决定「日志写哪个文件」。

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
| 会话消息 | **客户端**（+ jsonl 记录） | 铁律 3 |
| 项目 / 会话归属 / 归档 | **sqlite**（`harness.infra.db`） | 会被**改写**的状态 |
| 行锚点、已展示集合、撤销记录 | **sqlite**（`harness.infra.db`，四张 `hashline_*` 表） | 会被**改写**的状态；且会话长命，重启后日志里的锚点还得能用 |
| 文件编辑模式（`harness.edn` 的 `:editing`） | **文件**（每次调用现读） | 手编、改了不重启；按会话解析，两种模式各有完整用例 |
| 已执行的对话记录 | **jsonl 文件**（只追加） | 只追加的记录 |
| 配置 | **文件**（每轮现读） | 手编、改了不重启 |
| 开场块（指令文件、技能清单） | **不存**：每轮现读现拼 | 配置与技能根是真相源，缓存一份就会「改了没生效」 |
| system 消息里 hook 追加的那部分 | **不存**：每次组装现算 | 同上，而且更强：工具集合、绑定、provider 都会在会话中途变，冻结一份就是一句会过期的话 |
| 技能正文 | **不存**：每轮从会话自身重算 | 对话归客户端所有，注入只能是派生的（见 [skills-and-instructions](skills-and-instructions.md#技能正文是派生的不是累积的)） |
| **会话统计**（轮 / 模型调用 / 用量 / 缓存命中 / 输出速度） | **不存**：每次从记录折 | 它是**记录的读法**，不是状态——记一份就是同一件事实的第二份，两份必然会漂。客户端也不算它：缓存命中它无从知道，用量估出来就是编（见 [edge](edge.md#管理边路由表)） |
| 待决审批、会话 overlay、hook 连接 | **进程内存** | 重启即失是特性不是缺陷 |

判据不是「改得勤不勤」，是**能不能被改写**：归档标记一年改一次也是状态，它必须进库；
一条消息永远不会被改写，所以它永远不进库。详见 [home-and-storage](home-and-storage.md#库与文件的边界)。
