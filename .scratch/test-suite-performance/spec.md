# spec: 后端全量 22 分钟 → 16 分钟 —— 排水屏障没人回答，每个 run 白等 5 秒

**来源**：主人 2026-09-30「帮我优化单元测试性能」。

**一句话**：`harness.kernel.loop` 的**排水屏障**问的是**消费者**「前面的事件处理完没有」，而测试里
的读者几乎没有人回答它——于是每一次无工具、无网络的脚本化 run 都要等满那个**给卡死消费者兜底的
5 秒期限**。`harness.approval-test` 的 224s、`harness.session-tools-test` 的 122s、
`harness.edge.ag-ui-test` 的 35s，全是这一件事。

## 症状（实测）

一台 4 核机器、一次干净的单跑（2026-09-30）：

| | 改动前 |
|---|---|
| 墙钟 | ≈22.3 min |
| 各命名空间耗时合计 | 1337.1s |
| 用例 / 断言 | 1380 / 14371 |
| 最慢的几家 | `approval-test` 223.9s、`edge.http-test` 202.2s、`session-tools-test` 122.0s、`kernel.tools-test` 87.5s、`cap.git-test` 69.8s |

**而这台机器整轮只用了约 22% 的 CPU**（224s CPU / 1000s 墙钟）——它在等，不在算。
`approval-test` 是最刺眼的那家：22 个用例、**零服务、零子进程、零 sleep**，却 10 秒一个。

## 机制（实测）

`harness.kernel.loop/drained!` 在内核写自己那行记录之前，发一个 `:drained` 控制事件、附一个
promise，然后 `(deref done 5000 nil)`：

```clojure
(defn- drained! [emit]
  (let [done (promise)]
    (emit (ev/drained done))
    (deref done 5000 nil)))            ; <-- 兜底期限，不是停顿
```

**那 5 秒是给「卡死的消费者」的兜底**，注释里写得很清楚（「一个卡在记录上的 run 比一行写错位置的
记录更糟」）。但这个机制只有在**消费者回答**时才不花钱。

一个无工具的脚本化 run，逐事件打时间戳（`dev/scratch_drain_barrier.clj` 的前身）：

```
      1ms  :run/start
    164ms  :model/start
    164ms  :drained
   5165ms  :model/end          <-- +5001ms，凭空
```

拿两种消费者对照量（`dev/scratch_drain_barrier.clj`，n=10）：

| 消费者 | 平均 |
|---|---|
| 兑现屏障（`loop/answer-drain!`） | **151ms** |
| 从不兑现 | **5,182ms** |

**即 `drained!` 的答案本来是消费者那一半的义务，而 `run-chan` 的 docstring 从没写过这件事。**
`harness.edge.http` 的两个 drain 循环自己兑现了（所以走真 HTTP 的用例从不付这笔钱），
`harness.kernel.loop-test` 的 `drain-chan` 从屏障存在那天起也兑现了——但另外五个 helper 没有。

## 决策

1. **义务写在一个地方、由一个门执行**：新增 `harness.kernel.loop/answer-drain!`，并把
   `harness.edge.http` 里两处内联写法收敛到它（纯提取，行为一字不改）。
2. **答案留在消费者一侧，不搬到生产者。** 试过在生产者一侧兑现（无缓冲 channel 的 rendezvous
   看起来已经证明了答案：`>!!` 只有在有人取走事件后才返回），结果 `http_test` 那条专门守行序的
   `an-answer-lands-behind-its-call-even-with-a-message-behind-the-call` 红了 4 条——它在隔离环境下
   6/6 全过、推演也认为等价，但**证明不了它在负载下安全，而这条行序有 ADR 0006 管着**。
   「取走了」和「处理完了」不是同一件事，差的那个正是下一行记录该落在哪。
3. **`harness.edge.replay/resume!` 一并修**：它有同一个洞，而那是**真人会按到的路径**
   （重整化 fork / 作者续写），每道屏障白等 5 秒。
4. **`run-chan` 的 docstring 写明这条义务**，`docs/rules/testing.md` 的「写新用例时要自己守的」
   加一条——下一个自己写 drain helper 的人，正是会踩它的那个人。
5. **不动 http 的时序、不动那些「故意留宽」的余量。** `shell-test` 那两个固定超时（12s / 8s）
   和 `bash -lc` 的登录 profile（本机 850ms；`-c` 只 84ms，但仓库记录过 `-c` 会破坏超时子进程的
   回收）都是有意为之，本特征不碰。

## 非目标

- 不做跨命名空间并行（那是另一张票 04）。
- 不动前端套件的串行（票 05）。
- 不缩小任何超时余量，也不动 `drained!` 的 5 秒本身——它是卡死时的兜底，不是优化对象。

## 验收主线

1. 一条无工具的脚本化 run，兑现屏障的消费者下 **< 3000ms**（必须远离 5000ms 的期限）——
   已落成 `harness.kernel.loop-test/a-consumer-that-answers-the-barrier-is-not-made-to-wait-for-it`。
2. 全量后端：**用例数与失败集跟改动前逐条相同**（多的是新加的那一条）。
3. `approval-test` / `session-tools-test` / `ag-ui-test` 三家的耗时落到个位数秒级。

## 落地（2026-09-30）

**根因修复**

- `src/harness/kernel/loop.clj`：新增 `answer-drain!`（消费者那一半的唯一写法）；`run-chan` 的
  docstring 写明义务。
- 补上五个漏掉的消费者：`test/harness/approval_test.clj`（`drain`）、
  `test/harness/session_tools_test.clj`（`spy-run` / `drain-events`）、
  `test/harness/edge/ag_ui_test.clj`（`run-events`）、`test/harness/cap/skills_test.clj`（`drain-chan`）、
  `test/harness/kernel/tools_test.clj`（一条内联循环）；`test/harness/kernel/loop_test.clj` 的
  `drain-chan` 改用同一道门。
- `src/harness/edge/replay.clj`：`resume!` 的循环兑现屏障。
- `src/harness/edge/http.clj`：两处内联兑现收敛到 `answer-drain!`。
- 回归用例：`harness.kernel.loop-test/a-consumer-that-answers-the-barrier-is-not-made-to-wait-for-it`
  （预算 3000ms 是从 5000ms 的期限**推出来的**，不是挑的）。
- 证据脚本：`dev/scratch_drain_barrier.clj`（两种消费者对照，可重跑）。

**文档改口**

- `docs/rules/testing.md`：「数字是余量，不是目标」那一条里的实测数字**失真了十几倍**
  （写着「全量约 110s、最慢 16s」，2026-09-20 量的），改成 2026-09-30 重量的
  **全量约 960s、最慢的命名空间 `edge.http-test` 约 210s**；并加了一条
  「自己写 drain helper 的，每个事件都要兑现排水屏障」。
- `test/harness/test_runner.clj`：时间限制头上那段同样的数字一起改。

**验证（干净单跑，4 核，同一台机器）**

| | 改动前 | 改动后 |
|---|---|---|
| 墙钟 | ≈22.3 min | **16.0 min**（960.3s） |
| 各命名空间耗时合计 | 1337.1s | **932.1s** |
| 用例 / 断言 | 1380 / 14371 | 1381 / 14373（＋新加的那一条） |
| 失败 | 4 fail + 3 err | **同样 4 fail + 3 err** |

`approval-test` 223.9s → **7.1s**；`session-tools-test` 122.0s → **8.2s**；
`edge.ag-ui-test` 35.0s → **0.0s**；`edge.replay-test` 5.1s → **0.1s**。

**既有的红（不是这一刀的账，改动前就在）**：`cap.mcp-wired-test` 的
`a-run-sees-a-servers-tools-and-calls-one`（3 条，时红时绿）、`cap.project-test` 的
`this-home-can-write-its-own-sensitive-list`（3 个 error，配置 EDN 里塞 Windows 路径报
`Unsupported escape character: \U`）、`kernel.hooks-test` 的
`the-system-prompt-point-was-added-as-one-row-of-the-same-table`（1 条）。

## 还没做的（已量化，见 issues/）

`edge.http-test` 210s 现在是最大单点；`tools/specs` 带线程时 127ms/次；`shell-test` 的
~20s 固定超时；整轮 22% 的 CPU 占用指向跨命名空间并行；前端 80.5s 的串行是设计使然。
