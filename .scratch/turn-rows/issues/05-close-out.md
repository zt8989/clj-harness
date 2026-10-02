# 05 —— 收口

Status: open
Blocked by: 02, 03, 04

## 要做的

- 新 ADR 0017：推翻 0006 决策 3 与 0011「不改轮」那一条；（0006 / 0011 正文一个字不改，只加注）。
- `docs/architecture/edge.md` 的事实表：`turn/*` 那一行从「只上下行、不进记录」改成「两处都在」；
  `harness.edge.trajectory` 的 `turn-start` / `turn-end` 格从「折出来的」改成「读行」。
- `docs/architecture/client.md` / `kernel.md`、`CONTEXT.md` 词表一并核过。
- 两套全量表（后端 + 前端）与真浏览器走查（`node scripts/dev.mjs --scripted`）。
