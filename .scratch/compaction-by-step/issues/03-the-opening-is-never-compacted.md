# 03: 开场块永远不被压缩

**What to build:** 会话出生时写进对话的那几块（指令文件、技能清单、出生上下文）在任何 plan 下都不
进 head、不被摘要替代；压缩后的模型视图里，它们原样排在最前。

**Blocked by:** None（可以立即开始）

**Status:** ready-for-agent

- [ ] 受保护前缀的判定不再假设「开场块是对话的连续前缀」：按设计对话顺序是
      **system → 用户提问 → 开场块**，今天 `(take-while protected-node? nodes)` 因此在第一个节点
      就归零，开场块每次都被压掉。改成按身份认（开场块 entry 的 id / `opening-entry?`），
      不靠位置。
- [ ] 一条用例：含开场块的记录被压缩后，模型视图的前几条仍是那几块，且摘要不在它们之前。
- [ ] 一条用例：**没有**开场块的会话压缩行为不变。
- [ ] `overflow-plan`（aggressive）守同一条纪律。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（报数带分支与提交）。
