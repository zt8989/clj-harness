# 06 — 收口：改 ADR 与三份文档、两套全量表、真浏览器走查

**What to build:** 把「步是第三级」这件事写进本仓的正式记录里，并把「当时判它不必存在」的那一页改口——
否则下一个人读 ADR 0006 会以为 `step/*` 是没人批过的私活。

从用户视角：`docs/adr` 里能读到为什么翻案，`docs/architecture` 的事实表里有 `step/*` 两行，
`CONTEXT.md` 的词表里有「步」这个量词，且三处的说法彼此不打架。

**Blocked by:** 02、03、04、05

**Status:** ready-for-agent

## 验收

- [ ] **新 ADR**：`docs/adr/0011-…`（今天最高是 `0010`；落地前 `ls docs/adr` 再确认一次号）。
      它要写明：**推翻 0006 决策 2 与「边界（不做的事）」第一条**，
      以及为什么（记录里没有那个区间；「算得出来的不写，算不出来的写」是 0006 自己立的纪律）。
- [ ] **0006 加注而不改写**：在 0006 顶部或那两条旁边加一条「**被 0011 取代**」的注记，
      正文一个字不改——本仓的规矩是加注，不是把历史改成一直都对（照 `.scratch/trajectory/05` 先例）。
- [ ] **`docs/architecture/edge.md`**：事实表加 `step/start` / `step/end` 两行，写清它们**进记录**
      （与同一张表里 `turn/*` 的「不进记录」正好对照），以及收口的时机（最后一个工具的结局之后）。
- [ ] **`docs/architecture/kernel.md`**：run 的那条 drain 循环与「一步 = loop 的一次迭代」核过。
- [ ] **`docs/architecture/client.md`**：折叠那一行的数从哪来（`turn/end` 的 `steps`）、
      三族的分类（`lib/mux.ts` 的 `familyOf`）核过。
- [ ] **`CONTEXT.md` 词表改口**：「步（step）」成为一等量词——今天那一栏写死了「别叫成 step / round /
      回合 / LLM call」，要多一行说「步」是什么、以及**一次模型调用**与它怎么分（一步可以含多次调用，
      重试算同一步）。
- [ ] **两套全量表**：`clojure -M:test -m harness.test-runner` 与 `cd ui && npm test` /
      `npm run typecheck` / `npm run build`（报数带上分支与提交）。
- [ ] **真浏览器走查**：`cd ui && npm run build && node scripts/dev.mjs --scripted`，
      自己开浏览器问一句、看着它跑完三步、折起来读那一行——**它不驱动浏览器，机器门绿挡不住
      「渲染看不到布局」那一格**（2026-09-18 那次 i18n 合并的教训）。证据落进 `evidence/`。
- [ ] `.scratch/step-events/spec.md` 把落地当次的实话补上（改了哪几个文件、哪条判据绿了、
      哪条变成了已知风险），照 `.scratch/turn-and-model-events/spec.md` 末尾几节的写法。
