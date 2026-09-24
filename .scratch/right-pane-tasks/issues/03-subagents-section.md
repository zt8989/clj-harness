# 03 — 子代理那一段：看得到谁在跑，点开是那面镜子

**What to build:** 任务视图的「子代理」段列出**本会话**委派出去的每一个——名字、它的说明、状态、
什么时候开的，最新在前。点一行 → 右栏换成那一面镜像（`.scratch/subagent-view` 那个，一个字不改）；
镜像头部多一颗「返回列表」，回得到任务列表。

**Blocked by:** 01

**Status:** ready-for-agent

## 形状

- **不加端点。** `GET /api/subagents` 的 `runs` 就是「这个家委派过谁、还跑不跑」：
  `{:threadId .. :parent .. :subagent .. :project .. :delegatedAt .. :running}`。本段取
  `:parent` = 本会话 id 的那几行；名字的说明取同一份答案的 `definitions`（按名字 join）。
- 一行三样事实：**名字**、**说明**（`SubagentDefinition.description`，一行、过长截断）、**状态**
  （`运行中` / `已结束`，两个词，不许造第三档——服务端只有一个布尔 `:running`）。
  第二行还可以有「什么时候开的」（`:delegatedAt`，`null` 就别画）。
- **一个名字已经不在定义里**（子代理被删了）→ 只画名字与状态，不画说明。这是既定事实，
  `cap.subagents/runs` 的 docstring 就是为它写的（「a record whose definition was since deleted」），
  不是要兜住的错。
- **点一行 = 点那张 `agent` 卡**：同一个 `setRightPane({:kind "mirror" :threadId .. :subagent ..})`，
  `threadId` 取那一行自己的（**不是**猜的、也不是位置配的——`toolCallId` 那条规矩的同一条理由）。
- **镜像头部那颗 X 让给「返回」**：返回回到任务列表（前缘那颗收起仍然关整栏，01 已经把那个动词收走）。

## 决策

- **列表按「这个家委派过谁」而不是「这一会话转录里有哪几张 `agent` 卡」**：卡在对话里会被压掉、
  会被窗口截掉，而这一栏要回答的是「有什么在跑、跑过什么」。两条读法若不一致，这一栏就是第二份真相。
  代价：列表里可能有一行在转录里找不到对应的卡（老记录被清）——那行照样列，它就是记录。
- **刷新页面照实说**：`running` 是服务端进程内那张表，重启之后一律 false（`cap.subagents/running`
  的 docstring 已经立过这条），所以刷新之后一行写着「已结束」而它其实在别的进程里跑——不撒谎，
  这也是 `.scratch/subagent-view` 认下的同一条老实话。
- **轮询与后台任务同一个 tick**（02 那条），两条读一起问；栏关掉就停。

## 验收

- [ ] 用例：本段只有本会话（`:parent` 是它的）的行；另一个会话的委派不出现
- [ ] 用例：一个名字已不在定义里的 run 仍然列得出来（有名字、有状态、没有说明）
- [ ] 用例：`running` true / false 两个词各画各的；`:delegatedAt` 是 `null` 时不画那一句
- [ ] 用例：点一行开的是那一行自己的 `threadId`（两个子代理并发委派时点谁开谁——这条就是
      「不许按位置配」的证据）
- [ ] 用例：镜像头部那颗「返回」回到任务列表；关整栏仍是前缘那颗收起（X 与它不再并存）
- [ ] 走查证据：一条委派跑着时那行是「运行中」；跑完之后仍在列表里、写着已结束；刷新页面之后照实
- [ ] 中英两套文案都在；`ui/test/suites/subagent-view.tsx` 既有用例照旧绿；`npm test` +
      `npm run typecheck` 绿
