# 24 — 收口：拆掉旧四层与 `install!` 缝，改写现状文档

**What to build:** 到这一步新形状已经在跑、旧形状还在。这一票把旧的**删干净**并改写现状文档：
`docs/architecture.md` 的模块地图、`docs/architecture/layers.md`（四层那篇要么重写成三类服务、要么被新篇
取代）、以及受影响的其他现状篇。**不许留转发层**——「旧名字转发到新实现」会让两套形状同时活着，
而下一批人读不出哪套是真的。

**Blocked by:** 12–21 各接缝（`llm` / `fs` / `subprocess`·`shell` / `web` / `compaction` / `subagent` /
`mcp` / `sandbox` / 投影 / 指令与技能）、22、23

**Status:** ready-for-agent

## 验收

- [ ] `src/harness/` 下不再有旧的四层结构：旧层名与新角色名不同时存在，也不留转发
- [ ] `install!` 那两道门**必须作答**：要么删掉（能力全走接缝），要么只剩一处并说明为什么只剩下它。
      留着却说不清它是谁的，不算通过
- [ ] `docs/architecture.md` + `layers.md` 与代码一致；本仓写明了**守卫测试查 require 不查内容**，
      所以内容那一半靠人过一遍（那张归属表里没有一条还写着「待定」）
- [ ] 全量套件退 0（`clojure -M:test -m harness.test-runner`，以退出码为信号）
- [ ] `cd ui && npm test` 与 `npm run build` 照旧绿、`ui/` 一行没动
- [ ] 真路径走查一次：`node scripts/dev.mjs --scripted`，并自己开浏览器走一趟（本仓规矩：
      机器门全绿挡不住「渲染看不到布局」那一格）
- [ ] 本仓三条铁律与 ADR 0005 / 0012 / 0013 各有一条指向新形状的落点
