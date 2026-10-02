# 02 —— 服务端只认行：`stats` 与 `trajectory`

Status: open
Blocked by: 01

## 要做的

- `harness.edge.stats`：`stats-step` 的 `:turns` 改成数 `turn/start` 行；`user-ids` 删掉
  （它只服务这一处）。
- `harness.edge.trajectory`：`one-run` 里「这个 run 带来了没见过的 client user 消息 ⇒ 开一轮」
  改成「这一段的记录里有 `turn/start` ⇒ 开一轮」。`turn-cells` 的边界由行决定。
- `harness.edge.turn`：`records->turn`（离线 twin，读侧第二份答案）删掉；`state-step` 保留，
  它是**写侧**为 `turn/end` 攒数字用的，docstring 要改口。

## 判据

- 一份没有 `turn/*` 行的老记录：`:turns` 为 0，轨迹里没有 `turn-start` / `turn-end` 格。
- 一份新记录：`:turns` 等于 `turn/start` 行数；轨迹的每一格都在正确的轮里。
