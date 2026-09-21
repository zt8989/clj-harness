# 走查证据：开场块在会话出生那一刻就该看得见

跑法（真后端 + 真前端 + 隔离家，端口由脚本自己挑）：

```bash
node scripts/dev.mjs --scripted .scratch/session-opening/evidence/go.json --ui-port 5221
# 另开一个终端：
node .scratch/session-opening/walkthrough.mjs http://localhost:5221/
```

`go.json` 只有两轮。`walkthrough.mjs` 是那个人视角，它走的是**真实入口**：先 `POST /api/projects`
建一个临时项目（里面放一份 `AGENTS.md` 和 `.agents/skills/alpha/SKILL.md`——脚本化的那个家是空的，
技能根只能从项目来），刷新让侧栏列出它，然后点**那个项目行自己的 `+`** 造一场会话。这个动作是
`sidebar.tsx` 说的「the session belongs to THAT project the moment it exists」：**第一轮 run 就是出生那一轮**，
开场块就是它写进对话的。这正是复现那份「AGENTS.md 和 skills 都注入了、界面不显示」的入口。

## 走查当天看见的（2026-09-21）

- **出生那一轮**：两张卡在屏幕上——`Injected context · instructions 202 B`、`Injected context · skills 508 B`，
  点开是整份 `<instructions path="…/AGENTS.md">…ALPHA-STANDING-RULE…` 与整份 `<skills>…- alpha: …`。
  那一刻的步骤**没有折着**（脚本量了 `turn-steps-trigger`：零个），所以这就是人一眼看到的东西。
- **它们在哪儿**：`t01-birth-as-it-lands.png` 里两张卡画在**助手那一栏**（助手文本之前），人的提问还是
  唯一的气泡。原因是适配器：`RunAggregator` 的 `CUSTOM` 分支把这个 part 推进**正在流的那条 assistant 消息**
  且不看 `messageId`（`run-aggregator.js:215`）。**这是票 01 的代价，也是它换来的东西**：在那之前这一页
  一张卡也没有。
- **第二句**：卡还是两张（`injection-trigger` 计数），气泡两个——开场**没有再写一遍**。
- **刷新之后**（`t03-cards-back-after-a-reload.png`）：两张卡落到**人的那一栏**——提问底下、答案上面，
  与 `t01` 同一张卡、不同位置。顺序是 `aui_user-message-root, aui_user-injection-root ×2, aui_user-message-root`：
  提问在前、开场紧跟其后，正是票 03 要的顺序；而且**开场没有再被画成那个人的气泡**——这是票 02 的修正
  （修正前 `UserMessage` 会给它套上右对齐的灰气泡与 Edit 铅笔）。

截图（`evidence/`）：

| 文件 | 看什么 |
|---|---|
| `t01-birth-as-it-lands.png` | 出生那一轮刚落地的样子：两张卡在助手那一栏 |
| `t01-birth-cards.png` | 同上（展开动作是脚本对折叠的回合做的，这一轮没有折着的） |
| `t02-cards-open-and-question-above.png` | 点开卡之后：两份字节都在 |
| `t03-cards-back-after-a-reload.png` | 刷新之后：卡在人的那一栏，提问在前、答案在后 |

## 走查留下的未决问题（票 05）

**同一张卡，出生那一轮画在助手那一栏，刷新之后画在人的那一栏。** 这不是渲染的漂移，是两条不同的路：

- 出生那一轮只有**帧**可走（这一页既没有窗口也不跟 feed），而帧只能落进 assistant 消息；
- 窗口（feed / `sofar` / 重建）里的那一条是**user 消息**，所以落在人的那一栏。

`useWindowFeed` 的 `importWindow` 已经有把窗口整段 import 进运行时的能力，只是被
`if (isOwnRun()) return` 挡住——「自己驱动的那一轮，活流才是真相」。要不要在**自己的 run 结束之后**
读一次窗口把它落位（票 05 列的三个选项），是产品决定，不是这一票能替它答的。
