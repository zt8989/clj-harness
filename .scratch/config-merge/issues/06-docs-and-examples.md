# 06 — 文档与示例收口

**What to build:** 文档里今天说的「四份文件、两层副本」全部改成「一份 `config.edn` + 一份 `hooks.edn`」。

**Blocked by:** 04

**Status:** ready-for-agent

## 验收

- [ ] `harness.edn.example` 与 `mcp.edn.example` **删掉**，内容并进 `config.edn.example` 的
      `:session` / `:mcp` 两段（每条注释跟着搬，别丢——那几条注释是这几个键唯一的说明）
- [ ] `config.edn.example` 的头说**六段**（今天是「四段」），并说清「这个家只有这一份配置 + hooks」
- [ ] `README.md` 的「配置说明」那张文件清单：去掉 `harness.edn` / `mcp.edn` 两行，
      改成 `config.edn`（六段）+ `hooks.edn`；**README 只四节**，别长出新小节
- [ ] `docs/architecture/providers.md`：六段、`config-sections` 的新表、`ensure-config!` 的骨架文本
- [ ] `docs/architecture/mcp.md`（若有）：项目级整份取代那一段随项目级一起去掉，改成「`:mcp :servers`」
- [ ] `docs/architecture/projects.md` / `system-prompt.md` / `editing.md` / `hooks.md` / `compaction*`：
      凡是写 `harness.edn` 的地方改成 `config.edn` 的 `:session`，包括**失败句子**的样例
- [ ] `CONTEXT.md`：与配置家/围栏有关的那几条词条改住址；若有「harness.edn」做词条名，改名并留一句
      「曾经叫 harness.edn」
- [ ] `docs/rules/hotfix.md` 里若有「改 harness.edn 要 reset-prompt」之类的处方，跟着改
- [ ] **不许**在任何文档里留「harness.edn（另一种配置）」这种半句——合并的反面就是两份同名真相

## 不做

- 不重写这些文档的结构，只改事实与住址。
- 不动 `hooks.edn.example`（它还在）。
