# spec: eval 自我扩展

让 eval 的定位从「读自省」改为「写自我扩展」：它存在的理由是 agent 能在运行期给本次会话长出新的手脚，而不是替 agent 去读那些文件里本来就有的东西。

## 决策

- **eval 的价值在「写」，不在「读」（2026-09-13，牛总裁定）。**
  - **读**：config.edn 就在磁盘上、prompt.md 就在磁盘上、消息尾就在 JSONL 里。agent 用 read/bash 直读，可带 offset/limit/grep，比任何固定形状的自省入口更省——而且 config.edn 只有三行，为它保一个 accessor 收益为负，还要多花 prompt 篇幅向模型解释。**读自省面因此撤销**：prompt.md 的 Introspection 段删除，`harness.memory` 的会话注册表（sessions / record-provider! / record-history! / session）删除。
  - **写**：运行时自我扩展是 eval 不可替代的能力——包裹/改写已有工具做中间件、`alter-var-root` 热修 harness 自身、热改 prompt/provider。一级 tool API 只能表达作者枚举过的东西，eval 能表达作者没想到的。这条能力保留。
  - 安全论据不作为理由：本仓 bash 已是任意代码执行，`cat .env` 比 eval 更直接，所以「eval 危险」不成立。
- **一份真相源，派生态按需重建。** 本特征的总纲，也是仓内既有原则的延伸（config.edn 磁盘直读不缓存、base registry 不可变、会话注册表因「必须等于 JSONL」被判冗余——02 的测试断言正是这条）。推论：**工具定义不落盘**。日志已是完整记录，需要时再显式晋升。
- **eval 的记录入口是 message 行，不是审计行（实测）。** 审计三行 `tools/pre-execute|execute|post-execute` 只带 `toolCallId` / `toolName` / `outcome` / `error`，**不带 args**。一次 eval 的 code 正文在 assistant message 的 `tool_calls[].function.arguments`（JSON 字符串，需二次解码），返回值在 tool message 的 `:content`，两者靠 `tool_call_id` 关联。**因此不需要为 eval 新增任何日志行。**
- **晋升 = 显式、人工、单向。** 人读日志、抄源码、进 `src/harness` 的正式工具表、走 git 提交。版本/回滚/污染治理由 git 承担；未晋升前进程重启归零，天然就是回滚。**绝不自动重放历史 eval**——那等于把日志变成可执行输入，确定性与安全一起崩。
- **提取器属作者工具，不给 agent。** agent 重启后经 `replay/history` 重建的对话里本就含过去的 `tool_calls`（replay 的断言已覆盖），再给它一个读自己 eval 历史的入口，等于把刚撤掉的读自省面从后门请回来。
- **已知局限（明写）**：若工具的 `:run` 闭包在先前 eval 定义的 `def` 状态上，源码可重建但状态要连那几条 eval 一起读——这是晋升必须人工判断的根因，也是不该自动化的理由。

## 非目标

- 不做自动重放、不做启动时恢复历史 eval。
- 不做工具定义落盘，不引入任何常驻的新真相源。
- 不给 agent 增加任何「读自己历史/配置」的新工具面。
- 不改 kernel（loop/event）、不改 AG-UI 帧形状、不改既有 JSONL 行的 schema（03 是新增一种行，不是改已有行）。

## 验收主线

离线全量 `harness.test-runner` 全绿。

**02 是本仓第一次净减断言**：基线 55 tests / 247 assertions，移除的是「内存副本 == JSONL 逐字一致」这一族断言。移除清单必须在票面与本 spec 列明——`只增不减` 在此票不适用。01、03 仍按只增执行。

## 状态

- 01（只读提取器）：未开始
- 02（撤读自省面）：未开始
- 03（provider 快照落日志）：未开始
