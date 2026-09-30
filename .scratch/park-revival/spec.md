# spec: 重启之后，把还能重建的问题再问一遍

**一句话**：一次 run 起手发现历史里有**没人回答**的调用、而本进程没有它的 park 时，先看这条调用的 park
是不是它**参数的函数**——是（围栏审批、`ask`）就**不执行任何东西**把 park 重新造出来、换个新 interrupt id
把同一个问题再问一遍；不是（服务器的 elicitation、这场会话已经不服务的工具）就照旧按名字拒绝整条 run。

2026-09-29 立（牛总看完会话 `9fbc5c8c` 的 `RUN_ERROR` 之后定的方向）。
与其相邻的两件事**没做**，见「未覆盖」。

## 症状

主人报的是 `unknown interrupt: 10ca340b-cdc5-41e3-bf56-4a3a824141c0`。会话 `9fbc5c8c` 的记录里：

| 行 | 时间 | 事实 |
|---|---|---|
| 20038–20046 | 2026-09-24 16:12:07 | 模型调 `ask`（`call_00_WKfSHIN76XB97RY1u8vt6061`，五个关于 `.env` / `mcp.edn` 去留的问题），`tools/execute` 说 `the call suspended on a question` |
| 20047 | 同上 | `RUN_FINISHED` + `outcome.interrupts[0].id = 10ca340b-…`，reason `elicitation` |
| 20085 / 20089 / 20094 | 2026-09-29 15:43 / 15:44 / 15:47 | 三次 `RUN_ERROR: unknown interrupt: 10ca340b-…` |

`harness.infra.log` 对得上：park 之后进程反复重启（`shutdown` → `listening port` 在 10:11 / 10:40 /
15:08 / 15:46 各来一轮），五天后人回答那张仍画在页面上的旧卡，`resume-decisions` 在**空的**
`parked-registry` 里查不到这个 id，抛错，整个会话此后无法继续。

## 根因

park 注册表**故意只活在进程内存里**（`harness.kernel.tools` 的 approvals 一节），所以重启丢 park 这件事
本身是设计，不是缺陷——`.scratch/pre-tool-approval/spec.md` 的「非目标」里写明「跨进程重启后 resume
（非目标，且被 `resume-decisions` 明确拒绝）」。

真正的缺口是**丢得太彻底**：`harness.kernel.loop` 起手只分两种下场——本进程还 park 着（再把问题问一遍）
与谁也答不了（按名字拒绝）。可是 `ask` 的题面、围栏审批的理由**就在那条调用的参数里**，历史把答案留在
手上，代码却当它没了。

## 决策

1. **多一条出路，而不是多一份状态。** `harness.kernel.tools/repark!` 从历史里那几条 `tool_calls` 条目造
   park：过一遍缝本来会过的检查（停用 / 不服务 / 缺参数——**过了才 park**），再由两条声明之一得出 park 的
   内容。**什么都不执行**，所以它不能拿 `run!` 顶替（`run!` 在不 park 的那条支路上是把工具跑掉）。
2. **能重建 park 的是那两条「park 是参数的函数」的声明**：
   - `:park-reason`（围栏与审批走这条）——记录要的 `:reason` 正是它答的；
   - `:park-question`（新增，`ask` 是唯一的用户）——记录要的 `:question` 正是 `suspend!` 会拿到的那一份，
     `ask` 把 `ask-question`（题面 + schema + 谁在问）抽成一个函数，`t-ask` 与声明共用一个答案。
3. **新 id，老 id 作废。** 客户端手里那个 id 是**本进程没造过**的 park 铸的，不能在它名下答话
   （`take-decision!` 会找不到记录）。所以 `repark!` 每次铸一个新 id——这正是「同一个问题，再问一次」，
   客户端因此收到的是同一张卡（同一条 interrupt 的形状、同一句 `message`），线上一字未改。
4. **重建不出来的照旧拒绝。** 服务器的 elicitation 是**服务器的问题**、历史里没有它；这场会话已经不服务、
   或已经不再声明 park 的工具不该被无中生有地 park 一次。这些留在 `dead` 里，按名字拒绝（`unanswerable-call-message`）。
5. **不重问 hook。** `PermissionRequest` 是在缝里、去问人的路上发的，而**留下未答调用的那次 park 说明没有
   规则替它决定**——真有规则批过，那次调用在当时就跑了、答案也进历史了。所以重建出来的 park 直接找人，
   丢掉的正是那个人的答案。
6. **进程内存那半一个字不改。** 不做跨进程持久化、不做超时（`parked-registry` 仍重启即失），
   改的只是「重启之后拿历史里剩下的东西再问一次」。

## 判据

- `harness.kernel.loop-test/a-question-an-earlier-process-parked-is-asked-again`：手写的历史（一条没人答的
  `ask` 调用）+ 空注册表 ⇒ `:run/interrupt`、`:reason :elicitation`、题面原样回来、记录是
  `GET /api/elicitation` 读的那份（`:asked-by`/`:prompt`/`:schema` **平铺** + 嵌套 `:question`）、
  **模型一次没被问**；随后拿新 id 走一次 resume，答案真的落成那次调用的工具结果。
- `harness.kernel.loop-test/a-call-nothing-can-rebuild-is-still-refused-by-name`：同样手写但换 `bash`
  （它的 park 是本会话的规则、不是命令的函数）⇒ 仍旧 `:run/error`，且句子里点名那条调用。
- `harness.approval-test/an-out-of-bounds-call-an-earlier-process-parked-is-asked-again`：围栏那一半，
  `:reason :out-of-bounds`、工具没跑、拿新 id approve 之后文件真的落盘。
- 既有用例一条没改：`approval-test` 的「未知 interrupt 是错误不是批准」、`http-test` 的
  `a-call-nobody-can-answer-is-refused-by-name`、以及 `ask-test` 那些实时 park 的用例全绿——证明
  「本进程还 park 着」那条老路没被碰。
- 定向跑过的命名空间：`loop-test` 31/141、`approval-test` 22/113、`http-test`+`normalized-test`+
  `replay-test`+`subagents-test` 186/1622、`tools-test`+`ask-test`+`editing-mode-tools-test`+
  `install-test`+`mcp-wired-test` 98/502——**0 failures**（`mcp-wired-test` 的 3 条
  `the connection is on the record too` 在**干净 HEAD 上同样失败**，与本次改动无关）。
- **后端全量**：`clojure -M:test -m harness.test-runner` ⇒ **1360 例 / 14229 断言，5 failures 0 errors**。
  五条全部在**干净 HEAD 上同样失败**（逐条比对过）：`mcp-wired-test` 3 条 `the connection is on the
  record too`、`hooks-test` 1 条 `PreCompact` 的 stdout 归属、`claims-test` 1 条代管告警的日志断言。
- **热修复**：改完源码后在**跑着的那个进程**里 `(require 'harness.kernel.tools :reload)` →
  `(require 'harness.cap.tools :reload)` + `install!` → `(require 'harness.kernel.loop :reload)`，
  再按 `docs/rules/hotfix.md` 的判据问一句：注册表里 `ask` 的 `:run` 是不是刚编译出来的那一份（true），
  并真的调一次 `repark!`（一条没 park 过的 `ask` 调用换回一条新 interrupt）。

## 第二次：读那一侧的缺口（同一天，牛总报「重启后还是 404」之后补的）

第一版只补了 **run** 那一侧（`loop` 走 `tools/repark!`），实测**够不着人**：重启之后人打开那场会话、
页面把卡片画出来，而卡片问的 `GET /api/elicitation?interruptId=<记录里那个 id>` **还是 404**——因为
**没有任何东西在跑**，`repark!` 那一格根本没轮到；而 `parked` 的会话**composer 是关着的**（client.md：悬置
时门关，卡片是唯一的出路），所以也没有「下一条消息」可以触发它。于是补两件东西：

1. **读那一侧也把 park 造回来**（`harness.edge.sessions/revive-parks!`）：对话自己写着「哪条调用停在
   哪个 id 上」（`RUN_FINISHED.outcome.interrupts`，折到最后那条 assistant 的
   `metadata.custom.agui.interrupts`），所以窗口真有两条路把它交给读者时（`read-entries` —— `GET
   …/page`；`window-page` —— 下行那条尾页）就顺手把 park 按**它自己那个 id** 造回来。**这一笔只写
   注册表**：记录不动、不取 claim、不建会话，id 已经 park 着的一律跳过；`ask` 的题面与围栏的理由都由
   `repark!` 从调用的参数现推，推不出来的照旧没有（服务器 elicitation 照旧锁死）。
2. **折的时候，那个 id 得真的落在卡片该在的那条消息上**——顺手查出一个**当天就在的旧洞**：
   `apply-frames` 只把 interrupt 折进**它自己那个帧组**的最后一条 assistant，而一轮的帧会被它自己的
   `message` 行切成两组（`entries-step` 在每条 message 行前收组）。**当前写法**正是把模型的
   `kernel-message` 行夹在工具调用帧与终帧之间（实测：会话 `24b97ff5` 的两个 park 都是这个形状），于是
   终帧那一组里一条 assistant 都没有，卡**整张**从重建出来的对话里消失——刷新之后连 404 都没有，
   `parked` 的会话同样出不来（`.scratch/session-after-refresh` 票 06 那句「悬置的卡片刷新回来还在」在
   新记录上是不成立的）。改法：**终帧那一行**就把 interrupt 挂到「**它自己的 `toolCallId` 所在的那条
   消息**」上（`replay/park-on-call`）——call 自带答案，不必猜「最后那条 assistant」是哪条；消息还在
   `:pending` 里（整轮一个组，也就是旧记录的样子）时找不到落点，照旧交给 `apply-frames` 自己那一挂。

### 判据（第二次）

- 新用例 `harness.cap.ask-test/a-card-read-back-after-a-restart-is-still-answerable`，**端到端走真 HTTP**：
  真跑一次 park，把那份记录**复制**给另一个没 park 过任何东西的会话、并把 interrupt id 改成一个没人持有
  的（「park 是进程内的、又没有一扇门能撤销它」，所以重启只能这样造出来）⇒ 直接问端点先拿到那个 404；
  读一次 `…/page` 之后 **200**，`askedBy` 是 `model`、`schema` 里五个问题一个不少，拿这个 id resume 的答案
  真的落成那次调用的工具结果。
- 真机（本机那个活进程，会话 `9fbc5c8c`）：`GET /api/elicitation?interruptId=10ca340b-…` 由 **404 → 200**，
  五个问题的 schema 与 `askedBy: "model"` 都在；`24b97ff5` 的两个 park 在折出来的对话里**挂回了它们
  自己那条 assistant 消息**（`seq 65` / `seq 718`，id 与注册表里那两条一一对上）。
- **后端全量（第二次）**：1361 例 / 14239 断言，仍是那 5 条既有失败（见上）。

### 未覆盖（第二次）

- **旧 id 的 resume 仍被拒**：人若去点重启前那张卡（不重新读页面），`resume-decisions` 仍按名字拒绝。
  读一次页面/一次普通发言都能把这格补上，但「点旧卡」那条路没做——它要的是 resume 那侧的宽容（不可与
  「不猜一个批准」的纪律同改，见 kernel.md）。
- **`window-page` 那一挂只有 `since` 无尾页时可能扑空**：增量帧里不再含那条 assistant 消息，`named-parks`
  找不到 id（那种读者的对话早在前一次读里拿到了，所以不构成缺口）。

- **客户端手里那张旧卡。** 人若去点**重启前那一张**卡，`resume-decisions` 仍按名字拒绝（本次不动 res/线），
  所以那条路还是 `RUN_ERROR`——但会话不再是死的：下一次普通发言会把问题重新问出来。客户端一侧的做法
  （`/api/elicitation` 404 就丢掉卡片）没做。
- **服务器的 elicitation 仍然锁死会话**：题面在服务器那边，历史里没有，重建不出来。同一条拒绝照旧。
- **真浏览器没走**：本次没有改 `ui/src/`，所以按 AGENTS.md 不需要走查；重建出来的卡片与实时卡片在线上
  是同一串帧（同一条 `ev/run-interrupt` 终端），但这一格只有真浏览器能确认。
