# spec: `ask` —— 模型问人的一次调用，答案回到工具结果

**一句话**：模型拿不定主意时能**问人**：调一次 `ask`，题目是一列问题（一次可以多个），run 停在
一张卡片上等人填，答案作为**这次调用的工具结果**回到模型手里，run 继续。一个问题三种问法——
自由文本、给候选单选（外加"自己填"）、多选——在同一次调用里可以混着来。整条链**复用 elicitation
那一条**：不新开 reason、不新开端点、不新开卡片。

2026-09-19 立（牛总一句话拆成两个 slug：本 slug 三张票 + `tool-switchboard` 五张票，后者另落）。
**当日三张票全部落地**，票面按仓库约定删除（`.scratch/` 没有"完成"这个状态：记录在 git 历史与
本文件的落地记录里）。

## 问题

1. **这条链今天被 MCP 服务器独占。** 悬置的机制全都在：`kernel.tools/suspend!` → `:reason
   :elicitation` 的 park → `GET /api/elicitation` → `resume` 回灌——但唯一的提问者是 `cap.mcp`
   替外部服务器转的 `elicitation/create`。模型自己的内建工具没有一个能问人。
2. **卡片上的标题会说谎。** 卡片只会说「有服务器在向你提问」；把它原样画在一张内建工具的卡上
   就是假话——没有服务器。
3. **提问就是它的目的，不是副作用。** 别的工具先做事再答话，审批拦的是"即将执行的动作"；
   `ask` 停下来本身就是功能。给一个提问工具挂审批栅栏，等于请人批准"去请他回答"。

## 决策

1. **一个工具、一次调用、一列问题。** 工具名 `ask`；参数 `questions` 是一列
   `{key, question, options?, multiple?, allow_other?}`。run 停一次、人填一张卡、答案一起回来。
2. **复用 elicitation 那条链。** park 的 reason 仍是 `:elicitation`，题面仍是
   `{:prompt :schema}`，客户端仍取 `GET /api/elicitation`、仍走 `resume` 回灌。
3. **谁是提问的人要说清。** 题面带 `:asked-by`（`ask` 恒为 `:model`；`cap.mcp` 的题面仍带
   `:server`），边把在场的那一个放进 `GET /api/elicitation` 的答案——**缺的键不出现，不是
   null**（客户端靠在场与否分辨"没人署名"与"服务器名叫 null"）。卡片标题据此分三种：
   服务器在问 / 模型在问 / 说不清是谁在问。`ag_ui` 的 interrupt 兜底话术同步改成中立的
   "You are being asked for input."——它不去猜谁在问，猜了就是第二份会错的答案。
4. **决定由能力自己消费，与 `cap.mcp` 同形。** 工具体 `parked-for-call` → `take-decision!`
   （恰好一次，重复穿越不会把同一份答案交两遍）：`:approved` 渲答案、`:vetoed` 答
   "The person declined to answer."、没有决定就 `suspend!`。拒绝不是失败，是一个模型能接着干的
   答案——与 MCP 把人的 no 折成给服务器的 `decline` 是同一条判断。**不进审批那条路**：
   不标 `:requires-approval`、无 `:park-reason`，缝的 `:approved`/`:vetoed` 分支对它不可达。
5. **答案是人话，不是 JSON dump。** 一行一题：`- <题面> -> <答案>`，题面是模型自己写的那句。
   三种状态三种话：填了 → 答案原文；多选一个没勾 → `(nothing chosen)`；没答 → `(no answer)`。
   后两者是两个不同的事实（"都不要" vs "没答"），不塌缩成一个缺席让模型去猜。
6. **候选、自己填、多选，全走客户端既有的字段规则，`ask` 不发明第二种画法。**
   - 有候选：`options` 原样变成 property 的 `enum`（客户端画成 select）。**逐字不转义不重排**
     ——候选是模型对一件事的叫法，答案是照着它对上来的。
   - 自己填是**明写的开关**：schema 上带扩展键 `x-allow-other`（JSON Schema 没有这个词，而
     权限属于表单）。没有那个键的服务器 elicitation **逐字与今天相同**——同一套字段规则给两家用，
     不能拿客户端的形状去改服务器的问题。
   - 多选是 `{type: "array", items: {type: "string", enum: …}}`：客户端把它认成 checkboxes。
     **值在 wire 上是一个数组，绝不是拼接的字符串**——候选里出现逗号，join-split 的往返就散了。
   - 多选的答案顺序按**候选的顺序**，不按手速；填的自己的话 append 在数组末尾。单选那边
     自己填的话**覆盖**点选（"点选与手填是同一个答案的两个来源，不是两个答案"）。
7. **表单没人答得对的，在问到人之前就拒。** 空列表、空白的候选、没写题面的、两条问题共用一个
   key、`multiple` 却没给 `options`——都在 `suspend!` 之前拒掉，把浪费花在模型那侧而不是
   让真人面对一张答不了的卡。裸字符串收下（单问时模型顺手就写的形状），缺 key 按
   位置派生 `q1`/`q2`。
8. **名册不数数。** `ask` 进 `register!` 的和里（基础能力、默认装上、不属于任何编辑家族——
   两种模式都服务它）；`tools.clj` 开头"十八个工具"那句改成以名册为准，写死的数下次加工具时
   就是在沉默里变旧的东西。

## 非目标

- 不新开 park reason、端点、卡片；不动审批路径与 `decide-approval!` 那半。
- 不做提问的历史面板、不做"暂停的问题列表"——卡片跟着 interrupt 走，答完即散。
- 不改 `lib/elicitation.ts` 里与本票无关的纯规则（MCP 那半的判定、`coerce` 的标量语义原样）。
- 服务器自定义 schema 里带 `x-allow-other` 的行为不另立规矩——键在场就照画，没有就照旧。

## 验收主线（全部兑现）

- 端到端（离线，scripted）：`ask` ⇒ 工具未执行、run 以 `:run/interrupt` 收尾、park reason 是
  `:elicitation`、帧过客户端严格校验；resume（`resolved`/`cancelled`）两条路各自落地；
  `GET /api/elicitation` 答得出题面、schema 与 `askedBy` 且没有 `server`。
- 两问一次 ⇒ 一条 interrupt、一张卡、两份答案都在工具结果里；决定恰好消费一次。
- 三种问法在真浏览器里点得动、填得进、答案看得见（九张截图 + 两份走查记录，见 `evidence/`）。
- 服务器 elicitation（无扩展键、无 array）渲染与 payload 逐字不变——`ui/test/suites/elicitation.ts`
  既有用例一条没改。

## 状态

**2026-09-19 落地。** 分支 `workbuddy/main-e0b4cd9a`，从 `main` @ `a3847ea` 切出、当日 rebase 到
`main` @ `c5e8940`。三张票按 01 → 02 → 03 走完。

## 基线

- 定向：`--ns harness.cap.ask-test,harness.cap.editing-mode-tools-test` → 27 tests / 157
  assertions，0 failures / 0 errors（其中 `ask-test` 7 例 48 断言）。
- 边缘两个被改的命名空间：`--ns harness.edge.ag-ui-test,harness.edge.http-test` → 82 / 787，
  0 failures。
- 前端：`--build`（tsc + vite）绿；`--ui` 50/50（`EXPECTED_CASES` 44 → 50）。
- 真浏览器走查：`node scripts/dev.mjs --scripted` 两轮（`walkthrough.json` / `walkthrough-0203.json`），
  截图与记录在 `evidence/README.md`（票 01）与 `evidence/README-02-03.md`（票 02/03）。
- **后端全量在本机从来不是绿的**（Windows 形状：路径分隔符、CRLF、hook 脚本）。改动前后
  各跑一遍：HEAD 基线 `903 / 11524，169 failures / 22 errors`，改动后 `903 / 11525，
  169 failures / 22 errors`——失败集合的差只有五条计时/IO 抖动，没有一条是本特征加的。
  证据与对照表：`evidence/backend-run.md`。

## 落地记录

### 01 — `ask` 停在卡上，答案回到工具结果

- `cap/tools.clj`：`ask` 能力（`ask-questions` 收形并拒坏表单、`ask-schema` / `ask-prompt` 造题面、
  `answer-for` / `answer-lines` 渲答案、`t-ask` 收决定），注册在 `web_search` 之后。
- `edge/http.clj`：`elicitation-get` 改 `cond->`——`server` / `askedBy` / `expiresAt`
  缺的不出现，多答 `askedBy`（`:asked-by` 的 `name`）。
- `edge/ag_ui.clj`：interrupt 兜底话术不再谎报服务器。
- 前端：`approval-gate.tsx` 抽出 `ElicitationCardTitle`（导出，专为渲成字符串再读）；
  标题三态按 `askedBy` / `server` 的在场判定；`locales/{zh,en}/approval.json` 的
  `elicitation.title` 改中立 + 新增 `elicitation.model`。
- 测试：新 `test/harness/cap/ask_test.clj`（7 例，走真 HTTP 边）；新
  `ui/test/suites/elicitation-card.tsx`（渲染断言）；`tools_test` / `editing_mode_tools_test`
  的两份名单各加 `ask`。
- **顺手补的一个洞**：`harness.test-runner/test-namespaces` 是手写名单，新测试文件不进去就
  永远不会被跑——`ask-test` 已补进名单（第 14 个）。**"文件都进来了吗"的守卫没加**，
  留待决定（`ui.test.ts` 的 `EXPECTED_CASES` 是前端那侧的同一格）。

### 02 — 候选 + 自己填

- 后端：`options` 原样组进 property 的 `enum`（中文与空格不转义、不重排，有用例钉住）；
  `allow_other` 落成 `x-allow-other`。
- 前端：`fieldSpecs` 读扩展键；单选 + 自己填时"自己填的话赢"（下拉自己清空），payload
  这个键只有一个值。
- 拒绝在先：`options` 不是列表、空列表、含空白候选，都在表单画出来之前拒。

### 03 — 多选

- 后端：`multiple` ⇒ `{type: "array", items: {enum}}`；`multiple` 无 `options` 拒。
- 前端：`inputKindFor` 认 `array` ⇒ `checkboxes`（**先查 array 再查 enum**——多选的
  `items` 也带 enum，顺序反了会被画成 select 丢掉所有答案只留一个）；`FieldValue` 是
  `string | readonly string[]`，绝不拼接；空数组是一个被发出去的答案，不是缺席。
- **渲染那一格只有真浏览器说得清**：四种形状同屏、点选/手填/多选/空答的证据与
  "它没证明什么"的如实清单，见 `evidence/README-02-03.md`。
