# 06 — 收口：文档与 prompt 跟上「工具来自外部服务器」

**What to build:** 仓库里关于「工具有哪些、从哪来、配置放哪」的每一处说法与真实情况一致：home 的目录清单
多了 `mcp.edn`，架构段多了 `harness.mcp`，多了一节讲 MCP 的装配与契约，工具表那句枚举也已经不再是真的。
这一票是扇入点：01–05 都落地了，现状描述才真的过时到可以一次改对的程度。

**Blocked by:** 01, 02, 03, 04, 05

**Status:** ready-for-agent

## 验收

- [ ] README 的配置家目录清单加上 `mcp.edn`（含它「可以不存在」这条：不存在 = 一个服务器都没声明）
- [ ] 架构段的 `src/harness/` 清单加上 `harness.mcp`，一句话说清它是什么（客户端生命周期 + 工具桥接 +
      elicitation 接入），并指向本特征的 spec
- [ ] 新增一节「MCP（`mcp.edn`）」，写清：两级装配与整表替换、server 名规则与 `mcp__<server>__<tool>`、
      工具定义带 `:source`、**超时与重连**、**关闭不是隐藏**的语义、`mcp/server` 审计行的存在与用途、
      两条管理端点、以及 `:env`/api-key 那条「永不入日志与端点」的纪律
- [ ] **`prompt.md` 那句工具枚举不再枚举**：`Tools: read, write, edit, bash, eval.` 在外部工具进表那一刻
      就必然过时。改成不枚举的说法，并加一句让模型知道 `mcp__<server>__<tool>` 这类工具是**外部程序**提供的
      （来源不同，失败的样子也不同）——这是 prompt.md 的一次改动，按它的流程走（改完 `reset-prompt!` 或重启）
- [ ] 全仓搜一遍「六个内建」「six built-ins」那类把工具数写死的说法（`src/` 注释、`test/`、README、
      `.scratch/*/spec.md`），逐处判断是历史记录还是现状描述——**历史记录留着，现状描述改掉**
- [ ] `.scratch/general-harness/spec.md` 里 P1 的两条（工具来源泛化、MCP）标注为已由本特征交付，
      并把两处**收窄**写在那里：不做「按工具配审批策略与超时」，v1 只桥接 tools
- [ ] 验证段的测试基线更新为实际值（离线全量与 UI 两处）
- [ ] `clojure -M:test -m harness.test-runner` 全绿；`cd ui && npm test` 全绿；`cd ui && npm run build` 全绿
- [ ] 真 Chromium 把本特征各票的界面结论（MCP 面板三态）再走一遍，截图留档

## 复议（2026-09-16，`system-prompt-blocks` 落地时加，原文不动）

上面第 4 条验收要求把 `prompt.md` 那句工具枚举**改成不枚举的说法**。**不必了，而且比那个更强**：
`system-prompt-blocks` 让枚举**由构造正确**——`<tools>` 块在每次 run 组装 system 消息时从活的
工具表派生（会话注册的、关掉的、以后 MCP 桥进来的都在里面，还专门有一行说清哪些来自**外部程序**），
而 `prompt.md` 里那句枚举已经整段退场。

所以这一条落地时请**直接删掉**，不要再去写一句「不枚举」的话：**一句话都不写**比「写一句正确的空话」
更符合这个特征。其余各条不受影响。
