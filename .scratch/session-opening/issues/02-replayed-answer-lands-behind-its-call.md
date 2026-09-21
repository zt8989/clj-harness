# 02 — 重放的答案落在它回答的那条消息后面，而不是列表末尾

**What to build:** `kernel/loop.clj` 的 `replay!` 今天把批准后跑出来的工具消息 `conj` 在历史**末尾**。
当这一轮的提交列表里还有别的消息排在停住的那条 assistant 之后（`project/before-llm` 那一族**会变**的
注入：技能正文、后台作业的结尾——开场块由票 01 之后不再是问题），答案就落在它们后面，按
`llm/unanswered-tool-calls` 的相邻判定仍然"无人回答"，于是这一轮**再一次停在同一个 interrupt 上**，
再次批准会**再跑一遍工具**。本票让答案插在**点名它的那条 assistant 消息正后面**，与
`@ag-ui/core` 的 applier 一致。

**Blocked by:** 无（票 01 让它成为"唯一还剩的"原因，但不是前置）

**Status:** ready-for-agent

## 证据（用 kernel 自己的函数在真记录上重放过）

会话 `2754772f…` 的 resume 轮（run `2375c98b`）交给 kernel 的 30 条消息，尾部四类东西：

```
[26] assistant[tool_calls: call_00_ET_cG…]   ← 停住的、刚被批准的那条
[27] user <instructions …>                    ← 票 01 之后消失
[28] user <skills>                            ← 票 01 之后消失
[29] user <skill name="to-tickets">           ← 会变，留在尾部（本票要处理的就是它）
```

```
as handed + replay  -> [n7u3 lKCL sjgD Q0LN  ET_cG]   ← 刚批准跑完的那次仍在列
result behind the call -> [n7u3 lKCL sjgD Q0LN]       ← 插到调用后面就答上了

drive!'s decision, with the real functions:
   the replay took the verdict        -> {:verdict :approved, :payload {}}
   still   (the run ASKS AGAIN)       -> ["call_00_ET_cG…"]
   dead    (the run is refused)       -> [n7u3 lKCL sjgD Q0LN]
```

`take-decision!` 确实消费掉了 verdict，但 `tools/parked-interrupts` -> `parked-for-call`
**不看 `:consumed`**，所以同一条 park 记录还在，`ET_cG` 落进 `still`。

## 要落地的判断

1. **插在调用后面，不是末尾。** `replay!` 里的 `(swap! history conj msg)` 改成插在
   "点名了这个 tool-call-id 的那条 assistant 消息"之后（找不到那条消息时**不猜**：退回末尾并记一行，
   与 `unanswered-tool-calls` 的相邻判定保持同一套语义）。
2. **别把记录的"提交侧/返回侧"切错。** `edge/http.clj` 那条 `message` 行是按**计数**切的
   （`(subvec (:history ev) (count messages))`，`http.clj:972-1003` 那段注释说明它为什么
   load-bearing：`trajectory/run-segments` 靠它分两侧）。中间插一条之后，"历史长过输入"不再是
   "kernel 追加了什么"的等价说法。**正解是 kernel 自己报**：让 `:run/done` 的载荷带上
   "这次我往历史里加了哪几条"（一个向量，`replay!` 的插入与 `model-call!` 的追各记一份），
   edge 用那一份写 `message` 行、不再用计数推。
3. **不动厂商那条规矩。** `unanswered-tool-calls` 的相邻判定是对的
   （`.scratch/sessions-live-on-the-server/issues/08` 的 A/B 里选了 A），本票只把消息摆到它要求的位置。
4. **同一条 park 记录消费之后仍被当成"还停着"** 是两回事，本票不顺手改：那是"批准之后再问一遍"
   的语义问题（它至少保证了不会静默丢掉一次批准）。若 01/02 之后仍能观察到重复执行，另立一票。

## 判据

- 一场会话：有 `/技能` 触发（尾部会有派生注入）-> 工具调用被 park -> 批准 -> resume **跑到 provider**
  （terminal 是 `RUN_FINISHED`，不再是同一个 interrupt，也不再 `unanswered-tool-calls` 报错）。
- `harness.kernel.loop-test`：`replay!` 把结果插在调用后面（历史里那条 assistant 的
  `tool_call_id` 与紧随其后的 tool 消息对上），且 `:run/done` 报出的"追加了哪几条"与历史实际
  的增量和一致。
- 记录：`message` 行的返回侧仍是 kernel 真写的那几条，不因中间插入而把一条客户端消息错记成
  kernel 的回答（`trajectory/run-segments` 的用例跟着钉）。
