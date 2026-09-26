# spec: 会话的开场只发生一次 —— AGENTS.md / 技能清单跟 system prompt 一起出生

**一句话**：开场块（指令文件 + 技能清单）今天**每一轮都重新读、重新拼在对话末尾**（
`edge/http.clj` 的 `opening-blocks!` + `edge/ag_ui.clj` 的 `tail-blocks`）。本特征把它改成
**会话开场时进一次对话**：跟着 system prompt 的诞生一起写进会话（`role=user`，和现在同一形态），
此后作为历史的一部分被每轮继续，**ordinary run 不再追加任何东西**。压缩（尚未实现）是第二个也是
唯一的另一个时点：那时 system prompt 与开场一起重建。

2026-09-21 立。两张票：`01`（开场只进一次）、`02`（重放的答案落在它回答的那条消息后面）。

## 问题

**1. 每轮追加把「开场」变成了一件每轮都在发生的事。** 现场是一条真会话（`2754772f…`）的 resume 轮
（run `2375c98b`）交给 kernel 的 30 条消息，尾部是：

```
[26] assistant[tool_calls: call_00_ET_cGSmMaGvZxslMhi8R3TN8683]   ← 停着的、等批准的那条
[27] user <instructions path="…/AGENTS.md">
[28] user <skills>
[29] user <skill name="to-tickets">
```

开场块（27、28）是**这一轮**才读出来的，位置在**停住的那条 assistant 消息之后**。
`harness.kernel.llm/unanswered-tool-calls` 判的是**相邻**：那条 assistant 后面跟的是 user 消息而不是
它的工具结果，于是「刚批准跑完的那次调用」仍被算作无人回答，进 `still` 而不是 `dead` —— 这一轮
**再一次停在同一个 interrupt 上**（用真函数重放：`still => ["call_00_ET_cG…"]`）。

**2. 代价是白付的。** 开场块带 id 都不需要——它们今天**根本不进会话**，每一轮现读现拼，于是
prompt cache 的稳定前缀只能"从 system 一路穿过这场对话"，而开场块每轮落在它之外。把开场放进会话
开头，前缀变成 `system + 开场 + 对话`，开场不动就整段命中。

**3. 这条决定被两处旧决定挡着，本票把它翻过来。**（这是本 spec 必须先说清的部分）

- `.scratch/skills-and-instructions/spec.md` + `docs/architecture/skills-and-instructions.md`
  （2026-09-18 那次挪动）：写的是「注入物排在提问**之后**」「注入物**永远在稳定前缀之外**」，
  理由是「注入物每轮都可能变——加载一个技能就变——一变就把它后面整段对话一起作废」。
  **本票拆开这一族**：会变的（技能正文、后台作业的结尾）继续留在尾部、前缀之外；
  不随轮次变的（AGENTS.md、技能清单）跟着开场进前缀。那页要看的是"两类注入、两条规矩"，不是一条。
- `src/harness/edge/sessions.clj` 头部注释：写的是「指令文件每轮现读，好让改过的 AGENTS.md
  **不用重启就生效**。把它们冻在这里，正是这个设计拒绝的『改了没生效』」。**本票接受这个后果**：
  开场块在会话中途不再重读，改动要等下一次开场（新会话，或将来的压缩重建）。
  理由不是"重读没价值"，而是**开场块现在与 system prompt 同生命周期**：它属于这场会话的开场，
  变化应该在开场那一刻被采纳。**代价必须写在文档里，不能只活在这张票上。**

## 决策

1. **形态一字不动：`role=user` 的一条条消息，`<instructions path="…">` / `<skills>` 包裹。**
   `prompt.md`（+ hook 追加的文本）仍是唯一的 system 消息；开场块不进 system，也不是 system。
2. **进会话的时机 = 会话的开场，一次。** 和出生 context（`ag_ui/context-entry`，id 固定为
   `session-context`）走**同一条路**：`sessions/append!` 按 `:id` 去重，所以"出生两次"
   （第一次 action 的重试、id 被创建后又被问一次）只会有一份开场。
3. **id 固定、由边铸。** `session-opening-0`, `session-opening-1`, …（顺序是
   `cap.preamble/messages` 的决定，边只编号）。id 是**身份**：它让开场可去重、可在重建后同名回来。
4. **一条消息，两种读法。** 开场条目同时带两个 part：`data`（`injected-context`，页面画成卡片，
   与今天完全一样）与 `text`（模型读的那段）。`sessions/without-cards` 本来就只摘 `data` part、
   保留其余——**模型侧一行代码都不用改**，人看的那面也一行不用改（`ui/src` 不动）。
   这条照抄仓里已有的写法：「One conversation, two readings of it」。
5. **ordinary run 什么都不追加。** `opening-blocks!` 只在 `born?` 那一支被调用；run 的组装里
   `ag/inbound` 不再收 `blocks`（`tail-blocks` 整个删掉）。由此，这一轮的提交列表与
   `sessions/messages` 的差距只剩 `project/before-llm` 那一族**会变**的注入。
6. **卡片帧只此一次，不再每轮重发。** run 开始时的 `injected-context` 帧今天由
   `(into (vec blocks) injected)` 发出；改成只剩 `injected`。开场块的卡片**随条目进记录**
   （`input` 行的 `:added`），重建时由折叠带回来，不需要帧。
7. **不做的**：会话中途重读指令文件（决策见「问题 3」）；一个 `reload` action；把 system prompt
   本身存进会话。压缩落地时它会**重建开场**（见下）。

## 压缩：第二个、也是唯一另一个时点（尚未实现）

压缩今天只有 hook 点（`:pre-compact` / `:post-compact`，`kernel/hooks.clj:137-139`），没有实现。
本票把它的约束记在这里、也记进 `docs/architecture/skills-and-instructions.md`：

- 压缩会把开场**连同被压缩的那些消息**一起从对话里去掉，重建后的会话必须**再写一次开场**
  （同一条路：`opening-entries` + `append!` 的 id 去重；若旧开场还在，去重会让它自然不重复）。
- 重建的 system prompt 与重建的开场必须是**同一次**的事：模型读到的开场与 prompt 里说的东西
  不能来自两个时刻。

## 判据

- 一场有 AGENTS.md 的会话：第一轮 **提问在开场块之前**（2026-09-21 修正，见文末），
  且开场只在**那一轮**的 `:added` 里；第二轮提交侧**没有**新的开场块，历史里那一份仍在原位。
- 同一条会话折叠回来（重建）后，开场条目**同名同序**，`display` 画出卡片、`messages` 里是文本。
- 一场有 AGENTS.md 的会话在批准后 resume，**能跑到 provider**（不再第二次停在同一 interrupt 上）。

## 实现（2026-09-21，分支 `opening-at-birth`）

**落地形状**——与上面决策只差一条：第六条的「卡片帧只此一次」实际做成「开场永不发帧」，理由在下面第 2 点。

1. **开场在出生那一刻写进会话**（`edge/http.clj` 的 `run-agent!`）：`born?` 那一支读一次
   `opening-blocks!`，`ag/opening-entries` 编成 `session-opening-0,1,…`，和这一轮的 `:append`
   一起进 `sessions/append!`，再写进 `input` 行的 `:added`。此后每一轮它只是历史的一部分。
2. **开场永不发 `CUSTOM` 帧。** 帧的 id 是 `<run-id>-ctx<i>`、条目 id 是 `session-opening-<i>`——
   两者不同名，`keepInjectionCards` 按 id 配对，发帧就会把同一张卡画两遍。卡片随条目走：
   feed / `sofar` 把条目交给页面，重建由 `input` 行的 `:added` 折回来。`CUSTOM` 帧从此只发
   **这一轮自己派生**的注入，id 统一成 `-ctx<i>`（`edge/http.clj` 与 `ag_ui/step` 同一拼法）。
   **⚠ 这一条已被文末的修正推翻**（id 换成条目自己的，帧照发一次）。
3. **读失败仍按名字停下。** 开场的读取在 run 的 go block 里、`hook/*sink*` 之下，失败存成
   `opening-failure`；等这一轮的提问（`append!` + `input` 行）落地后在同一个 catch 里抛出，
   于是客户端拿到 `RUN_STARTED..RUN_ERROR`，而人的那句话已经留在会话里。
4. **「哪条 user 消息是人说的」成了一条具名规则**：`ag_ui/injected?`（出生 context 的固定 id，
   或 `opening-entry?`）。三个读者都改用它——`ag_ui/first-user-text`（会话标题）、
   `stats/user-ids`（轮数）、`trajectory`（View 里哪些是轮）。
5. **`sessions/without-cards` 更名 `model-view` 并公开。** run 的组装要它对**将要加进去的那一段**
   也生效：`input` 行的 `:added` 带卡片，交给 provider 的那一份不能带。
6. **trajectory 的对齐改看文本。** 记录里的对话带卡片 part、提交侧不带，比较原始 content 会让
   两边对每条开场消息都不相等，对齐退化成「什么都没匹配上」——把人的提问当成服务端注入。
   `ag_ui/message-text` 因此公开，`trajectory/shape` 用它；`text-of` 也不再渲染 `data` part。

**代价，已写进文档**（`docs/architecture/skills-and-instructions.md`、`sessions.clj` 头部、
`cap/preamble.clj`、`edge.md`、`client.md`）：改过的 `AGENTS.md` / 技能清单只在**下一次开场**被采纳
（新会话，或将来的压缩重建）。**出生早于本改动的会话没有开场，也不会补**——`born?` 问的是
「会话空不空」，老记录非空。现场那条 `2754772f…` 的 jsonl 里已经写下了改动前那几屏，代码修不了它，
**新开一场才是诚实的补救**。

**一个被 UI 套件第四条钉住的事实**：客户端会把开场条目原样发回去（`buildUserContent` 只带走
text part，id 不变），服务端按 id 去重丢掉这份重复（`sessions/append!`）——所以开场不会被写第二遍。

## 票 02 的实现（2026-09-21，同一分支）

**落地形状**——要落地的判断照原样，只是把"报一份"和"记一行"分开给两侧：

1. **`loop.clj` 的 `answer!`：插在点名它的那条 assistant 消息正后面。** 找那条消息用的
   `call-position` 与 `llm/unanswered-tool-calls` **同一个读法**（assistant 的 `:tool_calls` 里
   有它的 `:id`），插在 `(inc i)` 处，于是相邻判定立刻满足。**找不到时不猜**：退回末尾并答
   `false`，`replay!` 把那个 `tool_call_id` 收进 `:unplaced`——这条历史本来就没有那条消息
   （客户端重建窗口时弄丢了），猜一个相邻比说一声更坏。要落地判断里那条"记一行"落在**边**上：
   `:run/done` 报事实，`edge/http.clj` 为它写 `log/warn! :run/replay-unplaced`（带 thread-id /
   run-id / 那几个 id）。内核因此仍然不需要 `harness.infra.log`。
2. **内核自报"这次加了哪几条"：`drive!` 返回 `{:history :added :unplaced}`，`:run/done` 原样带上。**
   `added` 是**记发生的次序**，不是历史尾部的切片：`answer!` 的插入、`added!` 的追加、
   `with-skills` 新折进来的派生注入，三处各记一份（`swap-vals!` 的前后差）。边写 `message` 行
   改用 `:added`——`(subvec (:history ev) (count messages))` 那条计数切片删掉，理由写进
   `http.clj` 的注释与 `docs/architecture/{kernel,edge,client}.md`。
3. **厂商那条规则一个字没动**：`llm/unanswered-tool-calls` 的相邻判定是本票的**依据**，不是对象。
4. **"消费过的 park 仍算停着"照原样留着**，见票里的第 4 条。

**两个新用例，各自先证明过会在旧行为下红**（把 `answer!` 改回永远末尾 + 边改回计数切片那一版）：

- `kernel.loop-test/a-replayed-answer-lands-behind-the-call-that-asked-for-it`：交给 run 的历史里
  停住的那条 assistant 后面坐了一条注入，批准后要求 `:run/end`（旧行为红在 `:run/interrupt`），
  tool 消息紧跟在调用后面，且 `:added` 是 `["tool" "assistant"]` 而"计数切片"是 `["user" "assistant"]`
  ——正好把客户端那条记成内核的、丢掉真答案。
- `edge.http-test/an-answer-lands-behind-its-call-even-with-a-message-behind-the-call`：真 socket
  上 park → 批准（这次动作自己还带一条 `append`，于是尾部一定有东西）→ resume 必须
  `RUN_FINISHED` **不带 outcome**、`TOOL_CALL_RESULT` 在、工具只跑一次；记录里用
  `trajectory/run-segments` 读回，返回侧是 `["tool" "assistant"]` 且不含人的那句话。
  旧行为红在 `(contains? (last resumed) :outcome)`——**正是现场那个"再停一次"**。
- 另加一条退路用例 `a-replayed-answer-with-no-call-to-sit-behind-goes-to-the-end-and-says-so`：
  没有那条 assistant 消息时落末尾、`:unplaced` 报出 `c1`、run 照旧 `:run/end`。

## 修正（2026-09-21，票 01–03）

**三件事一起改，都是这一页落地形状的账**。票在 `issues/`（做完即删），这里是留下的那一份；上面「实现」一节的
第 2 点已被下面第 2 条推翻，其余保留为当时的形状。

1. **开场写进对话的位置：提问之后**（票 03）。落地时写成了「提问之前」——把「只发生一次」和「排在最前面」
   混成了一件事。`.scratch/context-frames` 决定 7 立下的规矩是材料排在**它要回答的那句话后面**，
   `CONTEXT.md` 的注入词条也一直这么写。现在 `run-agent!` 的出生支路是
   `(into (vec (:append input)) …opening)`，出生 context 仍夹在提问与开场块之间。**「一次」没变**：
   开场仍只在出生那一轮写进会话。
2. **开场的卡在写它的那一轮上流一次**（票 01）。落地时写的是「开场永不发帧」，理由是「帧 id 与条目 id
   不同名，会画两张」——**理由对，结论错：换 id 就行**。`added-card-frames` 发的帧用**条目自己的 id**
   （`session-opening-<i>`），于是记录 fold 出来的卡与条目带的卡是同一条消息
   （`replay/append-new`、`sessions/append!` 都按 id 去重）。**非流不可的原因**：自己开出这一页的客户端
   （`read: "none"`）既没有窗口也不跟 feed，出生那一轮是它唯一能收到开场卡的线——不流这一下，
   那一页就只有提问、没有开场。
3. **开场条目按卡片的形状画**（票 02）。它们是 `role: "user"`（对 provider 就是 user 消息），所以线程把
   AGENTS.md 画成了「这个人说过的话」：右对齐、灰气泡、旁边一支 Edit 铅笔。现在 `lib/injections.ts` 的
   `isCardOnly`（消息内容**只有**一张卡）把它引到左侧的注入行，动作条取消。**UI 套件第四条当时把这条消息
   写成 `role: "assistant"`**，与真实形状不符——机器门全绿而应用画错，差的就是那一格。

**留下的判据**（`edge.http-test/an-opening-block-reaches-the-model-and-the-client-can-see-it`、
`edge.http-test/a-slash-load-reaches-the-model-and-is-a-card-in-the-conversation`、
`ag-ui-test/what-a-birth-added-gets-a-card-under-the-entrys-own-id`、
`ui/test/suites/injections.ts` 第四条与第五条）：出生那一轮的 `message` 行里提问在开场块之前；那一轮的
`CUSTOM` 帧里开场三张按条目 id、派生那张按 `<run>-ctx<i>`，四个 id 互不相同；重建后开场条目同名同序且只有一份；
开场条目在客户端画成左侧的卡而不是气泡。

## 修正（2026-09-21，票 04）

**跑动中刷新看不到正在进行的那一轮**（票 04，做完即删）。根因与上面三条都不同：窗口读的是**会话表**，而
`settle!` 要到**终帧**才把这一轮折进表（「半截答案不算一轮」），于是**没赶上收帧的页面**——刷新、新标签页
——在跑着的时候只看到提问和出生那几条。**记录里那一轮的帧一直都在**（`log!` 一帧一行），未收尾的那一组
`replay/entries` 也会 flush，所以缺的不是读法，是**这条路没走它**。

落地：`read-entries`（page）与 `window-page`（feed 的首帧与后续增量）在**本进程正跑着这场会话**时改读记录，
**不新增任何存储**；表仍是「这一轮结束了」之后的权威。两条细节，都是判据：

- **读的是 `replay/read-records`**——一个正在被追加的文件，最后一行可能是半行，丢掉它才是「已经到达的」的
  诚实答案（这条规矩原来只写在 `stats/read-records` 里，现在住进 `replay`，两处调用同一份）。**没有记录**
  （表被人为喂过、首行还没写下）时退回内存；**记录读不了**（旧契约、中间行坏了）时按名字拒绝，与 `sofar`
  一致。本进程不持有这场会话、或没有 run 在跑时，照旧读表。
- **切换那一刻不多一条、不跳一下**：记录与表里是**同一批条目、同一个 id**，读者按 id 去重（`replay/append-new`
  与 `ui/src/lib/window.ts` 的 `unseen` 各守一半）。

**留下的判据**（`edge.http-test/a-running-session-reads-what-has-arrived-and-nothing-is-written` 里新增两段）：
跑动中的 `GET …/page` 与 `GET …/feed` 首帧**都**带得上这一轮已经写下的消息，且与同一刻 `sofar` 的
`:messages` **逐条相同**（记录一个读者，两扇门）；跑完落地后再读一次，条目 id **无重复**，内容与 `sofar`
一致。`docs/architecture/edge.md` 的窗口一节写下了这条取舍。

## 修正（2026-09-21，票 05 → 拍定：出生那一轮把对话交给页面）

**开场卡在出生那一轮落在助手那一栏**（票 05）。诊断里那份「适配器不看 `messageId`」是对的，但**光改适配器
不够**：出生那一轮客户端手里**根本没有那条 user 消息**——服务端只发 `CUSTOM` 卡帧，那条消息是记录里的一条
user entry。

票 05 先落地的补丁是「**这一轮结束时读一次记录、把读数 import 回来**」（`app.tsx` 那条 effect）。**主人当场
拍掉了这个方向**，原话：

> 你说的发送出去很奇怪 底层都是后端在发发送 前端只需要渲染

**改成：出生那一轮随 run 自己把对话发全。** 服务端在 `RUN_STARTED` 之后发一帧 `MESSAGES_SNAPSHOT`
（`harness.edge.ag_ui/conversation-snapshot`），内容是**这一轮写进对话的那些 entry**；`app.tsx` 那次
import 随之删掉（只留健康读数上报）。落地细节：

- **为什么消息列表而不是每块一张 `CUSTOM` 卡**：`CUSTOM` 是**一个 part**，适配器把它挂到**正在流的那条
  消息**上、帧自己的 `messageId` 在入口就被丢（`run-aggregator.js` 的 CUSTOM 分支不看它），客户端手里没有
  那条消息时卡就落到答案底下；`MESSAGES_SNAPSHOT` 带的是消息本身，落位由消息自己决定。
- **投影是必须的**（`ag_ui/wire-message`）：`@ag-ui/client` 对**它解析的每一帧**做 schema 校验，它的消息
  schema 要 `content` 是文本/输入块——本仓的 entry 带 part 向量，一个 `data` part 会**当场把这一轮打死**
  （实测：界面上一条 Zod 报错，截图见证据目录第一次跑的那一轮）。所以快照只带 `id` / `role` / 文本；
  **客户端自己发的消息不用投影**（它本来就是客户端那次转换的产物，也就是校验它的 schema 认可的形状）。
- **卡由读者按 id 和文本自己画**：适配器的快照转换保留 id 与文本、丢掉 `data` part，所以
  `thread.aui.tsx` 的 `UserMessage` 认两条——`isCardOnly(parts)`，或 `isOpeningEntryId(id)`
  （`session-opening-<i>`，纯函数，`ui/test/suites/injections.ts` 有案子）；两条路画的是同一个
  `InjectionCard`（`components/context-card.tsx` 现在接受一个 value，而不只是一个 part）。
- **只在这一轮发，且只在这一轮**：`client-never-sent`（`added` 减去客户端自己发的）非空才算，也就是会话
  出生那一轮——此后每一轮开场都是历史，窗口/feed 会说。

**留下的判据**（真浏览器，`.scratch/session-opening/walkthrough.mjs`，2026-09-21 全绿）：出生那一轮
**不刷新**就出现两行卡、在 `aui_user-injection-root` 里，人的消息是**唯一**一个气泡，屏幕顺序为
`message → injection → injection`（与 reload 之后**同一条断言**），并且第二句那轮不再多卡。断言不变，
变的只是**卡从哪来**：现在是那一轮自己的流，脚本里没有任何一次记录读。
