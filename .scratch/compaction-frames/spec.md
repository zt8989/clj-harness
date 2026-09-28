# spec: 压缩也长出那张卡 —— 走 AG-UI 的 `CUSTOM` 帧，刷新后由重建带回来

**一句话**：一次压缩今天只留下 `context/compacted` 这行事实——它改变**模型**读到的东西，人一个字都看不到。
本特征让 run **之内**发生的那次压缩以 **`CUSTOM` 帧**（名字 `compacted-context`）流到客户端：适配器把它
落成一条 `data` part，`thread.aui.tsx` 里 `case "data": return part.dataRendererUI;` **已经在**，于是「和
注入卡、工具卡同一套壳的折叠卡，点开是那段摘要」只差注册一个渲染器。刷新走的是**重建**（seed + 记录里
的帧），所以重建那一折也要认它——而**客户端永远不回发** `data` part，会话保持干净。

它和 `.scratch/context-frames` 是同一条缝的第二次使用，所以那一票的四条规矩原样搬过来（`CUSTOM` 出去、
确定性 id、重建带回、回发丢掉），只多一条：**说这句话的人是压缩自己**。

2026-09-27 立，当日落地。

## 问题

1. **压缩是安静的。** `harness.edge.compaction/perform!` 写三行（`compaction/start`、`context/compacted`、
   `compaction/end`），`harness.edge.replay` 照着事实把模型视图换掉——客户端从头到尾不知道这件事发生过。
   而它恰恰是**这次会话被改写了**的标记：模型下一次读到的开头已经不是人读到的开头了。
2. **轨迹那一栏也不是它的家。** 轨迹读的是 `message` 行与 `context-item`（注入物），压缩不是消息，它没有
   可读的落点（`.scratch/compaction-shape` 记的那次事故里，15 次失败的压缩连一行 `context/compacted` 都
   没留下，人只能从厂商的 422 反推）。
3. **能用的帧类型被 `context-frames` 已经证过**：`CUSTOM` 是唯一一类「客户端画、不回发」的帧，`apply-frames`
   把不认识的 CUSTOM 一律丢掉——所以它同时也是「重建要不要带回它」的那一个决定点。

## 决策

1. **帧的形状**：`CUSTOM`，名字 `compacted-context`，值里放那次压缩对**模型**说的话：`{:summary <摘要原文>
   :tokens <被折叠的那段估算 token> :messages <折叠掉几个节点>}`。`messageId` 用**压缩自己的 id**
   （`perform!` 里那次 `UUID/randomUUID`，已经落在 `context/compacted` 的 `:compactionId` 上）——它天然
   唯一、天然确定，重建出来的卡每次同名同 id，`sessions/append!` 与 `replay/append-new` 的 first-wins
   去重也认它。
2. **一处造帧，三处说话。** `harness.edge.ag_ui/compacted-frame` 是唯一的构造者（`injected-frame` 的那条
   规矩：两处 emit 一种形状）；谁说出去由**哪里有 run 的帧可发**决定：
   - **run 起点**（`compact-if-pressured!`）：这一刻 `RUN_STARTED` 还没发，帧不能抢在它前面。所以那次压缩的
     结果先记在 run 自己的 `state` 上，等到 `:run/start` 被转换时和 `-pre<i>` 那批注入帧一起发出去——
     和边自己那半注入物用的是同一个位置。
   - **run 途中**（`relieve-pressure!` 每发请求前那一问、`recover-overflow!` 厂商拒了之后那一次恢复）：
     run 的帧汇（`runner`）就在手上，压缩一发生就 `emit` 那一帧，照旧进记录、照旧广播。
   - **不是 run**（`POST /api/threads/<id>/compact`）：不发帧（下一条非目标）。
3. **压缩自己把结果交出来。** `perform!` 的返回从 `{:shadowed [ids] :summary text}` 变成多带 `:compactionId`
   与 `:tokens`；`run-compaction!` 原样把它交给调用者。**为什么不是 `run-compaction!` 直接发帧**：它同时
   服务手工路由（没有帧可发），而「谁有帧」是调用者的事实，不是压缩的事实。
4. **重建由重建带回来**：`harness.kernel.frames/apply-frames` 认这一种 `CUSTOM`，落成**一条 assistant 消息、
   内容只有一个 data part**（位置就是帧在流里的位置），和注入卡同一条分支的形状——**别的 CUSTOM 依旧丢掉**。
   同时 `harness.edge.replay/wire-custom-names` 收它：这一行是**帧**（会话的一部分），不是一条事实。
5. **模型永远读不到它**：卡是 `data` part，`harness.edge.replay/model-message` 只把 `injected-context`
   那种卡**实现**成消息（那是模型真读过的字节），别的一律丢——所以压缩卡进不了模型视图，也进不了下一次
   `ag/provider-messages`。用例钉这条。
6. **那张卡的画法**：与注入卡、工具卡同一套壳（`ToolFallbackRoot` / `ToolFallbackContent`）；折叠行是
   `压缩的上下文 · <摘要首行> · N tok`（`N` 走 `formatTokens`，和 composer 那条挤在同一个量纲上）；展开是
   摘要全文，等宽、可滚动。`makeAssistantDataUI({name:"compacted-context"})` 注册，组件挂在运行时提供者之内。
7. **前端那套「卡」的家要挪一格。** `isCardPart` / `isCardOnly` / `keepInjectionCards` 今天是按
   `injected-context` 一个名字写死的，而「只有卡的 assistant 消息不是气泡」「重建要把丢掉的 part 按 id 补回来」
   这两条规矩**对两张卡都成立**。所以它们搬进 `ui/src/lib/card-parts.ts`，名字集合收在一处：
   `#{injected-context, compacted-context}`；`injections.ts` 只留注入自己的算术（标题/预览/字节数），
   `compactions.ts` 留压缩自己的（首行/token 数）。

## 非目标

- **不做手工路由那一张卡**：`POST /compact` 没有 run，也就没有帧汇；要让它可见就得往记录里写一条**从未上过线**
  的帧行，而「记录里的帧=上过线的帧」是 `row-of` 明写的规矩，为一个今天**没有任何客户端入口**的路由去开一类
  新行不划算。手工压缩的可见性是它自己的票。
- **不做轨迹那一栏**：轨迹读 `message` 行，压缩不是消息。它今天不说话，本特征也不让它说。
- **不改压缩本身**（范围、阈值、锁、计划）：本特征只让它**可见**。
- **不画剪枝**（`context/pruned`）：那是另一条事实、另一句话，混进这张卡就是两件事挤一个名字。
- **不给卡加任何动作**（没有编辑、没有跳转、没有「撤销这次压缩」）。
- **不改 `job_output` / `bash` / 工具卡的任何语义**，不改记录里 `message` 行。

## 验收主线

1. **途中压缩，卡当场出现**：一次 run 里压力越过阈值 → 会话栏在那一刻出现一张折叠的卡，点开是那段摘要，
   右端是估算 token 数。
2. **起点压缩，卡跟着 run 的头一起出现**：run 起点就压了 → 卡在这一次 run 的第一帧之后、注入卡之前。
3. **刷新之后还在**：刷新页面（重建）→ 同一张卡还在（同一个 `messageId`）。
4. **会话干净**：刷新后继续对话，服务端收到的历史里没有这张卡；记录里也没有它的 `message` 行（它是帧）。
5. **模型读不到**：`model-view` 里没有那张卡，也没有把摘要再喂回去。
6. 两套全量与真浏览器走查证据。

## 与既有规矩的对照

- **`.scratch/context-frames` 决策 1、3、4**：本特征原样照搬（`CUSTOM`、重建带回、回发丢掉），只是名字、
  值、以及「谁在什么时刻说话」不同。
- **`.scratch/compaction-shape`**：那一票让 meter 说得出真话、让压缩真的发生；本特征让**发生过的**那次看得见。
  它不动阈值、不动计划、不动 `context/compacted` 的形状（只多一层「说出去」）。
- **`row-of` 的「记录里的帧=上过线的帧」**：本特征**没有**例外——三个会说话的压缩点都在 run 里，帧都真的
  上过线（手工路由因此不做，见非目标）。
- **`runner` 的「一处看到每一帧恰好一次」**：本特征让 `runner` 多了一个调用者（`drive!` 那侧跑在 kernel 线程
  上的那次压缩）。记录行由 `log!` 自己的锁定序、`:frames` 由 `swap!` 定序、广播对死连接是吞掉的，所以没有
  一次写入会被撕开；**代价写在这里不留白**：消费者与生产者是一个**无缓冲**通道（`run-chan` 的 `>!!`），
  消费者最多落后一帧，所以这张卡在重建里最多**早一行或晚一行**。没有一处算术读这个位置——折叠里按
  「assistant 且内容是字符串」配模型行的规矩天然把卡排除（它没有字符串内容），前端按 id 配卡——所以这是
  一个位置上的近似，不是一个可以算错的事实。

## 落地记录

### 后端

- `harness.edge.compaction/perform!` 多回答 `:compactionId` 与 `:tokens`（都在它刚写下的那条
  `context/compacted` 上）；`run-compaction!` 原样交出去，自己不发帧。
- `harness.edge.ag_ui`：`compacted-part-name`（`compacted-context`）与 `compacted-frame`——一处造这条帧。
- `harness.edge.http`：三个在 run 里的压缩点各自把它说出去。起点那次（`compact-if-pressured!`）**改回传值**，
  `run-agent!` 把它记在 run 自己的 `state` 上、到 `:run/start` 和注入那几张一起发（`RUN_STARTED` 之前发帧是
  客户端没有 run 可挂的）；`relieve-pressure!` / `recover-overflow!` 各多收一个 `emit`，压成就发——
  **不看那次更短的数组最后有没有被采用**，因为记录已经改了。`compact-post` 一个字没改（见非目标）。
- `harness.edge.replay/wire-custom-names` 收这个名字（它是**帧**，不是事实）；
  `harness.kernel.frames` 多一张 `card-custom-names` 表，`apply-frames` 把两种 CUSTOM 卡折成同一种
  只带这个 part 的 assistant 消息。
- 用例：`compaction_run_test`（答案里带 id/tokens）、`compaction_test`（折成卡、模型视图丢它、帧与真压缩对得上）、
  `ag_ui_test`（帧的形状与折叠）、`replay_test`（读作帧而不是事实）、`relieve_pressure_test`（压到就说、
  没压到就一个字不说）。

### 前端

- `ui/src/lib/card-parts.ts`（新）：**两条卡共用的三条规矩**——`isCardPart` / `isCardOnly` / `keepCardParts`，
  名字表 `CARD_PARTS` 是唯一一处列名字的地方；`lib/injections.ts` 因此只剩注入自己的算术，
  `lib/compactions.ts`（新）留压缩自己的（首行预览与两个数，**帧没说就不画**）。
- `ui/src/components/compaction-card.tsx`（新）：与注入卡/工具卡同一套壳的一行（折叠行 + 展开的摘要），
  `makeAssistantDataUI({name: "compacted-context"})`；`app.tsx` 里 `<CompactionCards />` 挂在 `<ContextCards />` 旁边。
- `lib/thread-messages.ts` 与 `thread.aui.tsx` 改叫同一份共用件；文案进 `thread` 命名空间（中英两份，`compaction.name` /
  `.tokens` / `.folded`）。
- 用例：新套件 `ui/test/suites/compactions.ts` 四条（算术、两条卡同一条规矩、重建按 id 补回来、不回发），
  `EXPECTED_CASES` 161 → 165；`injections` / `llm-timeout` 两个套件跟着改名。

### 文档

- `docs/architecture/edge.md`：CUSTOM 那一族从「一种东西」改成两件（注入物与压缩），`event` 行那一句列出两个名字。
- `docs/architecture/client.md`：新增「压缩在会话栏里的那张卡」一节（谁在什么时候说、重建怎么带回来、
  手工路由为什么不发、证据在哪），并把共用件改名与套件清单一起改了。
- `CONTEXT.md` 的**压缩**词条补一段：屏幕那一张卡，以及「帧只讲屏幕」。

## 撞上的坑

1. **项目级 harness.edn 在 `<project>/.harness/harness.edn`，不是 `<project>/harness.edn`**
   （`cap.project/harness-edn-levels`）。第一次把它写在了项目根上，于是那次走查用的是默认比例、什么都没压，
   从 `context/pressure` 行里记着的 `thresholdTokens: 89600` 才看出来。
2. **摘要是空串时卡不画**，而这一场的 scripted provider turn 用完之后就吐空串：记录里三条
   `compacted-context` 帧、屏幕上只有一张卡。这是 `compactionView` 的 null 那条规矩（与 `injectionView` 同一条），
   证据 README 里写清楚了，不是漏画。
3. **本机没有 Playwright**（仓库也不依赖它），所以这一场是**用 Playwright MCP 的浏览器**驱动的：
   流程与断言和 `walkthrough.mjs` 一样，脚本留在仓库里给装了 Playwright 的机器跑。

## 基线

- 动过的那几个后端命名空间：`harness.edge.compaction-test` / `compaction-run-test` / `relieve-pressure-test` /
  `ag-ui-test` / `replay-test` 合起来 **93 tests / 433 assertions，0 failures / 0 errors**。
- 前端：`npm test` **165 passed**（161 + 新套件四条）；`npm run typecheck`、`npm run build` 过。
- 真浏览器走查：见 `evidence/README.md`（两张截图：点开的那张、刷新之后折着的那张），四条断言全绿，
  含「点开是那段摘要」与「回发的那一份里没有它」。

### 全量（本机）

- 后端全量跑完那一轮：**1323 tests / 14002 assertions，1 failures / 0 errors**，那一条红是
  `harness.kernel.hooks.dispatch-test/a-hang-is-bounded-and-means-the-same-as-a-failure`
  ——「一个挂住的 gate 应该在 5s 内被掐掉」的计时断言，在负载下量到 10572ms。**单独跑是绿的**
  （19 tests / 65 assertions，0 failures），与本分支无关，仓库里记着的同一类「随负载时红时绿」
  （`.scratch/compaction-shape` 的「已知、未修」）。
- 又跑一轮（把输出留档到 `evidence/backend-full.log`）时撞上另一条已知的本机条件：
  `harness.edge.http-test` 没在 300s 内跑完，整轮**退出 2**（限额），先前完成的是 60 个命名空间、
  **1132 tests / 12207 assertions**，红的那一条还是上面同一个计时用例。
- 这两条都不是本分支动的（本分支动的五个后端命名空间在全量里都过了），如实报在这里，不放白。

## 落地（追加，2026-09-28）：**卡不是一步**，折着也要看得见

**现象（主人报的）**：一张压缩卡在短对话里看得见，在真会话里「不显示了」——那一轮的折线写着
`N 步`，卡没了。

**根因**：卡是一个 assistant 消息（重建那一侧）或 run 头那条 assistant 消息里的一个 `data` part
（实时那一侧），而 **turn 折叠默认把这一轮的所有步都收起来**（`components/turn-steps.tsx`）：
`fold === "head"` 的那条消息整个内容 `hidden`，`fold === "step"` 的根本不挂载。短对话里一轮只有一条
assistant 消息、`isFoldableOf` 为假（没有可折的东西），所以卡露着；一旦这一轮有工具步，它就被折进去了。

**决策**：**卡不是一步**。折叠只收起「这一轮做了哪些工作」；卡说的是模型视图变成了什么
（`compacted-context`）与它被喂了什么（`injected-context`），两条都不是工作，所以折着也画。
规则落在 `thread.aui.tsx`：`hasCard` / `putAway` —— 折着的头/步只画卡 part，卡之外的一律不画；
头没有卡时照旧整个 `hidden`（布局不动），没有卡的步照旧不挂载（挂载数不涨）。
`useStepFold` 那三个答案不变（"step" 还是一步），变的只是「一步里还有什么要画」——它读的是 part，
不是位置，所以它在 `turn-steps.tsx` 的注释里明说了。

**判据**：真浏览器（同一套三句话 + 每轮一个工具调用，`.scratch/compaction-frames` 那份走查）：
折着时 `[data-slot="compaction-trigger"]` 有高、读得出 `压缩的上下文 · 摘要…61 tok`，那条头的消息高 86px
（无卡的同形折头 58px）；展开后卡与步行同现。`npm run typecheck` / `npm run build` 过；`npm test`
与既有那两条无关的红（`approval` / `context`，本机既有）之外无新增。
