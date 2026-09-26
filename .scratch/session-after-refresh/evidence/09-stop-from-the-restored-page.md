# 票 09 的走查：刷新之后也能停

**怎么起的**（`AGENTS.md` 那条铁律：家自己造，不碰 `~/.clj-harness`）：

```
node scripts/dev.mjs --scripted .scratch/session-after-refresh/evidence/09-go.json --ui-port 5319
node .scratch/session-after-refresh/walkthrough.mjs
```

脚本替身四轮（`09-go.json`）：第 1、2、4 轮是 `bash` 跑
`mkdir -p /tmp/clj-harness-stop-09; echo $$ > /tmp/clj-harness-stop-09/$$.pid; sleep 45`
（**命令把自己的 pid 写下来**，所以「进程树没了」是问操作系统的，不是从记录里推的）；第 3 轮是一句回答。
浏览器是 agent 那台 Playwright 里的真 Chromium（`chromium.launch()`），页面走 vite（5319）、后端是
dev.mjs 自己挑的端口。**判据不是钟**：每一步等的是服务端那个 `state`（`GET /api/threads/<stem>/page`）、
命令写下的 pid、或 `GET /api/projects` 那一行的 `running`。

脚本替身的 playwright import 从一条绝对 Windows 路径改成 `npm root -g` 里取（`reasoning-order`
那份的写法）——这条本身是这次走查在 mac 上跑得起来的先决条件。

## 一、修之前

票 04 那次走查（`04-composer-does-not-pretend.md`）留下的是**一条刷新回来的会话没有能停的地方**：
composer 里站的是上游的 `ComposerPrimitive.Cancel`，它只 `abortRun()` 掐断**这一页的 fetch**；
一条刷新回来、这一页根本没在驱动的 run，连那条 fetch 都没有。服务端于是继续跑、记录继续长。

## 二、修之后：ALL GREEN（五次停，一个不剩）

```
ok   a run this page is driving offers the SERVER's Stop, not Send
ok   ...and the command it is waiting on is really running -- [[16432,true]]
ok   pressing Stop ends the run the SERVER was answering -- settled
ok   ...and the command's process tree is gone -- [[16432,false]]
ok   ...and it started no replacement run -- 1 -> 1
     right after the driving stop: ["bash · mkdir …sleep 45Needs approval"]
ok   ...and the stopped call is NOT drawn as one that returned (the client was not told it did)
ok   the reload lands back in the conversation that is still being answered
ok   ...and the server still says it is running (the reload did not stop it) -- running
ok   after the reload the composer does NOT offer Send -- it offers the Stop
ok   ...and it is OUR stop, not upstream's fetch-only Cancel
ok   ...and the turn on screen is still being written (not folded into a finished summary) -- foldedTurns=0
ok   pressing Stop on the RELOADED page ends the run the SERVER was answering -- settled
ok   ...and the command that run was waiting on is gone (its process tree was killed) -- [[16437,false]]
ok   ...and pressing Stop did NOT start a replacement run -- 2 -> 2
ok   a reload after the stop reads the conversation back
ok   the record keeps no open call: the abandoned call carries the cut-off sentence
ok   ...and the reloaded page draws that call as complete, from the cut-off answer in the record
ok   ...and nothing is drawn as an approval card (no error pop-up)
ok   the composer offers Send again once the run is stopped (the gate is not permanent) -- sendOffered=true stopShown=false
ok   ...and sending is answered by a RUN, not a refusal
ok   ...and its answer lands in the SAME conversation -- ["开着停一次。","第一句：慢慢跑。","停完之后这一句。"]
ok   the conversation on screen starts another run
ok   ...and it waits on a command of its own -- [16443]
ok   a new task is its own conversation -- 6514230f-…
ok   the second conversation starts a run of its own -- running
ok   ...with a command of its own -- [16448]
ok   switching back to the first conversation, it still says it is running -- running
ok   stopping the conversation on screen stops IT -- settled
ok   ...and the OTHER conversation's command is untouched -- [[16448,true]]
ok   ...and the other conversation still says it is running -- true
ok   the other conversation is still running when we get to it -- running
ok   ...and it stops on its own press -- [[16448,false]]
```

截图：`09-00-driving-stop.png`、`09-01-running-in-this-page.png`、`09-02-after-the-reload.png`、
`09-03-after-the-stop.png`、`09-04-stopping-one-leaves-the-other.png`、`09-05-both-stopped.png`。

要紧的几格：

1. **两种停是一件东西。** 这一页自己驱动的 run 与刷新回来的 run，按的是同一个按钮、发的是同一个
   `POST /api/threads/<id>/cancel`；区别只在「这一页的流」有没有在收那道 terminal。
2. **停的是服务端那条 run。** 命令的 pid 从 `true` 变 `false`（进程树真没了），服务端 `state` 离开
   `running`。这跟「掐断 fetch」是两件事。
3. **没有顺手起第三条 run。** 每次按停前后 `POST /api/agent` 的条数不变（票面明写的那一条）。
4. **同一场还能接着发。** 停完 composer 回到 Send，发出去 200，答案落在**同一场**对话。
5. **两场同时跑，只停看的那一场。** 另一场的命令 pid 还活着、`/api/projects` 那一行还是
   `running: true`，再去停它才停。
6. **记录完整。** 停完之后刷新，`sofar` 里那次调用有一条 `cut off` 的结果（票 08：记录里不留
   open call），页面上那一轮按记录画出来、没有报错弹窗。

## 三、停掉的那次调用，界面上画成了什么（说清一条没做到）

票面那句「那一轮按中止画（`message-parts.tsx` 的 `cancelled`）」**今天没有做到**，走查量到的是：

- **驱动那一页，按停之后**：那张工具卡画成 `Needs approval`（`requires-action`）。这是
  **票 04 就记下的同一族画法**——一条还没拿到结果的服务端调用，在 runtime 里就是
  `requires-action`，工具行据此画「待审批」；`RUN_CANCELLED` 把**消息**的状态标成
  incomplete/cancelled，却不改**那个工具 part** 的状态。
- **这一次改动让客户端「没有被告知调用已返回」**（走查断言的就是这个：卡片不画 `Done`）。为了这一点，
  loop 里那几条补出来的结果走的是**只进记录、不上线**的路（`harness.kernel.event/cut-off-result`
  → `harness.edge.http/recorder`）：记录必须完整（票 08），而按停的页面不该收到一条「它返回了」。
- **刷新回来**：记录里那条结果在了，于是按记录画成 `Done`、展开能看到那句 `cut off`——这正是票面
  要的「读得回来的是完整的一份记录」。

所以缺口只剩**那一格的词**：「一条被停掉的、没有结果的服务端调用」要画成 `Cancelled` 而不是
`Needs approval`，得改工具行状态的重建（票 04 把它记成与票 06 的卡片同族），**不在票 09 里偷偷做**。

## 四、它没证明什么

- **真人多标签页**：走查用 localStorage 的同一个键切会话（票 03 的机制），没有摆「两个标签页同时开着
  同一场、各自按停」的场面。
- **不可打断的调用**（厂商 HTTP 那条路）：这次停的都是真命令；厂商调用「放弃等待、terminal 按时到」
  由后端用例（票 08 的用例）守。
- **布局**：截图只有几张，「那颗方按钮看起来像停吗」这类问题只有眼睛说得清；走查量的是文本与属性。
