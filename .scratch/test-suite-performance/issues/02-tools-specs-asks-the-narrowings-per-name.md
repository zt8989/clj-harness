# 02: `tools/specs` 带线程时 127ms 一次，而 nil 只要 1.6ms

**What to build:** `(tools/specs thread-id)` 实测 **127.5ms**（n=20，max 160ms），而
`(tools/specs nil)` 只要 **1.6ms**——**80 倍**。前者正是**每一次 LLM 请求**要走的门，所以这笔钱
按调用次数乘在整轮上（`harness.session-tools-test` 里 `spec-names` 就被叫了几十次）。

差异只能出在 `served?`：`specs` 对**每一个工具名**都问一遍全部 narrowing 策略
（`(every? #(narrowing-serves? thread-id name %) @installed-narrowings)`），而带线程时那些策略要
为这个会话求一个事实（编辑模式 / 子代理范围），nil 时直接短路。要的是**一次 `specs` 只问一次
「这个会话服务哪些名字」**，而不是每个名字问一遍。

**注意别做错的事**：`served?` 是 `harness.kernel.tools` 的公开门，子代理按父会话推导自己那张表
也走它（`unserved-message` 同理），所以**不能把它缓存成进程级的事实**——会话各不相同，模式还会
在运行中变。要收窄的是 `specs` 这一次调用里的**重复**，不是这个问题的答案本身。

**Status:** ready-for-agent

- [ ] 先证一遍这 127ms 真的在 `served?` 上（把 narrowing 拆掉或计数，别猜）
- [ ] 一次 `specs` 里同一个会话的模式只求一次
- [ ] `harness.kernel.tools-test` / `harness.session-tools-test` / `harness.cap.editing*` 全绿
- [ ] 给出前后对照（`specs` 的 ms，以及**全量**的墙钟）
