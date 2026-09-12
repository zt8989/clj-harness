# 03: eval 集成 + prompt.md —— agent 的会话工具自改面

**What to build:** agent 得知并会用会话级工具能力：经 eval 调 `harness.memory` 的 add/remove 在**当前 thread** 添加、删除、覆盖工具（影响下一 run 的 tools 数组），并能在添加后立即用新工具跑通一轮。prompt.md 自省文档补工具章节：会话工具集 API、覆盖语义、base 不可变与"改动只影响当前会话"的声明。

**Blocked by:** 01: 会话级工具注册表 —— base 不可变 + per-thread overlay；02: 工具执行生命周期 jsonl 事件 —— pre-execute / execute / post-execute

**Status:** ready-for-agent

- [ ] 经 eval 工具走完整 tool-call 形态：session add 一个工具 → 下一 scripted run 的 tools 数组含它 → 该工具被调用且返回预期结果 → jsonl 三相行齐全
- [ ] session remove 一个 base 工具 → 下一 run 的 tools 数组不含它，模型调用它得到可读错误
- [ ] add 同名覆盖 base 工具 → 本会话内新实现生效，base 注册表原定义不变
- [ ] prompt.md 含工具章节（API、覆盖语义、base 不可变、会话作用域声明），`prompt-is-frozen` 语义不受影响
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
