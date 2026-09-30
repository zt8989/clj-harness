# 02 — UI 套件在没有改动的 main 上就是红的（2 条）

**Status:** needs-triage
**Blocked by:** None

**事实（2026-09-30 实测）**：`cd ui && npm test` → **193 passed / 2 failed（195）**。两条都在
**没有动过**的用例里，且在合并前的 main 上同样红（把 UI 改动 `git stash` 掉复现过）。

1. **`subagents` 套件**：`expect(body.path?.endsWith("harness.edn")).toBe(true)`
   —— 而 `src/harness/infra/home.clj` 的 `config-file` 现在发的是 `config.edn`，
   `harness-file` 自己标着 **RETIRED**。**这一条就是一行的事**（断言跟着改名走，或改成断言
   `config.edn`）。
2. **`elicitation` 套件**：`a-servers-question-parks-the-run-and-the-answer-finishes-it`
   —— `expected +0 to be 1`：run 没有在问题上 park。这条是**行为失败**；而 main 上刚并进去的
   `ask 卡片修复`（`17ab9e0`，park 必须落在最后一条 assistant 上）就在同一带，
   先判断是不是同一条。

**要定的（所以是 needs-triage）**：第 2 条要先定性（是产物陈旧还是行为回归），第 1 条直接改。

**验收**：`cd ui && npm test` 回到 0 failed；`npm run typecheck` 与 `npm run build` 保持绿；
`ui/test/ui.test.ts` 里钉死的 `EXPECTED_CASES` 跟着改（加/删用例必须一起改那个数）。
