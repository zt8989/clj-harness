# 03 — composer 之下的状态条

**What to build:** 输入框下面一条灰字：左边几轮几步与输出速度，右边总用量与缓存命中。它读 02 那个端点，
每次模型调用结束就刷新一次。

从用户视角：不用点开任何东西，就知道这条会话跑了多少、有多快、烧了多少 token、其中多少是缓存——
这几个数是这条会话**记录**里的，换台机器打开、重建过的会话，读出来的还是它。

**Blocked by:** 02（条子读的就是它的端点）

**Status:** ready-for-agent

## 挂在哪

- 新组件 `ui/src/components/composer-stats.tsx`，挂在 `ui/src/components/composer-chrome.tsx` 的
  **`ComposerFrame`** 里、`{children}` **之后**：同一个圆角框内、**输入框下面**。
  上下文条在输入框**上方**且对话一开始就收起来；状态条在**下面**且**有数才画**——两条各在各的位置，
  谁也不兼职。
- **不碰** `ui/src/components/assistant-ui/elements/thread.aui.tsx`：那是抄来的 registry 源码，
  保持与上游逐字可比（要改就得标 `LOCAL:`，本票不必改）。
- 取数：新 `ui/src/lib/stats.ts`，`GET /api/threads/<stem>/stats` 的类型化薄封装，
  照 `ui/src/lib/composer.ts` 的 `reasonFrom` / fetch 形状写，带 `threadId`，不跨会话缓存。

## 刷新时机（不为它加协议）

- 会话切换时取一次；
- 助手消息**多了一条**时取一次（一次 ReAct 轮在线上就是一条助手消息，所以这约等于「一次模型调用结束了」）；
- run 结束 / 出错时再取一次。
- **不轮询**，不每个 token 取一次。docstring 写明理由：一次调用的数只有在它 `model/end` 之后才存在，
  所以一次很长的调用进行中条子停在上一格——那是诚实，不是卡顿。

## 五格与「缺就不画」

| 位置 | 内容 | 缺的时候 |
|---|---|---|
| 左组 | `{turns} turn(s)` · `{steps} step(s)` · `{rate} tok/s` | `steps` 缺席（老日志）→ 只剩轮数；`tok/s` 缺席 → 那一小段不画 |
| 右组 | `{total} tok` · `{pct}% cached` | `cachedTokens` / `promptTokens` 缺席 → 缓存那一小段不画；`usage` 整个缺席 → 右组不画 |

- 整条**在轮数为 0 且没有任何调用时不渲染**（新会话、只有 `project/bound` 的日志）——与「开场前的东西」
  那条纪律一致，不画一条全是空格的灰线。
- 文案**英文**（仓库规矩）：`1 turn 42 steps · 242 tok/s` ／ `2.9M tok · 98% cached`。
  复数两档（`1 turn` / `3 turns`，`1 step` / `42 steps`）。
- 量级与百分比是**纯函数**（`2.9M` / `812k` / `409`，百分比取整），住 `ui/src/lib/format.ts`
  （与既有的 `formatBytes` / `formatTime` 同一处），**不复制第二份**。
  投影（payload → 条子要的那几个字符串）也做成纯函数，与取数分开。

## 样子

- 与上下文条同一档灰（`text-muted-foreground`）、小字号，`data-slot="composer-stats"`，
  两个分组（左：轮/步/速度，右：用量/缓存命中）照参考图，图标用仓库既有的那一套。
- **数字用 `tabular-nums`**：数一变宽度不变，条子不动。
- `data-slot` 给证据与将来的套件用：`stats-turns` / `stats-steps` / `stats-rate` / `stats-usage` / `stats-cached`。
- 取数失败时**整条不画**（与「没数就没条子」同一个形状），不画一句错误——这条灰线不是报错的地方。

## 验收

- [ ] 条子画在输入框**下面**、同一个圆角框内；`data-started` 两种状态下都在（它不属于开场前的东西）。
      真机证据（DOM 快照 + 截图）落在 `.scratch/composer-status/evidence/`。
- [ ] 一次调用结束时刷新：真机上跑两轮，条子上的轮/步/用量跟着长（证据同上）。
- [ ] 格式化与投影的**纯函数**用例进 UI 套件：新建 `ui/test/suites/stats.ts` 并注册进 `SUITES`，
      `ui/test/ui.test.ts` 的 `EXPECTED_CASES` 从 **15** 改成实际值（那个数是刻意的锚，改它要说明为什么）。
- [ ] 一条**走真 HTTP** 的用例：假 provider 的脚本带用量跑一轮，断言这个端点回来的数与脚本对得上
      ——也就是条子取数的那个口今天真的通。
- [ ] 缺数不画 0：构造一个 `usage` 缺席 / `cachedTokens` 缺席的响应，条子对应的小段不出现（纯函数用例）。
- [ ] `cd ui && npm test` 与 `cd ui && npm run build` 全绿；报数带上**分支与提交**。
