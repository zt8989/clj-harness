# 05: 前端 80.5s —— 187 条用例串行跑，而那 ~30 条真跑 HTTP 的共用一个 script 文件

**What to build:** `cd ui && npm test` 实测 **80.5s**，拆开是：

| | |
|---|---|
| 用例本身（187 条相加） | **48.8s** |
| 其余 | ~32s（harness 启 JVM 约 20s，`transform` 9%，`import` 6%） |

串行是**设计使然**，而且理由都在 `vitest.config.ts` 与 `ui/test/ui.test.ts` 里写着：一个测试文件
（`include: ["test/**/*.test.ts"]`）才会只有**一个** harness（一个 JVM），`fileParallelism: false`
是为了不让两个驱动同时起服务。所以「拆成多个文件」不是优化，是把一条规矩拆掉。

**能动的只有一处，而它有个具体的前提。** 慢的全是那 ~30 条**真跑 HTTP** 的用例（最慢 7.7s，前 25 条
占了 48.8s 里的大部分），它们之间没有共享的服务端状态（每条用自己的 thread id，服务端本来就支持
并行会话——`concurrent` 套件就是为这个存在的）。**唯一撞车的是 script 文件**
（`test/support/harness.ts` 的 `scriptPath`）：`script(turns)` 写它，而服务端在**新的 thread id**
到来时才重读（`dev/harness/e2e_server.clj` 的 `install-pin!`，每个 thread 缓存一个 provider）。
两条用例并排跑，一条的写入就会落进另一条的读取窗口里。

**所以：要么让 script 按 thread id 分开（`<base>/<threadId>.json`，服务端按 thread 找、找不到
回落到默认那份），要么就别并行。** 前者是一条真的控制通道改动（`e2e_server.clj` 的 `--script-file`
契约，`scripts/dev.mjs --scripted` 也走它），先想清楚它会不会把走查那条路弄脏。

**Status:** needs-triage

- [ ] 先量清楚 48.8s 里有多少是**可以并行**的（不碰 script 文件的那几条纯用例只有 ms 级，
      别把时间花在它们身上）
- [ ] 逐条确认那 ~30 条之间**除了 script 文件**还有没有共享的东西（`home` 下的日志文件、
      `userHome` 里种的文件、按名字找的会话）
- [ ] script 按 thread id 分开的方案：服务端的回落规则、`scripts/dev.mjs` 要不要跟着动
- [ ] 决定：值不值（省下的是 48.8s 里的一部分，不是全部）
- [ ] 若做：`node scripts/dev.mjs --scripted` 那条走查仍然走得通
