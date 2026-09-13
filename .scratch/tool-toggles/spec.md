# spec: tool-toggles（会话级工具的打开与关闭）

给会话级工具集补上**可用性**这一维：除插入/删除（定义的有无）之外，还要能动态关闭与打开一个工具——关闭不动定义、随时可逆。

## 决策

- **两个正交轴，四个入口（2026-09-13，牛总裁定）。** 定义的有无归 `session-register!` / `session-unregister!`；可用性归 `session-disable!` / `session-enable!`。一轴一动，互不串味。`session-unregister!` 因此收窄为"只撤本会话新增的定义"，对 base 名是 no-op。
- **关闭 = 可见但调用被拒（牛总裁定，不是"从工具表消失"）。** 被关的工具仍出现在该会话的工具表里，调用它不执行、由执行缝给出明确状态。选这条的理由：模型因此**知道限制存在**，而且能自己用 eval 把它打开——在"自我扩展"定位下这是更好的行为。隐藏方案会让模型把"被策略关掉"误读成"这能力不存在"，甚至去找 bash 绕路。
- **因此 `:removed` 退役，且明确让出一项能力。** 那条集合的唯一作用是把 base 名从工具表里抹掉，属"隐藏"语义，与本决策互斥。**本特性之后，任何工具都不能再从工具表里消失**：'从本会话移除'只对会话自己新增的定义成立（撤定义），base 工具只能关。
- **推翻 tools-lifecycle 的「remove 单调」。** 原 spec 写"remove 单调：先撤 added 再隐藏 base 名"，其 03 验收断言"unregister! base 工具 → 模型收到 unknown tool: read"。本特性推翻：base 名对 `session-unregister!` 为 no-op；关闭走 disable!/enable! 且可逆。先例：tools-lifecycle 非目标里的"不做审批暂停"已被 pre-tool-approval 推翻——本仓允许后出特性推翻前 spec 的决策，只需在此记明。
- **执行缝要说真话。** `run!` 的 lookup 是**调用时**做的，所以同一个 run 内被关掉的工具会随后被调用到。这类调用必须报 `:disabled`，不能报 `:unknown-tool`——它存在，只是被关，说 unknown 是谎话。
- **`:disabled` 检查先于审批检查。** 关闭是硬拒绝，没有理由为一个注定不执行的调用去 park 等人。
- **关闭不是禁止。** 取下能力 ≠ 禁止行为：关掉 `write` 挡不住 `bash` 写文件。真正的强制边界在审批 park 与沙箱，不在工具表。别让这个功能冒充护栏。
- **per-session，base 永不被改。** 与既有 overlay 约定一致，会话结束即弃。
- **不新增日志行。** 开关动作经 eval 调用，其 code 本来就落在该 thread 的 message 行里；被关的调用沿用既有三相行，只多一个 outcome 取值（不是新 kind）。这与 eval-self-extension 的"一份真相源"一致。

## 非目标

- 不改 `RunAgentInput`：宿主侧逐 run 指定关闭清单是另一个入口，tools-lifecycle 已否过一次。
- 不做"从工具表消失"的隐藏（本决策明确让出，见上）。
- 不做权限或强制：关闭不构成安全边界。
- 不改 kernel 事件种类、不改 AG-UI 帧形状（三种工具事件本就零帧）。

## 验收主线

离线全量 `harness.test-runner` 全绿。基线 55 tests / 247 assertions。本特性要**改写**既有断言，逐条列明：

- `session_tools_test` 的 `session-add-and-remove-are-scoped-to-one-thread`：其中"移除 base 工具即隐藏"与"移除单调"两个 testing 块重写为关闭/打开语义。
- `session_tools_test` 的 `eval-joins-the-session-across-the-real-tool-call-shape` 尾部："移除 base 工具 → unknown tool: read" 改为关闭 → 明确的拒绝信息。
- 会话记录那部分断言不在此列——`eval-self-extension/02` 会删除它们。

**协作提示（非硬阻塞）**：01 与 `eval-self-extension/02` 都会改 `session_tools_test.clj`，但落在不同区段。先落 02 再落 01 可少一次同文件重排。

## 状态

- 01（会话级开关）：**已完成**（`eb3b1c7`）
- 02（执行缝拒绝被关的调用）：**已完成**（`eb3b1c7`）
- 03（eval 集成 + prompt.md）：**已完成**（`bc269a8`）

## 已验证到什么程度（2026-09-13，三票全部落地）

- **01 + 02（`eb3b1c7`）**：overlay 的 `:removed` 退役，改为 `:added` + `:disabled` 两轴。`session-unregister!` 对 base 名 no-op；撤回 addition 时连带清 `:disabled` 标记（`retracting-an-addition-clears-its-disabled-mark` 锁定——否则同名重插会继承僵尸关闭态）。`session-disable!` 对会话看不见的名字不造标记；`session-enable!` 与重复关/重复开均幂等；跨会话隔离。`effective-tools` 的 docstring 明写「DELIBERATELY NOT the set of tools that will run」。
  - **实现坑**：`session-disable!` 需 `effective-tools` 判存在性，前者定义在前——加 `(declare effective-tools)` 解前向引用（Clojure 单遍编译）。另：禁用集合是 set，清标记用 `disj` 不是 `dissoc`（手滑过一次，5 个测试同时 CCE）。
  - **执行缝**：`run!` 的 `cond` 首支插 `:disabled`，**先于** `missing-args` 与审批；`disabled-message` 明说 disabled 并给出 `session-enable!` 的自助路径。`event/tool-pre-execute` docstring 补 `:disabled` 取值。
- **03（`bc269a8`）**：prompt.md 的 Self-extension 段把「读工具表」从 `@harness.memory/registry`（base）改为 `effective-tools`（本会话生效集）——原写法漏掉 overlay，agent 自己加/关的东西都不在里面。Session tools 段补 disable!/enable! 并写明「存在与可用是两个轴」「关闭不是禁止」。
- **端到端**（`the-agent-toggles-a-tool-through-eval-and-reads-what-it-has`）：全程真 eval tool-call 形态——读自己的工具集（含会话新增、含被关的；别的会话读不到）→ 关 bash → 调用得 `:disabled`（不是 unknown）→ 再打开 → 真实执行。
- **改写既有断言**（按本 spec 验收主线清单）：`session-add-and-remove-are-scoped-to-one-thread` 的两块改为 no-op 语义；`eval-joins-...` 尾部的 `unknown tool: read` 改为 `:disabled` → `:pass` 全形态。新增四组开关专项测试。
- **全量**：73 tests / 380 assertions，全绿，连跑两次一致。
- **`tools-lifecycle/spec.md`** 记入反转：remove 隐藏 base + remove 单调被推翻，`:disabled` 取值入缝；未被推翻的部分（base 不可变、overlay 落 memory、add shadow、thread-id 贯通、三相 + post 恒闭合）明列为地基。
