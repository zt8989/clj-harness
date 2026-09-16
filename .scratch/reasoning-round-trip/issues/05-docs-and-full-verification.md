# 05 — 文档与全量验收

**What to build:** 把这条要求与它的来路写进文档，并端到端走一遍证明它成立。

- `docs/architecture/providers.md`：思考模式下的往返要求（「开了思考的请求里，历史每条 assistant
  消息都要带 `reasoning_content`；缺的补空串」）、字段名的吸收表（`reasoning_content` 与
  `reasoning` 两种拼法都认）、以及**为什么补的是空串而不是编内容**。取证那两次请求（r8 400 /
  r9 200）作为依据引一句。
- `harness.kernel.llm` 的 docstring：那句「返回的 assistant 消息逐字进历史」要补一句**例外**——
  请求侧会补齐一个缺失的键（且补齐发生在审计行之前，所以日志仍是真话）。
- `docs/architecture/edge.md` 或 providers 页：`message` 审计行「逐字」这条契约与补齐的关系
  （写下来的东西必须与发出去的一致）。
- `test/harness/fake.clj` 的档位说明：严格档是什么、给谁用。
- 若引入了新词（例如「严格思考模式」），进 `CONTEXT.md`；没有就别造词。

**Blocked by:** 01–04

**Status:** ready-for-agent

## 验收

- [ ] 上述四处文档逐处改到，且**每一条陈述都能在代码里指到对应的一行**。
- [ ] `spec.md` 的落地记录：实数（两个套件的测试数与失败名单）、真机那次两回合对话的结果
      （把该会话的 `message` 行贴一行出来）、以及**落地时发现的与本文写的不一样的地方**。
- [ ] **全量验收主线**：在家里那台 `kongming` 上真跑一轮两回合的工具对话，全程无 400；
      再跑一轮**关掉思考**（`reasoning-effort` 清掉）的对话，确认那条路上没有多出这个键。
- [ ] 后端全量：`clojure -M:test -m harness.test-runner`，失败名单与动工前**逐条相同**
      （`spec.md` 状态一节：715 tests / 10561 assertions / 2 failures；名称对上，不是数字对上）。
- [ ] 前端全量：`cd ui && npm run typecheck` 0 error、`npm test` 通过（用例数按 04 的增量）。
- [ ] 票面收尾：按 `docs/agents/issue-tracker.md` 的规矩处理做完的票面，`spec.md` 留着。
