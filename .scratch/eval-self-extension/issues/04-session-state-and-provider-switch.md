# 04: 暴露会话状态（日志路径 + 生效配置）与经授权的配置变更

**What to build:** 四件事，按「读 / 写 / 授权 / 作用域」分层。**本票不含 provider 注册表本身**（那是 05），只处理「查现在生效什么」与「授权改它」。

**读 A —— 本 thread 的 JSONL 路径。** `harness.memory/log-path` 返回当前 thread 的日志文件路径，现算不缓存。**同源收口**：把 http.clj:42 与 replay.clj:25 两份重复的 sanitize 逻辑提成**一份**共享实现，`log-path` 复用之——这是收口，不是新增第三份。暴露给 agent 的办法在票面定：进 prompt 的事实陈述，或一个只读工具 `session-info`。

**读 B —— 当前生效的 provider / model / reasoning-effort。** `harness.memory/active-provider` 返回 `{:protocol .. :base-url .. :model .. :reasoning-effort ..}`，**api-key 及任何密钥永不出现在返回值里**。写法与 `mem/config` 同构（现算、不缓存）——它问的是「现在是什么」，不是「跑的时候抄了一份」。

**写 —— 改 provider / model / reasoning-effort，走审批。** 新增工具（拟名 `session-configure`），标 `:requires-approval true`；agent 调用即 park，人类 `resolved` 后生效，`vetoed` 则不生效。**三字段各自独立可覆**（选 provider 名不自动覆盖 model/reasoning；三者可分别指定，见 05 的解析优先级）。落 `approval/decided`，变更另落 03 的 `provider/changed`。

**作用域改为 per-thread。** `harness.opaque` 从 `defonce provider-override (atom nil)` 改为 `(atom {})`，键为 thread-id，值为该会话的配置覆盖。`effective-provider` / `current-provider` 带上 thread-id 参数。**这是本票唯一动 opaque 形状的地方**，需把既有调用点一并改完。语义：一个会话改配置不再连带影响其他会话。

**授权性质 = 流程约定，不是安全边界（如实标注）。** spec 与 README 明写：eval 仍可 `(harness.opaque/use-provider! ...)` 绕过，bash 仍可读 .env。本票的 gate 是**防手滑**，不提供安全承诺。不试图把 override 藏 private（Clojure 里 `alter-var-root` 仍可破，藏了是虚张声势）。

**Blocked by:** 02（副本已撤，`active-provider` 才不会被误当成新副本）；03（变更需有 changed 行可查）。**与 05 互为阻塞**（04 要 05 的解析器；05 要 04 的 per-thread 存储形状）——两票需同批次落地，票面注明。

**Status:** ready-for-agent

- [ ] `log-path` 返回当前 thread 的 JSONL 路径，与 http 实际写入的文件**逐字一致**（同一实现，测试断言两者相等）
- [ ] http.clj:42 与 replay.clj:25 的重复 sanitize 逻辑收口为一份共享实现，两处调用点改用它，行为逐字不变
- [ ] `active-provider` 返回 `:protocol` / `:base-url` / `:model` / `:reasoning-effort` 四键，且覆盖生效/不生效两条路径都正确
- [ ] 断言 `active-provider` 的返回值中**不存在 api-key**（含嵌套、含 nil 值键）
- [ ] `session-configure` 标 `:requires-approval true`；未批准时 park 且不生效（断言 opaque 的 per-thread 项未被写入）
- [ ] 批准后覆盖生效于**该 thread**，且**其他 thread 不受影响**（两个 thread 并发/交替，断言各自 `active-provider` 独立）
- [ ] **三字段可各自独立覆盖**：只改 `:reasoning-effort` 时 model/provider 保持原值；只改 provider 名时其余按 05 的解析规则取值
- [ ] 否决后覆盖未写入，`approval/decided` 落盘且 `outcome` 为 `:vetoed`
- [ ] `opaque` 存储形状由进程全局改为 per-thread map，既有调用点全部改完，无残留全局语义
- [ ] 已知调用点清单（实测，勿遗漏）：`http.clj:150`（`current-provider`，thread-id 已在作用域）、`replay.clj:109`（**用的是 `effective-provider` 而非 `current-provider`，改签名不得改变其语义**）、`http_test.clj:35/37`（装/卸 script，**需取得当前 thread-id 才能装卸**——本票的隐性工作量，若测试结构拿不到，需在票面记录并改 `with-server` 接线方式）
- [ ] spec 与 README 明写「本授权是流程约定，不是安全边界」及其绕过路径（eval 直调 `use-provider!` / bash 读 .env）
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
