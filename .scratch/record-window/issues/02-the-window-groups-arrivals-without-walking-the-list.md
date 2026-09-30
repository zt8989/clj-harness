# 02 — 窗口不要再为了一个 delta 把整份 entries 按到达分组一遍

**Status:** needs-triage
**Blocked by:** None

**症状（2026-09-30 实测，90 秒 JFR，pid 6116）**：`harness-session-sweeper` 还剩
**59 / 865 = 6.8%** 的采样，栈顶是

```
13  harness.kernel.session$arrivals_of$fn
 8  clojure.core$partition_by$fn
 4  clojure.core$seq
 2  harness.kernel.session$ring_growth_BANG_
```

也就是：**重读文件那一半已经没了**（整份采样里没有一处 `read-records` / `json/read-str`），
剩下的是 `since-of` / `tail-of` → `arrivals-of` 每次把**整份 entries 按 `:seq` 重新分组**——
每个 tick 十次，与文件无关，但**与会话长度成正比**。

参照：现场那场会话 897 个 entry 时约占 0.07 个核。`.scratch/record-window/spec.md` 记着的
53732 行记录那种会话，是它的六十倍。

**要定的（所以是 needs-triage）**：让这个分组不必每 tick 重算。至少两条路，得选一条：

1. **让折自己维护到达分组**：`entries-step` 已经知道行号，分组是它折叠过程的副产物——改动落在
   `harness.edge.replay` 的折里，受益的是所有读者（窗口、`before`、轨迹）；
2. **在窗口这一层记忆**：按 `(entries 的 identity, 数量)` 缓存一次分组结果——改动小，但记忆本身
   就成了第二份状态（`docs/rules/concurrency.md` 那条"快照不是事实"要在这里想清楚）。

**验收**：同一个 JFR 口径下 `harness-session-sweeper` 的采样占比显著下降；窗口的答案逐条不变
（`harness.edge.http-test` 与 `harness.edge.sessions-test` 保持绿）。
