# 03 — 三条声明里钉住 `AGENTMEMORY_URL`

**What to build:** `~/.clj-harness/hooks.edn` 那三条 `:command` 前面加上赋值前缀
（`AGENTMEMORY_URL=http://localhost:3111 node "…/scripts/hooks/agentmemory.mjs" session-start`），
并在文件头写一句为什么。

**Status:** ready-for-agent

## 为什么要紧

今天它靠**默认值恰好对**：脚本里是 `process.env.AGENTMEMORY_URL || "http://localhost:3111"`，
而这台机器的服务器就在 3111。换端口（或换一台机器）之后，`fetch(...).catch(() => {})` 会把失败
**吃干净**——没有报错、没有审计行、没有观察，只有「记忆好像不记事了」。

而 `hooks.edn` 的声明**没有 `:env` 键**（四个键是 `:command` / `:matcher` / `:timeout` / `:run`），
所以钉地址的唯一办法是命令行的赋值前缀——命令行走的是钉住的登录 shell，`VAR=value cmd` 成立。
`mcp.edn` 那边已经写明了 `:env`，两条配置说的是同一件事，不该只有一条说。

## 验收

- [ ] 三条声明都带前缀；改完跑一次，服务器照旧收到 `session/start`（库里出现那个 session）
- [ ] 把前缀里的端口**故意改成错的**跑一次，确认这次失败是**静默的**——然后把这个观察写进
      `hooks.edn` 头部的注释（这就是要钉它的理由：错了不会有人告诉你）
- [ ] 文件头补一句：`mcp.edn` 与 `hooks.edn` 各说一遍同一个地址，是故意的（两份配置各自完整，
      不互相读）
- [ ] 不需要动代码，不需要跑全量；真跑一条 `:session-start` 就够
