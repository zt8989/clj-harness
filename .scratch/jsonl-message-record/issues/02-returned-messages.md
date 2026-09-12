# 02: 返回侧消息落盘——:run/done 时记录 kernel 追加的 assistant 返回与 tool 消息

**What to build:** 同一 run 结束后，jsonl 的 message 行补齐模型真正返回的内容：drain loop 由忽略 `:run/done` 改为捕获它，把 history 在初始向量之后的尾巴逐条写盘——assistant 返回原样（`reasoning_content`/`tool_calls` 不重建，这是 `llm` 层的既有契约）、tool 结果按提交形态（按 call 顺序，每 `tool_call_id` 恰好一条）。RUN_ERROR 路径同样有 `:run/done`，失败 run 也留全量尾巴。至此同一文件按序构成完整消息数组，jsonl 能回答"模型答了什么、工具回了什么"。

**Blocked by:** 01-submitted-messages（message 行 schema 与写入口径在 01 定下）.

**Status:** ready-for-agent

- [ ] drain loop 捕获 `:run/done` 落尾巴，事件帧的转换与发送行为逐字节不变
- [ ] 第一轮 assistant 行 `reasoning_content` 与 `tool_calls`（含 id）逐字一致；最后一轮 assistant content 逐字一致
- [ ] tool 行按 call 顺序每 `tool_call_id` 恰好一条，content 与真实执行的读文件结果一致
- [ ] ns docstring 与 README 补齐返回侧描述；`the-log-the-server-writes-is-one-replay-can-read` 与 replay_test 全部不受影响
- [ ] 离线全量 `harness.test-runner` 全绿（45/175 基线之上只增不减）

## Comments
