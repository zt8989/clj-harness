# spec: eval 自我扩展

让 eval 的定位从「读自省」改为「写自我扩展」：它存在的理由是 agent 能在运行期给本次会话长出新的手脚，而不是替 agent 去读那些文件里本来就有的东西。

## 决策

- **eval 的价值在「写」，不在「读」（2026-09-13，牛总裁定）。**
  - **读**：config.edn 就在磁盘上、prompt.md 就在磁盘上、消息尾就在 JSONL 里。agent 用 read/bash 直读，可带 offset/limit/grep，比任何固定形状的自省入口更省——而且 config.edn 只有三行，为它保一个 accessor 收益为负，还要多花 prompt 篇幅向模型解释。**「抄一份历史」的内存副本因此撤销**：`sessions` atom 与 `record-history!` / `record-provider!` / `session` 三个入口删除。
  - **读（2026-09-13 补正，牛总要求）**：读自省面**未整体撤销**，撤销的是「抄副本」这件事。仍要暴露当前会话状态，但只暴露两类**问出来**（而非抄下来）的事实：① **本 thread 的 JSONL 路径**（文件事实，现算，零新增状态）；② **当前激活的 provider / model / reasoning-effort**（进程运行时事实，JSONL 里没有也不该有）。两者都按 `mem/config` 的同构写法**每次现算、不缓存**——「抄的是错的，问的是对的」。详见 04。
  - **写（2026-09-13 补正）**：provider / model / reasoning-effort 允许运行期改变，**必须经 human_in_loop 授权**后才生效。作用域为 **per-thread**（不再用进程全局 override）。此授权是**流程约定，不是安全边界**——eval 仍可绕过（`use-provider!` 是 public、bash 可读 .env），spec 与 README 如实标注，不吹。
  - **provider 是一等公民（2026-09-13 再补正，牛总要求）**：从「config.edn 里唯一的一套」升格为**具名注册表**（`providers.edn`，可有多套），`config.edn` 降为**默认档**。解析优先级四级：注册表具名项 → 默认档逐字段覆盖 → 会话级覆盖（04）→ 本次请求指定（05）。会话**初始继承默认档**，可在发起时指定、也可中途变更；三字段各自独立可覆。详见 05。
  - **provider 的每次变化都有迹可循（2026-09-13 再补正）**：落 **`provider/init`**（会话首 run 一次，含四字段 + 来源）与 **`provider/changed`**（每次变更，before → after + 授权结果）。**取消原「每 run 一行快照」**——时间线已能重建，快照在同 run 未变更时是噪声。审计的价值在时间线，不在快照密度。详见 03。
  - **写（自我扩展能力本身）**：运行时自我扩展是 eval 不可替代的能力——包裹/改写已有工具做中间件、`alter-var-root` 热修 harness 自身、热改 prompt/provider。一级 tool API 只能表达作者枚举过的东西，eval 能表达作者没想到的。这条能力保留。
  - 安全论据不作为理由：本仓 bash 已是任意代码执行，`cat .env` 比 eval 更直接，所以「eval 危险」不成立。
- **一份真相源，派生态按需重建。** 本特征的总纲，也是仓内既有原则的延伸（config.edn 磁盘直读不缓存、base registry 不可变、会话注册表因「必须等于 JSONL」被判冗余——02 的测试断言正是这条）。推论一：**工具定义不落盘**。日志已是完整记录，需要时再显式晋升。推论二：**激活态也现算、不缓存**——`active-provider` 是「现在是什么」而非「跑的时候抄了一份」，故不算第二份真相源。
- **eval 的记录入口是 message 行，不是审计行（实测）。** 审计三行 `tools/pre-execute|execute|post-execute` 只带 `toolCallId` / `toolName` / `outcome` / `error`，**不带 args**。一次 eval 的 code 正文在 assistant message 的 `tool_calls[].function.arguments`（JSON 字符串，需二次解码），返回值在 tool message 的 `:content`，两者靠 `tool_call_id` 关联。**因此不需要为 eval 新增任何日志行。**
- **晋升 = 显式、人工、单向。** 人读日志、抄源码、进 `src/harness` 的正式工具表、走 git 提交。版本/回滚/污染治理由 git 承担；未晋升前进程重启归零，天然就是回滚。**绝不自动重放历史 eval**——那等于把日志变成可执行输入，确定性与安全一起崩。
- **提取器属作者工具，不给 agent。** agent 重启后经 `replay/history` 重建的对话里本就含过去的 `tool_calls`（replay 的断言已覆盖），再给它一个读自己 eval 历史的入口，等于把刚撤掉的读自省面从后门请回来。
- **已知局限（明写）**：若工具的 `:run` 闭包在先前 eval 定义的 `def` 状态上，源码可重建但状态要连那几条 eval 一起读——这是晋升必须人工判断的根因，也是不该自动化的理由。

## 非目标

- 不做自动重放、不做启动时恢复历史 eval。
- 不做工具定义落盘，不引入任何常驻的新真相源。
- 不给 agent 增加任何「读自己历史」的工具面——**但暴露 JSONL 路径与激活配置是允许的**（04），因为那是「问现在」，不是「抄历史」。
- 不改 kernel（loop/event）、不改 AG-UI 帧形状、不改既有 JSONL 行的 schema（03 是新增两种行，不是改已有行）。
- **不做真实安全围栏**：provider 变更的授权只落流程（04），不试图阻止 eval 绕过（本仓 bash 已是任意执行，围栏承诺不诚实）。
- **不做 provider 的运行时增删注册**（改 `providers.edn` + 热读即可，与 config.edn 同规矩）；**不做健康检查 / 自动 fallback**（另一个特性）。

## 验收主线

离线全量 `harness.test-runner` 全绿。

**02 是本仓第一次净减断言**：基线 55 tests / 247 assertions，移除的是「内存副本 == JSONL 逐字一致」这一族断言。移除清单必须在票面与本 spec 列明——`只增不减` 在此票不适用。01、03、04、05 仍按只增执行。

## 跨特性前置

- **`config-home`**（配置家目录 `~/.clj-harness` + `CLJ_HARNESS_HOME` 覆盖）是 **05 的前置**：05 新增的 `providers.edn` 要落在这个家里。**config-home 先落地，05 才开工。** 03/04 也受益（日志路径由 `harness.home` 单点派生），但非硬依赖。

## 状态

- 01（只读提取器）：未开始
- 02（撤内存副本）：未开始
- 03（provider 时间线 init/changed 落盘）：未开始（Blocked by 02）
- 04（会话状态暴露 + 授权变更 + per-thread 作用域）：未开始（Blocked by 02、03；与 05 互为阻塞，需同批次落地）
- 05（具名注册表 + 默认档 + 继承 + 请求指定）：未开始（**Blocked by config-home**；与 04 互为阻塞）
