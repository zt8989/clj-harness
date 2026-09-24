# 03 — 不支持的端点：`message[0]` 照旧，读侧三处跟着核（压力表另见 06）

**What to build:** `:replace`（含缺省、含重启后的第一轮）时**不出现** developer 消息，`message[0]` 就是
新的全文——今天的行为**显式化**，并且成为一条**被用例盯住的**契约（今天没有任何东西盯着「变化如何
送达」这件事）。同时把读侧三处核一遍：记录、`run-segments`、`context` 的归属。

**Blocked by:** 02

**Status:** ready-for-agent

## 要落地的判断

1. `:replace` 是**正常的一档**，不是退化路径：缺省、重启后的第一轮、历史不合法时的退回，都落在这里。
   它必须有名字、有 docstring、有用例——不然它会变成一个「谁也不知道为什么走了这条路」的分支。
2. **记录**：`message` 行的 submitted 侧就是这次送出去的向量（`edge/http.clj:841` 前后那段注释说明
   两半怎么分）。有 developer 消息时，它**在里面**，不在 returned 侧。
3. **`run-segments`**（`edge/trajectory.clj:51`）按 **record kind** 切分（`input` 开一次 run，它第一条
   `event` 之前的 `message` 行是 submitted），**不是**按消息数量——所以多一条消息**不**改变切分。
   这一条要**有断言**，因为它恰好是「按计数切」与「按 kind 切」最容易被搞混的地方。
4. **`context` 的归属**（`harness.edge.context`，`context.clj:148` 按 `:role = "system"` 分桶）：
   developer 消息会落进**其余**那一桶。**这一票必须选一个并说清理由**（接受，或者把它算进 system），
   不能留着让读的人自己猜——那颗圈是用户看得见的东西。
5. 轨迹那一栏读 `message` 行，所以这条更新**本来就在那儿**；本票只核它没被上面几处的改动弄丢。
6. **压力表那一处不在三处里，另见票 06**：`harness.edge.pressure/anchored?` 同样会被这次改动碰到——
   它的 system 比较只在 `:replace` 下有理由。本票不重写它，只保证「知道有这一处」并在 06 里分档。

## 验收

- [ ] 缺省 / `:replace` 的模型：加/删一个工具 → 请求体里**没有** developer 消息，`message[0]` 是新的
      全文（有用例）
- [ ] 重启后的第一轮同上；第二轮起按能力位走（有用例）
- [ ] `message` 行的 submitted 侧包含那条 developer 消息（有用例读记录）
- [ ] `run-segments` 的切分与从前相同（新增一条用例钉住「按 kind 不按数量」）
- [ ] developer 消息在 `context` 里算进哪一桶**定下来**并有用例
- [ ] `clojure -M:test -m harness.test-runner` 全绿（失败用例名与基线一致）
