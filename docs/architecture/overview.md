# 一次请求的完整路径

一个 run 从浏览器到模型再回来，中间经过哪些地方。这一篇是地图，细节在各自的章节里。

```
浏览器 (ui/src/app.tsx)
  │  POST /  { threadId, runId, messages, context, resume? }        AG-UI RunAgentInput
  ▼
harness.http/handle-run ──► as-channel，SSE 回包（首帧带 status+headers）
  │
  ├─ log!  "input"        收到的 RunAgentInput 原样
  │
  ├─ providers/current-provider    三档解析（config → 会话 → 本次请求），挂上 api-key
  ├─ preamble/gather + messages     开场块：指令文件（每个折叠一次 InstructionsLoaded）+ 技能清单
  ├─ ag_ui/inbound                 客户端的消息 → provider 形状；开场块拼在 system 之后，context 变尾部 user 消息
  ├─ resume-decisions              客户端的 resume → 内核要重放的决定（未知 interrupt ⇒ 直接失败）
  │
  ├─ binding hook/*sink*           run 作用域的 hook sink（线程 + 审计写入者），**包住 set-up**，见 edge
  │   ├─ SessionStart（仅本会话第一次 run）
  │   ├─ log! "provider/init"      首次 run 落一行
  │   ├─ log! "approval/decided"   本次 resume 带的决定
  │   ├─ log! "provider/changed"   上一轮工具改过的 provider 档（outbox 排空）
  │   └─ log! "message" × n        冻结的 prompt + 开场块 + 每条入站消息，逐字
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
   推论：写日志的地方只有边（`harness.http/log!`），而且是流式响应里同步写的——
   写不进去就发不出去，不存在「帧到了日志没到」。
2. **system prompt 冻结。** `prompt.md` 首调读入即冻（`harness.llm/prompt`），因为 provider 的前缀缓存
   （prefill）靠的是逐字节稳定的前缀。改它要显式 `(llm/reset-prompt!)` 或重启。
   推论：per-run 的 context、指令文件、技能清单**一律不进 system 消息**——它们是尾部或前置的 user 消息。
   这条推论今天有实现：[skills-and-instructions](skills-and-instructions.md) 把开场块与技能正文全部
   落在 user 侧，`prompt.md` 仍是唯一的 system 消息。
3. **客户端持有会话。** 服务端不建会话状态权威：每轮从请求里现收全部历史，算完把新消息交回去。
   threadId 的主人在 React state 里，服务端只按它决定「日志写哪个文件」。

## 一个 run 有三个出口，不是一个

模型发起的工具调用在**执行缝**（`harness.tools/run!`）里被判定为三种结局之一，次序写死：

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
| 项目 / 会话归属 / 归档 | **sqlite**（`harness.db`） | 会被**改写**的状态 |
| 已执行的对话记录 | **jsonl 文件**（只追加） | 只追加的记录 |
| 配置 | **文件**（每轮现读） | 手编、改了不重启 |
| 开场块（指令文件、技能清单） | **不存**：每轮现读现拼 | 配置与技能根是真相源，缓存一份就会「改了没生效」 |
| 技能正文 | **不存**：每轮从会话自身重算 | 对话归客户端所有，注入只能是派生的（见 [skills-and-instructions](skills-and-instructions.md#技能正文是派生的不是累积的)） |
| 待决审批、会话 overlay、hook 连接 | **进程内存** | 重启即失是特性不是缺陷 |

判据不是「改得勤不勤」，是**能不能被改写**：归档标记一年改一次也是状态，它必须进库；
一条消息永远不会被改写，所以它永远不进库。详见 [home-and-storage](home-and-storage.md#库与文件的边界)。
