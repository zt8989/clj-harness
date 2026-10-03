# 01 — 内核事件行全量上记录（双写，帧照旧）

**What to build:** `harness.edge.http` 的 run 发射器（`runner`，两条 frame sink：agent 路由与
subagent 路由）今天只把「不产帧的八种」内核事件转成事实行（`lifecycle-record`）；本票把
**其余十六种**也写成行——`:run/start`、`:run/end`、`:run/error`、`:run/stopped`、
`:run/interrupt`、`:tool/call`、`:tool/result`、`:run/cut-off-result`、`:context/injected`、
`:model/timeout` 各一行，载荷就是事件本身（CUSTOM 事实信封，`row-of` 的第三支）。AG-UI 帧
**照旧**写、照旧发 wire——这一刀只加行，不减任何东西。`:text/delta`、`:reasoning/delta`、
`:drained` 三种不写（spec「事件基线」一节说了为什么）。

**为什么值得做：** 这是事件基线的第一半——记录里先有完整的事件流，读侧才有得折。
双写期也是对账期：票 02 的判据（两个 fold 逐 entry 相等）只有在帧与事件同时在记录里时才成立。

**Blocked by:** —（可开始）

**Status:** needs-triage

- [ ] `lifecycle-record`（或它的后任）覆盖十六种事件；每行载荷 verbatim，不自创键名；
- [ ] `:run/interrupt` 的行带 `:interrupts` 全量（id / tool-call-id / name / args / reason /
      question）——它就是 wire 上 `outcome.interrupts` 的同一份数据；
- [ ] `:model/timeout` 从 wire-only 变成也落一行（今天 `wire-only-frames` 只管帧，事件行是新通道）；
- [ ] `:run/cut-off-result` 落一行（拍板点 3：默认上，它写的 cut-off message 行照旧）；
- [ ] `:context/injected`（内核的 `-ctx<n>` 拼接）落一行，id 规则照旧由读侧按计数推；
- [ ] 顺序不变：事件行与帧行在同一条 drain 循环里按到达顺序写；
- [ ] 回归：一条 scripted run 的记录里，十六种事件行各有至少一行；既有帧行一个不少
      （这条用例就是双写期的对账锚）；
- [ ] format 版本**不动**（还是 2）：只加行不加新首行结构；header 里加不加 `:events true`
      的自述由票 05 一并定。

## Comments

2026-10-02 — 从「JSONL 不存帧」对账拆出。spec：`.scratch/event-baseline/spec.md`。
