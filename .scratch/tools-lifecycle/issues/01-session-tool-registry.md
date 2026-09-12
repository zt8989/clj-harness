# 01: 会话级工具注册表 —— base 不可变 + per-thread overlay

**What to build:** 工具集从全局单例变为"不可变 base + 会话 overlay"。base 启动时注册后运行期不改；`harness.memory` 提供会话级 add/remove（add 允许覆盖 base 同名——仅本会话胜出，base 原定义不动；remove 先撤 overlay 添加、再对 base 名记隐藏，对不存在的名字 no-op）；有效工具集 = base ⊕ overlay。thread-id 从 http 边贯通 kernel 到工具层：LLM 每轮拿到的 tools 数组、工具调用的 dispatch 都按该 thread 的有效集解析。会话结束后 overlay 即弃，新会话从纯 base 起步。

**Blocked by:** eval-introspection/01: 自省边界两 ns 划分 —— harness.memory 与 harness.opaque

**Status:** ready-for-agent

- [ ] scripted run 断言：LLM 收到的 tools 数组反映该 thread 的 overlay（session add 的新工具出现、session remove 的 base 工具消失、session 覆盖的同名工具为本会话版本）
- [ ] overlay 只影响本 thread：第二个 thread-id 的 run 看到纯 base 工具集
- [ ] add 覆盖 base 同名后，base 注册表原定义逐字不变；remove 对不存在名字 no-op 不抛错
- [ ] 未注册工具的调用返回可读错误（不抛裸异常），与现状等价
- [ ] kernel 签名扩展最小化：run-chan/drive! 只增 thread-id 传递，事件词汇表与 ag_ui 出站零改动
- [ ] 离线全量 harness.test-runner 全绿（基线 46 tests / 188 assertions，只增不减）
