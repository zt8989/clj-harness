# 07 — `ctx.systemPrompt`：组装升格成核心

**What to build:** system 消息的组装今天是四处凑出来的：`cap.system-prompt` 的函数、`SystemPrompt` 那个
hook 点、`kernel.llm` 里 `prompt.md` 的冻结头、以及每轮现算的三块（工具集合 / 绑定目录 / provider 档）。
这一票把**组装**升格成核心服务 `ctx.systemPrompt`：段落有唯一入口，工具 schema 从 `ctx.tools` 来，
冻结头仍逐字节冻结（provider 前缀缓存的前提）。

**Blocked by:** 06 — `ctx.tools`

**Status:** ready-for-agent

## 验收

- [ ] **铁律 2** 照旧：system 消息只有一条，它的开头冻结，追加都在其后
- [ ] 一次 run 的 system 消息与改动前**逐字节相同**（拿现成的记录对照）
- [ ] 「加一段上下文」有唯一入口，而核心**不认识**任何一段具体段落（谁在贡献是别人的事）
- [ ] 工具 schema 进提示走的是 `ctx.tools` 那一份，不是第二次拷贝（两处不一致会安静地坏事）
- [ ] 「本会话的事实每轮现算、承诺的开头冻结」这条分工不变；热改提示仍要 `reset-prompt!` 或重启
- [ ] 轨迹那一栏读出来的 system 条目照旧自包含（点开就有那张表，不必拉第二处）
