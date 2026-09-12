# 03: eval 自省端到端 —— agent 能在 eval 里查内存

**What to build:** agent 调用 eval 工具求值一段 Clojure，就能读到指定 thread 的会话消息尾与该 run 实际生效的 provider 快照——走 `harness.memory` 公共 helper，全程不需要文件 IO、不碰 ENV、不 var-quote opaque。prompt.md 补一节自省入口说明：`harness.memory` 是自省面（helper 名、thread-id 来源、返回形状），`harness.opaque` 是禁区。系统提示词的变更经冻结语义生效（提示词本身不动冻结机制）。

**Blocked by:** 02: 会话状态注册表 —— thread-id 寻址的 history 与生效配置快照

**Status:** ready-for-agent

- [ ] scripted provider run 完成后，经 eval 工具（`tools/run!` 走完整 tool-call 形态）求值读回该 thread 的消息尾，与注册表/落盘 JSONL 的 message 行逐字一致
- [ ] 经 eval 求值读回该 run 的 provider 快照，无 api-key 键
- [ ] 经 eval 求值读到 config.edn 的 `:protocol`/`:base-url`/`:model`（走 memory helper，无 api-key 字段）
- [ ] eval 对不存在 thread-id 的查询返回可读的空/缺失信息，不抛裸异常
- [ ] prompt.md 含自省入口说明（`harness.memory` helpers 与返回形状、`harness.opaque` 禁区声明），`prompt-is-frozen` 语义不受影响
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
