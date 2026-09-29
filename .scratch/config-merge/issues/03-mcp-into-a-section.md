# 03 — MCP 收进 `:mcp :servers`

**What to build:** `harness.cap.mcp/config` 改读 config.edn 的 `:mcp :servers`；`.harness/mcp.edn` 不再被读。
「什么都没说」与「声明了零个」的区分必须原样保住——那是这份文件今天最要紧的一条语义。

**Blocked by:** 01

**Status:** ready-for-agent

## 验收

- [ ] 缺 `:mcp` 段（或 `:mcp {}`）= 一个服务器都没声明，工具表里不出现 MCP 的工具
- [ ] `:mcp {:servers {}}` = **明确声明零个**（与今天 `{:servers {}}` 同义），且与「什么都没说」读起来是两回事
- [ ] 一条**坏声明**（既没 `:command` 也没 `:url`、两个都有、名字不可用……）仍是**按名字失败**，
      句子带上「哪个文件、哪一个 key」——今天那句是 `mcp.edn`，改成 config.edn 的 `:mcp`
- [ ] 会话级 overlay（`mcp/…!`）与工具表的行为一字不动（`mcp-test` / `mcp-wired-test` 绿）
- [ ] `.harness/mcp.edn` 里写什么都不再影响任何会话（一条测试钉住）
- [ ] `mcp.edn` 那份例子里「项目文件整份取代用户文件」的说明**随项目级一起去掉**（票 06 落到文档）

## 不做

- 不改传输（stdio / http）、不改工具命名与去重。
- 不做服务器热重载。
