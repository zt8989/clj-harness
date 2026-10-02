# 01 —— 两行上身：`turn/start` / `turn/end`

Status: open
Blocked by: 无

## 要做的

`harness.edge.http` 那条 run emitter：

- 开轮时（`(some speaks-for-a-person? added)`）先 `log!` 一行 `turn/start`（payload `{}`），
  再照旧写那些 `message` 行；发出去的 fact 用**这一行的 offset** 当 `:seq` / `turnId` 的底。
- 收轮时（`:turn/closes?`）先 `log!` 一行 `turn/end`（payload `{:steps :messages :seqFrom :seqTo}`），
  再发 fact，`:seq` 是这一行的 offset。

## 判据

- 一份真记录的 `turn/start` 行出现在开轮那条 `message`（`:source "client"`）**之前**。
- `turn/end` 行出现在这一轮最后一条 `message` 之后。
- 下行 fact 的 `:seq` 与记录里那一行的行号相等（一条用例读记录、读 wire，两边对一次）。
- 老边界一条不改：`run/interrupt` 不开不收；悬置回来关的是同一轮（同一份记录里只有一对 `turn/*`）。
