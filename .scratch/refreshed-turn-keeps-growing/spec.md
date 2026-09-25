# spec: 刷新回来的那一轮，回答接着长

主人报的（2026-09-23）：**正在进行的会话，浏览器整页刷新之后，明明提示还在运行，界面却不再刷新——
助手这一轮的回答停在刷新那一刻，之后一个字都不再长**（页面底部摆着「复制 / 刷新」，像是这一轮已经
写完了）。期望是：像没刷新过一样，回答一个字一个字接着长出来。

## 症状与现场

- 触发面：**整页刷新**（F5 / Cmd-R）落进一场服务端仍在回答的会话。
- 看得到的：那一轮**停在刷新那一刻**；composer 的「还在被回答」提示、侧栏那一行的
  「运行中」都对——**只有内容冻住**。
- 想要：刷新后这一轮继续增长，直到 run 结束、最终版本落在原位。

这不是 `session-after-refresh` 那一族没做的事：那一族已经保证「刷新落回这一场、看得见在跑、停得
下来」，走查 B 段断言了刷新后「这一轮仍被画成没写完」——但它**没有断言刷新之后还有新内容到达**。
本特征是那条缝。

## 根因（两条项目自己的事实夹出来的）

1. **服务端在 run 进行中就给半成品**，而且同一个消息 id 会反复出现、一次比一次长。
   记录折回时，run 的帧落在 `:pending`，没有 terminal 时按**记录最后一行**编号（`harness.edge.replay/entries`）；
   每多写一行，这条半写好的回答就被重新编号、重新给出，**id 不变、内容更长**。
   `test/harness/edge/replay_test.clj` 的 `a-log-that-stops-mid-run-numbers-its-partial-answer-last`
   钉的就是这一步。feed 的每一帧都在重读记录算增量（`harness.edge.http/window-page` 的
   `stream-feed!` 循环），所以这条一次比一次长的条目**真的会送到客户端**。
   
   **落地时更正（2026-09-25）**：这一条**只对了一半**。半成品确实按行写进记录，`window-page` 也确实每次都
   重读记录算增量——但**没有任何东西让那次重读发生**。门铃（`harness.kernel.session/watch!`）只在**会话表**
   变了的时候响：动作的条目（`append!`）、run 登记（`run-started!`）、终帧折叠（`settle!`）、行落盘。run 进行
   中写下的每一行**不响**，而那些行正是窗口画的东西。实测（`dev/scratch_refresh_watch.clj`）：run 跑着、记录
   在长，1.5 秒里 doorbell 一个都没响、窗口答的还是刷新那一刻的条目；`settle!` 一响，整轮一起出现——正是主人
   看到的那一格。所以这一族是**三票**，服务端那一半是票 03。

2. **客户端把「同一个 id 又来了」当重复丢掉**（`ui/src/lib/window.ts` 的 `unseen`，被
   `applied` / `prepended` / `aligned` 共用）。那条去重是为「行还没落盘、下一帧原样再来一遍」写的
   （`ui/test/suites/window.tsx` 的 `an-append-continues-the-window-and-a-repeat-is-not-a-second-entry`
   钉着它），可服务端这里的重复是**同一个消息的新版本**。于是：cursor 前进了、帧也 commit 了、
   `importWindow` 也跑了——但窗口里的条目还是那份旧快照，内容自然不动。

刷新后的 `ownRun` 是 false，`isOwnRun` 不拦导入（那一层是给「本页自己驱动 run」留的，见
`docs/architecture/client.md` 的审批门一节）——所以这一格**纯粹是窗口合并语义的问题**。

## 三票

| # | 票 | 依赖 | 交付 |
|---|---|---|---|
| 01 | 窗口把同 id 的新版本当更新，刷新回来的那一轮接着长 | 无 | 窗口合并升级成**原地更新**：同 id 的新版替换旧版、位置不变、不新增；内容真没变时窗口按原身份返回。刷新与「侧栏点开正在跑的会话」两条路都跟着长。 |
| 02 | 正在被回答的那一轮不摆「复制 / 刷新」 | 01 | 动作条与 composer 同一个判据（本页的 run **或**服务端的 `running`），不再把一条还在写、还会变长的消息当成品。 |
| 03 | 记录在长也算「会话变了」：刷新回来的那一轮服务端要说话 | 无 | 唯一的写路径每写一行留一个标记，**每 100ms 一次 tick** 把它变成对看着这场会话的门铃（一次 tick 折成一个 ring，按行 ring 会把重读压在 run 自己的帧循环上）；没人在看的会话不响，它的读者下次拿尾页。 |
| 04 | 工具行还在跑就该是转圈：服务端说了 run 在跑，别让适配器的猜测盖过去 | 无 | `fromThreadMessageLike` 是 `status: status ?? fallbackStatus`（**消息自己的 status 优先**），而 `fromAgUiMessages` 会给「有工具调用、结果还没回来」的消息安 `requires-action`（parked 的形状）。窗口说 `running` 时那个 status 必须**写在消息上**——`lib/thread-messages.ts` 是新家，`thread-messages` 套件钉它；parked / settled / 没有窗口原样留给适配器。主人第二次报（`bash` 在跑却画成感叹号）。 |

票面见 `issues/01-…md`、`issues/02-…md`、`issues/03-…md`、`issues/04-…md`。

## 非目标

- **不把「逐帧推送半成品」搬到另一条连接**：半成品在记录里，走的是**同一扇窗口门铃**——票 03 补的是让它响。
  一次 tick 一个 ring，也不新建接口、不新建连接。
- **不改记录或 feed 的形状**：编号规则、帧类型、`window-frame` 的字段一个字不动。
- **不改「重复」的去重本身**：一模一样重复仍旧不算第二条目，改的只是「同 id 但内容不同」。

## 验证（落地的样子）

- 客户端 `ui/test/suites/window.tsx`：「同 id、内容变长 ⇒ 原地更新」与「同版本来两遍 ⇒ 原身份返回」两条；
  既有重复用例保持绿。`ui/test/suites/running.tsx`：动作条那条判据（本页的 run **或**服务端的词）。
- 服务端 `test/harness/edge/sessions_test.clj`：标记 vs ring、一次 tick 折成一个 ring、没人看不响，
  以及 `start!` 真把这个 tick 排上钟；`test/harness/edge/http_test.clj`：run 跑着的时候 watcher 被 ring 到，
  而且 ring 的那一瞬记录里**已经有这个 run 的帧**（没有票 03 就一条都没有）。
- 实测脚本 `dev/scratch_refresh_watch.clj`：run 跑着取样，回答的字节数一路涨、ring 数一路涨。
- 客户端 `ui/test/suites/thread-messages.ts`（票 04）：适配器自己的读数是 `requires-action`；窗口说 `running`
  时那条消息的 `status` 是 `running`，**而且断言在消息上**（`fromThreadMessageLike` 的 `status ?? fallback`
  正是当初漏掉的那一半）；parked / settled / 没有窗口时仍是 `requires-action`。
- 真浏览器走查：`node scripts/dev.mjs --scripted walk.json`（脚本带 `pace-ms` 让回答慢慢流，并且第一次调用是
  一次真 `sleep 4`，所以「工具在跑」那一格也被走查覆盖），run 跑到一半整页刷新，断言刷新之后屏幕上的回答文本
  **在变长**、turn 末尾摆的是**「在写」那颗 `●`** 而不是动作条（空位也算错，主人指出来过）、而且**全场只有一颗**
  （正文那颗关掉了，主人第二次报），跑完 `●` 换成动作条（两态都是 24px，不跳）。
- 真浏览器走查（票 04）：`--scripted walk-tool.json`（`bash sleep 30`），刷新落在命令还在跑的时候，工具行是
  **运行中**（不是待审批），settle 之后是**完成**——截图 `evidence/04-after-reload-tool-still-running.png`。
- `cd ui && npm test` / `npm run typecheck` / `npm run build`；`clojure -M:test -m harness.test-runner`。
