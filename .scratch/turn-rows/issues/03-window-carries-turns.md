# 03 —— 窗口带 `turns`

Status: open
Blocked by: 01

## 要做的

- 服务端折一份每轮的账：`{:turnId :steps :messages :from :to}`，`from` / `to` 是记录行号区间。
  折的是 `turn/start` / `turn/end` **行**，别名按 `<threadId>-t<turn/start 的行号>` 造。
- `harness.edge.http/window-frame` 的载荷加 `:turns`（与 `:entries` / `:cursor` 并列），
  存量那条路（`page` / `tail`）与推送那条路（`append`）都要有。
- `ui/src/lib/feed.ts` 的 `WindowFrame` 加 `turns?`，`lib/window.ts` 的三个合并函数
  （`windowFrom` / `applied` / `prepended` / `aligned`）都要把它带上。

## 判据

- `GET /api/threads/<stem>/page` 与下行 `append` 带回的 `turns` 与记录里的 `turn/*` 行逐条相等。
- 老记录：`turns` 缺席或为空，客户端不因而出错。
