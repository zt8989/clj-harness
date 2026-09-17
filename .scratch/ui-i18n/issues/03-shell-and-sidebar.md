# 03 — 侧栏与外壳

**What to build:** 从用户视角：切成中文后，整条侧栏说中文——项目的分组与行、每个会话行上的磁盘事实
（大小、时间、「运行中」/「已归档」）、空态那两句、删项目的确认框（含「将不再列出 N 个会话」这类
带数字的句子）、加项目那一个按钮与它的悬停说明。

**Blocked by:** 01, 02 — 机制与那一族数字写法。

**Status:** ready-for-agent

## 验收

- [ ] `sidebar`（约 35 条）与 `thread-list.aui` 上游那 2 条（`current` / `Running`）进目录
      （`shell` namespace）。
- [ ] `lib/run-state.ts` 的两条拒绝句进目录（`errors` namespace）——它们由运行时那个适配器抛、
      由侧栏显示，是**本侧自己抬起来的句子**（服务端给的不翻，见 spec 决策 3）。
- [ ] 带数字与复数的句子走 `count`（「N 个会话将不再列出」），不是一个模板串拼出来的。
- [ ] `thread-list.aui` 的改动**逐处标 `LOCAL:`**——它已经有 5 处先例，照那个写法。
- [ ] 视图切换那两条（`Conversation` / `Trajectory`）**已经由 01 落地，不在本票范围**。
- [ ] 真机：中文下走一遍加项目 → 建一个会话 → 归档 → 删项目（确认框读中文、数字读中文），
      中英各一张截图进 `evidence/`。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
