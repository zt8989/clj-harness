# 票 01 的证据：模型调用的两条审计行

两条命令产生的两份东西，都在本目录：

- `record-lines.clj` —— **可重跑的脚本**：起**真的 HTTP 边**（`harness.edge.http/start!`，端口由 OS 分配），
  跑一轮真的会话，然后把那条会话的 jsonl 行按 kind 打出来。厂商是 `harness.fake` 的脚本替身，
  家目录是 `harness.test-runner/isolate!` 给的临时目录——**不碰真实的 `~/.clj-harness`、也不用真 key**
  （`AGENTS.md` 的规矩）。
- `model-call-lines.txt` —— 它的输出。

从 worktree 根重跑：

```
clojure -M:test -e '(load-file ".scratch/composer-status/evidence/record-lines.clj")'
```

## 它证明了什么

1. **一次模型调用恰好一对行，按次序配对。** A 段是两个模型调用（一次工具轮 + 一次收尾）：
   `model/start` / `model/end` 各两条，交替出现，`model/end` 带的是**那一次调用自己**报的用量
   （1093 与 1105，不是同一个数）。
2. **起点记的是这次调用的身份**：`:model` / `:base-url`（脚本替身自己报 `scripted` /
   `http://offline.invalid/v1`），而且**不是**请求体、不含 `api-key`。
3. **终点记的是厂商回来的话，逐字**：`usage` 里 `prompt_tokens` / `completion_tokens` /
   `total_tokens` / `prompt_tokens_details.cached_tokens` / `completion_tokens_details.reasoning_tokens`
   原样保留，一个键名都没被改过。
4. **没答上来的调用也把段闭合。** B 段是 `:protocol :explodes`（没有 defmethod，调用直接抛）：
   `model/start` 之后仍然有 `model/end`，**载荷是空对象**——「这次调用什么都没报」，
   与「报了零」是两件事（读侧靠这条分「还在跑」与「跑死了」）。
5. **帧一个字节都没多。** A 段 16 条、B 段 2 条 `event` 行，就是今天那些 AG-UI 帧；
   两条新行只落在 jsonl 里（`model/start` / `model/end` 都不上 wire）。

## 它没有证明什么（如实写在这里）

- **没有跑活厂商。** 这台机器上跑真请求要动真实的 `~/.clj-harness` 与那把 api-key，
  本会话不做（`AGENTS.md` 的家目录纪律）。厂商**真回来的数字**由
  `test/harness/kernel/llm_test.clj` 对仓库里那份**真录下来**的响应
  （`test/harness/fixtures/deepseek_sse.txt`）断言：769 / 324 / 1093 / 296、
  `cached_tokens 0`、`finish_reason "tool_calls"`、回声的 model——那是同一批数字的另一半证据。
- **缓存命中的键名拼法**（`prompt_tokens_details.cached_tokens` 是不是这台机器上那家厂商的拼法）
  留给票 02 的「以真机为准」那一节：那一票才把它折成条子上的一个数，也只有它需要为这个拼法负责。
- **`model/start` 里还没有 `:tools`**——照计划，那是 `.scratch/trajectory/` 的 04 往同一条行上加的键。
