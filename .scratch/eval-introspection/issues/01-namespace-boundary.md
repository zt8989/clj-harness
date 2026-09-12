# 01: 自省边界两 ns 划分 —— harness.memory 与 harness.opaque

**What to build:** 进程内存状态按 eval 可读性收拢为两个 namespace，自省边界成为结构边界而非 public/private 约定。`harness.memory`（自省面，全 public）：冻结 system prompt（frozen-prompt + prompt/reset-prompt!，迁自 llm）、工具注册表（registry + register!，迁自 tools）、config.edn 读取（迁自 llm/config 的 edn 半边——`:protocol`/`:base-url`/`:model`，无密钥，eval 可调）。`harness.opaque`（非自省面，var 尽量 private）：仅收拢密钥与可能携带密钥的原始 provider——api-key 的 dotenv/.env 解析（迁自 llm/config 的 env 半边）、provider-override + use-provider!（迁自 http）、effective provider 组装（memory config + opaque api-key，供 http current-provider 与 replay 使用）。原 ns 的调用点一次迁移到位。本票是纯迁移，零行为变化。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `harness.memory` 持有冻结 prompt、工具注册表、config.edn 读取（返回值无任何密钥字段），全 public；`harness.opaque` 持有 api-key 解析、provider override、effective provider 组装，var private
- [ ] 迁移后原调用点（llm/tools/http/replay 及对应测试）全部改指新 ns，旧入口不保留双份
- [ ] config.edn 磁盘直读语义逐字不变；dotenv api-key 解析语义逐字不变；prompt 冻结语义（prompt-is-frozen）不改测试断言地通过
- [ ] llm 的 http-client 等基础设施 atom 留在原位，不参与迁移
- [ ] 离线全量 harness.test-runner 全绿（基线 46 tests / 188 assertions，只增不减），除 import/require 外零测试改动
