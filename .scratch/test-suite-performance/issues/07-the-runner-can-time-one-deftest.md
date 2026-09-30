# 07: runner 能一条一条报 deftest 的耗时

**What to build:** `harness.test-runner` 现在每个命名空间只打一行 `[16.3s] 名字`，看不出是**哪一条**
在花钱——票 01 要「每条 deftest 的耗时」时，只能现写一个夹具（`dev/scratch_http_time.clj`，从
clojure.test 的 `:begin-test-var`/`:end-test-var` 两个事件读时间）。给 runner 一个**默认关**的开关：

- 环境变量，和两个时限（`CLJ_HARNESS_TEST_NAMESPACE_TIMEOUT_SECS` / `..._RUN_TIMEOUT_SECS`）同一族；
- 打开后每条 deftest 打一行 `[1.2s] 名字`，并照旧打出那一家的合计；
- **关着时输出与今天逐字相同**（这是硬要求：套件输出是很多人读的东西，默认行为不能动）。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] 开关（环境变量，默认关）——关着时输出与今天逐字相同
- [ ] 打开时每条 deftest 一行（名字 + 耗时），一家的合计不变
- [ ] 「从两个 report 事件算每条耗时」是纯逻辑，`test/harness/test_runner_test.clj` 直接驱动它三态
      （过了 / 抛了 / 时限内没跑完）
- [ ] `docs/rules/testing.md` 讲「怎么量慢在哪一条」的那一段提一句这个开关
