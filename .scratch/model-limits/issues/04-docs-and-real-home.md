# 04 — 收尾：文档、示例文件与开发机真实家目录

**What to build:** 一个读者照着 README 和示例文件配置，能知道这两个字段存在、写在哪、并且知道它们**只是
被报告**——本仓既不数 token 也不把 `max-output-tokens` 发给厂商。开发机真实家目录的 `providers.edn`
按新形状手工改写一次（人做，测试永不碰真实家目录）。

**Blocked by:** 01, 02

**Status:** ready-for-agent

- [ ] README 的「配置」段：provider 目录的形状里出现这两个可选字段，并写明两者都可选
- [ ] `providers.edn.example` 同步；顺手改掉示例里 `deepseek/deepseek-chat` 这个已作废的 id
      （内置表早就是 `deepseek-flash` / `deepseek-v4-pro`，示例文件漏改了）
- [ ] `harness.models` 的 ns docstring、`harness.llm` 里描述 provider map 的那段（提到 `:input` / `:output`
      的地方）与 `harness.http` 的 `/api/model` 示例响应同步
- [ ] 写明边界：本仓不做 token 计数、不拦超窗、不把 `max-output-tokens` 写进请求体——否则下一个读者
      会以为它在执行什么规则
- [ ] 开发机真实 `~/.clj-harness/providers.edn` 手工改写并实测一次（`active-provider` 能报出两个数字）
- [ ] 本特征的 `spec.md` 补上「已验证到什么程度」与结论
- [ ] 离线全量 `harness.test-runner` 全绿
