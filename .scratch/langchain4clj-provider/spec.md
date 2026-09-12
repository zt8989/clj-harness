# spec: langchain4clj-provider（新方向，强上）

用户决策：只用 langchain4clj 做 LLM provider，自建链路最终移除。本 spec 与
`.scratch/minimal-kernel/spec.md` 的已定契约**正面冲突**，冲突逐条列在下面。
这不是一次等价迁移，而是一次换内核目标：从"极简可自改"换成"站在 LangChain4j 上"。

## 证据（01/03 已做，未改 src）

- streaming 句柄只有文本 `onPartialResponse`，无 reasoning 通道、无 tool 请求流，
  且入参是单 string，不是历史向量 + tools。
- OpenAI builder 透 `:base-url`（OpenRouter 地址可填），但 DeepSeek/OpenRouter 的
  `reasoning_content` 回传语义 LangChain4j 不识别，带 tools 多轮会丢字段。
- 单依赖约 150 个 jar（含 tika/poi/pdfbox/grpc/vertexai）。
- 基线 44 tests / 167 assertions 全绿；02 已补 provider 契约测试（45/174 全绿）并归档。

## 与 minimal-kernel 契约的冲突（逐条）

| minimal-kernel 已定 | 本方向接受 |
|---|---|
| 消息形状 = provider 原始形状，原样 append | 改用 LangChain4j 消息 EDN，经转换层进出；`reasoning_content` 不再逐轮保真 |
| 7 种内核事件，reasoning/text 逐 delta | 推理流式丢失：只有文本增量，推理只在受支持 provider 上整段返回 |
| 工具串行自执行，无迭代上限，失败回灌 | assistant 接管循环与执行，`max-iterations` 封顶，memory 窗口自动丢旧消息 |
| 不写重试/超时；服务端不持历史 | 允许库内 retry/timeout/memory（库自带意见） |
| core 261 行 / 预算 500 行 | 预算作废：单依赖约 150 jar |
| OpenRouter + free 模型真机链路 | 经 OpenAI 兼容 builder 填 base-url 续命，DeepSeek 400 回归不再保证 |
| AG-UI 推理折返邻接规则全链路成立 | 推理缺席时折返无物可折，客户端推理卡片退化 |

## 目标形状

- `stream!` 新增 `:langchain4clj` 实现：blocking chat 包装，文本增量发出，
  返回 provider 形状的 assistant 消息（无推理字段、无 tool_calls，直到 tool 翻译层补上）。
- 默认 `:protocol` 仍为老实现，直到 flag-day；flag-day 删除老 provider、老 SSE 解析、
  老 fixture 断言，重写受影响测试与 `ui/verify*.mjs`。
- 工具翻译层（OpenAI specs ↔ deftool/create-tool，kebab/camel 归一）另起 ticket，不在本刀。

## 不做（本刀）

- 不碰 `loop` 的调度权（接管 loop 是下一刀，与 ticket 03 的探针结论衔接）。
- 不删老 provider（那是 05 的 flag-day，受 04 门禁约束；04 结论即本 spec）。
