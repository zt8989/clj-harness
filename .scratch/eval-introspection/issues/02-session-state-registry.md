# 02: 会话状态注册表 —— thread-id 寻址的 history 与生效配置快照

**What to build:** 一个 run 结束后，它的最终 history 能按 thread-id 从 `harness.memory` 的会话注册表读到；同一个 run 实际生效的 provider 快照（剥离 api-key）也在注册表里可按 thread-id 读到。注册动作全部发生在 http 层 drain loop 捕 `:run/done` 处（与 JSONL 尾巴落盘同一落点），kernel 零改动；provider 快照在 current-provider 求值点取值后立即剥离 api-key。读取走公共 helper（session/threads），eval 与测试都不鼓励 var-quote。

**Blocked by:** 01: 自省边界两 ns 划分 —— harness.memory 与 harness.opaque

**Status:** ready-for-agent

- [ ] scripted provider run 完成后，测试按 thread-id 从 `harness.memory` helper 读到该 run 的最终 history，消息尾与落盘 JSONL 的 `kind:"message"` 行逐字一致
- [ ] 按 thread-id 读到的 provider 快照等于该 run 实际使用的 provider（含 override 生效场景），且快照中不存在 api-key 键
- [ ] provider-override 为 nil 的场景下，快照等于 config.edn 派生的 effective provider（同样无 api-key 键）
- [ ] kernel（loop/event）源码零改动；`records-the-run-as-jsonl` 等既有测试不受影响
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
