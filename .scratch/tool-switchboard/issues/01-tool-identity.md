# 01 — 门的形状是 ctx：身份是 id，名字只是模型那一侧的门

**What to build:** 两件事，一件换门、一件换身份，落在同一处：

- **门换成 ctx。** `install!` 不再收一张贡献 map，改收 `{:name .. :capability ..}` 和一个
  `(fn [ctx] ..)` 的 **setup**。层的作者只声明"做了什么"，不写反操作：归 ctx 管的声明口
  （tools 的 push/pop、disable、planner、narrow、tools-for、disabled-for）由 ctx 记账，
  **teardown 调用时 dispose 自动按登记的逆序撤**——后登记的先撤。不归 ctx 管的副作用
  （起一个连接、建一个目录）走 `ctx.useEffect`：登记即执行，返回的那个函数是它的清理，
  dispose 逆序调它们。
- **身份是 id。** 一个工具定义有两个身份，分开用：
  - `:name` —— **模型说的话**（`specs` 里那一项、一次调用回来的那张门）。**同一个名字在最终
    名册里只出得一份**——后装的那一份赢，模型永远看不见两个 `write`。
  - `:id` —— **harness 自己的身份**，`<能力>/<名字>`：`base/write`、`hashline/write`、
    `mcp/<服务器>/<名字>`、`session/<名字>`。**关掉、报归属、拒绝话术、审计一律用它。**
  id 在 **push 那一刻盖章**：ctx 记着自己层的能力名，push 进来的定义拿到
  `<capability>/<name>`；定义**自带 `:id` 时原样用**。id 是确定的（不是 UUID），所以
  卸了再装一个字不变。

从用户视角：勾掉"锚点编辑"时，被收走的是 `hashline/write` **那一份**，`write` 这个名字立刻回落到
`base/write`；"我关掉 `write`"这句话的准确含义是**"我关掉现在用它那一份"**——先解析出在用的是
哪一份，再按 id 收走它。而层的作者从此只写 setup：撤的顺序是 ctx 的事，不是他的记性。

**Blocked by:** None (can start immediately)

**Status:** claimed

## 现场

- **门今天收的是一张贡献 map，teardown 是门给的**：`install!` 收
  `{:name :tools :disable :planner :narrow :tools-for :disabled-for}`，conj 进 `@layers`、
  `recompute!` 重折一遍，返回按层 id 过滤的 teardown。层作者**不写 teardown**（撤的就是整层），
  但也**没有地方声明"我这层动过什么顺序"**——`recompute!` 只留最终那张表，谁后说的算全靠 map
  merge 的"后装赢"，撤的时候也没有逆序可言（层内没有多步副作用要撤；有副作用的——MCP 的
  连接——根本不住在这扇门里，靠自己的 `shutdown!` 与退出钩子）。
- **表按名字收**：`@registry` 是 `{name → tool}`（`kernel/tools.clj:62`）；折叠把各层的
  `:tools` merge（`:146` 起，后装赢）；`effective-tools` 基座与会话答案**同样按名字 merge**
  （`:333`，`(into @registry (session-tools thread-id))`，overlay 最后）。**一个名字下只留得下
  一份定义**——不是丢，是盖掉了；谁盖了谁、这一刻在用的是哪一份，都没有地方记。
- 取工具那一处就一行：`(get (effective-tools thread-id) name)`（`:852`，`run!` 里）；`specs`
  造的是 `{:function (assoc … :name n)}`（`:410`）。**模型那一侧只有名字**，所以"名字 → 在用的
  那一份"这个解析只能发生在 harness 这一侧。
- **关掉今天按名字**：`session-disable!`(`:241`) 的 overlay 里存**名字的集合**；`base-disabled`
  是 `name → 层名`（`:90`）；`disabled-message`(`:624`) 的 re-enable 提示也把**名字**塞回
  `session-enable!` 里。hooks 那扇门（`kernel/hooks.clj:509`）的 session-disable!
  **已经是按 id 的**——本仓有先例，工具表是跟进的那一个。
- **`:disable`（层的静态关停）在默认栈里一处没用**，`install_test/switching-a-name-off-…`
  钉着它的表现：可见、拒调用、拒话点名是哪一层。
- **MCP 的工具走 `:tools-for` 按会话给**（`cap/mcp.clj:1163`），答案里每份定义带 `:source :mcp`
  ——出处有记号，身份没有。
- **`session-register!`**(`:214`) 把会话自己的定义存进 overlay，同样没有 id。
- `harness.infra.log/warn!` 是仓库里报"过程没出错、但这不是它该有的样子"的那一处
  （`cap/jobs.clj:208`、`edge/http.clj:915` 是既有用法）；**kernel 可以 require 它**（infra 层）。
- 审计三件套 `:tool/pre-execute` / `:tool/execute` / `:tool/post-execute`（`kernel/event.clj:24`）
  只带 `:name`；jsonl 那侧（`edge/http.clj:520` `lifecycle-record`）照事件拼 `toolCallId` /
  `toolName`。
- **另一扇门不动**：`kernel/hooks` 的 install! 仍是贡献 map（`hooks.clj:395`）。本票只换
  tools 的门；hooks 门要不要跟，是另一张票的事，这里不预支。

## 决策

### 门：ctx 与它的口

- **`install!` 收 `{:name "mcp" :capability "mcp"}` 与一个 setup fn。** install! 建 ctx、跑
  setup、返回 **dispose**（仍是一个 fn，组合根照旧收进 stop fn，测试照旧 `try/finally` 里调）。
  **teardown 这个形状不变**，变的是它背后的事：不再是"按层 id 过滤重折"，而是"把这一层的账
  逆序撤掉"。
- **ctx 的口就是今天的全部声明口**，一个不多、一个不少：
  - `(tools/push ctx name def)` —— 登记一份定义，**登记即生效**（recompute），回去的是盖好章的
    id。dispose 时自动 pop。
  - `(tools/pop ctx id)` —— setup 中途收回自己刚 push 的一份（账上划掉）。主路径是不 push，
    pop 是给"push 了又反悔"留的对称口。
  - `(tools/disable ctx [id ..])` —— 撤掉一份定义（**id**，见下）；dispose 时这层的关停一起没。
  - `(tools/planner ctx f)` / `(tools/narrow ctx {:served? .. :refuse ..})` —— 单槽，后登记的
    赢；dispose 后回到前一层那份（折叠本来就是这么算的）。
  - `(tools/tools-for ctx f)` / `(tools/disabled-for ctx f)` —— 按会话现算的两口；dispose 摘掉。
- **不归 ctx 管的副作用走 `(tools/use-effect ctx (fn [] ..副作用.. (fn [] ..清理..)))`。**
  登记**即执行**（setup 就是那一刻），返回的清理由 ctx 记下，dispose 时**按登记的逆序**调用。
  这一口是给 MCP 连接这类东西预备的——它们的 cleanup 从此必然跟着层走，不再指望作者记得在
  别处收尾。
- **dispose 的契约：抛了记 warn、继续撤。** 一步清理抛异常，`log/warn!`
  （kind `:tools/dispose-error`，带层名与第几步）记下，**其余清理照跑、层照撤**——撤一半
  不许挡住另一半，关停与去勾都要保证能撤到底。teardown 调两次是 no-op（幂等照旧）。
- **push 即时生效**：setup 里 `push` 完，本层与更早层的名字立刻 resolve 得出来——"setup 里想
  收走名字现在那一份"要在 setup 那一刻答得出来，顺序这条律（基础先装、增强后装）保证它。
  recompute 的粒度从"每 install 一次"变成"每 push 一次"，量级不变（map 折叠）。

### 身份：id 与解析

- **id 由 push 盖章**：`<capability>/<name>`；def **自带 `:id` 原样用**（`mcp/<服务器>/<名字>`
  由 cap.mcp 自己盖，`session/<名字>` 由 `session-register!` 盖）；层没写 `:capability` 就回退
  用层名。**id 不随层的切法变**——基础那一套无论切成一层还是十层装，都是 `base/<name>`；
  层重装、再加一层，id 一个字不变。这是 id 有用的前提：它是**定义的身份**，不是"第几次装"的
  编号。
- **表按 id 收，折叠另产出到达顺序。** `@registry` 变成 `{id → def}`，折叠多产一份
  `:order [id ..]`（各层按安装顺序、层内按 push 顺序接起来）——"谁后说的算"靠它，map 自己
  说不出来。
- **解析 = 名字 → 在用的那一份，读口叫 `resolve`**（`clojure.core/resolve` 在本 ns exclude，
  调用方都走别名，不冲突）：`(resolve thread-id "write")` → `"hashline/write"` 或
  `"base/write"`。从**到达顺序的末尾往前**找第一份**这一刻在服务的**定义。查的池子按今天
  `effective-tools` 的同一顺序：overlay 的添加 → 会话来源（`:tools-for`）→ 静态表；名字在
  前面的池子有答案就不再往后看。
- **"在服务的"要跳过两样**：被按 id 收走的（层的 `:disable`、会话的 `session-disable!`），
  以及被收窄的（这个会话不服务这个名字——`:narrow` 的 `served?` 说不服务，整个名字跳过）。
  于是"收走最上面那一份"自然表现为**名字回落到更早的那一份**——这是回落，不是消失。
- **关掉说的是 id，收窄说的是名字**（本票的核心区分，写进 `session-disable!` 与
  `disabled-message` 两处 docstring）：

  | | 收的是什么 | 名字会回落吗 | 谁说这句话 |
  |---|---|---|---|
  | `:disable` / `session-disable!` | **id**（`hashline/write`） | **会**（回落到更早那份） | 层 / 会话 |
  | `:narrow`（编辑模式的收窄） | **名字**（`write`） | **不会**（这个名字这个会话没有） | 策略（`cap.editing`） |

  两者都对，而且必须分开：一个能力的 setup 说"这一份我收走了"是 id 的话；一个会话偏好说
  "这个会话不玩锚点编辑"是名字的话。**名字被收窄时不因为另一份在册就把它露出来**——那等于
  策略被人架空。
- **`session-disable!` / `session-enable!` / `session-disabled?` 只认 id**，overlay 里存的是
  **id 的集合**。`session-disable!` 给一个不在册的 id ⇒ 照旧 no-op（"禁用不会凭空造出一个
  工具"这条不变），**外加一条 warn**（kind `:tools/disable-unknown-id`）——"这串不是任何一份
  定义的身份，是不是把名字当 id 传了？"。这条守卫值它的价钱：本票自己就是"调用点从名字改成
  id"的迁移，最容易犯的错正是这个，而它今天的表现（静默 no-op）恰好什么都看不出来。
- **缝的拒绝顺序换算成 id 的语言，行为逐字不变**：`run!` 先 `resolve`；解出来 ⇒ 直接往下走
  （缺参 → 审批 → gate；disabled/unserved 两关对解出来的那份**不可能再中**——resolve 跳过的
  正是它们）；解不出来但名字在册 ⇒ 看最上面那份的定义：它的 id 被关掉 ⇒ `:disabled`
  （`disabled-message`，它今天就会在同时不服务时把 unserved 那句接上——"两件事都说了"这条
  既有断言原样保住）；没被关掉 ⇒ `:unserved`（收窄）；名字根本不在 ⇒ `:unknown-tool`。
  **"disable 压过 mode"那条钉子**（`a-call-that-is-disabled-and-unserved-says-both`）因此
  原样成立：会话的关停记在最上面那份的 id 上，最上面的那份说了算。
- **`disabled-message` 的 re-enable 提示给 id 不给名字**：名字喂回 `session-enable!` 现在会
  warn，教模型一条会响的调用是拒绝话术自己的病。被收走那一份的 id、谁收的、拿什么替代，
  三样都要在话里；`cap.editing` 那句按名字的收窄话术照旧（收窄走的是名字那条路）。
- **`resolve` 是给能力自己的 setup 用的**：setup 里想说"把名字现在那一份收走"时写
  `(tools/disable ctx [(tools/resolve nil "write")])`——**不必把 `base/write` 写死**。写死的
  代价：基础那一套哪天换了 id 命名空间，接管就**静默失效**，而失效的样子与"没接管成功"长得
  一模一样。顺序这条律保证 setup 那一刻答得出来。**默认栈今天用不到这一句**：hashline 的
  接管按会话现算（见 02），基座那份留着正好让 str-replace 会话回落。
- **归属问的是 id**：`tools/owner-for thread-id name` 答**在服务那一份的 id**
  （`"hashline/write"`）。设置页那一栏、拒绝话术、审计行共用它，于是"谁盖了谁"精确到那一份，
  不是精确到一层。02 在折叠里加 `:owners`（id → 层名），那是"这一层叫什么"的另一半。
- **两种冲突，两种说法**，本票管第二种：
  1. ~~同一个名字、不同 id（接管）~~ —— 声明（`:replaces`）与那声警告归 02。
  2. **同一个 id 被两层供 ⇒ 撞车，push 时响一声**（kind `:tools/duplicate-id`，说清 id 与两家
     层名）。id 就是身份，重复就是错；行为照旧——一个 id 一个槽，后到的把在册的那份换下来，
     `specs` 里仍然只有一项。警告不改行为，只让人看见。
- **模型那一侧一个字不改。** `specs` 仍然一个名字一项（同名的多份里只出在用的那一份——从
  到达顺序解析出来）、调用仍然只带名字、**hooks 的匹配面仍然是名字**（规则是人写的，人说的
  就是名字）。id 只进内部与审计。
- **审计多一个 `:tool-id`**：`:tool/pre-execute` / `:tool/execute` / `:tool/post-execute` 带上
  跑的是哪一份（名字留着），jsonl 行相应多 `toolId`，与 `resolve` 的答案一致——不然一份审计行
  只说"跑了 `write`"，说不清跑的是哪张脸。unknown-tool 那条没有 id，键缺席。
- **调用点跟着改成 id**（不许删断言了事）：`install_test`（`:disable` 写 id、registry 读 id）、
  `session_tools_test`（关停先 `resolve`）、`tools_test` / `editing_mode_tools_test`（同）、
  eval 脸的 toggle 片段（`session_tools_test` 里 eval 执行的那句）改成先 `resolve` 再
  disable；`cap.tools` 的 install! 换成 setup（`:capability "base"`、逐个 push、planner/narrow
  各一口）；`cap.mcp` 的 install! 换成 setup（tools-for 答出的定义由它自己盖
  `mcp/<服务器>/<名字>` 的 id）；`session-register!` 给没有 `:id` 的定义盖 `session/<name>`。
- **不做**：MCP id 命名的更多细则（带 `:id` 就原样用，建议形状一个）；hooks 门的 ctx 化；
  编辑模式那套的**策略**（继续按名字说话）；协议、前端、设置页（02/03）；
  `:replaces` / `:owners` / 接管警告（02）。落点：`harness.kernel.tools` 一处为主（ctx、表、
  解析、归属、拒绝话），能力那一侧的调用点跟着改（`cap/tools`、`cap/mcp`）。

## 验收

- [ ] **ctx 的账**：setup 里 push 两份、use-effect 一件（登记即执行，有顺序记录）⇒ teardown
      调用后：两份定义都不在、清理**逆序**跑过、表回到装之前逐字相同
- [ ] **清理抛了也撤到底**：use-effect 的清理抛异常 ⇒ 一条 `:tools/dispose-error` warn，
      其余清理照跑、定义照撤、teardown 再调一次 no-op
- [ ] **push 即时生效**：setup 里 push 之后立刻 `(resolve nil <名字>)` 能答出本层的 id
- [ ] **同名的两份**：一层 push `base/widget`、后一层 push `enh/widget` ⇒ `specs` 里 `widget`
      **只有一项**、跑的是后者；撤掉后一层 ⇒ 名字回到 `base/widget`（回落这条是"名字 → 在用的
      那一份"最硬的钉子）
- [ ] **按 id 收**：`(session-disable! t (resolve t "widget"))` ⇒ 收走的是后装那一份（这就是
      "先解析出在用的是哪一份，再按 id 收走"），同一个 `resolve` 下一次答 `base/widget`，
      `widget` 照常跑基座那份，**没有任何一句话把 `widget` 说成"不在"**（回落不是消失）
- [ ] **按 id 收不会误伤**：`session-disable! t "base/widget"` 而 enh 那份在 ⇒ `widget` 照样由
      `enh/widget` 服务（关掉的是一份定义，不是一个名字）
- [ ] **按名字收窄不回落**：一个会话不服务 `widget` ⇒ 该会话 `specs` 里没有它，**哪怕
      `base/widget` 还在册**
- [ ] **拿名字当 id 传** ⇒ 一条 `:tools/disable-unknown-id` warn，行为仍是 no-op
- [ ] **同一个 id 被两层供** ⇒ push 那一刻一条 `:tools/duplicate-id` warn，说清 id 与两家层名；
      `specs` 里仍然只有一项，跑的是后到的
- [ ] **id 稳定且幂等**：装着卸、卸了再装，`base/write` 这个 id 一个字不变；重装两次，
      `specs` 与 id 一模一样
- [ ] **owner-for 答 id**：装 enh 后 `(owner-for t "widget")` 答 `"enh/widget"`，撤掉后答
      `"base/widget"`
- [ ] **模型那一侧没变**：`specs` 的每一项仍只有 `name` / `description` / `parameters`
      （**没有 id**）；provider 的调用形状与 hooks 的匹配面一字不改
- [ ] **审计带 `tool_id`**：三条 lifecycle 事件的 jsonl 行多 `toolId`，与 `resolve` 的答案一致；
      unknown-tool 那条不带
- [ ] **拒绝话点名 id**：被收走那一份、谁收的、拿什么替代；`disabled-message` 的
      `session-enable!` 提示里是 id；`cap.editing` 那句按名字的收窄话术照旧
- [ ] **两个既有钉子原样成立**：`switching-a-name-off-keeps-it-visible…`（关停可见、拒调用、
      拒话点名层，`:disable` 改写 id）与 `a-call-that-is-disabled-and-unserved-says-both`
      （disable 压过 mode，两件事都说了）
- [ ] 调用点迁移齐：`install_test` / `session_tools_test` / `tools_test` /
      `editing_mode_tools_test` 改 id；eval 脸的 toggle 片段先 resolve 再 disable；
      `cap.tools` / `cap.mcp` 的 install! 换成 setup
- [ ] `node scripts/test.mjs --backend` 全绿（本机基线差照 ask-tool 的先例记录在
      `.scratch/tool-switchboard/evidence/`）
