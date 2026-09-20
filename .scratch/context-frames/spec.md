# spec: 注入物在会话栏里画一张卡 —— 走 AG-UI 的 `CUSTOM` 帧，刷新后由重建带回来

**一句话**：注入物今天只在「轨迹」那一栏看得见（客户端从不持有它，它不产生任何 AG-UI 帧）。本特征让它
以 **`CUSTOM` 帧**（AG-UI 自己的扩展点）流到客户端：适配器把它按顺序落成一条 `data` part，
复制来的 `thread.aui.tsx` 里 `case "data": return part.dataRendererUI;` **已经在**，于是「左侧、和
`read`/`write` 工具卡一样可折叠、点开是注入内容」只差注册一个渲染器。刷新走的是**重建**
（seed + 记录里的帧），所以重建那一折也要认它 —— 而**客户端永远不回发** `data` part
（`toAgUiMessages` 只回 text / reasoning / tool-call），会话保持干净。

2026-09-20 立，当日落地。四张票：`01 → 02 → 03 → 04`。

## 问题

1. **注入物看得见，但要换个栏目。** 会话栏是客户端持有的会话；注入物是服务端每次调用现算的，两者之间
   只有 `message` 行（记录）连着。要看注入就得切到「轨迹」那一栏——而人看的是会话栏。
2. **帧就是会话，所以这条缝只能走"view-only"这一侧。** 任何作为帧发出去的东西，刷新时会被**重建**带
   回来（重建 = seed + 记录下来的帧），于是它会被客户端**再发回服务端**。所以能用的帧类型必须满足：
   客户端渲染它，但**不回发**它。
3. **`data` part 正好是这一类**：`@assistant-ui/react-ag-ui` 的 `run-aggregator.js` 里
   `case "CUSTOM"` 把它按顺序 push 成一条 `{kind:"data", name, value}`；而 `toAgUiMessages` 的
   `convertAssistantMessage` 只回 text 与 tool-call —— `data` part 被丢掉（**这是本特征成立的根据**，
   票 03 有用例钉它）。

## 决策

1. **帧的形状：`CUSTOM`，名字 `injected-context`，值里放注入的那条消息**（`{:role :text}`，`role` 是
   `user`——注入物本来就是一条 user 消息）。带一个确定的 `:messageId`（`<runId>-ctx<n>`），这样重建
   出来的那条卡消息在每次刷新里**同名同 id**。
2. **内核在每轮施加前置步骤之后 diff 出新加的那几条**，一条一个 `:context/injected` 事件；边缘转成
   CUSTOM 帧。不做「谁注入了什么」的二次判断：工具面、轨迹读侧、重建三处都只认帧里的字节。
3. **刷新由重建带回来**：`harness.kernel.frames/apply-frames` 认这一种 CUSTOM，落成**一条 assistant
   消息、内容只有一个 data part**（位置就是帧在流里的位置）；客户端导入时**保住 data part**
   （适配器的 `toAssistantSnapshotMessage` 只取文本与 tool-call，会把 data part 丢掉——所以这一步在
   `app.tsx` 的 `toThreadMessages` 里补回来，是一个纯函数，有用例）。
4. **客户端不回发它**：由适配器保证（`data` part 不进 `toAgUiMessages`），用例钉住这条**契约**——
   它是「会话干净」的唯一依据，适配器升级时第一个要看的就是它。
5. **画法**：左侧、与工具卡同一套壳（折叠时一行：`上下文注入 · <首行> · N 字节`；展开是那些字节，
   等宽、可滚动）。`makeAssistantDataUI({name:"injected-context"})` 注册，组件挂在运行时提供者之内。
6. **一次注入一张卡**：开场块、技能正文、运行上下文、作业通知**全都画**（同一条渲染路径，不分类）。
7. **注入物的位置：`system → 提问 → context → skill_context`。** 今天开场块拼在 **system 之后、客户端消息
   之前**（`ag_ui/inbound`），技能正文拼在**问它的那条消息之后**（`cap.skills/derived-injections`）。
   本特征把它们统一挪到**提问之后**，顺序是：**指令/目录等 context** 在前，**技能正文这类 skill context**
   在后（缺省没有）。理由与代价都写进票 05：模型先读问题、再读为它准备的料，最近的那段（技能正文）
   贴着问题；而**冻结前缀（prompt cache）不受影响**——前缀本来就是 system + 历史，注入物原本也在
   前缀之外。

8. **那句要改的谎话**：`docs/architecture/skills-and-instructions.md` 与 `CONTEXT.md` 里
   「注入物**从不产生任何 AG-UI 帧**」当天成立不了——改成「**会**以 CUSTOM 帧出去，但客户端**不回发**
   它，所以它仍然不是会话的一部分」。

## 非目标

- **不做推送**：帧跟着这一轮走，不唤醒、不新起 run。
- **不改注入物本身**（谁注入什么、顺序、幂等）：本特征只让它**可见**。
- **不改 `job_output` / `job_kill` / `bash` 的任何语义**，不改记录里 `message` 行。
- **不做点击跳转到轨迹那一栏**（可以后加；本票不做，理由：跳转要把 `onView` 从列上穿下来，而卡的
  价值在"就地看得见"）。
- **不给卡加编辑/复制以外的动作**（复制用与消息相同的那套）。

## 验收主线

1. **活着就出现**：一轮里起一条后台作业、去干别的、它结束 → 会话栏在**它真正搭上的那个位置**出现一张
   折叠的卡；点开是 `<job-ended id="j1" path="…">[exit 0]</job-ended>`。开场块同样：绑定会话的第一轮
   里，`<instructions path="…/AGENTS.md">` 那张卡在助手文本之前。
2. **刷新之后还在**：刷新页面（重建）→ 同一张卡还在、位置一致。
3. **会话干净**：刷新后继续对话，服务端收到的历史里**没有**这张卡（`toAgUiMessages` 丢掉它）——
   用例钉住这条契约，走查里也看一眼（再跑一轮，记录里的 `message` 行与从前一样）。
4. **帧在记录里**：`event` 行里有那条 `CUSTOM` 帧（重建的依据）；轨迹读侧不受影响（它读 `message`
   行，注入物照旧是 `context` 项）。
5. 两套全量与真浏览器走查证据。

## 跨特征对照

- **`.scratch/job-endings/spec.md` 决策 7**：「界面一个字都不用改」被本特征取代——**轨迹那一栏**仍然
  一个字不用改，会话栏从这一票起多一张卡。它的其余部分（谁算告知、一次、不推送、前置步骤）不动。
- **`.scratch/skills-and-instructions` 与 `CONTEXT.md` 的「注入」词条**：那句「从不产生 AG-UI 帧」要改
  （不是删：**注入物仍然不是会话的一部分**，只是**看得到**了）。
- **`.scratch/trajectory-injection-once`**：轨迹读侧那一栏的规矩（同一段字节整场只画一次）**不动**；
  会话栏这张卡按帧画，同一段注入在两次刷新之间是同一个 id。
- **`.scratch/job-output` 决策 3**（帧就是客户端的会话）：本特征是对这条规矩的一次**有意的例外**，
  而例外的边界由 `toAgUiMessages` 划——不是设计者的善意，是适配器的行为，所以用例钉它。

## 交付顺序

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 注入物以 `CUSTOM` 帧出去 | — | 内核在施加前置步骤后 diff 出新加的消息、每条一个 `:context/injected`；边缘转成 `CUSTOM`（name `injected-context`、`messageId` 确定、值里是那条消息）；记录照旧（帧进 jsonl）；用例：一轮里按序按量、没有注入时不发 |
| 02 | 会话栏那张卡 | 01 | `lib/injections.ts`（纯：标题/预览/字节数）+ `components/context-card.tsx`（`makeAssistantDataUI`，与工具卡同壳的折叠卡）+ i18n（中英）+ 套件 + 真浏览器走查证据（活着就出现） |
| 03 | 刷新之后还在 | 02 | `apply-frames` 认这一种 CUSTOM → 一条只带 data part 的 assistant 消息；`app.tsx` 的 `toThreadMessages` 保住 data part（纯函数 + 用例）；用例钉住「回发时丢掉它」这条契约；走查：刷新后卡还在、再跑一轮历史干净 |
| 05 | 注入物的位置：`system → 提问 → context → skill_context` | 01 | `ag_ui/inbound` 把开场块拼在**客户端消息之后**；`cap.skills/derived-injections` 把技能正文拼在**尾巴**（不打断工具块——厂商那条硬规矩照旧）；轨迹读侧的两个来源（`这次运行开始时` / `运行途中`）**收成一个**（注入物就是注入物，字节与标签自己说是谁）；`ag_ui_test` / `skills_test` / `loop_test` / `http_test` 的相应断言跟着改；`skills-and-instructions.md` 与 `kernel.md` 的顺序段落重写 |
| 04 | 收口：那句谎话、文档、报数 | 03, 05 | `docs/architecture/skills-and-instructions.md` 与 `CONTEXT.md` 的注入词条改写；`client.md`（会话栏多一张卡、重建带回它）；`kernel.md`（每轮的首步会说话）与 `edge.md`（第三种帧）；README 一句；两套全量报数；落地记录 |

## 状态

**2026-09-20 立票，01 → 03 → 05 当日落地；04 与收尾 2026-09-20 落地。** 五张票按
01 → 02 → 03 → 05 → 04 走完（05 是当天追加的：把注入物的位置挪到提问之后），票面按仓库约定删除，
落地记录见下。

## 落地记录

### 01 — 注入物以 `CUSTOM` 帧出去

- `harness.kernel.event/context-injected`：内核的事件表从 13 种变 14 种。
- `harness.kernel.loop/drive!` 的前置步骤那一步，原来是 `(swap! history prepare thread-id)`，现在是
  `swap-vals!`：尾巴上新加的每条发一个 `:context/injected`（「施加」照旧沉默，说话的是这一处）。
- `harness.edge.ag_ui/injected-frame` 造帧，`outbound` 的 `step` 多一个 `:context/injected` 分支：
  `{:type "CUSTOM" :name "injected-context" :messageId "<runId>-ctx<n>" :value {:role … :text …}}`。
- 记录照旧（帧逐条进 jsonl 的 `event` 行）；没有注入就不发帧。
- **边也发一批**：run 开头就带着的那几条（指令文件、技能清单、以及**开场时就已经在历史里的**技能正文与
  作业通知）由 `harness.edge.http` 发，id 是 `<runId>-open<i>`——见下面「与票面不一致」第 1、2 条。
- 用例：`http_test/an-opening-block-reaches-the-model-and-the-client-can-see-it`（一轮四张卡、按序、
  id 是帧自己的）、`…/a-slash-load-reaches-the-model-and-is-a-card-in-the-conversation`（`/name` 那条路
  各一张卡，且**没有别的帧**带那些字节）、`loop_test` 的两条（中途加载的那条 `-ctx` 帧、没有注入时零帧）。

### 02 — 会话栏那张卡

- `ui/src/lib/injections.ts`（纯：`injectionView` 出标题/预览/**UTF-8 字节数**，`keepInjectionCards` 把
  重建丢掉的 part 按 id 补回来）+ `ui/src/components/context-card.tsx`（`makeAssistantDataUI`，与工具卡
  同一套壳：`ToolFallbackRoot` / `ToolFallbackContent`）。**注册就是那个组件的挂载**：`app.tsx` 里
  `<ContextCards />` 挂在 `AssistantRuntimeProvider` 之内，`thread.aui.tsx` 一行未改。
- 文案进 `thread` 命名空间（`injection.name`，中英两份）。
- 套件 `ui/test/suites/injections.ts` 三条，`EXPECTED_CASES` 47 → 50；`npm run typecheck`、`npm run build` 过。
- 真浏览器走查：`.scratch/context-frames/walkthrough.mjs` + `evidence/README.md`（五张截图）。

### 03 — 刷新之后还在

- `harness.kernel.frames/apply-frames` 认这一种 `CUSTOM`：一条 assistant 消息、内容只有一个 `data` part，
  id 取帧自己的 `messageId`；**别的 CUSTOM 一律丢掉**（这条折叠没听过的事，会话里就没有）。
- `app.tsx` 的 `toThreadMessages` 变成 `keepInjectionCards(agUiMessages, fromAgUiMessages(...))`：
  适配器的 `toAssistantSnapshotMessage` 只取文本与 tool-call，把 `data` part 丢了——补回来的这一步是纯函数，
  **按 id 配对**（不是按下标：tool result 会被并进前一条 assistant 消息，下标会挪）。
- 契约用例：`ui/test/suites/injections.ts` 第三条（`toAgUiMessages` 不回发 `data` part）；后端那半
  （那些字节**只**从 CUSTOM 帧出去）在 `http_test` 里。
- 走查：刷新之后三张卡逐字还在（同一个 `messageId`）；再问一句，`POST /api/agent` 的请求体里没有它们。

### 05 — 注入物的位置：`system → 提问 → context → skill context`

- `harness.edge.ag_ui/inbound` 的 `after-system` → `tail-blocks`（拼在**尾部**；空块时照旧返回原向量本身）。
- `harness.cap.skills/derived-injections` 把技能正文追加在**历史末尾**（厂商那条「tool_calls 之后必须紧跟
  tool 消息」的硬规矩由「追加」自动满足）。
- 轨迹读侧的来源字段**退役**：`context-item` 不再有 `:source`，`trajectory.json` 的 `whenOpened` /
  `duringRun` 两个键删掉（不留死键），`lib/trajectory.ts` 的 `:source` 跟着删；`app.tsx` 的详情栏改成
  `注入: 已注入`。
- 用例跟着改：`ag_ui_test`（顺序）、`skills_test`（正文落末尾）、`loop_test`、`http_test`（顺序断言与
  轨迹那一条）。

### 04 — 收口：那句谎话、文档、报数

- `docs/architecture/skills-and-instructions.md`：「这些消息**从不产生任何 AG-UI 帧**」那半句改成
  「**会**以 `CUSTOM` 帧出去，而客户端**不回发**它」——立场没推翻（注入物仍然不是会话的一部分），只是说准了；
  同一页的开场顺序、技能正文落点、`inbound` 的拼接位置一起重写（小节名从「前端零改动、wire 零改动」改成
  「看得见，但仍然不是会话的一部分」）。
- `CONTEXT.md` 的**注入**词条同改（位置 + 会出去 + 不回发）。
- `docs/architecture/client.md`：多一节「注入物在会话栏里的一张卡」（data part、注册、重建带回、回发丢掉），
  测试套件清单补 `injections`。
- `docs/architecture/kernel.md`：事件 13 → **14 种**（表里多一行 `:context/injected`）、循环那一步多一行
  `swap-vals!` 的 diff、以及「注入物照旧进 `message` 行，而新加的每条都说出去」。
- `docs/architecture/edge.md`：AG-UI 边补「发出去的帧分三族」（`RUN_*` / 对话那两族 / **`CUSTOM`**），
  `inbound` 的拼接位置跟着改。
- `docs/architecture/overview.md`、`docs/architecture.md`（模块表的 `edge.ag-ui` 一行、`kernel.event` 的事件数）
  一并核过。
- `README.md`：「工具」那一节补一条——注入物在会话栏里也看得见（与工具卡同壳、刷新还在、回发丢掉）。
- 两套全量 + 走查证据：见下面「基线」。

### 与票面不一致的三处

1. **「内核在施加处 diff」只说了后半句。** run **开头**就带着的那几条是**边**施加的（提交侧的 `message` 记录
   要求它先施加一次），内核那一步看到的是「已经在了」，于是 diff 是空的——所以技能正文与作业通知**在它们
   第一次出现的那一轮**里有卡，**从下一轮起就没有**（它们每一轮都被重新注入，而没有任何一侧为它们说话）。
   2026-09-20 补上：边在它自己那次 `before-llm` 前后取同一个 diff，和内核各说自己的一半——「每一条注入一张卡」
   才成立（取过反例：绑定项目的会话第二轮，`/name` 的正文在模型手里，会话栏里却没有那张卡）。
2. **`messageId` 有两种前缀**，票面只写了 `-ctx<n>`：边发的开场那批是 `<runId>-open<i>`（`i` 是消息在
   「这一轮开头带着的那串」里的位置，开场块在前、随后是开场时就已存在的注入），内核发的中途那些是
   `<runId>-ctx<n>`。两者都确定、都不重号，重建出来每次同名。
3. **文案与票面措辞不同**：折叠行是 `injected context · <首行> · N 字节`（中文「注入的上下文」），
   票面写的是「上下文注入」；另外 ` · ` 分隔符**不在** `injection-trigger-title` 槽里（原先在，读它的人
   得先剥掉——已挪出去）。

### 撞上的坑

1. **上一轮落地漏了一条红用例。** `http_test/a-slash-load-reaches-the-model-and-never-the-client` 断言
   `/name` 的正文**不在任何帧里**——01 之后它正是一张卡。改名成
   `…-and-is-a-card-in-the-conversation`，断言拆成「是一张 `CUSTOM` 卡」与「没有别的帧带它」。
   同一条测试里还有一条**量不到任何东西**的断言（把 `{:messages []}` 写成 JSON 再断言它不含
   `injected-context`），换成上面那句真话。
2. **走查脚本踩了两个坑**（都写进 `evidence/README.md`）：**跑完的一轮是折着的**——卡在 DOM 里但
   `display:none`，「看不见」不等于「没有」，脚本得先展开那一行；以及**不能拿「记录 settled」当
   「这一轮跑完了」**——composer 在流式期间是可打的，回车不生效于正在跑的那一轮，于是下一句根本没发出去，
   而脚本以为发了（那一场的 jsonl 里 `project/bound` 落在 `input` 与 `message` 之间，就是这个错误的化石）。
3. **文档里「不发帧」的断言不止一处。** 除了票面点名的那两句，`kernel.md`（作业通知「不发帧」）、`client.md`
  （技能那半「从不产生任何 AG-UI 帧」）、`README.md` 都有同一句话的副本——一处改不动全部，只能一处一处核。

### 基线

- 立票时（上一票 `job-endings` 落地之后的树）：后端 **953 / 11943**，0 failures / 0 errors，退出码 0。
- 落地当天（2026-09-20）：后端 **956 / 11980**，0 failures / 0 errors，退出码 0（`node scripts/test.mjs --backend`，16:39 完）；
  前端 `node scripts/test.mjs --ui` **50 passed**；`npm run build`（`tsc --noEmit` + vite）过。
- 走查：`node scripts/dev.mjs --scripted .scratch/context-frames/evidence/go.json --ui-port 5219`
  加 `.scratch/context-frames/walkthrough.mjs` → **GREEN**（五个场景：未绑定无卡、绑定后 instructions 卡在助手
  文本之前、作业结局卡在工具卡之后、刷新后逐字还在、下一次请求里没有它们）。
