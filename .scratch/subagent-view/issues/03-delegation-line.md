# 03 — 委派在父会话记录里有名字（`toolCallId` → 子会话）

**What to build:** 委派**开始时**，往**父会话**的记录里落一行，说清「主 agent 的哪一次调用开的是
哪个会话」：`{:kind "delegation" :toolCallId .. :subagent .. :threadId ..}`。再加一条读得回的路由
`GET /api/threads/<stem>/delegations`。

没有这一行，工具卡不知道该开谁——**而这是可以是一个事实的，不该靠猜**。

要点：

- **键是 `toolCallId`。** `kernel.tools/*tool-call-id*`（`kernel/tools.clj:345`）在工具体里可见，
  `run-subagent!` 跑在那条工具线程上（`t-agent` 是它的调用方），所以这个 id 拿得到。
  **不要**用「同一父会话里第 N 次委派」去配对：一轮里委派两个子agent 是支持的，两个调用并发跑，
  完成顺序不等于发起顺序，猜法会错，而且错得安静。
- **落在父会话的记录里，由 edge 写**（`run-subagent!` 手上才有 `log!`）。父会话的其余记录由父会话
  自己的 run 线程写，而这次委派期间**那条线程正阻塞在这次工具调用上**——所以不是「两个写手抢一个
  文件」。但**一轮里的两个委派会是两条工具线程**，它们会同时往同一个父记录追加：这一行必须是
  **一次写完整的行**（不拆成多次 write），或者走记录写手已有的那把锁。先问记录写手怎么保证的，
  别自己加一把。
- **`rebuild` 不许折进这一行。** rebuild 只折 `message` 与 `event`，所以一个新的 kind 天然不进对话
  ——这一条要**验证**而不是假定，因为往父会话的对话里塞一条「某次调用了谁」会改变模型看到的历史。
- **在 http.clj 的头注释里登记这个 kind。** 那里是记录里所有行种类的唯一清单（`\"input\"`、
  `\"event\"`、`\"message\"`、`\"tools/*\"` ……），加一个种类就得在那里说清它是什么、什么时候写、
  谁写。不登记的话，下一个读记录的人会把它当噪音。
- **`at` 是毫秒时间戳**，因为读的人可能想按时间排；`threadId` 是子会话的 id（`/api/threads` 里那个
  stem，也是 `rebuild` 认的那个 id）。
- **写的时候（委派开始时）不是跑完之后**——面板要在子agent **还在跑**的时候就能点开，这是整件事的
  意义。所以这一行必须先落。
- 读路由是**只读**的：只读记录，不碰库的写侧、不写 `~/.clj-harness`。

**Blocked by:** 无（与 01/02 可并行）

**Status:** done

**落地情况（2026-09-22）：** `run-subagent!` 写一行 `delegation`（`{:toolCallId :subagent :threadId :at}`），
`toolCallId` 取工具体里可见的 `kernel.tools/*tool-call-id*`，读回是
`GET /api/threads/<stem>/delegations`（信封 `{:threadId :delegations [..]}`）。
用例：`harness.edge.delegation-line-test`。
