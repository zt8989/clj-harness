# 01 — 没有投影时读一遍：兜底门现在把同一份记录读四遍

**做什么**

`GET /api/threads/<stem>/stats` 在**没有投影可读**的那条路上（`?fold=1`，或者 store 里没有快照），
把**同一份记录读四遍**。这一票把它变成**一遍**。

四遍是这个样子（`src/harness/edge/http.clj` 的 `stats-get`，第 4063 行那一支）：

| # | 在哪 | 读什么 | 现在做什么 |
|---|---|---|---|
| 1 | `(sessions/read-records stem)` | `replay/read-records` | 只为了两个门（404 / 400）**和**把行交给下面三个 |
| 2 | `(pressure/meter-of-records records)` | `replay/fold-consumers` | 折出 meter 的 band |
| 3 | `(stats/records->stats records)` | `reduce` 整份 | 折出五个数字 |
| 4 | `(context/records->context records …)` | `reduce` 整份 | 折出上下文那三块 |

**第 1 遍的行走已经没了，2/3/4 还在。** 而且第 1 遍的行**只**是被交给 2/3/4——它自己的两个门
（`replay/find-log` 就能答，一个字节都不读）并不需要那些行。

**为什么是「一遍」，不是「零遍」**

这**不是**「一遍都不读」那一票（`.scratch/cheap-session-load/issues/05` 说的同一个问题），
那一票治的是「读几遍」并且已经把前两条候选做掉了：活着时从内存答（`live-numbers`）、
以及 store 里那次 SELECT（`project/numbers-for`）。**那两条是投影，本票治的是投影没有时那条兜底。**

答案天生需要扫全份（`model/*` 那些行散在整份里），所以「零遍」做不到。**「有投影就读投影、
没有就读一遍」已经是这一票的全部主张。**

**该走哪扇门（已经量过，都在案）**

`harness.edge.sessions/fold-record`（`session.clj:578`）——它把每一行交给一个 reducer 当
`[line-index row]`，而且 `:fold` 那道缝背后就是 `replay/fold-records`，**本来就是一趟多消费者的走法**。
三个 reducer 都已经注册在会话的两条缝上（`stats/install!`、`context/install!`、`pressure/install!`），
所以**冷读与活读是同一份实现**，不是两套可能走偏的东西。

`:pressure` 已经是一趟了（`meter-of-records` 内部就是 `fold-consumers`）；**是另外两个又绕了一圈。**

**动手时绕过的两个坑**（我踩过，记在这儿省下一次）

1. **`empty-band` / `band-step` 是私有的。** 一份「手动 fold 记录」的调用方要能**指名**调它们，
   否则那个 fold 只有 `install!` 能开——所以这两件事要变 public（理由照 `stats-init` /
   `state-init` 已经公开的理由抄）。
2. **门不能问 `fold-record`。** 它 `fold-records` 一个不存在的文件时答 `{:ok nil}` 而不是
   `{:missing …}`，所以「这里没有记录」仍然要由 `replay/find-log` 答（它只**指名**文件、不读它）。
   已有的 404 用例（`the-endpoint-says-not-here-for-a-session-that-has-no-log`）把这句话钉住。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] **四遍变一遍**：一次 `?fold=1` 只把记录从磁盘取一次。判据是**数**不是计时——数
      `harness.edge.sessions/fold-record` 被调了几次（数在那道门的外面，因为 `:fold` 缝在
      namespace 加载时就闭包了 `replay/fold-records`，`with-redefs` 够不着它）。
- [ ] 那条用例必须先红后绿：旧代码上是 4 次。
- [ ] **答案一个数都不变**：同一份记录，新旧两条路的结果逐键相等
      （`a-run-writes-the-numbers-the-same-folds-would-answer-with` 已经要求 store / 活读 /
      记录三者一致，别把它弄坏）。
- [ ] **记忆里有的会话答 `stats` 一次都不读记录**——`live-numbers` 先答，这是投影那条路；
      新用例要 `sessions/drop!` 把会话放掉之后才走兜底，否则量的不是这一票的东西。
- [ ] 404 仍然是 404、仍然是 locator 自己那句话；400（记录在但读不了）仍然是 400。
- [ ] `messages-in` 要的行**不许**成为第四次读：reducer 顺手收着（`:rows`），
      或者另立一个消费者 fold——两条都行，但要在票里说清选了哪条、为什么。
- [ ] 现场数字进 `evidence/`：同一场 65 MB 会话，`?fold=1` 一次读几遍整份记录（改前 / 改后）、
      各花多久。