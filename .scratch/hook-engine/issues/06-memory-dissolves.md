# 06 — 收口：自省面归位并删除 harness.memory

**What to build:** 剩下的"问现在"的两问一答各回原位——本 thread 的日志路径回 `harness.home`
（它本来就是 home 路径的派生，写入者与它共用同一条派生规则），会话绑定的项目目录回 `harness.project`
（绑定就住在那里，仍然是"问出来、不抄副本"）。随后 `harness.memory` 整个文件删除：全仓零引用是这一票的
验收条件。README 的架构段按新模块图画。

这一票是这次搬迁的 contract 步：前面五票各自把一段搬走，本票确认没有一段留下。

**Blocked by:** 01, 03, 04, 05（01–05 是四张并行搬家的票，都能立刻开工；本票等它们全部落地）

**Status:** ready-for-agent

- [ ] 日志路径回 `harness.home`，与写入者共用同一条派生规则（一份实现、两个调用点）不变
- [ ] 会话的项目目录绑定回 `harness.project`，仍是"问出来、不抄副本"，nil = 未绑定（正常态，不是错误）
- [ ] `harness.memory` 文件删除，全仓零引用：src、dev、test、`prompt.md`、`README.md`、配置示例
      （`rg 'harness\.memory|mem/'` 归零）
- [ ] `README.md` 的架构段按新模块图重写：who 住在哪一段落一张表/一句话说清；
      **不再有"单一可自省面"这个说法**
- [ ] 旧 ns 的 docstring 里那些只为自省面辩护的话（"eval 可以自由读这些"之类的理由）不随文件一起消失——
      它辩护的能力已经换到 hook 引擎上，理由要在 07–13 的票里重新立起来（本票不写新文档，只在
      落地说明里点明哪些理由已经作废、哪些仍然成立）
- [ ] 全绿，断言数与基线持平（189 tests / 930 assertions）
