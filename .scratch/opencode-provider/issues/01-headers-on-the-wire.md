# 01 — wire 能带「每次请求的头」

**What to build:** 一次 provider 解析可以带一组**要加在每一次出站模型请求上的请求头**；
OpenAI 兼容那条路径（`chat/completions`）把它们贴上去。这是为 opencode 弹开的一条窄而完整的路：
从「解析结果上有一个 `:headers`」到「线上的请求里真出现了那个头」，中间每一层都说得出自己为什么在这里。

从用户视角：没有 opencode 的时候，这一票**什么都看不见**——一个不带请求头的 provider，行为逐字节不变。
它的产出是一条**机制**，以及一个证明这条机制真的到线上的用例。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 验收

- [ ] `harness.kernel.llm` 的 `:openai-completions` 把解析结果上的 `:headers` 并进出站请求。
      `:headers` 缺席或为空时，请求**逐字节**与今天相同（用例对两种情形都断言一次）。
- [ ] **`stream!` 的契约不变**：方法里不解析 thread-id，它只从 provider map 上取一组已经算好的头。
      判据是 `grep -rn "x-opencode" src/harness/kernel/` **无输出**——kernel 里一个字都不出现具体头名。
      守卫测试查不到这一条（它查 require、不查内容），所以它是**人守的**，写在这里就是为了让人守。
- [ ] `harness.cap.providers` 的解析把 `:headers` 放上解析结果：`resolve-provider`、离线工具与设置面板
      走的 `effective-provider`、run 走的 `current-provider`，三条路都一样拿得到。
- [ ] 一个**本地 stub HTTP server**（`harness.kernel.llm-test` 里已有那一类；不出网）断言：
      配了头的 provider，出站请求里真有那个头、值正确；没配的 provider，一个多余的头都没有。
- [ ] **头的值从不落盘、从不上 `GET /api/settings`**：`wire` 的渲染字段表里**不新增** `:headers`
      （`never-rendered` 那条规则的同一立场——今天头的值只是一个 thread-id，明天会是一个密钥）。
      `harness.infra.llm-debug` 要记也只记**名字**，不记值。
- [ ] 三档解析的「谁赢」一字不改：头上线不改变 `GET /api/settings` 里任何一条**今天已有**的字段
      （`settings_test` / `providers_test` 现有断言逐条不变）。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单与本票动工前**逐条相同**（判据是名字不是数量，
      见仓库测试铁律）。失败实数与名单写进 `spec.md` 的落地记录。

**落地后把实数写进 `.scratch/opencode-provider/spec.md`**：失败名单与新增用例数。
