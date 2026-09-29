# 01 — config.edn 长出 `:session` 与 `:mcp` 两段

**What to build:** `harness.cap.providers`（config.edn 的唯一主人）的段表与形状检查认识新两段，
并给出**唯一**的读取入口，供 `cap.project` / `cap.mcp` / `edge.*` 用。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 验收

- [ ] `config-sections` = `#{:default :providers :ui :security :session :mcp}`；顶层未知键仍**按名字失败**，
      失败句子里把六段说全（今天那句只列了四段）
- [ ] `:session` 认这七个键：`:editing` / `:compaction` / `:llm` / `:approval` / `:skills` / `:instructions` /
      `:subagents`；`:mcp` 只认 `:servers`。**未知键按名字失败**（与 `:ui` / `:security` 同一条规矩：
      一个打错的键不许坐在那里什么都不做）
- [ ] 每个键**内部**的形状检查**不搬进来**：`editing.clj` 的「:editing 是这个形状吗」、`compaction` 的两个
      比例、`llm` 的整数……仍归各自消费者，报错句子里的文件名要改成新住址（票 02/03 做）
- [ ] `providers/session-config` 一次读出 `:session`（缺段 = `{}`），**每次调用重读文件**（与 `config` 同一条纪律）
- [ ] `providers/mcp-servers` 一次读出 `:mcp :servers`
- [ ] `providers-test` 覆盖：六段都写得进去、未知段/未知键按名字失败、缺段读成空、读的是新鲜的
- [ ] **不删** `home/harness-file` / `home/mcp-file`（票 04 的迁移还要用它们找到老文件）

## 不做

- 不改 `:session` 里任何键的语义（逐键合成的规矩、默认值、失败句子都照旧）。
- 不让项目级回来。
