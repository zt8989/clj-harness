# 01: 真机验证与预算收口

**What to build:** 把 harness 指向真实的 DeepSeek 端点跑通一轮完整对话——包含一次工具调用——并确认**同一 thread 的第二句话不返回 400**。第二句话才是关键：reasoning_content 跨 run 折返回传这条硬规则，只有走到第二句话才会被检验到。同时用手写的流式 fixture 换成一份真实响应体，并量一次各模块的真实行数。

**Needs:** `.env` 里的 `HARNESS_API_KEY`。这是一次性的凭据提供，不是人类实现，所以标签仍是 `ready-for-agent`——但 AFK agent 独自完不成，需要有人先把 key 放进去。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `.env` 有 key，`config.edn` 的端点与模型名是实际要用的那个
- [ ] 一轮含工具调用的完整对话在浏览器里跑通：正文、工具调用、可折叠推理卡片三样都可见
- [ ] **同一 thread 发第二句话不返回 400**——这是 reasoning 折返成立的唯一证据
- [ ] 手写的流式 fixture 被一份真实响应体替换，解析器测试对着它仍然全绿
- [ ] `cloc` 打出三个模块的真实行数，与 500 / 200 / 100 的预算对照，把结论写回 spec
