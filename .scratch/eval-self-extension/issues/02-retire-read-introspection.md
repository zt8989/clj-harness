# 02: 撤掉读自省面 —— 内核不再维护内存副本，prompt.md 定位改「自我扩展」

**What to build:** `harness.memory` 不再维护会话内存副本：`sessions` atom 与 `record-provider!` / `record-history!` / `session` 三个入口删除，http 层两处调用点（run 开始处记 provider、`:run/done` 处记 history）一并删除。prompt.md 删掉 Introspection 段，改写成「自我扩展」定位：eval 是本进程内的自我扩展手段，注册的工具**仅本会话生效、进程重启即失**，每次 eval 的 code 与结果都落在该 thread 的日志里（可事后查阅、必要时固化为正式工具）；Session tools 段与 `harness.opaque` 禁区声明保留。`eval-introspection/spec.md` 记入本次反转：撤销了什么、为什么、由本特征的 01/03 取代。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

- [ ] `harness.memory` 里不再有按 thread-id 读 history / provider 的入口；eval 再也读不到会话内存副本
- [ ] http 层不再有记录调用，且 run 的生命周期与 JSONL 落盘时序完全不变：message 行、审计三行、`approval/decided` 的内容与顺序逐字不变
- [ ] prompt.md 不再出现会话自省说明；含「仅本会话生效 / 进程重启即失 / 每次 eval 都落日志」三条事实；Session tools 段与 opaque 禁区声明原样保留
- [ ] 移除的断言在票面与 spec 列明：http_test 的 `the-session-state-is-addressable-by-thread-id`（整条），session_tools_test 里读 provider/history 的那处与读不存在会话的那处。**移除而非改写为读文件**——读文件是 01 的职责
- [ ] `eval-introspection/spec.md` 记录反转（撤销内容、理由、取代关系），其既有「已验证到什么程度」段不回改（那是历史事实）
- [ ] 离线全量 harness.test-runner 全绿（本票净减，清单见上）
