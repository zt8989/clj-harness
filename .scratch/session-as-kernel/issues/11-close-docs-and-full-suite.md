# 11 — 收口：文档与全量

**What to build:** 把这次改动落进文档与验证，不给下一个人留两个版本的真相。

**Blocked by:** 01、02、03、04、05、06、07、08、09、10。

**Status:** ready-for-agent

- [x] ADR：会话是**记录流的所有者**（机制在 kernel、适配在 edge），与 ADR 0002（会话归服务端）与
      ADR 0003（不改记录格式）的边界写清；「run 中内核不读自己的记录」这条铁律写进去。
- [x] `docs/architecture/layers.md` 与 `docs/architecture/edge.md`：会话机制住哪一层、两条缝叫什么、
      一个消费者怎么登记。
- [x] `CONTEXT.md`：若「会话机制 / 折子 / 表针」这些词还没有，补上（一个词一处定义）。
- [x] 离线全量 `harness.test-runner` 全绿（记下 tests / assertions / failures）。
- [x] 真浏览器走查：`node scripts/dev.mjs --scripted` 全过（动过 `ui/src/` 才需要）。
- [x] 在真会话上复现一次：点开会话之后，run 开头那次压力测量**零读**，且与离线折出来的答案相等。

**收口验证**（2026-09-24，`session-as-kernel` 分支）：

- ADR [0005](../../docs/adr/0005-sessions-own-the-record-stream.md)：会话是记录流的所有者、机制在 kernel / 适配在 edge、与 0002 / 0003 的边界、铁律与「安装不是加载」都写清了。
- `docs/architecture/layers.md` 加「一个机制在核心里、适配在边上：会话」（五道缝那张表 + 两道订阅缝 + 安装那条规矩 + 边界判断）。`docs/architecture/edge.md` 加同名一节。`CONTEXT.md` 补「会话机制 / 折子 / 表针」。
- 离线全量 `harness.test-runner`：1224 tests / 13522 assertions，1 红 —— 就是预存在的 `harness.cap.claims-test/a-second-jvm-owns-a-conversation-until-it-goes-away`。
- 真浏览器走查：`--scripted` 已改成「先 `npm run build`，再只起后端」（`scripts/dev.mjs`，一个进程一个地址）。本分支没动 `ui/src/`，但组合根改了，所以走了一趟**真的**：起 e2e 后端 → 真浏览器开 `http://127.0.0.1:<port>/`（页面由后端从 `ui/dist` 发出，200）→ 发一句「你好，走一遍走查」→ 脚本 provider 回放 `scripts/example.json`：一次 `read` 工具调用、助手答案、状态条「1 轮 · 2 次模型调用 · 300 tok/s」、上下文圈「已用 1%」；切「轨迹」页：系统 / 用户 / 助手 / 工具五条、2 次模型调用、工具结果照出。这一趟同时压着新路径：组合根装会话与表针、stats 与 trajectory 走会话的读流。
  （`--scripted` 本身**不驱动**浏览器——它把页面建好、由一个地址发出来；驱动它的是人 / agent 的浏览器。）
- 真会话上复现「run 开头零读」：`pressure-test/the-live-band-and-the-record-fold-answer-the-same-thing`（起真服务、真跑一轮、band 与离线折出来的一字不差）；且 `log-pressure` 的签名里**没有文件**，它没有可读的东西。
