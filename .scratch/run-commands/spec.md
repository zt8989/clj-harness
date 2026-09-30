# run 命令（commands）

**状态**：已拆票，未实现。票面在 `issues/`。

## 要解决什么

今天客户端对一场会话做的事分**两条路**，而且都不是「对正在跑的会话说话」：

- `POST /api/agent` 起一场 run——run 已经在跑时再来一次被 `refuse-second-run!` 拒（409）；
- `POST …/cancel` 停它——一个 run 之外的独立路由；
- `/compact`、`/goal` 各自是 run 之外的管理路由（`POST …/compact`，以及 `.scratch/goal` 里原计划的 `POST /api/goal`）。

于是：**想对一场正在跑的会话说点什么**（改主意、插入一句话、让它压缩一下、立一个目标）没有统一的地方，
每来一个都要新造一条路由，而**停**（打断）偏偏是进程里一个 flag + doorbell（`harness.kernel.stop`），
与别的命令没有共同的次序。

这套给一场会话一条**统一的有序队列**：命令挂在 run 请求上进队，run 在边界处按**写死的优先级**取，
**打断最高**。`/queue`、`/steer` 也在这条队列上，这一轮**不做**（票 05 只把它们的形状写下来）。

## 决定

### 1. 命令挂在 run 请求上，进**一场会话的队列**

`POST /api/agent` 的体多一个 `commands` 数组（一个 `{:type …}` 表一条）。`append` 照旧——
它是**模型要读的消息**，命令是**harness 要做的事**，两条字段分开。

命令**不属于某一次请求**：edge 把 `commands` 放进**这场会话的队列**（进程内存，按会话；
与待决审批、作业注册表同族——「这个进程要对这场会话做什么」，重启即失）。
这是「一场会话一条队列」，所以两个连接同时下命令也有先后，先来的先被取；
队列里同一条命令只生效一次（取走即出队）。

**带 `commands` 的请求不再是 409。** 今天 run 在跑时再来一次 `POST /api/agent` 被拒是**对**的——
那会开第二场 run。但「对同一场 run 说话」不是第二场 run：**一个带 `commands` 而没有 `append` 的请求
不拒、答 200**，命令入正在跑的那场 run 的队列，由它在边界处取。
（带 `append` 的请求照旧 409——那才是第二场 run。）

### 2. run 在三个边界取队列，顺序写死

- **每次模型调用前**（pre-LLM 缝，`.scratch/skills-and-instructions` 那一处，`compact-if-pressured!` 也在那儿）
- **每次工具调用前**（执行缝，`harness.kernel.tools/run!`）
- **每次工具结果回来后**（同一缝的 post 相）

**优先级与到达顺序无关，写死一条**：

```
interrupt  >  steer  >  compact  >  goal  >  queue
```

一次取队时把能做的按这个顺序全做掉（`steer` 让位给 `interrupt`，`queue` 让位给一切）。
这张表在**一处的数据**里（一张 `type → {priority, disposition}` 的 map），消费者按它排——
再加一条命令就是在这张表里加一行，不是在新的一处写一个 `cond`。

### 3. `interrupt`：stop 从「一个 flag」升级成「一条命令 + 一条事件」

今天：`POST …/cancel` → `sessions/cancel!` → `ring!`（`harness.kernel.stop` 的 flag + doorbell），
循环在边界问 `rung?`、park 在慢调用上的用 `:wake` 醒。

升级后：客户端把 `{:type "interrupt"}` 放进 `commands`；edge **同时**做两件事——

- **入队**：这条命令因此**有序**（与别的命令有共同先后）、**可寻址**，并且是一次显式的动作；
- **立刻 ring 那场 run 的 stop 开关**：park 在一场慢调用/慢厂商上的 run 必须**当场**醒，
  不能等到下一个边界——那正是按下 stop 的人在看的那一格。**「打断最高」的落点就是这一条**：
  它不只是队列里的第一等，它**不进队列也能起作用**。

`harness.kernel.stop` 那个 flag+doorbell **留着**：它仍是「怎么停」的机制（一条命令可以**唤醒**一个
park 住的循环，一条队列条目不能）。变的是**谁触发**它：从一条路由变成一个命令。
`POST /api/agent` 之外的 `POST …/cancel` **退役**。

### 4. `compact` 与 `goal` 只在 run 里发生

- `POST …/compact` **退役**：`/compact` 是一条 `{:type "compact"}` 命令，落进队列，
  由 run 在**模型调用前**那个边界执行；那里今天已有自动触发（`compact-if-pressured!`），
  手工与自动合成一处、同一个锁、同一批行。
- `POST /api/goal`（原计划）**退役**：`/goal …` 是一条 `{:type "goal", :action …, :objective …}` 命令，
  由 run 执行；`harness.cap.goal` 的动词与栅栏一个字不改（见 `.scratch/goal`）。
  `GET …/goal` **留着**：它是只读存量，不是命令——面板「先拉一次存量」那条纪律要它
  （`docs/rules/panel-data.md`）。

**没有 run 在跑时这两条命令起一个 run**：「只在 run 里发生」要求有个 run，所以
edge 起一场**只有命令、没有提问**的 run（`append` 为空），它取队、执行、收尾。
（同一扇门也是 `.scratch/goal` 的 driver 开下一轮用的——一处起 run，两处受益。）

### 5. 每条命令有一个「没有 run 时怎么办」，写在同一张表里

| type | 没有 run 时 | 这一轮 |
|---|---|---|
| `interrupt` | **拒**（409，今天那句：没有 run 就没有可打断的东西） | 做 |
| `compact` | **起一个 run** | 做 |
| `goal` | **起一个 run** | 做 |
| `steer` | 要求有 run；没有就**拒**（它说的是「正在进行的那一步」） | **后面做** |
| `queue` | **不起 run**，留队列等下一个 run | **后面做** |

**「留队列等下一个 run」正是 `queue` 的语义**，不是通用规则：命令会经历一次 run 收尾之后还在队列里
（比如随一次已结束的请求进来），下一个 run 取它——这是队列的本性；而 `queue` 是**故意**不起 run 的那一条。

### 6. 命令的入口是输入框里的 `/命令`，不是新路由

`/compact`、`/goal …`（这一轮）、`/queue …`、`/steer …`（后面）都是**输入框指令**：composer 在发送前
认出、包成 `commands`、随一次 run 请求发出去（没有 run 时这次请求就起一场只有命令的 run）。
解析仍是一处（每种命令一个模块，形状与技能斜杠同一条），删掉 run 之外那几条路由。
`/goal` 的名字保留（一个叫 `goal` 的技能在斜杠这条路上够不着）。

### 7. 命令是消息还是事件，按它改变什么

- **改变对话的**（`steer`、`queue` 带的文字）：作为一条 **user 消息**进对话、进记录——
  模型真的读到它，这是「消息」那一半。
- **改变 harness 行为的**（`interrupt`、`compact`、`goal`）：是**事件**——
  不产生一条模型消息，它做的事自己已经落进记录（`context/compacted`、`goal/change`，
  而 `interrupt` 的结局是 run 自己的终止帧）。
  命令的**到达**不另写一行：一行「有人下过令」而没有它做的事，是同一件事的第二份。

## 代价（写清楚，不藏）

- **队列在进程内存里**：重启即失。跨重启要保留的东西（一句排队的话）是**客户端的**——它手里有原话，
  下一次请求再带一遍（与 `append` 按 id 去重同一套）。这一条写进票 05 给 `queue` 的形状里。
- **`POST …/cancel` 与 `POST …/compact` 是破坏性退役**：前端 `lib/agent.ts`、`lib/composer.ts`
  与 composer 的停止按钮都要改（本 feature 的一部分）。
- **一个只有命令、没有提问的 run 会走一遍 run 的收尾**（`turn/*`、`model/*` 里那一场**一次模型调用都没有**）——
  读过 `turn/end` 的读者要能接受「零次模型调用的 turn」。这是「只在 run 里发生」的直接代价。
- **`commands` 与 `append` 是两个字段**：一次请求既有提问又有命令时，命令先入队、提问是这场 run 的输入；
  两者的先后是「队列优先级」与「run 输入」两条规则，不是一条。写在这里，免得日后以为是一条。
- **打断能插队，别的不能**：`steer` / `compact` / `goal` 都等到下一个边界；只有 `interrupt` 立即生效。
  这是刻意的——一条队列条目不能唤醒一个 park 住的循环，而「停」必须能。
