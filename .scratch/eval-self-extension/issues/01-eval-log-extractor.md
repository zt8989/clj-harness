# 01: 只读提取器 —— 把一个 thread 的 eval 历史从日志里读出来

**What to build:** dev 侧一个只读入口（`harness.evals`，加一条可跑命令），输入日志目录与 thread-id，按发生顺序列出该 thread 每一次 eval：`toolCallId`、code 正文、返回值、是否出错。code 取自 assistant message 的 `tool_calls[].function.arguments`——注意它是 JSON **字符串**，要二次解码后取 `:code`；返回值按 `tool_call_id` 关联到 tool message 的 `:content`。读侧纪律复用 `harness.replay` 既有函数（`read-lines` / `lines->records`：坏行指名行号硬失败、不静默截断），不复制第三份文件名 sanitize 逻辑。同一入口附晋升路径的说明：看日志 → 判断哪段值得留 → 抄进 `src` 的正式工具表 → git 提交。不改 kernel、不新增任何日志行、不引入常驻状态。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 一轮含 eval 调用的真实 run 之后，提取器输出的 code 与该 thread 日志里 assistant message 的 `tool_calls[].function.arguments` 中的 code 逐字一致（含中间有空格的写法）
- [ ] 每条 eval 的返回值与该次 `tool_call_id` 对应的 tool message `:content` 逐字一致；出错的那次能被辨认出来（返回值内容或审计行的 error）
- [ ] 提取器与审计三行按 `toolCallId` 对得上；且「审计行不带 args」这一事实被测试锁定——防止日后有人误以为 code 在审计行里
- [ ] 没有任何 eval 的 thread：输出为空集合，不抛异常
- [ ] 日志坏行 / 截断：沿用 replay 的硬失败语义（指名行号），不静默返回一个短结果
- [ ] 新测试文件挂进 test-runner 的命名空间清单；kernel（loop/event）与 http 零改动
- [ ] 离线全量 harness.test-runner 全绿（基线 55 tests / 247 assertions，只增不减）
