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

## 票 01：`edge.http-test` 单点拆开与夹具 spawn（2026-09-30 完成，票已删）

**结论：不是又一个排水屏障。** 它是 123 条真 HTTP 集成用例，每条至少跑一次真 run（真服务、真
HTTP/SSE、真记录）。这台机器上：一次真 run 在客户端侧约 **300ms**（`dev/scratch_mux_cost.clj`），
一次 `bash -lc` 约 **730ms**（其中约 700ms 是登录 profile）——这两条是工作的下界。

**票里点名的那两处轮询**：`mux-run!` 的订阅重试是「POST 到 `/subscribe`，200 就走」（20ms × 最多
40 次是失败上限），`await-log` 是 25ms 轮询到那行出现为止——两者都是**到了就走**的有界轮询，不是
排水屏障那种「不回答就等满 5 秒」。一次真 run 那 300ms 主要是 WebSocket 连接 + 一次真 HTTP/SSE 往返。

**逐条量**（`dev/scratch_http_time.clj`：跑该家一遍，从 `:begin-test-var`/`:end-test-var` 读每条耗时）：
单跑 **129.9s / 123 条**。最大两条是 git 用例
（`a-directory-this-home-lists-is-read-and-moved-without-a-session` 26.9s、
`the-git-endpoint-reads-and-moves-the-sessions-working-tree` 16.6s，合计 43.5s = 33%），其余 121 条
合计约 86s（约 0.7s/条）。

**慢的是哪几条**（改动前、单跑，按秒；完整清单由 `dev/scratch_http_time.clj` 现跑现出）：

| 秒 | 用例 |
|---|---|
| 26.9 | `a-directory-this-home-lists-is-read-and-moved-without-a-session` |
| 16.6 | `the-git-endpoint-reads-and-moves-the-sessions-working-tree` |
| 3.8 | `the-log-the-server-writes-is-one-replay-can-read` |
| 3.2 | `the-projects-listing-joins-the-store-with-the-disk` |
| 3.1 | `switching-a-row-off-takes-its-text-out-of-the-next-runs-message` |
| 2.9 | `removing-a-project-unbinds-it-and-leaves-every-log-where-it-was` |
| 2.8 | `a-running-session-reads-what-has-arrived-and-nothing-is-written` |
| 2.7 | `the-provider-timeline-is-init-once-then-changes` |

top-15 合计 74.7s（57.5%）。除两条 git 用例的夹具 spawn，其余都是各自真跑一到几次 run 的价钱。

**修的是夹具的 spawn 次数**：`init-repo!` 原先八个 `shell/run`（八次 `bash -lc` = 八个登录 profile），
合成一条 `&&` 链——仍是八条 git 命令，省掉的是 profile。那两条用例 43.5s → 29.1s。
（同一个形状还剩两处：`test/harness/cap/git_test.clj` 的 `build-repo!`（每 namespace 一次，约 6s），
以及这两条用例仍建三个仓库而 `cap/git_test.clj` 已有「模板 + 拷贝」的写法——都落在票 **06**。）

**夹具本身：同一窗口内交替量**（`dev/scratch_git_fixture_cost.clj`，让负载抵消）——同一个仓库，
老写法八次 `shell/run` vs 新写法一条 `&&` 链：

```
round 1:  old 7067ms   new 2168ms   saved 4899ms
round 2:  old 7138ms   new 1629ms   saved 5509ms
round 3:  old 7264ms   new 1542ms   saved 5722ms
```

**一个仓库省约 5.4s**（八条 git 命令不变，省的是七个登录 profile）；两条 git 用例一共建三个仓库，
所以它们约省 16s。

**全量**：改动前、改动后、以及最终树上复核各一次干净全量——**用例数 1397 / 断言 14398 三次逐条
相同，失败集相同**（每次只是那条已知 flake 时红时绿）；各命名空间耗时合计 **613.9s / 596.5s /
690.0s**，`edge.http-test` 在整轮内 **134.5s / 120.0s / 134.5s**。三次差 ±15–20%，那是**负载**不是
这一刀，所以不拿整轮墙钟当证据——上面那个同窗口的量才是。

（上面排水屏障那一节的 `1381 / 14373`、`4 fail + 3 err` 是 2026-09-30 中午在**当时的树**上量的；从那以后
又合了几家别的票、负载也不同，所以这里另起一栏量改动前后。用例从 1381 涨到 1397 不是这一刀加出来的。
票面写的「118 条」也是更早那次的快照——现在是 123 条。）

**命名空间那一档留 300s 没动**：它还没响过；按 `docs/rules/testing.md` 的规矩（响了才调、调了要写清
为什么），没响就不调。改动后 `http-test` 是 120s，仍是这条线的工作下界，不是可以点掉的等待。

**证据脚本**：`dev/scratch_http_time.clj`（逐条耗时）、`dev/scratch_mux_cost.clj`（一次真 run 与一次
spawn 的实测成本）、`dev/scratch_git_fixture_cost.clj`（一个仓库老写法 vs 新写法，同一窗口交替量）、
`dev/scratch_http_client_cost.clj`（一次真 run 的客户端各阶段：机件 ~55ms，剩下 ~195ms 是 run 本身）。

## 票 02：`tools/specs` 每个名字问一遍策略（2026-09-30 完成，票已删）

**证明**（`dev/scratch_specs_cost.clj`，计数而不是猜）：一次 `specs` 里 `harness-config`
被调用 **22 次**（20 个名字 + 2），单次约 0.5ms —— 正是那 8ms 里的大头。`served?` 对每个名字问一遍所有
narrowing 策略，而 `cap.editing` 的那个每次都要解析本会话的配置（`editing-mode` → `blocks` →
`project/harness-config`，docstring 明写每次重读，不快照）。

**修**：`install!` 的 `:narrow` 多一个可选键 `:served-names`（一次答完整个集合）。`specs` 于是每个策略
只问一次；只有 `:served?` 的策略照旧逐名问。**两扇门必须给出同一答案**，所以只给 `:served-names` 的
策略在 `install!` 就被按名拒绝（执行缝按名单问 `:served?`，只答集合的策略会让名字从表里消失却仍然
可调用 —— 那正是拒绝语汇要把两种状态分开的那一对）。`served?` 这个公开的按名单问门保留不动（子代理按
父会话推导自己的表要用它）。**没有新增缓存**：模式仍然每次现求，改 config.edn 下一次问就生效。

**实测（同一台机器、同一会话；先热身再取 10 次均值；对照是把三处源码 checkout 成 main 的版本跑同一条
脚本）**：

| | 改动前 | 改动后 |
|---|---|---|
| 一次 `specs` 里 `harness-config` | 22 次 | **3 次** |
| `specs(thread-id)` 均值 | 8ms | **3ms** |
| `specs(nil)` 均值 | 10ms | **5ms** |

省下的钱随工具数增长（从前是每个名字读一次配置）。剩下那 3 次是常数：`served-names` 自己一次，另两次是
`read` / `write` 各自的 `:describe` 在说自己的模式——不是按名字重复。

**用例**：`tools_test` 一条（有 `:served-names` 的策略被问一次、`:served?` 零次；只有 `:served?` 的
照旧逐名；bulk 答案照样能收窄；只给集合的被按名拒绝）；`editing_mode_tools_test` 一条（两条门对同一组
名字必须给出同一答案）。该组原有 7 fail + 1 err，与基线逐条相同。

## 票 06：剩下的两处 git 夹具 spawn（2026-09-30 完成，票已删）

**两处，两种省法。**

**(a) `cap/git_test/build-repo!` 的八次 spawn 合成一条链**（步骤仍是可枚举的 vector；`run-in` 照旧
查退出码，所以某一步失败会停在它那里，而不是留下半建好的仓库去让下面的用例为一个无关的理由红）。同一个
仓库、同一窗口交替量（`dev/scratch_repo_copy_cost.clj` 的 (a) 段）：**八个 spawn ≈ 10.4s vs 一条链
≈ 2.3s**（这台机器现在被别的 agent 的测试占着，一次 spawn 约 1.3s，所以比票 01 那次量的更大——看比例，
别看绝对值）。

**(b) `edge/http_test` 的三个仓库改成「建一个、其余拷贝」。** 三个目录不能共用（一个会被切到 `side`，
listing 那条要一个被列出的、一个不被列出的），但只有第一个需要花钱在 git 上：**模板仍是 git 建的**
（路线见的还是真 git，不是捏出来的 `.git`），另外两个用 `harness.test-support/copy-tree!` 拷。
同窗口交替量（(b) 段）：**建三个 ≈ 6.8–7.9s vs 建一个 + 拷两个 ≈ 2.25s**（省约 5s；第三轮 9.1s 是
负载尖峰，如实记）。这次省的是**两次 git 构建**，不是两次 spawn。

**`copy-tree!` 从 `cap/git_test` 提到 `harness.test-support`**：两个命名空间现在都要它，而它那段
「用 `java.nio.file.Path` 相对化、不是切字符串」的算术踩过 Windows 的坑，不该有第二份。顺手修掉一个
真 bug：它原来答的是 `doseq` 的 **nil**——`git_test` 的 `scratch-repo` 靠 `track-temp-dir!` 的返回值
遮住了这件事，而 `http_test` 的 `init-repo!` 直接把 nil 当仓库路径用了出去（listing 那条用例当场
NPE）。现在它**答 DST**。

**用例**：`cap.git-test` 与 `edge.http-test`（后者那两条走 `/api/git` 的用例）绿；整轮见下。

## 票 07：runner 能一条一条报 deftest 的耗时（2026-09-30 完成，票已删）

`CLJ_HARNESS_TEST_TIMING=1` 打开后，每条 deftest 在它自己的命名空间那行下面多打一行 `[1.4s] 名字`，
并在头上多一行说明。**默认关。**

- **关着时输出与以前逐字相同**（票面的硬要求）：量法是同一家跑两遍（开 / 关）逐行 diff——差异只有两次
  跑各自的临时路径，和打开时多出来的那 18 行计时。
- 时间读的是 clojure.test **自己**的两个 report 事件（`:begin-test-var` / `:end-test-var`），做法是包一层
  `t/report` 并照旧转发给真的那个——所以失败还打在原来的位置，也没有第二条 `test-ns` 路径。
- 值只认 `1`/`true`/`yes`（大小写随意），**别的一律按名拒绝**而不是当没设——与两个时限同一个纪律：
  开了却悄悄没开，是对这一轮的错误陈述。
- 纯逻辑由 `test_runner_test.clj` 直接驱动：`timing-flag` 的三态，与 `var-timing` 的「起了、没结束」
  那一态（一个被命名空间上限打断的用例不该有一条假的耗时）。

## 票 03：`shell-test` 的两笔钱（2026-09-30 完成，票已删）

**先用票 07 的开关量逐条**（38.5s，24 条用例）：

| 秒 | 用例 |
|---|---|
| 12.0 | `a-command-that-does-not-finish-is-stopped-together-with-what-it-started` |
| 8.0 | `a-program-that-does-not-finish-is-stopped-with-what-it-started`（git-read-cost 那轮加的） |
| 8.0 | `what-a-command-printed-before-the-limit-comes-back` |
| 3.3 | `the-shapes-a-command-is-built-out-of-still-mean-what-they-mean` |
| 2.4 | `a-quoted-word-reaches-every-shell-this-machine-has-whole` |
| 1.5 | `a-login-shells-logout-does-not-clear-the-pipe` |

**两笔分开处理**（票面就要求分开）：

1. **四个形状合成一次 spawn**：`the-shapes-...` 的四次 spawn（单引号词 / 重定向 / 管道 / stdin）变成
   一次能分辨各段输出的调用，**四条断言一条没少**，每条仍点名它是哪一段。3.3s → 1.5s。
2. **三个「等满预算」的超时从 12s / 8s / 8s 收到 5s**（28s → 15s）。这三个数买到的只是等待——命令本来
   就不结束。
3. **登录 profile 一个字没动**：它修过一次真事故（`-c` 会让超时的子进程收不回来），票面也点明不许动。

**下界是量的，不是记的**（`dev/scratch_shell_bounds.clj`，n=5，2026-09-30）：`bash -lc true`
**716ms**；`bash -lc 'node --version'` **831ms**（profile + 起 node）。5s 是六倍今天的地板，也仍然
>2× 仓库里记过的最坏 profile（2026-09-23 那次 2.2s）——那个数没有删掉，它作为**另一端**留在注释里。

**结果**：`harness.infra.shell-test` **38.5s → 26.8s**，24 条用例 0 失败。剩下的 >1s 是：三个 5s 预算
（命令不结束）、`a-quoted-word-...` 的 ~5s（**每个 shell 各起一次**——Git Bash / pwsh / cmd 各自就是那
条断言的主体，合不了）、以及几条各一次 spawn 的。

## 还没做的（已量化，见 issues/）

整轮 22% 的 CPU 占用指向跨命名空间并行（票 04）；前端 80.5s 的串行是设计使然（票 05）。
**产品那一侧**：一次 `/api/git` 读已修到 ≈0.13s（见 `.scratch/git-read-cost/`）。
