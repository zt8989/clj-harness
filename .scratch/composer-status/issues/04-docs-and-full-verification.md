# 04 — 收口：文档与全量验证

**What to build:** 把本特征从「在办」搬进「现状」：`docs/architecture` 跟上，`CONTEXT.md` 的词与代码对齐，
两套全量 + 真机证据跑一遍，并把「记录清单」逐行核一遍。

从用户视角：下一个人读 `docs/architecture` 时看到的是**今天的样子**——状态条读哪个端点、那五个数从哪来、
一次调用的两个数什么时候才存在；而不是一篇要么没写、要么写着「计划中」的文档。

**Blocked by:** 03

**Status:** ready-for-agent

## 要动的地方

- `docs/architecture/edge.md`：同页那句「**读日志的代码只认 `input` / `event` 两种行**；其余是审计轨迹」
  今天不再成立——本特征的读侧正读 `model/*`，把它改成今天真实的分工。
  （那张行表与 `kernel.md` 的「11 种」表由票 01 改，这里只核。）
- `docs/architecture.md`：`harness.edge` 那张表加 `edge.stats` 一行（是什么、与 `edge.replay` 的分工）；
  顶部**快照点**更新到收口那次的提交；**「在办」一节里本特征那条删掉**（它落地了）。
  ——那条「在办」是**立票当天在主仓 `docs/architecture.md` 上未提交地加的**（与 `session-context`
  那条同处一个未提交改动里，见 `docs/architecture.md` 的「在办」）。所以本票在 worktree 里做的事是：
  把**现状**写进这一节的替代文字；那条「在办」在收尾回主仓时删掉（主仓那份是权威），**不要**
  为了删它在 worktree 里重新加一遍。
- `docs/architecture/client.md`：装配那棵树加 `components/composer-stats.tsx` 与 `lib/stats.ts`；
  「状态的归属」里写清两件事——条子读的是**记录**（不是客户端内存里的那份对话），
  以及它的**刷新时机**（一次调用结束、run 结束；不轮询，理由是调用的数只有 `model/end` 之后才存在）。
- `docs/architecture/overview.md` 的**状态表不动**：统计是记录的**读法**，没有新增状态，也没进库。
  落票时核一遍，确认真的不必动——**不是**跳过它。
- `CONTEXT.md`：核 01 立的四个词（轮 / 模型调用 / 会话统计 / 缓存命中）与落地后的代码**一致**
  （名字、判据、「别叫成」那行）；代码里若用了别的词，改代码或改词条，不许两个词并存。
- `README.md`：**验证**那节引的测试数（今天写着后端 664 / 9996 与前端 11 tests）跟着改成实际的数，
  并带上分支与提交。README 的其余部分照旧（入口页只讲介绍/前置/配置/启动）。
- **不回头改 `.scratch/trajectory/` 的旧话**：01 已经加过复议注，这里只核它还在。

## 验收

- [ ] `docs/architecture` 里搜「读日志的代码只认」与「十一（11）种事件」两处旧话，都不再存在；
      `docs/architecture.md` 的快照点 = 本次收口的那次提交。
- [ ] 「在办」一节里搜不到本特征；`edge.stats` 出现在模块地图里；`client.md` 里查得到
      `composer-stats.tsx` / `lib/stats.ts` 与刷新时机那两句。
- [ ] `CONTEXT.md` 的四个词条与代码里的用法一致（逐条对着 `harness.edge.stats` 与
      `composer-stats.tsx` 核，含「别叫成」）。
- [ ] **记录清单逐行核一遍**：条子上每一格都指得到记录里的某个字段，且**没有第二处算同一件事**的地方
      （评审检查表，逐行打勾写进 evidence）。
- [ ] 两套全量 + 构建：`clojure -M:test -m harness.test-runner` **不许多出新的失败名字**
      （本机那 4 条既有的写在 spec 的「状态」那节，条数每次跑不一样，看名字不看条数）、
      `cd ui && npm test`（15 + 本特征新增的用例全过）、`cd ui && npm run build` 无错；
      报数带上**分支与提交**（两套的数都要）。
- [ ] 真机一轮：一条会话跑三轮（含一次工具调用、一次让 provider 中途失败），
      条子的截图与 `GET /api/threads/<stem>/stats` 的输出并排放进 `.scratch/composer-status/evidence/`；
      失败那次在 `steps` / `calls` 里、不在用量与速率的分母里——**在这一份证据上看得见**。
