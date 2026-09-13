# 03: provider 的会话时间线落盘（init + changed）

**What to build:** 把会话的 provider 历史落成 jsonl 时间线，**两种新行**：

- **`provider/init`** —— 会话第一次 run 开始时落一次。内容：生效的 `:protocol` / `:base-url` / `:model` / `:reasoning-effort`，**来源**（默认档 or 请求指定，见 05），以及 api-key 已被剥离的事实标记（**不含值**）。
- **`provider/changed`** —— 每次中途变更落一行，**before → after**（各四字段）+ 授权结果（`resolved` / `vetoed`）。

**取消原「每 run 一行快照」的设计。** 理由：init + changed 已能重建完整时间线；每 run 重复一行，在同一 run 内未变更时纯属噪声，长会话会刷屏。**审计的价值在时间线，不在快照密度。**

**api-key 绝不入行**（含嵌套）。这两种是**新行种**，既有行的内容与顺序逐字不变。

**Blocked by:** 02: 撤掉内存副本 —— 只删「抄下来的」，不删「问出来的」

**Status:** ready-for-agent

- [ ] 会话首 run 落**恰好一行** `provider/init`，含四字段与来源标记；断言行中不存在任何 api-key 键（含嵌套）
- [ ] 同一 thread 的第二次 run **不再**落 init（断言 init 只出现一次）
- [ ] 中途变更落 `provider/changed`，含 before 与 after 各四字段；before 必须等于变更前该 thread 的生效值（测试连续两次变更以上，第二次的 before 应等于第一次的 after）
- [ ] `provider/changed` 带授权结果；被 veto 的变更让读日志的人能**明确区分**「改了」与「被拒了」（落 changed 行并标 `vetoed`，或不落行——二选一在票面注明，但必须可区分）
- [ ] `:reasoning-effort` 缺省时该行照常落，值省略或 nil（票面注明），不因缺字段报错
- [ ] 落点与时序在票面与代码注释写明：init 落在 `input` 行之后、第一条 message 行之前；changed 落在 `approval/decided` 之后
- [ ] README 与 http 的日志行种类清单同步新增这两种
- [ ] 回放读侧不受影响：`replay/history` 只认 `input` / `event`，两种新行不参与重建；既有 replay 与 http 集成测试不改断言通过
- [ ] 离线全量 harness.test-runner 全绿（只增不减）

**与 04/05 的分工**：03 = 落盘（事后重建时间线）；04 = 当下查询与授权写（现算、不落盘）；05 = 多 provider 注册表与解析。三者不重复。
