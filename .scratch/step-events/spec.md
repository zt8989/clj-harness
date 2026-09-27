# spec: 第三级 —— 步（step）

**一句话**：`turn/*` 与 `model/*` 之外，再加一族 **`step/*`** —— **一步 = 一次模型请求 + 它调的那些工具**
（内核 loop 里的一次迭代）。这是 ADR 0006 决策 2 与它「边界（不做的事）」第一条**有意推翻**的那件事：
当时判 `step` 与本仓的 `model` 是同一件东西，所以不新增第三对边界。

## 主人拍的（2026-09-27）

1. **`step` 就是 DSH 那个定义**：一次模型请求 + 它调的那些工具。不是给 `model/*` 补个名字，
   也不是把 UI 里平铺的步骤行升成一等事实——它就是内核 loop 的一次迭代。
2. **`step` 的两端进记录**：每步两行（`step/start` / `step/end`）。于是 `:seq` 是**真行号**，
   走的是 `model/*` 那条已经通了的路（记录 + 下行），不是 `turn/*` 那条「不进记录、边界算得出来」的路。
3. **这一族的第一个消费者是折叠那一行**：轮末尾的摘要按 step 计（如「3 步」）。

## 为什么这一处分歧值得翻案

ADR 0006 拒 `step/*` 的三条理由里，第 2、3 条说的都是「工具那一圈已经有一族说全了，再包一层是
同一个事实的第二个名字」与「本仓的调度单位是轮，中间不需要第三层」。翻过来的理由是**记录里没有那个区间**：
今天「这一步做了哪几件事」只能由读者拿 `model/start` 与随后的 `tools/*` 行**现推**，
而推法至少两份（`ui/src/lib/turns.ts` 与 `harness.edge.turn`），谁也说不准一步的边界在哪行。
把边界写进记录，与 ADR 0006 里「轮的边界由记录算得出来，所以不必再写一行」是**同一条纪律的两个方向**：
算得出来的不写，算不出来的写。

## 于是三层

```
turn/start                                   ← 人说了话（没见过的 user 消息），不进记录
  step/start                                 ← 这一步开始（进记录）
    model/start   {…身份, context-window, 签名}
    model/end     {payload: 厂商逐字, numbers: 到这一刻的总量 + 上下文}
    tools/pre-execute / tools/execute / tools/post-execute   ← 这一步调的工具，各自的结局
  step/end       {tools: [{:id :name :outcome} …]}           ← 进记录
  …（一轮里的每一步各一对）
turn/end        {turnId, steps, messages, conclusionId, seqFrom, seqTo}   ← 不进记录
```

- **`step/end` 的时机**：这一步调的工具**都有了结局之后**（最后一个 `tools/post-execute` 落盘之后）。
  一次工具也没调的步，它就紧跟在 `model/end` 之后——那是最窄的一刀。
- **`run/interrupt` 不是一步的终局**（同 ADR 0006 决策 3 对轮的说法）：停在半路等人，回来收的是同一步。
- **停止（stop）与被停的步**：被停的那一步要收口，不留一个没有 `step/end` 的 `step/start`。

## 边界（不做的事）

- **不改 `turn/*` 的语义**：轮还是「一条没见过的 user 消息」开、「run 的终局」关；不进记录。
- **不改 `model/*` 一个字**：它照旧两处都在（ADR 0006 决策 4）。
- **不动 AG-UI 的帧词汇**：三族事实**都不是** AG-UI 帧——`harness.edge.ag-ui/step` 对它们仍是空操作
  （它今天是个穷尽 `case`，漏一个分支就会抛）。
- **不开第三条 socket**；不做历史回放（`step/*` 与 `model/*` 一样只服务「你看着的时候」）。
- **不加逐条持久缓冲**：事实仍是一段有界的内存环（`harness.edge.mux`），断线补的是短缺口。

## 票

| # | 票 | 依赖 | 交付 |
|---|---|---|---|
| 01 | 事实族的类型表只有一处拼写 | 无 | 加第三族之前先把「哪些名字是事实」收敛：服务端一处、客户端一处（e2e 引用它），对不上就红 |
| 02 | `step/*` 上身：没有工具的一步 | 01 | 内核发、记录两行、下行两帧、客户端显式分类；一条最窄的端到端 |
| 03 | 一步带工具 | 02 | `step/end` 在最后一个工具的结局之后收口，载荷带这一步的工具与结局；停/park 各有一条判据 |
| 04 | 游标与顺序 | 03 | 三族的相对顺序；step 帧进事实族那条 `seq` 游标，断线一段不漏不重；复核内存环的容量 |
| 05 | 折叠那一行按 step 计 | 03 | 轮末摘要「3 步 · 25 条消息」；数字的主人在服务端 |
| 06 | 收口：改 ADR 与三份文档 | 02–05 | 新 ADR 推翻 0006 决策 2；`edge.md` / `kernel.md` / `client.md` / `CONTEXT.md` 词表改口；两套全量表 + 真浏览器走查 |

## 词表

**步**（英文 `step`）——一次模型请求加上它调的那些工具。这是本仓第一次把「步」当量词用；
`CONTEXT.md` 今天写死了「别叫成 step」，票 06 要改口。**轮**与**一次模型调用**照旧。

## 验证（两套读法必须相等）

- **步数**：同一轮上，服务端 `turn/end` 的 `steps` 与客户端 `turnCounts` 数出来的一样（一张共享用例表）。
- **顺序**：一轮里 `turn/start` 在任何 `step/start` 之前，`model/*` 落在自己那一步的两端之内，
  `turn/end` 在最后；断言的是**相对顺序**，不是时刻。
- **记录的自洽**：`step/end` 的行号大于同一步最后一个工具结局的**那一次**模型调用还不算完；
  一步的区间恰好覆盖 `model/*` 与这一步的 `tools/*`，不多不少（读侧一条用例钉住）。
- **展示**：一轮一步一条消息时那一行**照旧不出现**（今天的规矩）；有 step 有工具时逐字形成「N 步 · M 条消息」。

## 落地（票 01，2026-09-27）

**落了。** 落在 `.worktrees/step-events` 这个 worktree 里（分支 `step-events`），改动六处：

| 文件 | 改了什么 |
|---|---|
| `harness.edge.mux` | 新增 `fact-types` —— 事实族名字的**唯一一处拼写**，与它本来就持有的
  `fact-buffer-size` / `record-fact!` / `facts-after` 同住 |
| `harness.edge.http` | 那条 `contains?` 的门从字面量 `#{"model/start" "model/end"}` 换成 `mux/fact-types` |
| `test/harness/test_support.clj` | `fact-frame-types` 不再是第二份集合，直接就是 `mux/fact-types`（多一个 require） |
| `ui/test/e2e.ts` | 删掉自己抄的 `WINDOW_TYPES` / `FACT_TYPES`，改问 `lib/mux.ts` 的 `familyOf`；
  socket 那一段拆成 `readRun`，两个读者共用 |
| `ui/test/suites/frames.ts` | 新用例 `the-wire-says-which-names-are-facts` |
| `ui/test/ui.test.ts` | 用例总数 166 → 167 |

### 两次红，都值得记

1. **空 `append` 的那次**：一开始给 `frameTypesFromRun` 传了 `[]`，于是那一轮**没有一条 user 消息**，
   按 ADR 0006 决策 3 就没有轮——`turn/*` 两个名字一个都没上线，期望的四个回来两个。
   **「跑了一轮」不等于「有人说话」**：模型照样被调用了。
2. **终帧不等于最后一条**：补上 user 消息之后 `turn/end` 仍然缺，而原因是真事实——`turn/end` 在
   `harness.edge.http` 那条 drain 循环的 `:run/done` 分支里发，而 `RUN_FINISHED` 帧来自**前一个**
   内核事件 `:run/end`（那段注释自己写着 `:run/done` 从不被转换）。于是「这一轮结束了」比
   「这一轮的最后一个事实」**早到**；读取器在终帧就挂断，就丢了尾巴。修法是 `readRun` 的
   `afterTerminalMs`——只有想要会话那半事实的读者才等。
   **这是给票 04 的现成结论**：客户端在 `RUN_FINISHED` 上收手的地方，都要留出这一格。

### 判据

- 新用例**先红再绿**：把 `model/start` 从客户端那份表里删掉，报的正是
  `model/start must be the client's fact family: expected 'run' to be 'fact'`。
- `harness.edge.mux-test` + `harness.edge.http-test`：125 用例 / 1276 断言，**0 失败**（226.5s，
  这是带上本次改动的一次全绿）。
- 前端：`npm test` **167/167 通过**；`npm run typecheck`、`npm run build` 通过。

### 一处没绿，以及它为什么不是这一刀的账

`clojure -M:test -m harness.test-runner`（整轮）在本机**撞了单命名空间 300s 的硬限制、退出 2**，
两次点名的都是 `harness.edge.http-test`。证据说这不是本次改动造成的：

- 同一份改动的**单独一轮是绿的**（上面 226.5s 那条）。
- 用**没有本次改动**的树（`c5cdfb9`，只多 spec 与票）单独跑 `http-test`，同样撞 300s、退出 2。
- 本机 **4 核**，跑测试那会儿 **CPU 100%**，另有两个 Clojure JVM 与若干 node 进程——其中一个是
  **在那一轮跑到一半（20:14:55）起来的**。把限制放宽到 900s 重跑，`http-test` 用了 **711.5s**，
  并出现 28 处「等了 5000ms 那扇缝还没开」这类**对负载敏感**的失败。

**下一张票（或任何一次收口）要在没有别的会话抢机器的机器上复跑整轮，把那个数留下。**

## 落地（票 02–06，2026-09-27）

**落了。** 同一个 worktree、同一个分支，与票 01 同一台机器。

| 文件 | 改了什么 |
|---|---|
| `harness.kernel.event` | `step-start` / `step-end` 两个构造；词表 19 种 |
| `harness.kernel.loop` | 一步在请求之前开、在**四条收口路**上关（工具都答了 / 停止 / 悬置 / 失败），
  `close-step!` 幂等 |
| `harness.edge.http` | `lifecycle-record` 多两行（广播的门在票 01 已经读 `mux/fact-types`） |
| `harness.edge.ag-ui` | `(:step/start :step/end)` → 空操作（那个 `case` 没 default，不写会抛） |
| `harness.edge.mux` | `fact-types` 多两个名字 |
| `harness.edge.turn` | 折 `step/start` 行成 `:steps`，`turn/end` 带它；工具调用计数去掉 |
| `ui/src/lib/turns.ts` | `turnCounts` 数步；`turnSummaryLabel(steps, t)` 只说「3 步」 |
| `ui/src/components/turn-steps.tsx` | `turnStepsOf`；`data-steps` |
| `ui/src/lib/mux.ts` | 事实族多两个名字（类型联合 + `FACT_TYPES`） |
| `ui/src/locales/{zh,en}/thread.json` | `summary.steps`；删掉没人再画的 `summary.calls` / `summary.messages` |
| `ui/src/locales/en/format.json` | 状态带那一格从 `3 steps` 改成 `3 calls`——**同一个界面里 "step" 不能有两个意思** |
| 测试 | `loop_test` 五条事件序列加上步两端（含「重试是一步」）；`mux_test` 新用例 + 用 `mux/fact-types`
  替掉又一份抄写；`frames.ts` 期望六个名字；`turns.ts` / `stats.ts` 跟着改 |

### 三处决定（都在 ADR 0011 里）

1. **悬置关的是同一步**，回答人的那次请求是**下一步**——步不跨 run，轮才跨。内核里只有这样写才自洽：
   悬置之后那次请求本来就是一次全新的 `model/start`。
2. **重试是一步**（`loop_test` 的 `:model/timeout` 那条钉住）：vendor 因长度拒绝、这一层重发，步数不涨。
   这正是折叠那一行敢只说步数的底气。
3. **那一行只说步数**：「3 步 · 3 条消息」是把同一个数说两遍——客户端的步数就是助手消息数（一次请求一条
   消息）。工具调用的计数一并撤掉：没人再画它。

### 判据（都是实测）

- `harness.kernel.loop-test` + `harness.edge.mux-test` + `harness.edge.stats-test`：52 用例 / 231 断言，**0 失败**
- `harness.edge.mux-test` + `harness.edge.sessions-test` + `harness.edge.context-test` + `harness.edge.trajectory-test`：
  76 用例 / 311 断言，**0 失败**
- `harness.edge.http-test`：**115 用例 / 1240 断言，0 失败**（一次复跑；见下面那条已知情况）
- 前端 `npm test`：**167/167**——其中 `the-wire-says-which-names-are-facts` 现在期望**六个**名字，
  也就是说真 run 的 socket 上确实走过了 `step/start` / `step/end`
- `npm run typecheck` / `npm run build` 通过
- **真浏览器走查**：`node scripts/dev.mjs --scripted` + 浏览器，折起来那一行是「2 步」、展开两步的行都在
  （`evidence/summary-line-2-steps.png` 与 `evidence/walkthrough.md`）

### 两条已知情况

- **票 02–05 的那一轮 `http-test` 红过一条**
  （`an-overflow-refusal-compacts-aggressively-and-retries-in-one-turn`：重试的答案没有文本帧）。
  **更正（见「补二」）**：当时判成负载下的 flaky，**错了**——它不是负载，是**只在整轮上下文里出现**的一处
  既有红，两棵树（有 step / 没有 step）一模一样。单独跑整个命名空间两次都是全绿。
- **记录每步多两行**：一次没有工具的请求现在是四行（`step/start` + `model/*` + `step/end`）。
  内存里那圈事实随之涨：一步四帧起，`fact-buffer-size`（1024 帧）仍够——一次断线缺口是秒级的，
  而 1024 帧约等于十六个三十步的轮（`mux_test` 那条新用例把这个算式的结论写在自己的注释里）。

## 补：票 05 那条没做到的验收（2026-09-27 晚）

**红两次，红出两个真缺口，都修了。**

### 落下的两半

- **共享用例表**：`ui/test/suites/turn.ts` 的 `the-read-side-and-the-server-count-the-same-steps` ——
  一条真 run，**同一个轮数两遍**（服务端 `turn/end` 的 `steps`，与读侧 `turnCounts`），两边必须相等。
  读法落在折叠**真正看到的那个形状**上：原始 AG-UI 里工具结果是**独立一条** `role: "tool"`
  （这次实测到 `[user, reasoning, assistant, tool, assistant]`），它会把 `turnBounds` 的助手消息连续段
  切断；页面自己的视图不带这条（结果是助手消息里的一个 part —— 走查里这一轮正是「2 步」，来自
  同一份读侧）。
- **客户端开始吃服务端那对数**：新增 `ui/src/lib/turn-numbers.ts`（每场会话留最后一个 `turn/end` 的
  `steps` / `messages`，可订阅），`app.tsx` 在它那条事实订阅里记下；折叠那一行按 `lib/turns.ts` 的
  `turnStepsFrom` **只认一个主人**——服务端拥有它刚关掉的那一轮（最新那轮），别的轮归读侧
  （事实不重放，读侧是唯一能回答「你没看着的过去」的那个）。

### 红出来的两个真缺口

1. **`subscribeFacts` 从不声明这场会话。** `lib/mux.ts` 的 `wantedThreads()` 只算窗口与 run，
   于是「只要事实」的页面在服务器那边等于没订过——事实根本不会推来。现在它也算一条认领
   （并且在订阅时就把声明发出去）。
2. **run 一收尾就把整条声明撤了。** agent 的流一结束就退订 run，而 `turn/end` 是**后一个内核事件**
   才推的（同一个 `:run/done` 的先后）；只靠 run 认领的页面——**刚新建的会话就是这样**——
   永远收不到收轮的那条事实。三处收尾现在都问一句 `stillWanted`：窗口 / run / 事实是三条
   各自独立的认领，撤自己那条不能把别人的带走。

**这个 bug 只有真跑才看得见**：单元套件里的 `declaredSet()` 断言、`mux` 的路由用例都不会注意到
「服务器没在给你发」这件事。

### 判据

- `npm test`：**170/170**（新增三条：纯规则、store、以及上面那条真 run 的共享用例表）
- `npm run typecheck` 通过
- **真浏览器复走一遍**（`node scripts/dev.mjs --scripted`）：折起来仍是「2 步」，展开两步的行都在，
  控制台只有既有那两条 404（`favicon.ico` 与新建会话的 `/stats`）——**这一格是这次改动唯一会被渲染到的
  地方**（`turn-steps.tsx` 的新钩子没有单元套件能渲染它）
- 后端一个字节没动，这一轮没有重跑后端套件

## 补二：剩下那两半（2026-09-27 深夜）

### 断线补齐的**客户端**一侧（票 04 的另一半）

`ui/test/suites/turn.ts` 的 `a-reconnect-would-ask-for-the-gap-and-the-step-frames-are-in-it`：
一条真 run，把这一族的事实连同它们的 `:seq` 收下来，然后问 `declaredSet()` —— **重连要报的那个缺口
起点**。服务端那一半早有各的用例（`mux_test/the-step-family-rides-the-same-cursor`）。

它同时钉住这条最要紧的性质：**每一条都带记录行号，步的两端也算**——那正是 ADR 0011 把它们写进记录的理由，
也是推来的那一半能和窗口那一半对齐的原因。

**顺带一条同一条规矩的另一面**（写在用例里当注释）：认领撤掉之后 `declaredSet` 就不再提这场会话——
所以读它必须在撤之前。写这条用例时先按相反的顺序试过一次，红得正好。

### 环的容量：算式换成读数

`evidence/fact-ring.md`：一次「两次请求 + 一个工具」的轮 = **10 条事实**（`turn/*` 两条 + 每次请求四条），
由上面那条用例**逐条钉住**（不是推算）；`fact-buffer-size` 1024 帧 ≈ **102 个这样的轮**
（加步之前同样一轮是 6 条 ≈ 170 个）。**上限没改**——读数没给出改它的理由。

### 整轮后端：跑完了，剩下的那条红是**既有**的

机器闲下来之后整轮跑完了（不再撞 300s 上限）。两棵树各跑一遍做对照：

| 树 | 用例 / 断言 | 红 |
|---|---|---|
| 加 step（本次） | **1334 / 14071** | 1：`http-test/an-overflow-refusal-compacts-aggressively-and-retries-in-one-turn` |
| `4a97153`（**没有** step 的树） | **1333 / 14069** | **同一个** |

两个数差得刚刚好：**+1 用例**（`mux_test` 那条新的）与 **+2 断言**（它里面那两条），都是这次的，
**一处红都没多**。

那条红本身是既有现象，也早于这条线：**单独跑 `harness.edge.http-test` 是绿的**（两次），**只在整轮里红**，
两棵树一模一样（现象是「重试的答案没有文本帧」，而同一个用例里那两条压缩断言是过的）。它属于另一条线，
这里的结论只有一句：**不是 step 这笔账**。
