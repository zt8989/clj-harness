# 02 — `step/*` 上身：没有工具的一步

**What to build:** 内核在每一次模型调用外面发出这一步的两端，`edge` 把它们写成**记录的两行**并**发上会话下行**，
客户端认这一族——一条最窄的端到端：**一次没有工具调用的 run**（问一句、模型直接答）。

从用户视角：这一轮在线上多出一对 `step/start` / `step/end`，它们的 `:seq` 就是记录里那两行的行号，
而记录里那两行**确实在**。

**Blocked by:** 01（类型表收敛之后，加名字只碰一处）

**Status:** ready-for-agent

## 验收

- [ ] **内核发两端**：`harness.kernel.event` 多两个具名构造（照 `model-start` / `model-end` 的形状），
      run 的每一次模型调用外面各发一次——**发在 `model-call-watched` 的外面**，因为一步的区间比一次调用大。
- [ ] **进记录两行**：`harness.edge.http/lifecycle-record` 多两个分支（`step/start` / `step/end`），
      `log!` 照旧**回答行号**（ADR 0007 的同步写，不读盘）。
- [ ] **载荷先留空**：`step/start` 与 `step/end` 的记录行载荷是 `{}`——这一步的开始与结束本身就是全部事实，
      身份由记录行号（`:seq`）说。**不要在 02 里发明第三个 id**（有 `turnId` 是因为轮没有记录行）。
- [ ] **发上会话下行**：走 `family-send!` 那条已经在跑的路（事实进 `harness.edge.mux` 的有界环、
      按 `threadId` 广播），两支帧都带自己的 `:seq`。**同一条路，不另开机制。**
- [ ] **`ag-ui` 空操作**：`harness.edge.ag-ui/step` 是穷尽 `case`，漏分支会当场抛；
      给 `:step/start` / `:step/end` 各加一个「状态原样返回」的分支（`model/start` 那一支就在它旁边，
      理由也写在同一段注释里）。
- [ ] **客户端显式分类**：`ui/src/lib/mux.ts` 把这两个名字加进事实族；`ui/test/suites/mux.ts` 的
      `a-fact-frame-is-not-a-run-frame` 一类的用例钉住 `familyOf("step/start") === "fact"`——
      **这是必须先落的那半**，落到 `run` 那一支就是 `@ag-ui/client` 的 schema 校验把这一轮打死。
- [ ] **端到端判据**：一次没有工具调用的 run 跑完，同一条 socket 上收到一对 `step/*`，
      且 `seq` 与记录里那两行对得上；**这一步的区间恰好覆盖它的 `model/*`**——
      `step/start` 的行号 `<` `model/start` 的行号，`model/end` 的行号 `<` `step/end` 的行号。
- [ ] **记录只多这两行**：跑一轮前后比行数，多出来的只有这一步的两行（`step/start` + `step/end`）。
- [ ] 读侧不被这两行绊倒：`harness.edge.replay/kind` 把 `step/*` 读成它自己的名字（事实，不是帧），
      会话的几份折叠（`stats` / `context` / `turn` / 压力表）**遇到它原样不动**；
      一条用例钉住「加了这两行，`stats` 与 `context` 的答案一个字不变」。
- [ ] `clojure -M:test -m harness.test-runner` 与 `cd ui && npm test` 全绿（报数带上分支与提交）。

**已知陷阱（照实记）：** 内核事件是在 **run 自己的线程**上发的，而记录写手的门铃在另一条时间线上。
`model/*` 今天的行号是 `log!` **同步回答**的（ADR 0007），所以这里也必须是同一条路——
**不要**从 `sessions/watch!` 的落盘门铃发（ADR 0006 决策 6）。
