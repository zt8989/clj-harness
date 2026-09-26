# 05 — 收口：文档、ADR 与两套全量

**What to build:** 把这次改动落进文档与验证，不给下一个人留两个版本的真相。

**Blocked by:** 01, 02, 03, 04

**Status:** ready-for-agent

- [ ] `docs/architecture/edge.md`：更新 `model/start` 一行（去掉 `:tools`，写 `:tools-names-hash` /
      `:hooks-names-hash` / `:tools-bytes` / `:tools-count`）；在讲压缩 / 压力表的那节写清「模型面只有一份，
      压缩与压力表都消费它；锚点比的是名字签名，不是 system 文本」
- [ ] `docs/architecture/edge.md` 或 `harness.edge.pressure` docstring：写明压力表**不再每轮读整份记录**，
      读的是 registry 增量维护的表针（并引用 ADR 0002 决定 2）
- [ ] 判断并落下 ADR：记录不再背工具表、以及「模型面是可交给 provider 的纯投影」这两条里，
      哪条值得进 `docs/adr/`（与 ADR 0003「不改记录格式」的边界写清）
- [ ] `CONTEXT.md`：若「模型面 / 客户端面」这两个词还没有，补上（单上下文，一个词一处定义）
- [ ] 离线全量：`clojure -M:test -m harness.test-runner` 全绿（记下 tests / assertions / failures / errors）
- [ ] 前端：`cd ui && npm test`、`npm run typecheck`、`npm run build` 全过
- [ ] 真浏览器走查（动过 `ui/src/` 才需要）：`node scripts/dev.mjs --scripted` ALL GREEN，
      证据放 `.scratch/model-surface-and-meter/evidence/`
- [ ] 在 thread `bbcd4ae4-…` 的副本上手工复现一次「80% 时压缩成功」，把前后压力数写进本票 `## Comments`
- [ ] 票 01–04 的 `Status` 收敛，spec 的「落地记录」补一段（与票面不同的地方、撞出来的坑）

## Comments

2026-09-24 — 证据基线：thread `bbcd4ae4-…`（129.7 MB / 362,359 行 / 2,165 条消息）。
落地后拿同一份对比「压缩前 79% usage → 压缩成功」与「run 开头压力表 < 10 ms」。

2026-09-24 — 收口做到哪一步：`docs/architecture/edge.md` 的 `model/start` 行与 system 行已改；补了一段
「模型面只有一份，锚点比的是签名」和一段「读记录是流式的」；新增 `docs/adr/0004`；`CONTEXT.md` 新增
「客户端面 / 模型面」词条，改了 `压力` / `轨迹` / `那三样` 三处口径。

离线全量 `harness.test-runner`：**1219 tests / 13494 assertions / 1 failure**（`harness.cap.claims-test`
的 `a-second-jvm-owns-a-conversation-until-it-goes-away`，KILLED 接管那条，预存在、与本特征无关）。
前端 `npm test`（128 全过）/ `npm run typecheck` / `npm run build` 全过；`node scripts/dev.mjs --scripted`
走查过：脚本跑一轮后 composer 的统计条正常（`log-stats` 已走流式折叠），轨迹面板「工具表」按签名分组，
显示「18 个工具」与「工具表本身已不再写进记录（票 04）…」。

**没做**：ADR 里「模型面是可交给 provider 的纯投影」我并进了 ADR 0004 的决定 5，没有单开一条；
「在 thread `bbcd4ae4-…` 上手工复现 80% 压缩成功」没做（隔离家里没有那份真记录）；票 03
（`state->pressure` + registry 表针）**未实现**，它的 `< 10 ms` 基准因此没有数字。
