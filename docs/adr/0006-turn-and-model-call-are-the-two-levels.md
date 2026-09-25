# 0006 —— 轮与一次模型调用是两个层级：`turn/*` 上 wire 不进记录，`model/*` 两处都在

- **日期**：2026-09-25
- **状态**：**已采纳**
- **参照**：DSH（DeepSeek Harness）的架构参考，"Turn flow" 一节：
  <https://deepseek-harness.github.io/deepseek-harness/en/reference/>
- **不推翻** 0002（会话归服务端）、0003 的窗口/游标代数、0004（下行是 WebSocket、订阅点两条）、
  0005（会话拥有记录流）。本决定**只加一族下行事实**，不改记录的行格式。
- **取代** `docs/architecture/edge.md` 事实表里 `model/start` / `model/end` 两行的
  「**不上 wire**」——它们从此也走会话那条下行。

## 背景

**轮与模型调用今天都不是事件。** 轮有两份实现，都在读侧现推：客户端
`ui/src/lib/turns.ts`（相邻 assistant 消息 = 一轮）与服务端 `harness.edge.stats/user-ids`
（没见过的、`:source "client"` 的 user 消息 = 一轮）。`harness.edge.stats` 自己写着隐忧：
这条规则「has ONE spelling and the two folds share it… **no chance to disagree about where one
turn ends**」——一个被两处推导的事实。

**一次模型调用有边界，但没有出口。** `model/start` / `model/end` 已经存在，只落 jsonl 审计行
（`harness.edge.http/lifecycle-record`）。它带的正是模型这一级的数（`:usage`、`:context-window`、
工具表签名），而 composer 那条状态带今天靠**另一次 `GET /stats`** 从头解析整份记录去算同一件事
（实测：68 MB 的记录，一次 `read-records` ≈ 1.8 s，一个 run 里被问 3 次）。

**而 DSH 把两个层级都做成 durable session events。** 它的原话：

> A **step** is one model request plus the tools it calls. A **turn** is zero or more steps:
> it opens before its first input is claimed and closes once nothing is owed.

## 决策

1. **三个名字，两个层级。** 轮（`turn/*`）与一次模型调用（`model/*`）。**`step` 不是第三个东西**：
   它就是本仓的**一次模型调用**。本仓词表照旧——中文「轮」与「一次模型调用」，英文 `turn` 与
   `model`，`step` 只作为 DSH 那边的说法出现。
2. **不吃 DSH 的 `step` 那一圈（它含工具），这是有意分歧。** 三条理由：
   a. 我们要的三个数（`calls` / `messages` / `reasoning`）与模型那一级的数，**没有一个**需要
      「一次请求 + 它的工具」这个区间；
   b. 工具那一圈已经有一族说全了（`tools/pre-execute` / `execute` / `post-execute`，与 DSH 拼写
      一字不差，外加 `TOOL_CALL_*` 帧），再包一层是同一件事的第二个名字；
   c. DSH 那样切是因为它的 loop 拥有 claim 与工具——那个区间是它**自己的调度单位**。本仓一次 run
      就是一次动作，**调度单位是轮，请求单位是调用**，中间不需要第三层。
3. **`turn/*` 走会话级下行（`events.mux`），不进记录。** 轮的边界在记录里由「没见过的 user 消息」
   算得出来，再写一行就是同一件事的第二份；而且**没有记录行就没有历史**，正合「事件只服务你看着的
   时候、旧会话仍走读侧」。
   - `turn/start`：由**一条没见过的 user 消息**触发（**不是**由 run 触发——悬置恢复写出的第二个
     `input` 是**同一轮的继续**，不开新轮）。
   - `turn/end`：run 的**终局**（`:run/end` / `:run/error` / `:run/stopped`）。**`:run/interrupt`
     不是终局**（那正是停在半路等人）。**发在返回尾巴落地之后**，因为它带的 `messages` 要数这一 run
     的返回侧才算得准。
   - 载荷：`{turnId, calls, reasoning, messages, conclusionId, seqFrom, seqTo}`——折叠那一行要显示的
     东西，加上展开时要用的 `seq` 区间。
4. **`model/*` 两处都在**：记录里照旧（ADR 0003 决策 9 一个字不改），**同时上这条下行**。
   - `model/start`：照旧带它自己那一行的载荷（`:model` / `:base-url` / `:reasoning-effort` /
     `:context-window` / 工具表签名）。
   - `model/end`：分两层——`payload` 是厂商逐字的 `:usage` / `:finish-reason` / `:model`；
     `numbers` 是**到这一刻的总量**（`steps` / `usage` / `cacheHitPercent` /
     `outputTokensPerSecond` / `context`）。同一个键名不承担两个意思。
5. **两族的 `seq` 都是记录行号**（0003 决策 1/9），不是内存计数器：只有这样，推来的那一半才能与
   窗口那半对齐（断线补齐按它）。
6. **发送点只有一个**：`harness.edge.http` 里 run emitter 那一处，与 run 的帧**同一颗钟、同一段
   顺序**。**不从 `sessions/watch!` 的落盘门铃发**——那是另一条时间线，轮与消息的相对顺序会在那里
   丢掉，而折叠最怕顺序。
7. **不背历史**：新事件不进「打开会话」的载荷，也不为旧会话补发。打开一场旧会话仍走读侧
   （`rebuild` / `page` / `sofar`），过去作为**一次快照**，不作为事件重放。
8. **「总量」要有地方可问，所以那份折叠得先在内存里。** `stats` / `context` 照
   `harness.edge.pressure` 注册成会话的读折叠（`sessions/register-fold!` 走出生那一遍读、
   `register-step!` 跟着写流）。**这是决策 4 的前置**，不是优化：没有内存里那份折叠，`model/end`
   就没有「到这一刻的总量」可发。

## 代价

- **`edge.md` 要改口**：事实表里 `model/*` 两行的「不上 wire」不再成立；另加一行说轮的边界也上 wire
  但**不进记录**。`kernel.md` / `client.md` 一并核过。
- **两族各有一份实现（读侧现推 vs 事件）**：轮的边界与它的三个数在客户端与服务端各有一份，照窗口
  代数的先例由**同一张用例表**钉住；折叠时**只有一个主人**（运行中的轮以事件为准，落定的轮以读侧为准）。
- **「思考几次」今天没有定义**：一个 assistant 消息里可以有一段或多段 `reasoning` part，服务端把推理
  折成 `role: "reasoning"` 的消息。「几条推理消息」「几次模型调用带推理」「几段推理」**三选一要先钉死**，
  中途变一次口径，两个客户端画出来的折叠就会不一样。
- **`turn/end` 的时机是个真陷阱**：发早了条数是错的，发晚了折叠要等；而尾巴在**另一个线程**上落盘。
- **断线要补得上**：推丢一段就少一段，所以游标按 `seq` 而不是内存计数（决策 5）。
- **`harness.edge.context/records->context` 今天不是增量的**：它建在
  `harness.edge.trajectory/run-segments` 这条整份记录的切段之上，所以「注册成折叠」这一条对
  `stats`（`stats-step` 现成）容易、对 `context` 要先把「最后那一段 + 时间线 + 尾巴落没落」做成
  增量。**票 01 的实话。**

## 边界（不做的事）

- **不做 DSH 的 `step/*`**：不新增第三对边界（决策 2）。
- **不改记录的行格式**，也不因本特征多出记录行（决策 3：轮不进记录；`model/*` 本来就在）。
- **不动 AG-UI 的帧词汇**：轮与模型调用**不是** AG-UI 帧——不进 `apply-frames`、不落 `data` part、
  不被回发。
- **不开第三条 socket**：家级 `events.host` 与会话级 `events.mux`，就这两条（0004）。
- **不做历史回放**（决策 7）。

## 落地

`.scratch/turn-and-model-events/spec.md`（主人的五条 + 融合一节）与六张票：

| # | 票 | 依赖 |
|---|---|---|
| 01 | `stats` / `context` 成为会话的读折叠 | 无 |
| 02 | 「思考几次」的口径 | 无 |
| 03 | 会话级下行多两族（四个事件） | 01, 02 |
| 04 | 客户端显式分类 + composer 直接画 | 03 |
| 05 | 游标：断线补齐 | 03 |
| 06 | 收口：文档、两套全量表、真浏览器走查 | 01–05 |
