# 票 04 的走查：那一场还在跑的时候，输入框不装作能发

**怎么起的**（`AGENTS.md` 的那条铁律：家自己造，不碰 `~/.clj-harness`）：

```
node scripts/dev.mjs --scripted .scratch/session-after-refresh/evidence/04-go.json --ui-port 5319
node .scratch/session-after-refresh/walkthrough.mjs
```

脚本替身三轮：**第一轮调 `bash` 跑 `sleep 45 && echo slept`**（好让刷新撞上一条真的在跑的 run），
第二轮与第三轮是回答。浏览器是 agent 那台 Playwright 里的真 Chromium（`chromium.launch()`），
页面走 vite（5319）、后端是 dev.mjs 自己挑的端口。

**判据不是钟，是服务端自己那句话。** 走查在刷新之前**等** `GET /api/threads/<stem>/page` 的 `state`
变成 `running`——这是这一步最花时间也最值钱的东西。第一版走查不等它，结果是：run 边写下 `input` 之后
要花**约两秒**装配系统提示词（`hook/SystemPrompt`）才登记 run，而「刷新 → 打一句 → 按发送」能整段落进
这两秒里，于是门答「没有在跑」、**第二条 run 真的起来了**（记录里两条 `input`、两个 runId 交错）。
那条不是本票的 bug，但它说明「判定在跑」只能问服务端，不能问时间。脚本的头注释把这件事写下来了。

## 一、修之前：按钮亮着，按下去是 409（2 RED）

`bash` 睡着的那 45 秒里按 F5（同一场会话回来了，服务端仍说 `running`），落地瞬间 DOM 说：

```
sendPresent: true,  sendOffered: true,   ← composer 装作能发
cancelShown: false,
runningSpinners: 0,                      ← 侧边栏那一行没在转（列表快照拍在 run 登记之前）
notice: null,                            ← 没有任何一句话
assistants: ["bash · sleep 45 && echo slept待审批"],
bubbles: ["第一句：慢慢跑。"],
foldedTurns: 0                           ← 那一轮确实画成「没完」
```

——**「没完」是画对了的（票 03），装作能发是假的**。把这一页刚发出去的那条请求原样再打一次
（走查用 raw `fetch` 重放，免得把被拒的消息塞进这一页的 runtime）：

```
POST /api/agent -> 409
this session already has a run in this process, so this run was not started: the harness
answers one run per session at a time (threadId "…", the run going is "…"). Wait for the
current run's terminal frame, or for this session's row to stop saying it is running, and
send again.
```

这就是主人报的那两半：**「展示不再运行」**（按钮、侧边栏那一行、没有一句话）+ **「点击发送提示这个
任务正在运行中」**。截图 `04-03-what-the-server-answers.png` 是修之前那一刻留下的（修之后走查不再走
那一条分支，所以这张图是复现的证据，不是修后的状态）。

## 二、修之后：门关着、话说出来（ALL GREEN）

同一段走查，同一个替身，同一台浏览器：

```
ok   the run registers and the SERVER says this conversation is running -- running
ok   ...and this page is driving it (Cancel is up)
     while running: sidebar listing says running=true
ok   the reload lands back in the conversation that is still being answered
ok   ...and the server still says it is running (the reload did not stop it) -- running
     after the reload: {"sendPresent":true,"sendOffered":false,"cancelShown":false,
                        "approvalShown":false,"runningSpinners":1,
                        "notice":"这一场还在跑；等它结束再发。",
                        "assistants":["bash · sleep 45 && echo slept待审批"],
                        "bubbles":["第一句：慢慢跑。"],"foldedTurns":0}
                        sidebar running=true
ok   after the reload the composer does NOT offer Send: the server is still answering
ok   ...and the page SAYS so, in a sentence of its own
ok   ...and the turn on screen is still being written (not folded into a finished summary)
ok   the run settles on its own -- settled
ok   ...and its answer is on screen -- ["bash · sleep 45 && echo slept完成","第一轮的回答：睡完了。"]
ok   once it settles the composer offers Send again (the gate is not permanent) -- sendOffered=true
ok   and sending is answered by a RUN, not a refusal -- 200
ok   ...and its answer lands in the same conversation
ok   ...as the third question of it
```

四条要紧的：

1. **按钮不再亮**（`sendOffered: false`）——而输入框照旧能打字（走查先打字再看那颗位，所以
   `isEmpty` 不是把它关掉的原因）。
2. **话说出来了**（`notice`），而且是**当时那门语言的**：这台机器的浏览器是中文，所以走查里看到的是
   中文那一句；英文那一句由 `ui/test/suites/running.tsx` 渲染出来读回去。
3. **侧边栏那一行也亮了**（`runningSpinners: 0 → 1`）。这是**顺手修掉**的同一个洞的另一半：列表是
   **快照**，刷新那一下它可能拍在 run 登记之前（上面量到的 0 就是），而这一页手里那个窗口的 `state`
   是活的。`statusOf` 把两个读数并起来上报给页面，那一行据此点灯——两者都不比对方全，所以是**并**。
4. **跑完之后门自己开**：`settled` 之后 `sendOffered: true`、那句话消失、发出去是 **200** 且答案落进
   **同一场**对话。一个只关不开的门是这一票最容易做坏的地方，所以它是**一条验收**而不是一句注释。

## 三、它没证明什么

- **悬置那一半没有验**（票面要求的第二条）。原因写在 spec 的落地一节：悬置的卡片刷新回来还回不来
  （票 06），门为它关上就是一扇出不去的门，所以 `parked` 这一格**故意**仍取本页自己的读数——
  `ui/test/suites/running.tsx` 把这个决定钉住了（`parked` 的服务端读数**不**算「在跑」）。
  票 06 落地之后，那一条要与它共用一次走查。
- **布局**：截图只有四张，「那句话挨着输入框的间距对不对」「关着的按钮看起来像关着的吗」这类问题只有
  眼睛说得清；走查量的是文本与属性上的事实。
- **真人多标签页**：同一个会话在两个标签页里同时开着、一个在跑一个在看，走查没有摆这个场面。
