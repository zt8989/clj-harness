# 票 05 的证据：带子放不下时让格，不让折行

对象：`node scripts/dev.mjs --scripted` 起的**那一个服务**（它自己 `npm run build`，再把 `ui/dist`
发给浏览器），家目录与 OS 家都是临时目录（退出即删）。浏览器：Playwright（Chromium），视口
1280×800 / 390×844 / 360×844 / 300×640 / 200×640。

## 怎么造一场「数字很多」的会话

脚本替身一回合一趟，凑一场一千次模型调用的会话就要真跑一千趟；而带子读的是**记录**折出来的数
（`harness.edge.stats/records->stats`），所以记录里已经有这些数就够了：

```
node scripts/dev.mjs --scripted                        # 起服务（打印临时家目录）
node .scratch/mobile-adaptation/evidence/long-session.mjs "<那场会话的 jsonl>" 1024
```

`long-session.mjs`（本目录）往那份记录尾部加 1024 对 `model/start` · `model/end`，形状是**从同一份
jsonl 里抄的**：`model/end` 的 `value.usage` 就是厂商的原话（`prompt_tokens` / `completion_tokens` /
`total_tokens` / `prompt_tokens_details.cached_tokens`），每次调用 4 秒。折出来的答案是：

```
GET /api/threads/<stem>/stats →
turns 1 · steps 1026 · cacheHitPercent 85 · outputTokensPerSecond 225 ·
usage.totalTokens 123802876 (123.8M) · pressure 94%
```

## 证据一：改之前，同一份内容在 360px 上撑破那一行

同一份记录、同一个宽度（360px，带子 `clientWidth` = 301px），把这一票换掉的那两条 CSS 放回去量一次
（`white-space: normal` + `overflow: visible`）：

```
slots: stats-turns / stats-steps / stats-rate / stats-usage / stats-cached   ← 五格全在
固定高 h-5 = 20px，内容高 scrollHeight = 24px
```

**五格要 24px，带子只有 20px**：多出来的那半行从 composer 的圆角盒子里溢出去，压在对话上。
`h-5` 是 04 票定的（数字迟到不许顶动 composer），所以能动的只有「画几格」。

## 证据二：改之后，360px 让掉速率

```
text        : 1 轮 · 1026 次调用 | 123.8M tok · 85% 缓存
slots       : stats-turns / stats-steps / stats-usage / stats-cached      ← stats-rate 不在了
clientWidth : 301   scrollWidth: 301（不溢出）  高: 20px
在盒子里     : 行底 838 ≤ composer 盒底 844
```

## 证据三：390px 与宽窗，五格都在

```
390px （带子 331px）: clientWidth 331 · scrollWidth 331 · 五格全在 · 高 20px
1280px（带子 660px）: 五格全在 · 一行 · 高 20px
```

数字大而屏幕够宽时，五格一格不少——让格是**放不下才发生**的事，不是窄屏的固定形状。

## 证据四：更窄按表继续让，宽回来会回来

```
300px（带子 241px）: 1 轮 · 1026 次调用   |   85% 缓存       ← 速率之后轮到会话总量
200px（带子 141px）: 1026 次调用          |   85% 缓存       ← 秒表跟着轮数一起走
                     这两格自己也放不下（144 > 141）：右边裁掉 3px，不折行——这是地板
再回 390px          : 五格全回来（同一条 ResizeObserver 的路）
```

## DOM：360px，让掉速率之后

```html
<div data-slot="composer-stats" class="text-muted-foreground flex h-5 items-center justify-between gap-4 overflow-hidden px-1.5 pt-0.5 pb-1 text-[10px] whitespace-nowrap tabular-nums">
  <span class="flex items-center gap-1.5"><svg…/><span data-slot="stats-turns">1 轮</span><span aria-hidden="true">·</span><span data-slot="stats-steps">1026 次调用</span></span>
  <span class="flex items-center gap-1.5"><svg…/><span data-slot="stats-usage">123.8M tok</span><span aria-hidden="true">·</span><span data-slot="stats-cached">85% 缓存</span></span>
</div>
```

秒表 `svg` 跟着轮数：200px 那一刻行里只剩数据库那颗。

## 机器门

- `cd ui && npm test` → **148 passed**（含 `stats` 那组改过的中文断言与 `i18n` 的两份目录对齐）
- `cd ui && npm run typecheck` / `npm run build` 干净
- `clojure -M:test -m harness.test-runner` → **撞上限**，见下

截图：`05-390-five-cells.png`、`05-360-rate-gone.png`、`05-300-rate-and-total-gone.png`、
`05-1280-five-cells.png`（都由这份记录、这几次视口变化拍下）。
## 后端一轮（与这一票无关，如实记）

`clojure -M:test -m harness.test-runner` 在这一台机器上跑了**两次**，两次都撞到整轮的 1800s 上限
（`EXIT=2`）：

```
第一次：Ran 1096 tests containing 12037 assertions.  1 failures, 0 errors.
        never started: harness.edge.mux-test, harness.edge.host-test, harness.edge.sessions-test,
        harness.edge.record-test, harness.edge.delegation-test, harness.edge.delegation-line-test,
        harness.edge.frames-route-test, harness.layers-test
第二次：Ran 1096 tests containing 12037 assertions.  3 failures, 1 errors.
        ERROR in (a-command-that-would-hang-is-stopped-at-the-limit)  ← 抢 child.pid 抢输（文件还没写出来）
        FAIL in (a-call-that-hangs-times-out-and-the-connection-is-dropped) ×3  ← mcp 替身没起来
        never started: 同上面那 8 个
```

红的都是**超时型**的用例，两次红的条数还不一样——这一票没碰后端一个字节（`git diff` 里只有 `ui/` 与
文档），所以它们是这台机器/这一轮的，不是这一票的。**没有**在改动前的树上单独调一次基线来比。

## 它没有证明什么

- **厂商还是脚本替身**：这一千多次调用是**往临时家目录那份记录里追加**出来的（脚本如上），
  不是真跑了一千趟；`long-session.mjs` 只写给 `--scripted` 造的那份 jsonl。
- **没有在一场真的长跑中途盯着看**：让格发生在渲染后的 layout effect 里，React 在 paint 前同步跑完，
  所以中间那一帧不落屏——但「一场长跑里数字连着变三次」没有真跑过。
- **只测了宽度变化这一种重新考虑**：右栏开合也会改列宽（走同一条 ResizeObserver 的路），但没有单独走一遍。
