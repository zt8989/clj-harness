# 04 — 写流归会话：一行进门，订阅者自动更新

**What to build:** 唯一的写入口（往记录里 append 一行）在把行交给写者的同时，**把行喂给订阅者的实时 step**。
今天 `harness.edge.http/log!` 里那段「顺手喂压力表」的特殊分支随之消失——加一个新消费者只要登记一个 step，
不必动 `log!`。

**Blocked by:** 02 — 折子挂点。

**Status:** ready-for-agent

- [x] 写入口通知订阅者；一个订阅者的实时 step 在每一行落下时更新（测试能证明）。
- [x] `log!` 不再认识压力表（也不认识任何具体消费者）。
- [x] 记录的字节与今天**逐字节相同**：写者、队列、顺序、严格的 append-only 都不动。
- [x] 03 的表针改由这道缝维持之后，与记录折出来的答案仍逐字段相等。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- 唯一写入口 `http/log!` 只叫 `sessions/row-written!`，由会话把行交给登记过的实时 step（`register-step!`）；`log!` 里已搜不到压力表的名字（`grep pressure src/harness/edge/http.clj` 只剩注释）。
- `sessions-test/a-write-step-advances-only-a-session-this-process-holds`：持有者原地推进，不持有的不动。记录的字节未动（`record-test` / `http-test` 全绿）。
