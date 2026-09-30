# 票 04：轨迹换成 dsh 的平铺账本（后端折法 + payload）

Blocked by: 无。

## 目标

`GET /api/threads/<stem>/trajectory` 的 payload 从 `{:turns [{:index :items :calls}]}` 换成**一条平铺的账本**
`{:threadId :incomplete :cells […]}`；`system_prompt` 在轮外、`turn-start`/`turn-end` 夹出每一轮、
轮内顺序是 `message` → `context` → `message`(assistant)/`tool`；没有轮的行是 `Between turns`。

## 照搬依据（本机实读，写进 docstring）

`@deepseek-ai/dsh-client-ui-trajectory@0.1.0-rc.6`
（`~/.npm/_npx/1e7f6d9597241db0/node_modules/@deepseek-ai/dsh-client-ui-trajectory/`）：

- `lib/types/client/trajectory-record.d.ts`：`TrajectoryCellKind = 'system' | 'user' | 'context' | 'compacted' | 'message' | 'tool' | 'subtool'`；一格 `{index, kind, text, opensTurn?, …}`。
- `lib/types/client/layout.d.ts`：`TrajectoryTurnModel { turn: number|null, groups }`；`turn === null` 是 between turns。
- `lib/client.js`：`sectionLabel(turn)` 出 `Turn N` / `Between turns`；`summarizeTurn` 出 `N steps · M tool calls`；
  工具栏 `toolbar.collapseTurns` / `collapseCalls`。
- 真记录 `~/.dsh/sessions/--Users-zhouteng-Documents-workspace-clj-harness--/session-*/session.v3.jsonl.zstd`：
  `system/message`、`turn/start`、`step/start`、`user/message`、`request/context`、`assistant/message`、
  `tool/call`、`tool/result`、`step/end`、`turn/end` 平铺。

## 改哪里

1. `harness.edge.trajectory`：
   - `trajectory-init` / `trajectory-step` / `trajectory-answer` 的产物改成 `:cells`（一条 vector），
     每条 `{:index n :kind … :turn n|null …}`，字段沿用今天每一项已有的自包含读数
     （`:call`、工具四时刻、`:reasoning`、`:tools`）。
   - `system` 格：`:kind "system"`、`:turn nil`；同一份字节只出一条，字节变了再出一条，**插在它所属轮的
     `turn-start` 之前**（序列自己说明它在第几轮）。
   - `turn-start` / `turn-end`：由今天的轮边界折出来（记录里没有这两个 fact——见 `docs/architecture/edge.md`
     第 473 行那句「只上会话那条下行，不进记录」），所以是**折法算出来的两条**。
   - `context` 格：每一条注入一行，`opening` / 技能正文 / 作业通知 / 会话上下文条目一视同仁。
   - `compacted` 格与任何不属于一轮的行：`:turn nil`（Between turns）。
   - 今天的 `entry-row?` / `own-calls` / `pending-tool-items` / `tool-lifecycles` / `call-index` 照旧复用；
     只换**输出形状**，不换判据。
2. `ui/src/lib/trajectory.ts`：`TrajectoryPayload` 换 `{threadId, incomplete, cells}`；
   `TrajectoryItem` 的 kind 联合加 `turn-start` / `turn-end` / `compacted`，`system` 仍在。
3. 帧的载体：`.worktrees/trajectory-on-the-downlink` 的 `trajectory` 帧按 `:turns` 设计；合的时候以本票的
   `:cells` 为准（那条线自己改一帧的形状）。

## 判据

- `trajectory-test`：
  - 手搓一份两轮记录，断言 `cells[0].kind == "system"` 且 `:turn` 为 nil；每一轮被 `turn-start`/`turn-end` 夹住；
    轮内 `user` 在 `context` 之前；`message` / `tool` 顺序与记录一致；
  - 提示词字节变了：第二条 `system` 落在**它所属轮的 `turn-start` 之前**；
  - 独立压缩：`compacted` 且 `:turn` 为 nil；
  - 一次真 run（`http-test`）：端点答的就是账本，且 `:cells` 的 `:index` 连续。
- 后端定向 + 全量。

## 不做

- 不做视图（票 05）。
- 不改注入物外形（票 01–03）。
- 不照搬 dsh 的虚拟滚动 / 补页 / `subtool`。
