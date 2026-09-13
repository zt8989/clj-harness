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

- 01（只读提取器）：**已完成**（`4d6202e`）
- 02（撤内存副本）：**已完成**（`762b0d2`）
- 03（provider 时间线 init/changed 落盘）：**已完成**（本批次）
- 04（会话状态暴露 + 授权变更 + per-thread 作用域）：**已完成**（本批次，与 05 同批）
- 05（具名注册表 + 默认档 + 继承 + 请求指定）：**已完成**（本批次，与 04 同批）

## 已验证到什么程度（2026-09-13）

- **01（`4d6202e`）**：`dev/harness/evals.clj` 就位，`clojure -M:evals <thread-id> [log-dir]` 可跑（deps.edn 新增 `:evals` 别名）。提取器的两条键事实被**测试锁定**：code 来自 assistant message 的 `tool_calls[].function.arguments`（JSON 字符串，二次解码取 `:code`），返回值按 `tool_call_id` 关联 tool message 的 `:content`；`the-code-is-NOT-in-the-audit-lines` 用测试钉住「审计三行不带 args」。**端到端实测**：真写一个 http 形态的 jsonl，经 `replay/lines->records` 读回，code 与提交时**逐字一致**（含内部换行与空格），CLI 渲染正常。边界：无 eval → 空集合；非 eval 调用跳过；调用已记录但返回未落地 → 照常列出、`:result` 为 nil；参数解不开 → `:undecodable` 而非丢弃；坏行沿用 replay 的指名行号硬失败。kernel 与 http **零改动**。
- **02（`762b0d2` + 收口 `4e4d267`）**：见 `eval-introspection/spec.md` 的反转记录。memory ns 纯删 26 行，http 的 `:run/done` 分支由 `(do ..)` 塌缩为单个 `log-messages!`，落盘时序逐字不变。测试净减 2/8。
- **03 / 04 / 05（本批次）**：
  - **05 解析器**：`opaque/resolve-provider` 四级（注册表具名项 → `config.edn` 默认档字段覆盖 → 会话级 per-thread override → 请求级字段），逐字段合并，字段不出现即落回上一档。`config.edn` 支持三形（具名 `{:provider :cheap}` / inline map `{:provider {...}}` / flat `{:protocol .. :model ..}`），缺 `providers.edn` = 空注册表**不报错**（不命名就不强制创建），命名了一个不存在的名字 → **报错信息指明缺的是哪个名字** + 列出注册表已有名字。请求字段走顶层 `(:provider input)`，**不进 `:context`**（会污染 prompt cache）。`api-key` 唯一解析点在 `opaque/api-key`，只进 `resolve-provider` 的返回，其它任何路径都不挂。`http.clj:145` `(opaque/current-provider thread-id (:provider input))`；`replay.clj:109` `(opaque/effective-provider thread-id)`。
  - **04 per-thread 拆分**：`opaque` 从单槽 `provider-override` 拆为**两个语义独立的 atom**——`scripted-pins`（测试 seam，整 provider 装上）vs `session-overrides`（partial `{field value}`，tier 3）。`current-provider` 检查 `pinned-provider` 优先；`use-provider!` 与 `set-override!` 是两个 public 入口，**互不污染**。`with-server` 测试夹具必须按 thread 装，不能借进程全局槽——这条**铁律**有测试在守（`http_test.clj` 的接线改造、parked test 显式 per-thread pin）。
  - **04 自省面**：`mem/log-path` 与 `http.clj:42` 共用 `home/log-file` → `home/sanitize`（一份实现、两个调用点），`replay.clj` 通过 `[dir thread-id]` 双 arity 走自己的目录、不依赖 `home` 的写侧路径。`mem/active-provider` 现算、走 `opaque/effective-provider` 后**只 select-keys 四字段**——`api-key` 永不进返回值（测试 `active-provider` 内无 `api-key` 任何深度）。
  - **04 写侧（`session-configure` 工具）**：标 `:requires-approval true`，调用即 park、批准后 body 才跑；body 调 `opaque/set-override!` + 记 `provider-changes` outbox。`mem/take-provider-changes!` 由 `http.clj` drain 落 `provider/changed`（after `approval/decided`）。三字段独立可选、空调用拒绝。`session-require-approval!` 状态不参与（**工具自带的 `:requires-approval` 已是 sufficient gate**）。
  - **03 时间线**：`provider/init` 一次每 thread、`provider/changed` 每次变更，**不落否决**（"改了" vs "被拒"通过该行是否存在区分）。init 落点在 `input` 后、第一条 `message` 前；changed 落点在 `approval/decided` 后。`replay/history` 只认 `input`/`event`，两种新行不参与回放（`provider/init` 早于第一 message：`assert: (< indexOf "input" < indexOf "provider/init" < indexOf "message")`）。
  - **依赖环拆掉**：`memory` 现在 require `opaque`（为 `active-provider`），`opaque` 反向只 require `home`（**不再 require `memory`**——它通过 `home/config` 直接读 `config.edn`）。把 `mem/config` 的调用挪进 `opaque` 的私有 `config` 函数，循环即解。
  - **测试基线**：83 → 101 tests / 405 → 467 assertions（净增 18 tests / 62 assertions，0 failures），全量通过。
- **tool-toggles 联动**：本特征的「可写」一侧由 `tool-toggles` 特性独立落地（会话级 disable!/enable!，`:disabled` 取代误报的 unknown-tool），见 `.scratch/tool-toggles/spec.md`。
- **全量**：101 tests / 467 assertions，全绿；测试跑在隔离家目录，真实 `~/.clj-harness/logs/` 零写入。
