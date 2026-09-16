# 01 — 脚本 provider 学会「严格思考模式」：把这类 400 变成套件里的一条用例

**What to build:** 测试替身 `harness.fake` 多一档**严格思考模式**，好让「思考模式下没把
`reasoning_content` 送回来」这件事在套件里**可复现**，而不是只能等真机撞上：

- 某一轮的 tool call **不带**推理（象真厂商那样：`reasoning_content` 这个键在 delta 里出现过，
  但值一直是空串，于是我们组装出来的消息里没有这个键——见 `spec.md` 的病因第 1、2 条）；
- 任何**处于思考模式、而历史里某条 assistant 消息没有 `reasoning_content` 键**的请求，
  回 **HTTP 400 + DeepSeek 那句原话**（`The \`reasoning_content\` in the thinking mode must be
  passed back to the API.`），一字不改。

从用户视角：以后谁再动「历史怎么组装」，套件里有一条**红得清清楚楚**的用例，而不是等某家厂商
在生产里报 400。

**Blocked by:** None — can start immediately（与 03 互不阻塞）

**Status:** ready-for-agent

## 验收

- [ ] 严格档是**一个开关**而不是另一套替身：产物里仍只有一个 `:protocol`（`harness.fake` 现在
      定义在 `test/harness/fake.clj`），模式由脚本文件的一个字段选中，这样 `dev/harness/e2e_server.clj`
      那台真后端也能起严格档（04 要用它）。
- [ ] **严格档的判据是请求本身**，不是轮数：拿到 messages 就检查「思考模式开着吗、有没有 assistant
      消息缺这个键」。缺就 400，句子与真厂商一字不差（把那句话抄进测试，别用近似话）。
- [ ] **非严格档一个字不变**：现有套件（尤其 `ui/test/suites/client.ts` 与
      `harness.kernel.loop-test`）全绿，且没有任何一条用例因为这一票改了期望值。
- [ ] 一条**红用例**：走真 edee（`harness.edge.http`）跑一轮「模型调一次工具、拿到结果再继续」的
      对话，严格档下第二个 LLM 请求应当**不**被 400 —— 这条用例在 02 落地前是红的，**这就是它的用处**，
      用例的 docstring 要把这一点写明（红得有理由，而不是「先写个必过的壳」）。
- [ ] 严格档下**把 02 的补齐去掉**会让这条用例变红（02 落地后回到本票核一次，把这一步写进 02 的验收里）。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单与动工前逐条相同（`spec.md` 的状态一节）:
      严格档是**新增**的档，不该动任何既有断言。
