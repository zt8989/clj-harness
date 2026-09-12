# spec: jsonl 忠实消息记录

参考 applepi 的会话记录模型（append-only jsonl、消息行与过程行分离、"LLM 消息数组是文件的只读纯函数"），给本仓 jsonl 在 wire 记录之外补上**消息级忠实记录**：真实提交给模型的 system prompt、用户的发送的消息、LLM 返回的消息。

## 决策

- **行 schema：沿用 `{ts, runId, kind, payload}`，新增 `kind:"message"`。** payload = provider 形态消息**原样**——不展开、不重建。assistant 的 `reasoning_content`/`tool_calls`、tool 的 `tool_call_id` 都在 payload 里，角色读 `:payload.role`。不采用 applepi 的扁平 `role+content` 行：本仓 assistant 消息字段多，拍平必丢信息，原样嵌入才是"忠实"。applepi 的 start/end 事件族也不引入——本仓的过程记录已由 wire 帧行（`kind:"event"`）承担。
- **system prompt 冻结（2026-09-12 反转，牛总指出）。** 初版设计是 prompt.md 每 run 重读 + context 拼进 system 消息——system 前缀每次 run 都变，provider 的 prefill/前缀缓存永远打不中。反转后：`llm/prompt` 首次调用读取 prompt.md 即冻结（atom），`llm/reset-prompt!` 是热改的显式 counterpart（换一次冷 prefill 换新前缀）；per-run context 一律不碰 system 消息，改为尾部 user 消息提交（`ag/inbound`），冻结前缀覆盖 system + 全部历史。
- **写入时机 = 两个落点。** 提交侧：http 边在 `ag/inbound` 成功后、首调 LLM 前，把初始消息向量逐条写盘——system 行即冻结的 system prompt 原文，不是 prompt.md 文件的转述。返回侧：drain loop 捕获 `:run/done`，把 history 在初始向量之后的尾巴逐条写盘。
- **尾巴为何在 `:run/done` 落，而不是逐条增量。** kernel 事件词汇表七种、只携带 delta；组装后的 provider 消息只存在于 `drive!` 的 history。在 http 层从 delta 重组 assistant 消息等于复制 `llm.clj` 的 absorb 逻辑。`:run/done` 在 RUN_ERROR 后也会发出（`drive!` 捕 Throwable 后仍返回 history），任何 kernel 启动的 run 尾巴都完整。已知代价：JVM 被杀的窗口内该 run 的 message 行缺失——wire 帧行仍逐帧在盘，记录（RECORD）语义可接受，不为此给 kernel 加观察者参数。
- **replay 读侧不动。** `records->messages` 按 kind 过滤 input/event，message 行天然不可见；wire 帧仍是 replay 的 source of truth。读侧将来若改用 message 行，属另一个特性。
- **爆炸半径：`http` + `http_test` + README + 记录语义描述；冻结反转追加 `llm`（prompt 冻结）与 `ag_ui`（context 出 system），kernel 的 loop/event 仍零改动，replay 读侧不动。**

## 非目标

- 不记录发给 provider 的完整请求体（model/tools 字段）与原始 SSE chunk。
- 不改 replay/resume、不改 AG-UI 帧形状、不改既有 `input`/`event` 行。

## 验收主线

离线全量 `harness.test-runner` 在每一票落地后保持全绿（当前基线 45 tests / 175 assertions，只增不减）。集成测试 `records-the-run-as-jsonl` 扩展后：system 行 content 逐字等于 `prompt.md`（测试内 context 为空）、user 行等于客户端提交的消息、assistant 行含 reasoning_content 与 tool_calls 且与脚本逐字一致、tool 行按 call 顺序每 id 恰好一条；`the-log-the-server-writes-is-one-replay-can-read` 及 replay_test 全部不受影响。

## 已验证到什么程度（2026-09-12，两票全部落地）

- **01**（`4ba546f`）：提交侧——`ag/inbound` 成功后、首调 LLM 前，初始向量逐条落 `kind:"message"` 行。断言 system 行逐字等于 prompt.md、user 行 content 一致且无 AG-UI-only 字段（`:id` 已剥离）。
- **02**（`197ec05`）：返回侧——drain loop 捕 `:run/done`，`log-messages!` 落 history 尾巴。断言 assistant 行 reasoning_content/tool_calls 完整 payload 逐字（id/type/function.name/arguments）、tool 行按 call 序且 content 等于 read 工具真实返回的 deps.edn 原文。
- **code-review 修复**（`1531547`）：两处 doseq 落盘循环收口为 `log-messages!`；补齐上述逐字断言。
- **实测确认的时序窗口**：返回侧尾巴落在终端帧（RUN_FINISHED）之后一拍——`:run/done` 在 SSE 关闭后才到达消费者。测试读文件曾两次抢跑抓到空尾巴，`wait-for-recorded` 轮询（25ms 步进、2s 上限）容忍该窗口。这与"JVM 被杀窗口"同族，属记录语义的已知代价。
- replay 读侧（replay_test 全部 + `the-log-the-server-writes-is-one-replay-can-read`）零改动全过；kernel 零改动。

- **冻结反转已落地**（2026-09-12）：`context-rides-as-a-trailing-user-message` 断言 system 恒等于冻结 prompt、context 为尾部 user 消息、无 context 不追加；`records-the-run-as-jsonl` 的 system 行逐字断言改为无条件成立。全量 45 tests / 186 assertions 全绿。

**测试**：45 tests / 186 assertions，全绿。

**环境踩坑**：scoop 各 app 的 `current` junction 在本机是 msys 风格软链，原生 Windows 进程走不通；PATH 上的 `java` 是 JDK 8（无 java.net.http）。跑测试须用版本化实路径：`JAVA_HOME=scoop/apps/openjdk17/17.0.2-8` + `CLOJURE_TOOLS_DIR=scoop/apps/clj-deps/1.12.6.1673` + 直接调版本化 deps.exe。
