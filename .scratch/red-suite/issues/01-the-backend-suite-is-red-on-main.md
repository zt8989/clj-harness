# 01 — 后端套件在没有改动的 main 上就是红的（12 + 1）

**Status:** needs-triage
**Blocked by:** None

**事实（2026-09-30 实测）**：`clojure -M:test -m harness.test-runner`（整轮）在合并前的 main 上是
**1397 tests / 14394 assertions，12 failures + 1 error**；同一批命名空间在某次合并后的树上少三条
（那三条是 `mcp_wired_test` 的，见下）。

**红的四个命名空间**（`git log` 上看，最后一笔相关提交是 `hashline-upstream-parity` 那一族
「按上游 4.4.0 整体移除 boundary dedup」）：

- `harness.kernel.tools-test`：`specs-expose-every-base-tool`、
  `edit-requires-an-exact-unique-match`（四处）、
  `a-huge-answer-comes-back-as-a-tail-and-a-way-to-read-the-rest`、
  `a-background-command-runs-where-a-foreground-one-would`，另有
  `a-bound-session-roots-relative-paths-at-its-project` 在 `RT.java:1241` 抛错；
- `harness.kernel.hooks-test`：`the-system-prompt-point-was-added-as-one-row-of-the-same-table`；
- `harness.cap.claims-test`：`a-second-jvm-owns-a-conversation-until-it-goes-away`；
- `harness.cap.mcp-wired-test`：`a-run-sees-a-servers-tools-and-calls-one`（**flaky**：单独跑绿、
  和别的命名空间一起跑红，两种跑法都见过）。

**要定的（所以是 needs-triage）**：逐个判"断言陈旧（跟着那笔移除改）还是行为回归"。
`edit-requires-an-exact-unique-match` 那一组几乎肯定是前者（`str-replace` 那条路的断言没有跟着
`boundary-dedup` 的移除一起改）；`hooks` / `claims` 两条要先看行为。

**验收**：整轮后端套件回到 **0 failures / 0 errors**；或把剩下的每一条按名字裁成 `wontfix`
并把理由写进对应功能的 `spec.md`，然后把这张票删掉（约定：做完的票是删掉，不改标签）。
