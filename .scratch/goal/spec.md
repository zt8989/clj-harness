# 目标（goal）

**状态**：已拆票，未实现。票面在 `issues/`。

## 要解决什么

一个人想让这场会话朝一个方向走，模型聊到一半却跑偏了；更麻烦的是**长任务**——他不得不一直坐在
前面喊「接着做」。仓里今天没有地方存这句话：

- `todo_write` 是**模型自己**拆的步骤（模型写、模型改，横条只看不写）；
- `AGENTS.md` 是每场会话都成立的规矩，不随这次要做什么变；
- 输入框里那句话是一次性的，说出去就沉进对话里。

**目标**补这一格：一个**跨多轮/多步的完成目标**，一句话、会话级、人立人撤，**而且它自己会往前走**——
每一轮模型都看得到它，一轮结束、目标还活着、上一轮真有进展，就自动开下一轮，直到完成 / 卡住 / 撞上限 / 人喊停。

目标是「去哪儿」，任务清单是「这一步怎么走」，两者不互相覆盖，各自有家。

## 参考实现：dsh 的三件套

DeepSeek Harness 的 goal 不是一条提醒，是一个机制（`@deepseek-ai/dsh-goal`、`dsh-tool-goal`、
`dsh-goal-round-driver`），我们**照搬它的语义**：

| 部件 | dsh 的样子 |
|---|---|
| 工具 | `get_goal()` / `create_goal(objective, max_goal_rounds?)` / `update_goal(goal_id, revision, action, objective?, max_goal_rounds?, blocked_reason?)`，`action` ∈ `edit`/`pause`/`resume`/`complete`/`block` |
| 栅栏 | 每次改带 `GoalRef{id, revision}`，compare-and-set；模型必须先 `get_goal` 抄下准确的 ref |
| 相位 | `edit` / `pause` / `resume` / `complete` / `block` / `clear`；`block` 是**统一的阻塞相位**（kebab-code + 说明） |
| 上限 | `maxGoalRounds`（dsh 默认 256），一轮一轮地数 |
| 续跑许可 | `armed` / `disarmed`，**不是 revision**：会话 resume/fork 后自动 disarmed，要人再说「继续」才 rearm |
| 人的命令 | `/goal`（看）/ `/goal <目标>`（建，未完成的目标不被顶掉）/ `/goal edit <目标>`（只改文字）/ `/goal pause` / `/goal resume` |
| 续轮驱动 | `goal-round-driver`：active+armed 的目标 → 连续 goal round，直到 complete/block/撞上限 |

两条 dsh 定死的规矩我们照抄：**人暂停的目标，只有人能恢复**（模型的 `resume` 只对
「active 但 disarmed」有效）；**模型的 `block` 要同一个 blocker 连续若干轮才成立**。

本仓要适配的只有一处：dsh 的 goal 住在**会话日志**里，本仓正好有 ADR 0008「记录是真相、sqlite 是其投影」，
所以照搬——`goal/change` 事件进 jsonl，`goals` 行是投影。

## 决定

### 1. 目标是记录 + 投影，不是一行状态

`goal/change` 事件行进会话的 jsonl（append-only，**带完整后态快照**；`clear` 是一条带 revision 的墓碑行），
`harness.edge.replay` 的 fold 把它折成「现在的目标」，`goals` 表那一行是这个 fold 的**投影**——
照 `sessions.numbers` 那条既有形状：挂载时一次 SELECT，**fold 是修复路径**。

**为什么与 `todos` 分家。** `todos` 是纯状态，因为它整体替换、没有历史价值。目标不一样：
**两只手并发写**（人在 run 之外按，模型在 run 里写），需要一个可比较的序号来挡陈旧写；
**要回答「改过几次、为什么 block、跑了几轮」**，那些是记录才答得出的问题；dsh 自己也这么做。
改一次就追加一行、外加一行投影，代价是两处要一致——一致性由 fold 兜底（crash 之间的窗口，重建时折回来）。

**`goals` 那一行按 `sessions.numbers` 那条既有形状申报**：它是「一条 fold 物化进一行」——写整份、就地改、
无历史、fold 是修复路径——所以进的是 `declared-state-columns`（带这条论证），**不是**
`declared-projected-columns`：那条 allowance 是给「镜像日志内容」的（`messages`/`tool_calls`），
而这是一条派生摘要。同时配一条「折记录 = 那一行」的验收（`sessions.numbers` 那条同款）。

### 2. `{id, revision}` 是栅栏，不是装饰

每次写都带一个 `GoalRef{:id .. :revision ..}`。`id` 在 create 时铸一次（clear 后新建就是新 id）；
`revision` 是快照里的单调计数（create 是 1，每次改 +1），**fold 能把它重建出来**。
ref 与当下不符就按名字拒绝（`:goal-moved`），句子让人/模型先 `get_goal` 再抄一遍。

**没有这道栅栏会怎样**：人的 `POST /api/goal` 与模型在 run 里的写**不串行**（本仓只把 run 之间串起来），
于是模型一次基于旧认知的 `complete` 能悄悄盖掉人刚按的 `pause`。栅栏把这种写变成一次响亮的拒绝，
而不是一次静默覆盖——这正是 dsh「paused 只有人能 resume」立得住的原因。

### 3. 相位与字段

```
{:id          "g-8f3a…"          ; create 时铸一次
 :revision    3                   ; 每次改 +1，快照里带着
 :objective   "把登录模块重构完，补齐测试和迁移说明"
 :phase       "active" | "paused" | "blocked" | "completed"
 :rounds      7                   ; 已经跑过的 goal round 数
 :max-rounds  25                  ; 上限（见决定 9）
 :blocked     {:code "no-progress" :reason "…"}   ; 只有 phase=blocked 时
 :updated-at  1696…}
```

`phase` 是**数据不是 cond**（照 `todos/statuses`）：每个拒绝都说出合法集合。
`clear` 不产生 readonly 相位，它就是墓碑行——**没有变更过的会话与 clear 过的会话，对读者是同一个 `nil`**。

**`armed` 不进记录、不是 revision，它是进程内存**（`harness.cap.goal` 里一张按会话的表，
与待决审批、作业注册表同族）：它是「这个进程还允许替你把下一轮开起来」，
**重启即失**、会话 resume/fork 后自动失——正是 dsh 那条，也正是「人没说话，就不要再烧钱」那道闸。

### 4. 三个工具、一条命令、一个只读——同一套动词

`harness.cap.goal` 是**唯一**的规则处，三处都调它，拒绝不写第二份：

- 模型：`get_goal` / `create_goal(objective, max_goal_rounds?)` / `update_goal(goal_id, revision, action, …)`；
- 人（输入框）：`/goal …` 是一条 `{:type "goal", :action …}` **命令**，走 `.scratch/run-commands` 那条
  会话队列、**在 run 里执行**（没有 run 时这条命令起一个，见那份 spec 决定 4）；
- 人（面板存量）：`GET …/goal` 只读快照——**不是**命令，面板「先拉一次存量」那条纪律要它。

**`POST /api/goal` 不存在**：写入一律经命令，只在 run 里发生（同一份 spec 决定 4）。
**`show` 是读**：`GET` 与 `/goal` 单打都走它，答一份快照。
**`show` 是读**：`GET` 与 `/goal` 单打都走它，答一份快照。

### 5. 模型可以从人的直接请求立目标，但顶不掉未完成的目标

dsh 的规则照抄：`create_goal` **可以从人的直接请求推断出目标**（任何语言），
但**一次性的小活儿不要建**（描述里写死）；**已有一个未完成的目标时拒绝**，
告诉它先 `update_goal complete` 或等人 `clear`。

这条修正了我上一版的「人立、模型只汇报」——dsh 是两只手都能立，靠的是**栅栏 + 相位**兜底，
而不是靠一方不给写。人仍然随时能 `pause` / `clear`。

### 6. 暂停只有人能解除；模型的 resume 只 re-arm

- `update_goal(pause)`：模型可以暂停（它发现自己卡住、要走别的路）。
- `update_goal(resume)`：**只对 `active` 但 `disarmed` 的目标有效**（会话 resume/fork 之后）。
- **`paused` 的目标，模型的任何动作都不能让它回 `active`**——只有人的 `POST`/`/goal resume` 能。
  按名字拒绝（`:paused-by-human`），句子说清「人暂停的目标等人恢复」。

### 7. `block` 要同一个 blocker 连续若干轮

模型可以在 `update_goal` 里报 `blocked_reason`（kebab-code + 说明）。它**不是**一次调用就生效：
同一个 `code` 必须连续出现 **`block-rounds`（默认 3，一个 knob）** 轮才写进 `phase: blocked`；
在此之前那条 reason 作为**挂起的阻塞**留在快照里（`:pending-block`），相位不动。
理由就是 dsh 那条：一个模型因为一次报错就宣布目标阻塞，是把「这一轮不顺」写成「这件事做不成」。

`blocked` 之后：driver 停（决定 9），提醒照旧注入（要告诉模型「卡在哪」），人的 `resume` 清掉 blocker、
`revision +1`、相位回 `active`。

### 8. 每轮提醒：派生注入、按内容幂等、只在 active 时注入

模型每一轮读到的是 pre-LLM 缝（`harness.cap.project/before-llm`）追加的一条 `role=user` 消息，
与技能正文、作业结局同一个缝、同一种形状（`.scratch/skills-and-instructions`）：

```
<goal revision="3">
把登录模块重构完，补齐测试和迁移说明
round 7/25
朝着这个目标推进。用 `get_goal` 看清现状、`update_goal` 报进展或标完成；做不下去就说明卡在哪。
</goal>
```

- **只在 `active` 时注入**：`paused`（别再推我）、`blocked`（已经说了卡在哪）、`completed` 都不注入。
- **按内容幂等**：历史里已有一条与当前提醒**逐字相同**的 `<goal>` 块就不再追加；目标一改、
  轮数一动，就在末尾追加一条新的（旧的留着——模型确实读过那一版）。
- **派生、不落盘**：每次模型调用现算；一次压缩把它折掉，下一次调用带回来。
- 注入在 `:run/start` 作为一张注入卡发给客户端（`injected-frame`，id 前缀 `-pre<i>`），客户端**不回发**。

**兑现代价**：按内容幂等意味着一条提醒**写在它的目标版本出现处**，长会话里会退到中段。
**driver 那一半正是为此存在的**：每一轮由 driver 追加一条**新的** round 开场（决定 9），
把目标重新带到队尾；人自己驱动、没有 driver 时，靠目标条与控制命令把目标改一下也就刷新了。

### 9. `goal-round-driver`：一轮收尾后自动开下一轮

**触发**：一场 run 以正常终局收尾（`:run/done`，不是 interrupt、不是 error）时，看这个会话的目标：
`active` **且 armed** **且 `rounds < max-rounds`** **且上一轮有进展** → 追加一条 round 开场、开下一轮；
任何一个条件不成立就什么都不做。

- **round 开场**是一条**真的 user 消息**（进对话、进记录），内容由 `harness.cap.goal` 一处产出：
  带 objective、round 序号、上限，以及那句「做到就 complete，做不下去就说卡在哪」。
  它不进 `prompt.md`，不碰 system 前缀。
- **有进展** = 刚刚这一轮的记录里至少有一次**通过的、改文件的工具调用**（`write`/`edit` 那一家）。
  没有一个，就是**零进展**：driver **不开下一轮**，把目标置 `blocked`（`code: no-progress`）并停下，
  在目标条上告诉人。这不是保守，是 dsh 那次 642M token 事故的直接教训——
  「模型原地打转还一直续」是这套机制唯一的真危险。
- **上限**：`rounds` 每开一轮 +1（写进记录，重启后还在）。默认 **25**（`harness.edn` 的
  `:goal {:max-rounds 25}`），不是 dsh 的 256——上限是**保险丝不是目标值**，
  256 轮配上一次坏压缩就能烧掉一场会话（dsh issue #7894）。
- **人随时能停**：每一轮开始前重读一次相位与 armed，`pause`/`clear` 立刻生效；进程重启后 armed 是空的，
  所以**重启不会自己接着烧钱**——要人再说一句（发一条消息，或 `/goal resume`）。
- **多开一轮的机制**照 `harness.edge.http/run-subagent!`：本仓已经有「服务端主动开一场 run、
  自己的帧汇、经 mux 广播给看客」的先例，driver 不再造第二套。

### 10. `/goal` 是输入框指令，不是一条消息

```
/goal                 看（快照：相位、轮数/上限、阻塞原因、可用的后续命令）
/goal <目标>          建（未完成的目标不被顶掉，要人先 clear）
/goal edit <目标>     只改文字，**不动相位、不动激活**（dsh 那条，修正上一版）
/goal pause|resume    暂停 / 恢复
/goal clear           清（墓碑）
```

解析只有一个模块（`ui/src/lib/goal-command.ts`），composer 在**发送前**认出来、包成 `commands` 里的
一条命令（`.scratch/run-commands`），**不发一条提问**：没有 run 在跑时这次请求起一场只有命令的 run。
形态与技能斜杠同一形状。`goal` 这个名字被保留（一个叫 `goal` 的技能不能用 `/goal` 触发，代价写下来）。

### 11. 目标条在输入框之上、任务横条之上

画**相位、objective、round n/max、blocked 原因**，按相位给按钮（暂停/恢复/编辑/清除），
没有目标就什么都不画。存量 + 推送（`goal` 帧）+ 两个 fact 补一次 + 重连补一次，照 `docs/rules/panel-data.md`。

### 12. `prompt.md` 一个字不动

目标不进 system 消息：它会中途变，冻进前缀就是一句会过期的话，而且每改一次换来一次冷前缀。
它走注入那一半（决定 8）。

## 代价（写清楚，不藏）

- **依赖 `.scratch/run-commands` 那条通道**：`/goal …` 是它的一条命令、在 run 里执行；
  那套没落地之前，人那半边（建/改/暂停/恢复/清）没有入口——模型那三个工具不依赖它。
  面板的**存量**（`GET …/goal`）与 `goal` 推送帧也不依赖它。
- **记录 + 投影两处**：crash 之间会不一致，靠 fold 重建兜底（与 `sessions.numbers` 同一条）。
- **driver 是这套里最重、最险的一块**：它让服务端主动开 run、烧的是按量计费的钱。
  上限默认小、零进展即停、进程重启即 disarmed、人随时能停——四条刹车一条都不能省。
- **多一族帧名 + 多一张表 + 多处文档**：`goal` 帧要进 `ui/src/lib/mux.ts` 的 `familyOf` 与那条读 wire 的测试；
  `goals` 要在 `db_test` 的 `declared-state-columns` 里申报（物化 fold 那条论证）；`CONTEXT.md` /
  `panel-data.md` / `overview.md` 三处文档要跟着。
- **零进展的判据只认改文件的工具调用**：靠 `bash`（`git apply`、构建产物）改的东西**不算进展**，
  一轮纯 bash 的推进会被判成零进展、把目标置 blocked。这是选便宜判据的代价；人 `resume` 一句就接着走。
- **`/goal` 保留名**：一个叫 `goal` 的技能在斜杠这条路上够不着。
- **`block` 的三轮规则**：模型第一次报到 blocker 不会立刻停，相位要第三轮才变——
  拿「晚一点停」换「不因一次报错停」。
- **会话 resume/fork 后 disarmed**：人回到一个跑过一半的会话，目标还是 active，但不会自己续——
  要他说一句。这是特性不是缺陷（别让「打开页面」等于「继续烧钱」）。
