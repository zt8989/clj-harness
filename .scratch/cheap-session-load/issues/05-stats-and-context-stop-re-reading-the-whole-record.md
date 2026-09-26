# 05 — `stats` / `context` 不再是一遍又一遍的全量读

**做什么**

`GET /api/threads/<stem>/stats` 是打开会话时的**第三遍全量读**，而且是最坏的一遍：

- 它走 `harness.edge.stats/log-stats` → `replay/read-records`，**从头解析整份记录**；
- `ui/src/components/composer-numbers.tsx` 的 effect 依赖里有 `assistantCount`，所以**一个 run 里每多一条
  assistant 消息就问一次**——一场 65 MB 的会话，每次都是从头解析；
- `context` 就在同一个载荷里（`harness.edge.context` 又走一遍 `stats/read-records`）。

**先说清楚这一票不治什么**：`stats` 要的是散在整份记录里的 `input` 与 `model/*` 行，所以它**天生**是
O(文件)；「一遍都不读」不是目标，也做不到（`model/*` 那些行本身就散在整份里）。这一票治的是
**读几遍**和**什么时候读**。

**三条候选，票里必须说清选了哪条、为什么**（可以并用）：

1. **活着时从内存答**：`sofar` / `rebuild` 已经有「活着的会话从内存答」这条先例，判据也现成
   （`sessions/live-entry`）；`composer` 那条状态条读的是**本进程正在服务的那场会话**，绝大多数情况下
   就是这一格。
2. **并进「打开」那一次读**：第一次打开无论如何要读一遍整份记录（票 03），让那一次把 `stats` 与 `context`
   一起折出来，别让第二个端点再从头来一遍。
3. **客户端少问**：effect 的依赖该是「一轮结束」或那次 `nonce`，不是消息条数。改前改后各记一次
   「一个 run 问了几次」。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `stats` 的答案一个数都没变：同一份记录，新旧两条路的结果相等（`test/harness/edge/stats_test.clj`）。
- [ ] **活着的会话**答 `stats` 不再读文件（判据：那一次调用过程中没有再打开那份 jsonl）。
- [ ] 客户端不再随 `assistantCount` 每次重问；一个 run 里问的次数写下来（改前 / 改后各一次）。
- [ ] `context` 不再走**第二遍**整份读：它与 `stats` 共用那一次。
- [ ] 现场数字进 `evidence/`：同一场 65 MB 会话，打开一次一共读了几遍整份记录（改前 / 改后）、
      一个 run 里 `stats` 被问了几次、一共花了多久。
- [ ] 如果选了候选 3，`ui/test/suites/stats.ts` 有一条钉住新依赖的用例（不是删掉旧的就完）。
