# 05 — 触发量「活会话」、选范围折「记录」：同一个阈值量的是两个表面

**What to build:** 压缩的**触发**与**选范围**量的是两个不同的数组，今天它们会互相打架：

- 触发（`harness.edge.pressure` 的 band + `log-pressure`）量的是**这一发要出去的活会话数组**
  （`sessions/messages` + 系统提示词 + 本轮注入），锚在厂商报的 `prompt_tokens` 上；
- 选范围（`harness.edge.compaction/plan`）折的是**从记录重折出来的模型视图**
  （`replay/read-records` + `replay/model-nodes`），预算按 `window × retain-ratio` 算。

两个数组并不相等：活会话持有每一轮派生的注入卡（技能体、作业收尾），记录重折只把**最后一轮**的
补回来（`pressure/injected-rows`）。于是可以出现这种局面：触发说「过阈值了」，而 plan 折的是那个
**又小又只比保留预算大一点点**的表面，它给出的 head 只有**刚写下的那张摘要自己** —— 换上一张
同样大的新摘要，表面原地不动，下一次调用前再触发一次。

**2026-09-30 的实测（thread `62f30024-…`，一次 run 前后）**：

| 事实 | 读数 |
|---|---|
| 触发（`context/pressure` 那一行） | `pressureTokens 778435`（74%，阈值 734003，`baseline usage`） |
| 同一时刻厂商报的下一发 | `prompt_tokens 607913` |
| 记录重折的表面（估算） | ≈180k（修编号之前之后都一样大） |
| plan 当时选出的 head | `:shadowed [12587]`，`head-tokens 4594` —— **就是上一张摘要** |
| 结果 | 2 分 22 秒里连压 4 次，4 张摘要卡，每次都白烧一次摘要调用（6–12k 输出 token/次） |

其中「活会话的条目号与记录重折不一致」那一半**已经修了**（合并进 main 的
`compaction-numbering`，见 `.scratch/entry-numbering` 与 `test/harness/edge/replay_test.clj`）：
现在同一批 facts 从记录侧与从活会话删掉的条目数一致，压缩真的删得掉东西了。**剩下的就是这张票**：
两个表面要不要合一、不合一的话凭什么可以不一样。

**要主人拍的一件事**（两种做法，实现时定了也照旧可推翻）：

- **(A) plan 折活会话** —— 触发量哪个数组、plan 就折哪个数组。代价：`plan` 现在只吃
  `records`（`harness.edge.compaction/plan` 的签名），而活会话是 `harness.edge.sessions` 手里
  那份模型视图；edge 侧把 `sessions/messages` 交给它，或者在 `plan` 上开一个「表面」参数。
- **(B) 让记录重折等于活会话** —— 把每轮注入卡也留在记录/折法里（`messages-in` 现在只补最后一轮），
  两个表面自然合一。代价更大，动的是记录与投影两处，收益是「记录里就写着模型看到的东西」。

**不论选哪条，同一张票里要落一条保险**：**折不动就不要折**。一次 compaction 之后若
`estimate-messages` 没有真的变小（或 head 里只有上一张摘要），下一发之前**不再压**、也不写那一对
`compaction/start` + `context/compacted` 行 —— 一个「压了等于没压」的空转不该留下卡片，也不该再烧
一次摘要调用。

**位置**：`harness.edge.http` 的 `relieve-pressure!`（mid-run 触发）、`compact-if-pressured!`
（run 起点触发）、`harness.edge.compaction/plan`（选范围）、`harness.edge.pressure`（尺子）。

**Blocked by:** 无（编号那一半已修完并合入 main）。

**Status:** ready-for-agent

- [ ] 定 (A) 还是 (B)，并把「为什么两个表面可以不同」或者「怎么保证它们一样」写进代码注释
- [ ] 保险：一次压缩之后表面没变小 → 下一次触发不再压、不写行（或 `perform!` 直接拒绝这种 head）
- [ ] 回归：一个「表面只比保留预算大一点点」的会话，压缩一次之后**不再压第二次**
- [ ] 回归：head 只剩上一张摘要时，不写卡片（或写明为什么写）
- [ ] 真实记录走查：拿 `62f30024-…` 那份记录复现「4 连压」，修后应当只压一次
- [ ] 离线全量 `harness.test-runner`（本机本来就红的 12 failures + 1 error 除外：
      `harness.kernel.tools-test` 7F+1E、`harness.cap.mcp-wired-test` 3F、
      `harness.cap.claims-test` 1F、`harness.kernel.hooks-test` 1F）

## Comments

2026-09-30 — 这张票来自主人报的「所有的 Context 开头的一直在压缩」。查到两层根因：一层是活会话的
条目号与记录重折不一致（**已修**，`compaction-numbering`，commit `2a14a68`，merge `e573bbd`），另一层
就是这里说的两个表面。上面那张表是 18:55–18:58 那一轮的真实读数（记录行 12584–12712）。
