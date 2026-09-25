# 02 — 折法改从模型那一行取推理：一份事实、一处匹配

**做什么**

记录里的推理帧没了，折法要从**模型自己那一行**拿回来：assistant `message` 行（envelope `:source "model"`）
的 `reasoning_content`，折成那条 assistant 消息的推理。

**折出来的形状必须与今天逐字节相同**——仍是 `harness.kernel.frames` 折推理帧折出来的那条 `reasoning`
role 的消息（文本、id、位置都要一样）。这一条守住，下面这些东西就一个字段都不用改：
`harness.edge.ag_ui/inbound`（它把 reasoning 折回 assistant 的 `reasoning_content`，是 DeepSeek 那条
往返要求的）、客户端、`rebuild` / `sofar` / `page` 的载荷。

## 唯一的难处：哪一行配哪一条消息

`message` 行**不带 AG-UI 的 message id**——`.scratch/reasoning-round-trip/spec.md` 决策 7 已经点名这件事。
所以这一票的核心不是折，是**给它们一条匹配规则**。

**规则按本仓已有的那条来：按顺序配。** `model/start` 与 `model/end` 就是这么配的，而且写明了为什么**不**记一个
call id（`harness.kernel.event/model-start` 的原话：「A counter in the record would be the same fact written a
second time, and two copies drift」）。推理这里是同一个形状：一次 run 里「帧折出来的 assistant 消息」与
「`:added` 里的 assistant 消息」各一份、都在记录里、顺序都是模型调用的顺序。

**折里算，不往记录里写。** 写侧（`log-messages!`）手上是这一次 run 的帧与 `:added` 两份表；折侧从记录里读到的
是**同样这两份表**——所以往 envelope 上补一个 `:message-id` 不会让任何一方多知道一件事，只会往只追加的记录里
放一个**推得出来**的字段。这正是 `model/start` 拒绝 call id 的那条理由，本票照它走。

**顺序前提**：模型那一行是 `:run/done`（`log-messages!`）才写的，那一次 run 的帧**已经写完了**——所以折到那一
行时，它配的那条消息一定已经在手上。这条要有用例钉住，因为它是整条链的隐含前提。

**配不上就不配，并且说出来。** 一次模型调用一条 assistant 消息（工具调用再多的那一轮也会开一条空的
TEXT_MESSAGE），所以两份表的 assistant 条目应当逐个对上。**数量不等时不许猜**（一次调用什么都没返回、run
中途断了，都会不等）：不贴、留一行说得出来的记录（审计行或日志），让这件事在案发现场可见，而不是静默配错。

**两种日志都要读得对**：旧日志有推理帧（照旧折）、新日志有行（照新规则贴），**两者并存**，不是二选一。

**Blocked by:** 01（没有 01，两种来源会同时存在，判据就不干净）

**Status:** ready-for-agent

- [ ] **最强的判据**：同一场对话，一份带推理帧的日志、一份不带的（或同一场跑两遍），`entries` 的结果
      **逐字节相等**（含 `seq`、消息 id、推理文本、位置）。这一条绿了，这一票就算成了。
- [ ] 旧日志不回归：既有那几条把推理折出来的用例一个字不改地保持绿。
- [ ] 按顺序配、且**只在折里**配：有一条用例钉住它贴的是**那一轮**的推理（不是上一轮的、不是第一条的），
      而且记录里**没有**多出任何用来配对的字段。
- [ ] 数量不等时**不猜**：有一条用例造成这种不等，断言「没有贴上」且**留了话说**。
- [ ] 顺序前提有用例：模型那一行一定在它配的消息**之后**到达折。
- [ ] 折出来的推理消息与 `harness.kernel.frames` 那条形状一致（同一个构造函数、同一份 shape 用例），
      **不是**在 `replay` 里再拼一份长得像的。
- [ ] 如果落地时发现写侧的一对一其实不可靠（真跑几场就配不上），票面要如实记下并改用备选：**每个推理消息
      在记录里留一行**（`REASONING_MESSAGE_CONTENT` 带整段文本、`messageId` 就在里面）——那条路折法一行都
      不用改，代价是记录里同一个事实仍有两份，也就是主人否掉合批的那条理由。**不许静默换路。**
