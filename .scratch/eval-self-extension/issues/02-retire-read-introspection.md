# 02: 撤掉内存副本 —— 只删「抄下来的」，不删「问出来的」

**What to build:** `harness.memory` 不再维护**会话内存副本**：`sessions` atom 与 `record-history!` / `record-provider!` / `session` 三个入口删除。理由精确表述为「副本会与 JSONL 腐坏」，**不是**「读自省面整体撤销」。

http 层两处调用点（run 开始处记 provider、`:run/done` 处记 history）一并删除，run 生命周期与 JSONL 落盘时序逐字不变。

prompt.md 删掉 Introspection 段，改写成「自我扩展」定位：eval 是本进程内的自我扩展手段，注册的工具**仅本会话生效、进程重启即失**，每次 eval 的 code 与结果都落在该 thread 的日志里。Session tools 段与 `harness.opaque` 禁区声明保留。

**本票不做的事（划清与 04 的界）**：不新增 `active-provider`、不新增 `log-path`、不给 prompt 写「如何查自己的日志路径」。那些是 04。本票只做减法。

`eval-introspection/spec.md` 记入本次反转：撤销了什么、为什么、由本特征的 01/03/04 取代。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `harness.memory` 里不再有 `sessions` atom，不再有 `record-provider!` / `record-history!` / `session`
- [ ] http 层不再有那两处记录调用，且 run 的 JSONL 落盘时序完全不变：message 行、审计三行、`approval/decided` 的内容与顺序逐字不变
- [ ] 本票**不引入**任何新的读入口（`active-provider` / `log-path` 属 04），memory ns 的 diff 是纯删
- [ ] prompt.md 不再出现会话自省说明；含「仅本会话生效 / 进程重启即失 / 每次 eval 都落日志」三条事实；Session tools 段与 opaque 禁区声明原样保留
- [ ] 移除的断言在票面与 spec 列明：http_test 的 `the-session-state-is-addressable-by-thread-id`（整条），session_tools_test 的 `the-session-record-never-holds-a-key`，以及 `eval-joins-the-session-across-the-real-tool-call-shape` 尾部读 `session` 的两处。**移除而非改写为读文件**
- [ ] `eval-introspection/spec.md` 记录反转（撤销内容、理由、取代关系），其既有「已验证到什么程度」段不回改（那是历史事实）
- [ ] 离线全量 harness.test-runner 全绿（本票净减，清单见上）
