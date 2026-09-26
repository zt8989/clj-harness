# 02 — 折子挂点：会话的读流可以订阅

**What to build:** 装会话那次走查开出一道**订阅缝**：一个消费者登记它的「折子」（一个
`(fn [acc [line-index row]] acc)` 形状的 step），装会话时按行喂它，最后的结果**落在会话上**，消费者从会话读。
没有折子时，装会话的行为、记录的字节、对外答案都不变。

这是「其他都是消费者」那道门：消费者不再自己去开文件、自己去折，而是把折子挂到会话上。

**Blocked by:** 01 — 装会话收成一次走查。

**Status:** ready-for-agent

- [x] 能登记折子；装会话时每一行按文件顺序喂给每个折子一次。
- [x] 折子的结果能从会话上取到（一个测试折子能证明）。
- [x] 没有登记折子时：装会话的行为、记录的字节、对外答案都不变。
- [x] 折子挂在**同一次**走查上——不是让消费者再折一遍。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- `harness.kernel.session/register-fold!` + `fold-value`：折子 `{:init (fn [] v) :step (fn [acc ctx [line-index row]] acc)}`，在装会话那一次走查上按行喂，结果落在会话行 `:folds` 上。
- `sessions-test/a-fold-rides-the-one-walk-the-session-already-makes`：行号连续 0..n-1（证明是同一趟）。没有折子时行为一字不变（其余用例即是）。
