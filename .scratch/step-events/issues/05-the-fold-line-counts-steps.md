# 05 — 折叠那一行按 step 计

**What to build:** 轮末尾那一条摘要行改说**步数**：`3 步 · 25 条消息`（`3 steps · 25 messages`）。

从用户视角：一轮折起来只留答案时，那一行现在说的是「这一轮走了几步」——一步就是一次
「模型要了点什么、或者直接答了」。数字的**主人在服务端**（`turn/end` 带它），展示照旧只由一个函数画。

**Blocked by:** 03（一步的边界得先在记录里）

**Status:** ready-for-agent

## 验收

- [ ] **服务端折得出来**：`harness.edge.turn` 的折叠多数一个 `steps`（数 `step/start` 行），
      `turn/end` 的载荷从 `{calls, messages}` 变成 `{steps, messages}`。
      **`calls` 是否留着**要一句话说清：不在任何地方画了就从载荷与折叠里去掉，不留没人读的键。
- [ ] **客户端那份读法跟着改**：`ui/src/lib/turns.ts` 的 `turnCounts` 数出 `steps`（读侧那一份，
      与上面同一张用例表）。折叠时**只有一个主人**：运行中的轮以事件为准、落定的轮以读侧为准
      （ADR 0006 代价一节那条规矩，照旧）。
- [ ] **那一行的规矩**（逐条钉住，`ui/test/suites/turns.ts`）：
      - 一轮三步两工具 ⇒ `3 步 · N 条消息`；
      - **一轮一步一条消息 ⇒ 那一行不出现**（今天的 `calls === 0` 那条规矩在 step 上的对应：
        step 恒 ≥ 1，所以「不画」的判据变成「1 步 1 条消息」——与今天逐字一样的行为）；
      - 中英两份文案：`ui/src/locales/{zh,en}/thread.json` 加 `summary.steps`，
        不再被任何地方画的 key（今天的 `summary.calls`）**从两份语言文件里删掉**，不留孤儿。
- [ ] **两个数相等**：同一轮上，服务端 `turn/end` 的 `steps` 与客户端 `turnCounts` 的 `steps` 相等——
      一条共享用例表（照 ADR 0006「一份规则、一张用例表」的先例）。
- [ ] **展示的其余部分逐字不变**：`statsCells` / `contextCells` 一个字不改；状态带、上下文圈、
      步骤行本身都不动——变的只是折叠那一行的一个数。
- [ ] `cd ui && npm test`、`npm run typecheck`、`npm run build`
      与 `clojure -M:test -m harness.test-runner` 全绿（报数带上分支与提交）。
