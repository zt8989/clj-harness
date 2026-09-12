# 01: 提交侧消息落盘——run 开始前记录真实提交的 system prompt 与入站消息

**What to build:** POST 一次 run 后，thread 的 jsonl 里出现 `kind:"message"` 行（schema 见 spec.md），payload 为 provider 形态消息原样：本次 run 组装出的 system prompt（prompt.md 重读 + context 拼接）与每条入站消息。日志从此能回答"当时喂给模型的是什么"。http 命名空间的 docstring 与 README 的行格式说明同步为三种行（提交侧部分）。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] 提交侧落点在 `ag/inbound` 成功之后、首调 LLM 之前：初始消息向量逐条写为 message 行，system 在首位
- [ ] system 行 content 与 `prompt.md` 逐字一致（测试输入 context 为空，故无拼接后缀）
- [ ] user 行 content 与客户端提交的消息一致，且为 provider 形态（AG-UI-only 字段如 `:id` 已被剥离）
- [ ] `records-the-run-as-jsonl` 先删旧文件再跑（对齐 replay-e2e 的做法，保证 first/last 断言确定），新增 system/user 断言
- [ ] ns docstring 与 README 行格式说明更新；离线全量 `harness.test-runner` 全绿

## Comments
