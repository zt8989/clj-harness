# 02 — 请求里把缺的 `reasoning_content` 补成空串

**What to build:** 开了思考的那次请求，历史里**每条 assistant 消息都带 `reasoning_content`**：
有就原样带，没有就补一个空串（那轮确实没有推理内容——我们**不编**内容，只把厂商要求的那个键还回去）。

这一步发生在**请求发出去之前、`message` 审计行写下之前**——`message` 行的契约是「LLM 真实看到的
provider 形状消息，逐字」，补在 `llm/stream!` 里会让日志记一份与实际发出的不同的东西。

从用户视角：**那条 400 消失**，两回合的工具对话在开了思考的厂商上继续能跑。

**Blocked by:** 01（红用例就是这一票的靶子；`spec.md` 的病因一节与 `evidence/r8-*`、`evidence/r9-*`
是这条修法的依据——同样的历史，缺键 400、补空串 200，已在真厂商上验过）

**Status:** ready-for-agent

## 验收

- [ ] **只对思考模式生效**：resolved provider 带 `:reasoning-effort` 时才补；没有它的 provider
      byte-for-byte 不变（用例：同一份历史、同一套消息，两个 provider 各发一次，断言一个加了键一个没加）。
- [ ] **已有的推理逐字不变**：历史里本来带 `reasoning_content` 的消息，值一字不改（用例拿一段
      真推理文本比对）。
- [ ] **补的位置让审计行仍然说真话**：跑一轮之后读该会话 jsonl 的 `message` 行，
      **缺键的那条 assistant 消息在日志里也带 `reasoning_content`**（补发生在 `log-messages!` 之前）。
      一条用例断言这件事——这是本票最容易做错的地方（补在 `stream!` 里最方便，但会让日志说谎）。
- [ ] 01 那条红用例转绿，并且**把补齐去掉会让它立刻变红**（手动验一次并在落地记录里写明）。
- [ ] 补出来的值**只可能是空串**：一条断言「没有一条消息的 `reasoning_content` 是凭空长出来的文本」，
      即除了历史里原有的值，其余都是 `""`。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单逐条不变（`spec.md` 的状态一节）。
- [ ] 真机一次：在家里那台 `kongming`（`~/.clj-harness`，`reasoning-effort "high"`）上跑一轮
      两回合的工具对话，**不再出现 400**；把那条会话的 jsonl 与该轮的 `message` 行贴进落地记录。
      （那次对话会花掉几十个 token，用的是主人自己的 provider——他为此明确授权过。）
