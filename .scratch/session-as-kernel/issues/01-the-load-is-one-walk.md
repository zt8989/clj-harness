# 01 — 装会话收成一次走查（`sofar` 单趟）

**What to build:** 点开一场会话（以及任何由它重建的入口：窗口、feed、rebuild、state）时，记录**只走一遍**：
`entries`、`state`、`compactions`、`prunes` 在同一个 `reduce` 里算出来。今天同一份记录被折 **5～6 遍**
（`entries` 一遍、`state` 一遍、`messages`（又调 `entries`）一遍、`compactions` 一遍、`prunes` 一遍），
这一点要消掉。

**Blocked by:** 无 — 可以立刻开始。

**Status:** ready-for-agent

- [x] 装会话只遍历记录一次（同一份记录上不再有 5～6 次折叠）。
- [x] 大记录上：峰值堆是 O(对话) 不是 O(文件)，且不再把整份记录物化（沿用票 06 的流式折法）。
- [x] 对外行为一字不变：窗口 / entries / state / rebuild / feed 的既有用例全绿。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- 装会话走一条流：`replay/fold-sofar`（`fold-records` 驱动），对话 / 状态 / 压缩 / 剪枝一次折完，不再物化整份、不再折 5～6 遍。
- `replay-test/sofar-is-one-walk-and-the-same-answer` 把答案逐一钉在它取代的那几个折上；全量绿（除预存在那条）。
