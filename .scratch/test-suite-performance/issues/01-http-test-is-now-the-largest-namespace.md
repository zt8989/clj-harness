# 01: `edge.http-test` 现在是最大的一家，300s 的命名空间上限只剩 1.4 倍余量

**What to build:** `harness.edge.http-test` 一条命名空间 **210s**（118 条用例，约 70 个
`with-server`），是排水屏障修掉之后的**最大单点**。而 `CLJ_HARNESS_TEST_NAMESPACE_TIMEOUT_SECS`
默认 **300s**——余量只有 1.4 倍，机器再慢一点它就会开始响，而按 `docs/rules/testing.md` 的规矩，
那时候唯一诚实的做法是把它调大并写清为什么，不是让它一直响。

先把这 210s 拆开：现在只有**整只命名空间一个数**，看不出是哪几条用例在花钱。要的是**每条 deftest
的耗时**（一个跑一遍这家的临时夹具就够，不用改进 runner），然后按实测决定下一步：

- 有没有**第二个像排水屏障那样的系统性等待**（一次真HTTP run 要几秒，而一条脚本化 run 只要 150ms）；
- `harness.test-support/mux-run!` 的 subscribe 重试（`(Thread/sleep 20)` × 最多 40 次）和
  `await-log` 的 25ms 轮询各占多少；
- 每个 `with-server` 起服务实测只要 ~50ms（从日志时间戳量的），所以**不是**起服务的问题——
  别在这一条上做优化。

**Status:** ready-for-agent

- [ ] 每条 deftest 的耗时（哪几条占了大头，按秒列出来）
- [ ] 结论：那几秒是真实工作的下界，还是又一个可以点名的等待
- [ ] 如果是等待：修掉它，并给出**全量**改动前后的对照（不是只有这一家）
- [ ] 如果不是：把 300s 那个默认值连同理由一起调大（改 `default-namespace-limit-ms` 与
      `docs/rules/testing.md` 的表格及「数字是余量」那一条）
