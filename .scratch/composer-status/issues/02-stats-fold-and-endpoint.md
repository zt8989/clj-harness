# 02 — 会话统计的折法与端点

**What to build:** 把一条会话的记录折成条子上那五个数（轮 / 模型调用 / 输出速度 / 总用量 / 缓存命中），
并从管理边吐出去。本票只到边为止，没有界面。

从用户视角：`GET /api/threads/<stem>/stats` 回来的就是那条状态条要画的东西，而且每个数都指得到
日志里的某一行——没有一个数是在客户端算出来的。

**Blocked by:** ~~01（没有 `model/start` / `model/end` 两行就没有可折的用量）~~
**01 已于 2026-09-16 落地**（见 spec 的「落地记录」，票面已按仓库约定删掉）：
两条行在记录里、`consume-sse` 留着用量、替身也能报。本票现在**可以直接开工**。

**Status:** ready-for-agent

## 折法

- 读侧住**新 ns `harness.edge.stats`**，与 `edge.replay` / `kernel.frames` 并排（记录的两个读侧）。
  它**不学着知道 root 在哪**：拿的是行或记录，目录是调用者的——与 `replay` 同一立场，docstring 里写明。
  解析复用 `harness.edge.replay/lines->records`，不写第二份 JSON 解析。
- 折法是**对记录序列的纯函数**（不吃文件、不吃服务）：能用 `evals_test` 那种 record 构造器手搓一小段
  记录来断言——这是本票可测性的关键，别把它写成只有起服务才能跑的东西。
- **轮的判据只有一个实现**（spec 决策 7）：`input` 的客户端消息里出现**新的** `role: "user"` id 才开新的一轮；
  悬置恢复那种「同一个 `runId` 的第二个 `input`、没有新的用户消息」**归给当前轮**，不新开一轮。
  用 id 差集，不用内容的公共前缀（同一条用户消息发两遍、system 消息会变，两种都会咬到内容比较）。
- **容忍半截的 run**：最后一个 run 没有终帧时**不抛**（`replay/ensure-complete!` 是重建的规矩，不是读数的规矩），
  读到哪算哪，并在 `incomplete` 里如实标出来。
- **时长由 `:ts` 差出来**，不加新的时间戳字段：一次模型调用的时长是
  `model/end` 的 `:ts` 减同一次调用的 `model/start` 的 `:ts`。
  **不拿两次工具之间的间隔冒充模型耗时。**

## 产出形状（本票定下）

    {"threadId": "<stem>",
     "turns": 3,
     "steps": 42,
     "calls": 42,
     "callsWithUsage": 41,
     "usage": {"totalTokens": 2900000, "promptTokens": 2800000,
               "completionTokens": 100000, "cachedTokens": 2744000},
     "outputTokensPerSecond": 242,
     "incomplete": false}

- `usage` 里**缺的键就是没报**——不是 0。`cachedTokens` 只在厂商报过那一格时在；
  `promptTokens` / `completionTokens` 同理；`totalTokens` 该键缺了的那次按 prompt + completion 补，
  两者都缺就这次整个不计。`usage` 一个键都没有时，`usage` 这个键**不出现**。
- `steps` 只在**至少有一行 `model/start`** 时出现：一份老日志（本特征之前记的）里没有这两行，
  那**不是**「这次会话一次模型调用都没发生」，是「这份记录里看不出来」——写 0 就是把「不知道」说成「没有」。
- `outputTokensPerSecond` 只在有调用**同时**报了输出用量与一对时间戳时出现，取整（参考图是整数 `242`）。
  分母是**这些调用**的时长之和，不是会话墙钟。
- `calls` / `callsWithUsage` 是**覆盖数**：条子今天不画它们，但端点是自足的——谁读它都看得出
  这几个数盖住了几次调用里的几次。**部分覆盖时数值就是报了的那些调用的合计**，条子不加标记
  （参考图没有）。这条是**可以复议的**，写在这里以便评审时一句话推翻。

## 端点

- `GET /api/threads/<stem>/stats`。`thread-verbs` 那个**闭集**加 `"stats"`，并且要改掉 dispatch 里那段注释：
  它今天写着「one shape, two verbs, and **every one of them is a POST because every one of them has an effect**.
  A GET on this shape is answered 405 HERE」——加了它之后这段话不再成立。
  `case [(:request-method req) verb]` 那两行也跟着长出第一个 GET 分支；
  **`GET .../rebuild` 仍然 405**，只是 405 不再覆盖全部动词。词表与注释一起改，不留一句过期的话。
  （`.scratch/trajectory/` 的 02 也计划做同一处改动；谁先落地谁改，另一票落地时会看到它已经改好。）
- **不新造寻址方式**：用 `replay/locate` 那套把 stem 落到目录，找不到就是它自己的那个拒绝，不另发明一个。
- 没有日志、或会话存在但没跑过（只有 `project/bound` 的日志）：返回 `turns: 0` 与空，**不报错**
  ——那是一个全新会话的诚实答案，不是截断。

## 缓存命中的拼法：以真机为准

- 仓库里那份录下来的响应用的是 `usage.prompt_tokens_details.cached_tokens`（fixture 里是 0）。
  「DeepSeek 用 `prompt_cache_hit_tokens`」这个说法在本仓库**没有任何证据**，不凭说法写代码。
- 真机跑一次带前缀缓存的请求，**看厂商实际回的是哪个键**：是 fixture 那种就照它；是另一种就把第二种拼法
  加在**同一处**（一个函数里），并写明它是哪个厂商的、证据在 `.scratch/composer-status/evidence/`。
- 一个都没报的会话：那一格不画（不是 0%）。

## 验收

- [ ] `test/harness/edge/stats_test.clj`（注册进 `harness.test-runner`）：折法用手搓记录断言，
      至少四条情形——①开新轮的判据 ②悬置恢复不新开轮 ③中途失败的那次在 `steps` / `calls` 里、
      **不在** tokens / 速率 / 缓存的分母里 ④老日志（没有 `model/*`）与半截的 run。
- [ ] 纯函数的性质：给定一段记录，`turns` / `steps` / `usage` / `outputTokensPerSecond` 与手算一致；
      `:ts` 是唯一的时长来源（记录里没有别的时间戳字段）。
- [ ] 端点用现成的 `with-server` + `api-call` 走**真 HTTP**（端口由 OS 分配，见 `AGENTS.md`）；
      404 / 拒绝的形状与 `rebuild` 同一条缝。
- [ ] 真机一次带工具调用的会话：`curl` 的输出与手算的五个数并排放进
      `.scratch/composer-status/evidence/`；缓存那一格按上面的「拼法」小节核过。
- [ ] 老日志（本次改动之前记的）打这个端点**不报错**，`steps` 缺席、`turns` 照旧。
- [ ] **协议那侧一个字都没动**：本票不加事件、不加帧。
- [ ] `clojure -M:test -m harness.test-runner` 跑完；报数带上**分支与提交**。
      本机有 4 条与本特征无关的既有失败（名字见 spec 的「状态」那节），**条数每次跑都不一样**：
      比对的判据是**失败的名字**，本票不该多出任何新名字。
