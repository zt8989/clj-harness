# spec: eval 自省

让 agent 能通过 eval 工具自省进程内存状态：当前会话的消息尾（run history）与本 run 实际生效的 provider 配置快照。

## 决策

- **现状边界（2026-09-12 确认）**。eval 是进程内求值（常驻 `harness.user`），任何 var 持有的状态都能读（含 private，var-quote 穿透）：`tools/registry`、`llm/frozen-prompt`、`http/provider-override`。config.edn 本身无密钥（api-key 来自 .env/dotenv），**是可以被自省的**——eval 调读取函数即可拿到 `:protocol`/`:base-url`/`:model`；唯一不可自省的是 **ENV 来源的 api-key**（2026-09-12 牛总澄清）。另一处不可自省：run history 是 `drive!` 内的**局部 atom**，非 var、不可寻址，run 结束后进程内无痕迹。
- **自省边界结构化：按可读性分两个 namespace（2026-09-12，牛总指令；api-key 粒度经牛总澄清修正）。** 把"eval 支持读取的内存数据/方法"与"不支持读取的"各自收拢，自省边界从 public/private 约定升级为结构边界：
  - **`harness.memory` —— 自省面（eval 可读，全 public）。** 收拢：冻结 system prompt（迁自 llm 的 frozen-prompt + prompt/reset-prompt!）、工具注册表（迁自 tools 的 registry/register!）、config.edn 读取（迁自 llm/config 的 edn 读取半边——`:protocol`/`:base-url`/`:model`，无密钥）、会话注册表（本特性新增：thread-id → history + provider 快照）。eval 的自省文档只指向此 ns，var-quote 兜底不再是约定接口。
  - **`harness.opaque` —— 非自省面（eval 不可读）。** 收拢且仅收拢密钥与可能携带密钥的原始 provider：api-key 的 dotenv/.env 解析（迁自 llm/config 的 env 半边——ENV 来源密钥绝不进自省面）、provider-override/use-provider!（原始 provider 可能携带密钥）。effective provider 的组装（memory/config + opaque api-key）也在此 ns，var 尽量 private。
  - **范围界定**：两 ns 只收 agent 相关的内存状态（会话、prompt、工具表、配置/密钥），不是收拢所有 defonce——llm 的 http-client 等基础设施 atom 留在原位。JSONL 落盘是磁盘 I/O 非内存数据，不参与划分；`drive!` 的 history 局部 atom 是 per-run 瞬态，无法入 ns，其最终值由 http 层在 `:run/done` **复制**进 `harness.memory` 会话注册表。
- **注册点 = http 层，kernel 零改动。** 注册发生在 drain loop 捕 `:run/done` 处——与 JSONL 尾巴落盘同一落点、同一时序窗口（尾巴落在终端帧之后一拍，是记录语义的已知代价，继承自 jsonl-message-record spec）。kernel（loop/event）不为此加观察者参数。
- **provider 快照剥离 api-key。** eval 是 agent 可调面，密钥绝不能进可寻址 var。快照在 `current-provider` 求值点取值后立即 dissoc。
- **不引入配置缓存。** config.edn 维持磁盘直读语义（edn 读取迁到 memory 后语义不变）；内存快照只回答"本 run 实际用了什么"，不复制缓存语义。
- **爆炸半径（实测 grep）**：`llm/prompt` 4 文件 6 调用点（http、replay、replay_test、llm_test）；`llm/config` 2 点（http、replay）；`use-provider!` 测试 1 点（http_test）；`register!` 测试 1 点（loop_test）。小仓一次迁移到位，expand-contract 不必要。

## 非目标

- 不做 run 进行中的实时消息自省（与 JSONL 尾巴同一时序窗口）。
- 不改 replay/resume、AG-UI 帧形状、JSONL 行 schema。
- 不缓存 config.edn、不引入 ENV 类自省、不收拢基础设施 atom。

## 验收主线

离线全量 `harness.test-runner` 在每一票落地后保持全绿（当前基线 46 tests / 188 assertions，只增不减）。ns 划分票为纯迁移零行为变化；会话注册表票断言：scripted provider run 完成后，按 thread-id 读到的消息尾与落盘 JSONL 的 message 行逐字一致、provider 快照等于 scripted provider 且无 api-key 键。

## 状态

- 01（自省边界两 ns 划分）：已落地（`1d92d5e`）
- 02（会话状态注册表）：已落地
- 03（eval 自省端到端）：已落地

## 已验证到什么程度（2026-09-12，三票全部落地）

- **01**：`harness.memory`（frozen-prompt/registry/config，全 public）与 `harness.opaque`（api-key 解析/provider-override/effective-provider 组装，var private）就位；调用点一次迁移（http/replay/四份测试），零行为变化，基线 46/188 全绿。
- **02**：`mem/record-provider!`（run 开始，dissoc api-key）+ `mem/record-history!`（:run/done，与 jsonl 尾巴同一落点）+ `mem/session` 读取 helper；http 集成测试断言快照无 api-key、history 尾巴与 jsonl message 行逐字一致（`the-session-state-is-addressable-by-thread-id`）。
- **03**：`the-session-record-never-holds-a-key`、`eval-joins-the-session-across-the-real-tool-call-shape`——走完整 eval tool-call 形态读到 config keys、unserved thread 返回 nil；prompt.md 增自省入口与 opaque 禁区声明。
- **最终全量**：55 tests / 247 assertions，全绿（含 tools-lifecycle 特性）。
