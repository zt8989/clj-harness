# spec: jsonl 忠实消息记录

参考 applepi 的会话记录模型（append-only jsonl、消息行与过程行分离、"LLM 消息数组是文件的只读纯函数"），给本仓 jsonl 在 wire 记录之外补上**消息级忠实记录**：真实提交给模型的 system prompt、用户的发送的消息、LLM 返回的消息。

## 决策

- **行 schema：沿用 `{ts, runId, kind, payload}`，新增 `kind:"message"`。** payload = provider 形态消息**原样**——不展开、不重建。assistant 的 `reasoning_content`/`tool_calls`、tool 的 `tool_call_id` 都在 payload 里，角色读 `:payload.role`。不采用 applepi 的扁平 `role+content` 行：本仓 assistant 消息字段多，拍平必丢信息，原样嵌入才是"忠实"。applepi 的 start/end 事件族也不引入——本仓的过程记录已由 wire 帧行（`kind:"event"`）承担。
- **写入时机 = 两个落点。** 提交侧：http 边在 `ag/inbound` 成功后、首调 LLM 前，把初始消息向量逐条写盘——system 行即本次 run 真实提交的提示词（prompt.md 每次重读 + context 拼接的结果，不是 prompt.md 文件本身）。返回侧：drain loop 捕获 `:run/done`，把 history 在初始向量之后的尾巴逐条写盘。
- **尾巴为何在 `:run/done` 落，而不是逐条增量。** kernel 事件词汇表七种、只携带 delta；组装后的 provider 消息只存在于 `drive!` 的 history。在 http 层从 delta 重组 assistant 消息等于复制 `llm.clj` 的 absorb 逻辑。`:run/done` 在 RUN_ERROR 后也会发出（`drive!` 捕 Throwable 后仍返回 history），任何 kernel 启动的 run 尾巴都完整。已知代价：JVM 被杀的窗口内该 run 的 message 行缺失——wire 帧行仍逐帧在盘，记录（RECORD）语义可接受，不为此给 kernel 加观察者参数。
- **replay 读侧不动。** `records->messages` 按 kind 过滤 input/event，message 行天然不可见；wire 帧仍是 replay 的 source of truth。读侧将来若改用 message 行，属另一个特性。
- **爆炸半径：`http` + `http_test` + README，kernel（loop/llm/event/ag-ui）零改动。**

## 非目标

- 不记录发给 provider 的完整请求体（model/tools 字段）与原始 SSE chunk。
- 不改 replay/resume、不改 AG-UI 帧形状、不改既有 `input`/`event` 行。

## 验收主线

离线全量 `harness.test-runner` 在每一票落地后保持全绿（当前基线 45 tests / 175 assertions，只增不减）。集成测试 `records-the-run-as-jsonl` 扩展后：system 行 content 逐字等于 `prompt.md`（测试内 context 为空）、user 行等于客户端提交的消息、assistant 行含 reasoning_content 与 tool_calls 且与脚本逐字一致、tool 行按 call 顺序每 id 恰好一条；`the-log-the-server-writes-is-one-replay-can-read` 及 replay_test 全部不受影响。
