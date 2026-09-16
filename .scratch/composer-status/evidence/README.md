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

---

# 票 02 的证据：端点的答案

同一个目录里多两个文件：

- `stats-endpoint.clj` —— **可重跑**：起真的 HTTP 边、把脚本厂商按上、跑一轮真会话（一次工具轮 = 两次模型调用），
  然后**真的 curl 一次**（`clojure.java.shell` 里的 `curl -s`）并把 curl 自己吐的字节打出来。
- `stats-endpoint.txt` —— 它的输出。

```
clojure -M:test -e '(load-file ".scratch/composer-status/evidence/stats-endpoint.clj")'
```

## 它证明了什么

1. **端点答的就是那五个数**，而且是 `curl` 从外面问到的（不是 JVM 内部调函数）：
   `{"turns":1,"steps":2,"stepsWithUsage":2,"usage":{"totalTokens":2248,"promptTokens":2200,
   "completionTokens":48,"cachedTokens":2000},"cacheHitPercent":91,"outputTokensPerSecond":2667,...}`
2. **每个数都与脚本报的数对得上**，输出里逐个写明了它是由什么加出来的：
   1040+1208、1000+1200、40+8、900+1100、2000/2200=90.9%→91。
   速率 2667 也对得上日志里那两对时间戳（48 tok ÷ 18ms）。
3. **`steps` = 模型调用**：一轮工具调用就是两次调用（一次带工具、一次收尾），记两行、算两步。
4. **不在这里的会话是 404，而且带句子**：`never-ran` 那次回来的是定位器自己那句
   「no log for thread … under …/projects -- nothing there is named never-ran.jsonl」，
   不是一句空白的 404。

## 它没有证明什么（如实写在这里）

- **还是没跑活厂商**（同票 01 那条：动真实 `~/.clj-harness` 与 key 本会话不做）。
  厂商真回来的数字由 `llm_test` 对 `test/harness/fixtures/deepseek_sse.txt` 断言。
- **`prompt_tokens_details.cached_tokens` 这个拼法没有在真机上核过**——它是仓库里那份录下来的响应用的拼法，
  而票 02 的「以真机为准」那一节要的是拿一台真厂商再核一次。**这一条留给你**：
  折它的是 `harness.edge.stats/number-at` 那一个函数，核出来是另一种拼法就在那一处加第二种（并写明是哪家厂商）。
- **`cacheHitPercent` 比票面的形状多一个键**：票面的产出形状里没有它，而条子第五格要画它——
  客户端不许自己除，所以它在这里算完再出去。`calls` / `callsWithUsage` 两个键**没有**落地：
  `calls` 就是 `steps`（同一件事实的第二份），覆盖数改叫 `stepsWithUsage`，一个名字一件事。
