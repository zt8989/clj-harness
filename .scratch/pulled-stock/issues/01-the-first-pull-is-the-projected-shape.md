# 01 — 第一次拉：存量答的是投影的形状（折好的轮 + 最后一轮原样）

**做什么**

「一切存量都拉」的第一步：**第一次拉只答折好的那一层**。一场会话的一次读，答的不是整场对话，而是
**每轮一张卡 + 最后一轮原样**；卡带着它自己的 `seq` 区间，供票 02 的第二次拉寻址。

三条门一起改，答同一形状（同一条会话、逐条比）：

1. 侧栏点开 → `POST /api/threads/<stem>/rebuild`
2. 刷新回来 → `GET …/sofar` + 尾页 `GET …/page`
3. 往前翻 → `GET …/page?beforeSeq=N`

**与 `.scratch/cheap-session-load` 的关系：这是同一件 work。** 这一票 = 那份的票 01（读侧的「一轮折成
摘要」）+ 票 03（两扇门改用投影）。折法、`turnIsSettled`、共享用例表都按那份的口径走，不在这里另立
第二套规则。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] 服务端有一处**投影**：给一串记录，答「折好的轮 + 最后一轮原样」；折法照 `ui/src/lib/turns.ts`
      已经写死的规则（`turnBounds` / `turnConclusion` / `turnCounts` / `turnSummaryLabel`）。
- [ ] `rebuild` / `sofar` / `page` 三条答的是同一形状，逐条比一致。
- [ ] **最后一轮永远原样**，含 `state = :running` 那一格：还在被写的那一轮一步不少。
- [ ] `rebuild` 收口断掉 run 的那条写（`close-off-open-run!`）没被投影吃掉。
- [ ] 客户端不再对「服务端已经折好的轮」自己折一遍；`ui/src/lib/turns.ts` 与 Clojure 侧读**同一份**
      共享用例表（现 `ui/test/suites/turns.ts` 只有 TS 那一半）。
- [ ] 现场数字进 `evidence/`：同一场 65 MB 会话，`rebuild` 载荷字节数与「从点击到画出来」前后各一次。
- [ ] 被本票取代的旧边界写明：ADR 0004 里「不改 `WindowFrame` 的形状」那句。
