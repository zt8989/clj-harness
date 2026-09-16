# 02 — 解析结果与线上暴露：两个数字随 model 走完端点与日志

**What to build:** 「这个会话现在用的模型，上下文多大、最多吐多少 token」这个问题，在三个地方都有答案：
`GET /api/model`、解析结果（`active-provider`）、以及 JSONL 的 `provider/init` 与 `provider/changed`
行。换一个 model，答案随之改变；目录没声明某个数字时，该字段**缺席**而不是填 `null`——「没人声明过」
和「声明为零/空」是两件事。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 两个数字进入 `harness.models` 的 `resolved-fields`（`resolved-fields` 同时是 inline 形式的字段集），
      解析结果因此自然带上它们
- [ ] `active-provider` 的答案里带上两者；未声明时字段不存在
- [ ] `GET /api/model` 的 200 响应带上 `context-window` / `max-output-tokens`，值是**数字**不是字符串；
      未声明时字段缺席，不是 `null`
- [ ] 该端点仍然是只读的：不落任何审计行（该 thread 的日志文件根本不存在）
- [ ] `provider/init` 与 `provider/changed` 的 `:resolved` 带上两者。日志活得比目录久：内置表以后改了，
      旧日志仍说得出「当时这个模型声称多大窗口」——这正是 `:resolved` 当初要记录而不是留给读者重算的
      同一条理由
- [ ] 端到端：会话切到另一个 model（数字不同）后，端点的答案随之改变，含 endpoint 一起动——证明读的是
      目录声明，不是写死的常量
- [ ] 序列化只发生在写线的那几处：内部是整数，写日志/HTTP 时原样是整数；集合渲染成排序字符串数组的
      既有规则不受影响
- [ ] 离线全量 `harness.test-runner` 全绿
