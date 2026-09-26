# 04 — 每次 model start 之前量一次压力（run 中途不再盲飞）

**What to build:** 现在 `compact-if-pressured!` 只在**每次 run 开始**量一次。事故里那个 run 从
62% 长到 100% 花了 12 分钟、946 次模型调用，中途没人复看，最后一发才被厂商拒（HTTP 400，
`requested 1048581`）；`recover-overflow!` 补的这一步**要求厂商先拒**。主人（2026-09-26）拍定：
**每一发请求送出去之前都量一次**。

**位置**：`harness.kernel.loop` 的 `drive!` 每次调用那个 `let` —— `with-skills`（每调用一次的
pre-LLM 步）之后、`model-call-stoppable` 之前，与 `:on-overflow` **对称**的一个新 seam
（`:on-pressure`）。kernel 不知道「压力」是什么：它只问一句、拿回一个**更短的 history 或 nil**，
和 `:on-overflow` 的契约一模一样。决定与实现在 edge（`harness.edge.http`），meter 是
`harness.edge.pressure`。

**量的是哪个数组**：`@history` —— 就是**这一发要发出去的那个数组**，provider 形状，带着本次
run 自己派生的注入。这正是 run 起点的 `log-pressure` 拿到的那个东西，所以两次读同一把尺子。

**压完交回什么**：只能**从会话重建** —— 量过了，provider 形状的数组里 `:id` 被 `absorbed` 拿掉，
认不出「被 shadow 的那一段」，原地替换这条近路不存在。所以重建 =
`system + ag/provider-messages(sessions/messages)`，和 `recover-overflow!` 同一个形状；重建出来
的那个数组**缺本次 run 派生的注入**（技能体、job 收尾），因此要把 pre-LLM 步**再跑一遍**补回来 ——
但**不重新记账**：这些消息这一个 loop 迭代里已经进过 `added`、也已经发过 `context/injected` 帧，
再报一次就是同一件事画两张卡。

**挡住一种坏结果**：会话那边可能**慢半拍**（刚发出的工具结果还没落到会话视图里）。重建后如果
`llm/unanswered-tool-calls` 不空，就**不要**这个更短的视图（厂商会拒「tool_calls 后面没有 tool
消息」），让这一发照原样出去，交给 `recover-overflow!` 兜底。

**Blocked by:** 01（摘要请求要折成 provider 形状，否则中途压的那一发还是 422）

**Status:** ready-for-agent

- [ ] kernel：`drive!` 在每次模型调用前问一次 `:on-pressure`；答非 nil 且**更短**时换掉 history，
      然后把这个 loop 迭代的 pre-LLM 步应用上去（不重复 `added`、不重复发 `context/injected`）
- [ ] kernel：**不更短就不换**（`>=` 长度一律忽略），且换了之后若 `unanswered-tool-calls` 不空，
      退回原来的 history —— 宁可让厂商拒那一发，也不要自己造一个缺结果的请求
- [ ] edge：`relieve-pressure!` —— 用 `pressure/log-pressure`（拿这一发的数组 + 本次 run 的
      window）量，到/过阈值才**读记录**、才拿 `compaction-lock`、才做一次 `run-compaction!`
      （**预算那条计划**，不是激进的）；没到阈值的那 99% 调用**一个文件都不碰**
- [ ] 回归：一个 run 中途跨过阈值 ⇒ 下一发之前有一次压缩（脚本 provider 的第 N 个 turn 被摘要
      用掉，之后的请求换成了压过的数组），且这一发**照常成功**
- [ ] 回归：不更短的答案被忽略（history 原样）
- [ ] 回归：重建出来的视图若留下未答的工具调用，则**放弃**这次压缩，history 原样
- [ ] 真实记录上走查一次：拿 `.scratch/compaction-shape/evidence/` 里那个切片，让它中途跨过阈值
- [ ] 离线全量 `harness.test-runner` 全绿（本机本来就红的那几条除外）

## Comments

2026-09-26 — 这是 01/02 修完之后**唯一还没盖住**的那一格：meter 现在说得出真话，但它只在 run
起点开口。票 03 的事故时间线是它的证词：62%（22:40:38 起点）→ 97%（22:40:51 第一发）→ 100%
（22:52:01）→ 被拒（22:52:04）。

2026-09-26 — **已落地**（分支 `compaction-shape`，commit `be3adc1`）：kernel 的 `drive!` 每次调用前
问 `:on-pressure`（`:on-overflow` 的对称位）；edge 的 `relieve-pressure!` 量这一发要出去的数组，
过阈值才读记录/拿锁/压一次。测试：`loop_test.clj` 三条 + 新命名空间
`harness.edge.relieve-pressure-test` 两条，摘掉源码改动先红后绿（edge 侧编译即失败，loop 侧 3 处
断言失败）。

**实现时定了两件票里没写死的**，主人在 review 时可推翻：
1. 换掉 history 之后，**把这一个迭代的 pre-LLM 步重新应用一遍**（`(swap! history prepare
   thread-id)`），而不是把步刚加的那几条搬过去 —— `prepare` 是按「缺什么补什么」写的（技能体
   自己推出来、job 收尾在注册表里记着），所以不重复；也因此**不重新记账**、不重发
   `context/injected` 帧。
2. kernel 侧「更短」这一判据**交给 edge**（只有它有估价器）：kernel 只挡两种它自己能看出来的坏
   答案 —— 跟原来一模一样（`not=`）、以及换完留下未答的工具调用。
