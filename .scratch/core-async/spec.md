# spec: 引入 core.async 异步化

把内核事件流从"同步 `on-event` 回调"升级为"core.async 通道",并让 AG-UI 边真正异步抽取;随后在通道契约之上实现同一轮多工具调用的并发执行。

## 决策

- **异步边界 = kernel 事件通道。** 落点在 `loop` 层:把 `loop/run!` 的 `on-event` 接到一条 core.async channel(`run-chan`),而非改造 `llm/stream!` 或 fake provider。这样 `llm`、`fake`、`consume-sse` 及其测试零改动,爆炸半径被限制在 `loop` + `http` + `loop_test`。
- **expand–contract 改契约。** 先新增 `run-chan`(旧 `run!` 保留),再把 HTTP 边迁过去,最后删除旧 `run!`。每步 CI 绿。
- **线程纪律。** 工具执行为阻塞 I/O,用 `async/thread`(thread 池)而非 `go`;网络/SSE 抽取用 `go`/`alt!`。
- **历史如何带走。** `run-chan` 关闭前放一个携带最终 history 的终止事件;消费端(HTTP 边)忽略它,测试侧据此取 history。
- **并发工具的正确性。** `ag/outbound` 的 `:tool/result` 用 atom 上的 `(update :n inc)` 生成 id,并发 emit 会竞态——须让每条结果的 message id 在对应 `:tool/call` 时定下,或仅串行化"转换+emit"、并行化"工具执行"。

## 非目标

- 不改 AG-UI 帧形状、不改日志/回放格式、不改 `prompt.md`/`config.edn` 热读机制。
- 不为并发而并发:单工具轮、无工具轮的帧序列与现状逐字节等价。

## 验收主线

真实 HTTP 集成测试 `serves-a-well-formed-run-over-real-http` 与离线全量 `harness.test-runner` 在每一票落地后均保持全绿。

## 已验证到什么程度（2026-09-12，四票全部落地）

- **01**（`6d7f64b`）：`deps.edn` + `loop/run-chan`。旧 `run!` 当时保留，脚本化 provider 下通道事件序列与回调序列逐项一致，终止事件携带的 history == 旧 `run!` 返回值。
- **02**（`029ff18`）：`http/run-agent!` 去 `future`，`go` 循环抽取 `run-chan`，`:run/done` 忽略；inbound/provider 解析失败在 go 块内补 `RUN_STARTED..RUN_ERROR` 对。日志、CORS、UTF-8 字节边界、emitter/converter 每 run 一次的纪律不变。
- **03**（`eb876d7`）：`loop_test/drive` 改 drain `run-chan`（`:history`/`:seen` 形状不变），`ag_ui_test` 两处与 `replay/resume!` 一并迁移；全仓库无 `loop/run!` 残留后删除该函数及 ns 的 refer-clojure exclude。与旧 `run!` 的对比测试随之删除，场景覆盖由保留断言承接。
- **04**（`f1e5151`）：一轮多 tool call 每个各开一条缓冲 1 的 channel、`async/thread` 执行、`alts!!` 收集，`:tool/result` 按完成顺序回流；history 仍按 call 顺序回执（每 `tool_call_id` 恰好一条）。转换+emit 天然串行于通道的单一消费者，`ag/outbound` 的 id atom 无竞态——走的是 spec 决策的"串行化转换+emit、并行化工具执行"分支。channel 故意不关：alts!! 视已关空端口为就绪，drain 会吞幻影 nil。单测断言两个 300ms 慢工具 wall-clock <550ms（串行 >=600）；http 集成脚本扩成一轮双 read 工具。
- **清理**（`6965329`）：http 边去幽灵 `go-loop`（从不 recur），`start!` 去重复 merge。

**踩坑记录**：`alts!!` 返回 `[值, 端口]`，对返回值直接 map 解构得到全 nil——曾经让工具结果全部变成 `{:toolCallId nil, :content nil}`，靠集成测试抓住。

**测试**：45 tests / 175 assertions，三连跑全绿（含真 HTTP 集成的多工具轮、回放链路）。
