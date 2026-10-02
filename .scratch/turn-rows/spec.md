# spec: 轮（turn）也写进记录

**一句话**：`turn/start` / `turn/end` 从「只上线、不上记录」改成**两处都在**，与 `step/*` / `model/*` 同形；
读侧一律读行，**一处推导都不留**。这是 ADR 0006 决策 3 与 ADR 0011「不改轮」那一条的**有意推翻**。

## 主人拍的（2026-10-02）

1. **轮不要算，记录。** 边界的判据今天至少三份实现（`harness.edge.stats/user-ids`、
   `harness.edge.trajectory` 的 `fresh`、客户端 `ui/src/lib/turns.ts` 的 `turnBounds`），
   三份就是三次机会各说各的；主人要它成为记录里的一等事实。
2. **老记录不补。** 读侧只认新行；今天之前的记录读出来**没有轮**（轮数、轨迹分组会缺一块）。
   不写迁移、不做翻译层。
3. **两处都在。** 记录里有行，会话那条下行照旧发，`:seq` 是**它自己那一行**的行号
   （不再是借开轮那条 user 行的号）。
4. **三处读法全改**：`harness.edge.stats`、`harness.edge.trajectory`、`ui/src/lib/turns.ts`
   —— 一处推导都不留。

## 为什么值得翻案

ADR 0006 决策 3 的纪律是「算得出来的不写」：轮的开合由记录算得出来（没见过的 `:source "client"`
user 行），所以不写行。**纪律没变，是"算得出来"这个前提站不住了**——它算得出来，但要算的地方
不是一个，而且 `step/*`（ADR 0011）已经把这条纪律反着用过一次了：

> 0006 自己对轮立的纪律是「算得出来的不写，算不出来的写」。一步的开合**算不出来**……
> 同一个纪律，反的方向。

轮的问题不在"算不出来"，在"**算的人太多**"，而且**算的结果没有被写下来**：一个已经落定的轮，
读侧每次都要重新推一遍它的边界，三份实现里任何一份改了规则，同一份记录就被读出两种轮。
把它写下来，与 `step/*` 的理由是同一条：**记录下来的是事实，推出来的是读法**。

## 于是三层

```
turn/start                                     ← 人说了话；这一行自己开一轮（进记录）
  step/start                                   ← 这一步开始（进记录）
    model/start   {…身份, context-window, 签名}
    model/end     {payload: 厂商逐字, numbers: 到这一刻的总量}
    tools/pre-execute / tools/execute / tools/post-execute
  step/end       {tools: [{:id :name} …]}      ← 进记录
turn/end         {steps, messages, seqFrom, seqTo}   ← 进记录
```

## 行的形状

- **`turn/start`**：payload `{}`。这一行**就是**边界（开轮那条 user 行紧随其后）。
  轮的名字（`turnId`）**不由行自己写**——写它的时候还不知道自己的行号；读侧按
  `<threadId>-t<这一行的行号>` 造，与 ADR 0006 决策 3 的原话一致（名字是 derive 不是 mint）。
- **`turn/end`**：`{:steps n :messages n :seqFrom n :seqTo n}`。`seqFrom` / `seqTo` 与今天的 fact 同义
  （这一轮覆盖的记录区间，`seqTo` **不含** turn/end 行自己）。
- 两行都是 CUSTOM frame 形状的行（`harness.edge.http/row-of`），所以 `replay/kind` 直接答出名字，
  与 `step/*` / `model/*` 走同一条路。

## 写在哪

- `turn/start`：`harness.edge.http` 那条 run emitter 里，**开轮那条 user 行之前**。今天它在这里先发
  fact（`:seq` 借"即将写入的那一行"）；现在这一行自己先落盘，fact 的 `:seq` 就是它的行号。
- `turn/end`：同一条 emitter 的 `:run/done` 分支，**在返回尾巴落地之后**（与今天的 fact 同一处、
  同一颗钟）。
- 边界一个字不改：`:run/interrupt` 不是终局（那正是停在半路等人），带着答复回来的 run 关的是**同一轮**；
  一轮可以有多个 run。

## 读侧

| 读处 | 今天 | 改成 |
|---|---|---|
| `harness.edge.stats` | `:turns` 数「没见过的 client user 行」（`user-ids`） | 数 `turn/start` 行；`user-ids` 删掉 |
| `harness.edge.trajectory` | 一轮 = 这个 run 带来的、没见过的 client user 消息 | 一轮 = 一条 `turn/start` 行 |
| 客户端 `ui/src/lib/turns.ts` | `turnBounds` 按相邻 assistant 消息分组、`turnCounts` 数消息 | 读服务端折出来的 `turns`（窗口帧带下来） |

服务端把每轮的 `{:turnId :steps :messages :from :to}`（`from`/`to` = 记录行号区间）作为窗口载荷的一部分
送给客户端；客户端按条目的 `seq` 与消息的 `id` 归属，不再自己数。

## 边界（不做的事）

- **老记录不补行、不迁移、不做翻译层**（主人拍的 2）。读侧只认行。
- **不改 `step/*` / `model/*` 一个字**。
- **不动 AG-UI 的帧词汇**：三族事实都不是 AG-UI 帧。
- **不开第三条 socket**；不做历史回放（fact 照旧只是 `harness.edge.mux` 那个有界内存环）。
- **`harness.edge.turn` 那份内存折保留，但只在写侧**：它数本轮到这一刻有多少步、多少条助手消息，
  好让 `turn/end` 的载荷有数可写。它不再是任何读侧的答案（`records->turn` 删掉）。

## 票

| # | 票 | 依赖 |
|---|---|---|
| 01 | 两行上身：写侧发 `turn/start` / `turn/end`，fact 的 `:seq` 改成自己那一行 | 无 |
| 02 | 服务端只认行：`stats` 与 `trajectory` 改成读 `turn/start` | 01 |
| 03 | 窗口带 `turns`：服务端折出来，客户端能拿到 | 01 |
| 04 | 客户端读行：`lib/turns.ts` 不再分组、不再数 | 03 |
| 05 | 收口：ADR 0017、四份文档、两套全量表、真浏览器走查 | 02–04 |

## 验证

- **记录的自洽**：一轮里 `turn/start` 在任何 `step/start` 之前，`turn/end` 在最后一条返回消息之后；
  断言的是**相对顺序**，不是时刻。
- **一行一处拼写**：`turn/start` / `turn/end` 这两个名字在服务端只有一处（`harness.edge.mux/fact-types`
  是事实族那一处；记录这一处跟着它）。
- **数字**：同一轮上，`turn/end` 行里的 `steps` 与同一份记录折出来的步数相等（一条读侧用例钉住）。
- **客户端与行一致**：窗口带来的 `turns` 与记录里的 `turn/*` 行逐条相等（一张共享用例表）。
- **老记录**：一份没有 `turn/*` 行的记录读出来 `:turns` 为 0 / 没有轮 —— 这是**判据**，不是缺陷。

## 落地

**票 01 / 02 落了，票 03 的「服务端一半」也落了（2026-10-02）；票 03 的 UI 一半与票 04 还没做。**

| 文件 | 改了什么 |
|---|---|
| `harness.edge.http` | 开轮写一行 `turn/start`、收轮写一行 `turn/end`；两族 fact 的 `:seq` 改成**它自己那一行**；窗口帧带 `:turns`（`window-frame`），live 与 record 两条路都带上 |
| `harness.edge.turns` | 新命名空间：把记录里的 `turn/*` 行折成每轮的账（`{:from :to :steps :messages}`）、`turn-id`（名字只有一处拼写）、一个会话 fold |
| `harness.edge.stats` | `:turns` 数 `turn/start` 行；`user-ids` 删掉 |
| `harness.edge.trajectory` | 一轮由 `turn/start` 行开（段机多一个 `:turn?` 标记）；message 行的请求侧/返回侧改按**行的性质**判 |
| `harness.edge.turn` | `records->turn` 删掉；docstring 改口为「写侧为本轮攒 `turn/end` 的载荷」 |
| 测试 | `client` fixture 现在带它开的那一行；`client-again` 表示不带；stats / trajectory 跟着改 |

### 判据

- `harness.edge.trajectory-test` + `harness.edge.stats-test`：50 用例 / 237 断言，全绿。
- 其余后端（mux / replay / normalized / sessions / ui / compaction / fork / pressure / context）：214 用例 / 975 断言，全绿。
- `harness.edge.http-test`：126 用例 / 1378 断言 —— **单跑与重跑都绿，整轮里红过一次**：
  `a-conversation-whose-runs-predate-the-numbering-heals-on-the-next-read` 报过 `:numbered-from-record` 为 nil。
  单跑这个用例（`clojure.test/run-test-var`）是绿的，重跑整轮也绿，主检出（未改）整轮绿 —— 记为**与负载/时序有关**，不是这一刀的逻辑。

### 三个踩过的坑

1. **真实记录的 `system-prompt` 行不在 run 的第一行**：它在 `turn/start` 之后、`model/start` 之后
   （`.scratch/record-envelopes` 把 prompt 与人的话放进了首个调用的信封里）。原来「段由第一条 message
   行开」的规则让它恰好当了段首；`turn/start` 行插到它前面以后，它落到了 `:returned` 一侧，prompt 从轨迹里
   消失。修法是把「请求侧/返回侧」从**位置**改成**行的性质**（entry 与 prompt 永远在请求侧）。
2. **`opens-segment?` 原来那条「entry 行 + streaming 就开新段」**会把同一个 run 的 client 行切成两段
   （`RUN_STARTED` 在它之前），于是那一轮没有 `:turn?`、user cell 消失。有了 `turn/start` 行做边界，
   这条规则整个删掉。
3. **同一个 run 里两条人的消息**不能各开一段：`one-run` 本来就会在一个段里开多个轮。判据是
   「这一段已经有返回侧的东西了」（`:streaming` / `:returned`），不是「已经有轮了」。

### 票 03（UI 一半）与票 04 落地（2026-10-02）

| 文件 | 改了什么 |
|---|---|
| `ui/src/lib/feed.ts` | `TurnRow` 类型 + `WindowFrame.turns` |
| `ui/src/lib/window.ts` | `Window.turns`（四个合并函数都带上，`applied` / `aligned` 的 identity 判据也认它） |
| `ui/src/lib/turn-rows.ts` | 新 store：一轮的账 + 每条 entry 的记录行号，按 message id 记住；`turnOfMessage` 是「这条消息在哪一轮」的唯一答案 |
| `ui/src/lib/turns.ts` | `turnBounds` 改成问 `turnOf`（记录说的）；新增 `turnStepBounds`（一轮里的**助手**那些消息）；`turnCounts` / `turnStepsFrom` 删掉 |
| `ui/src/components/turn-steps.tsx` | 所有选择器改成读 store（`useTurnOf`），步数直接读行里的数 |
| `ui/src/app.tsx` | `commit` 与两条 hydration 路都把 turns 交给 store |
| `src/harness/kernel/session.clj` | **写流的 live step 现在拿到真行号**（以前是 nil）——见下面第 5 个坑 |

### 又踩了四个坑

4. **一轮里有人的那条消息**：记录的边界站在它界定的东西**前面**（ADR 0017），所以 `turnBounds` 给出的轮从**问题**开始，而摘要行是**助手**那些消息的事。于是多了 `turnStepBounds`：轮里的助手那段才是折叠的对象，它的第一条画摘要行。
5. **写流的 live step 以前拿到的是 nil 行号**（`harness.kernel.session/row-written!` 的注释就写着「offset 未知」）。`harness.edge.stats` 不看它，所以一直没露出来；`harness.edge.turns` 要**写下这一轮开在哪一行**，于是整个轮都是 `{:from nil}`，客户端一条都认不出来。修法是把流监听拿到的 `:seq`（写入落地的那个 offset，`ADR 0007` 保证它已经是真的）传下去。
6. **`useSyncExternalStore` 里每次 new 一个 `[]`** 就是每次一个新的快照 —— React 无限重渲染（线上就是 React error #185）。`turnRows` 对「还没有轮」的那个答案是一个冻结的常量。
7. **hydration 那扇门不走 `commit`**：`app.tsx` 的 `read` 适配器自己把窗口交给 host，于是 turns 从没进过 store —— 打开一场旧会话时摘要行不在（重开一次就没了），只有跑过一次的会话才对。三处都要说一句。

### 判据（票 03 / 04）

- `ui`：`npm run typecheck`、`npm run build` 都过；`npm test` **226 用例全绿**（含改写过的 `suites/turns.ts`）。
- 后端：trajectory / sessions / stats / mux / normalized / replay **150 用例 / 677 断言全绿**；`http-test` 见上。
- **真浏览器走查**（`node scripts/dev.mjs --scripted` + 本机 Playwright）：发一句「看看这个项目」，一轮折叠成一条摘要行，读作 **「2 步」**，与记录里那条 `turn/end` 行的 `steps` 相等；页面没有 React 报错。
