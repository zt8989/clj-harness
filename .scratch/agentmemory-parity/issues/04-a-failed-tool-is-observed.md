# 04 — 工具抛了，也留一条观察

**What to build:** `PostToolUseFailure` 这一行早就声明着（`src/harness/kernel/hooks.clj:106`，
`:payload #{:tool_name :tool_input :error}`），而没有触发源的原因是**同一个 `when-not err`**：
`tools.clj:894` 只在成功那一路 emit。在它旁边补一条失败那一路，并在
`~/.clj-harness/hooks.edn` 加一条指向 agentmemory 的 `post-tool-failure`。

**Status:** ready-for-agent

## 决策

- **一条假失败都不能有：暂停不是失败。** 同一条缝上面几行已经把「挂起」单拎出来了
  （`suspended`，注释写着 "A SUSPENSION IS NOT A FAILURE, and it is picked out before the
  generic handler can read it as one"）。失败那一路必须写成
  `(when (and err (not suspended)) …)`——否则每次等人批准都记一条工具失败，观察流里全是谎话。
- **`error` 用审计行那同一句**：`(some-> err ex-message)` 就是 `ev/tool-executed` 报出去的那句；
  它是 nil 的时候退回 `(str (class err))`，因为 `:error` 是个声明过的字段，**不能没有**——
  一个没有理由的失败观察，与「什么都没发生」长得一样。
- **`tool_input` 照旧传 `parsed`**（这次调用真正的参数），与 `:post-tool-use` 对齐。
- 这一路是**观察者**（`:on-error :proceed`），所以记忆服务挂了只是审计行里一句，run 不受影响。

## 验收

- [ ] 真跑一次会抛的工具（例如 `bash` 一条不存在的命令）→ 库里一条 `post_tool_use_failure` 观察，
      `error` 那句与审计行/工具结果里说的是**同一句**
- [ ] **审批挂起跑一次 → 没有失败观察**（这一条比上一条重要：它防的是假账）
- [ ] 一条成功调用仍然只有 `post_tool_use`，不会两条都发
- [ ] 定向 `harness.kernel.tools-test`（或 hooks 那一组）补上「抛错时 emit 什么、挂起时不 emit」
      两条断言；全量绿

## 落地提示

- 缝在 `src/harness/kernel/tools.clj:862` 起的那个 `let`：`[result err]`（875）、`suspended`（885）、
  两个 emit（894 起）全在同一段里，改动是**紧挨着**的一行。
- 声明那一行照抄 `:post-tool-use` 那条的形状（`node "…/agentmemory.mjs" post-tool-failure`，
  `:timeout 8000`）。
