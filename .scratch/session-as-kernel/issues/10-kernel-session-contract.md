# 10 — 会话搬进 kernel：删掉 edge 那份（contract）

**What to build:** 没有调用者之后，删掉 edge 那份残留；会话机制只有 `harness.kernel.session` 一处。
`harness.edge.*` 里只剩适配：怎么读文件、怎么解析行、怎么把帧送出去。

**Blocked by:** 09 — 消费者搬过去。

**Status:** ready-for-agent

- [x] edge 里不再有会话机制的第二份实现（按名字查得到）。
- [x] `harness.layers-test` 绿。
- [x] 离线全量 `harness.test-runner` 全绿。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- edge 里没有会话机制的第二份实现：`grep -nE "^\(defonce|\(atom " src/harness/edge/sessions.clj` 无输出；机制只有 `harness.kernel.session` 一份（`layers-test` 绿）。
- edge 只剩适配：装缝、再导出，以及 `read-records` / `fold-record` 这两道读流的接线。
