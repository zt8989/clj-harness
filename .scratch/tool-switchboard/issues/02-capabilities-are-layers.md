# 02 — 能力就是层：名册是算出来的，装上是 setup、去掉是 dispose

**What to build:** 本 harness 手里的工具**不是一份写下来的清单，是算出来的**：按优先级把一层层
能力装上去，折叠出工具表。基础能力先装（`bash` / `read` / `edit` / `write` / `glob` / `grep` /
`web_fetch` / `web_search` / `todo_write` …，`eval` 默认不装），增强能力后装（hashline 锚点
编辑）。**一次 `install!` 就是那一层的 setup，它返回的 dispose 就是卸下**；去掉勾选 = dispose =
这一层带来的一切一起消失——包括它**对别的能力**做的事：hashline 在时 `edit` 不被服务，hashline
一卸 `edit` 自己回来，**没有任何一处配置写着"edit 开"**。

**同名是两份定义**：一个名字（模型那一侧的门）下可以站着多份完整定义，**后装的那一份算**——
hashline 的 `write` 就是这样压在 `base/write` 上的，卸掉它、名字自己回落到基座那份（01 的
`resolve` 与回落是这里的地基）。两份定义各有自己的 **id**（`base/write` / `hashline/write`），
**收走与接管一律按 id 说**。**接管要声明**：层得说出它接管哪些 id，push 时没声明就**响一声
warning**（推荐写法：**先声明把名字从旧的那份手里收过来、再 push 自己那份**；要连旧的那份
一起收走，就 `(tools/disable ctx [(tools/resolve nil "write")])`），**行为一个字不改**，只是
让人看见——否则"谁盖了谁"只有读代码的人知道。

从用户视角：勾上"锚点编辑"，`edit` 就没了、`read` 的每一行长出锚点；去掉那个勾，`edit` 自己
回来。而层作者写的是**一个 setup**：`push` 自己的定义、声明接管、把带副作用的活交给
`use-effect`——撤的时候 ctx 逆序 dispose，不靠他记性。

**Blocked by:** 01（ctx 门与 id 是这一票的语言）

**Status:** claimed

## 现场

- **门今天收贡献 map**（01 正在换成 ctx）：`install!` 收
  `{:name :tools :disable :planner :narrow :tools-for :disabled-for}`，conj 进 `@layers`、
  `recompute!` 重折。`:tools` 后装赢；`:planner` 与 `:narrow` 是**单格后装赢**；teardown 按
  层身份撤、重折。
- 组合根今天装四层：`cap-tools/install!`、`cap-hooks/install!`、`system-prompt/install!`、
  `cap-mcp/install!`（`edge/http.clj:2471`），teardown 都收进 stop fn（"一百个服务器留下
  一百层"那句注释说的正是这个）。
- **可内建工具是一层整装的**：`cap/tools.clj:1338` 把 `@built-ins`（`register!` 攒下的全部
  名字）**整张**、连同 `replace/plan-turn` 与 `cap.editing` 的收窄，作为一层 `"built-ins"`
  装进去。名字之间没有优先级，也没有"谁属于谁"。
- **模式是运行期读出来的**：`cap/tools.clj` 四处在工具体与描述里
  `(if (= :hashline (:mode (editing/editing-mode thread-id))) …)`——`read`/`write` 的"脸"和
  描述靠这个分支换；`cap.editing/served?` 又拿同一个 mode 判"这个名字服不服务"，`families`
  是那张名字表。逐处点名（实现者不必自己找）：
  - `cap/tools.clj` `t-read` 的体：hashline 走 `serve/read!`（快照 + 锚点），否则 `slurp`。
  - `t-write` 的体：hashline 走 `hashline.write/perform!`（释放锚点、清撤销记录、回锚点行），
    否则 `write-file!`。
  - `read` 的 `:describe`：两套**描述 + 参数**（锚点那张多 `offset`/`limit`）。
  - `write-face`：参数一样，**文字**两套。
  - `cap/editing.clj` 的 `served?` —— 成员判定本身。
  四把锚点工具的体只把这份配置当参数读，整块属于 hashline 那一层。
  **`read` / `write` 的名字只有一个**，增强层贡献的是**同名定义**（后装赢），所以基座那份在
  dispose 之后自动回来，模型也不会看见两个 `read`。
- **两套实现之间其实没有共用面**，除了注册名：plain 的 `read` 就是 `slurp`，锚点的 `read` 是
  `serve/read!` 那条链（`reading` 的文本判定 + `anchors` + `store`），两者的参数表与描述也是
  各自的。真正共用的只有三样**周边**：`project/resolve-path`（会话内重定根，`cap.project`，
  公开）、审批栅栏，以及"两条脸都过栅栏"这条规矩。栅栏那两个函数（`fence` 与 `fenced-path`）
  **今天是 `cap.tools` 的私有函数**——分开实现时两边都要它，得挪到两边都够得着的地方
  （建议 `cap.project`：项目边界本来就是它的题目）。
- **"hashline 在起作用"今天一半是函数体里的 if、一半是配置值**，而不是一层：没有一层能在
  setup 时撤掉 `edit`，也就没有 dispose 能让它回来。
- **`read` / `write` 的接管只能是现算的**：模式是**会话偏好**（项目级
  `harness.edn :editing {:mode :str-replace}` 可以让一个会话用精确字符串编辑、要 `edit` 回来），
  所以 hashline 把锚点那张脸放进静态 push 就等于钉给所有会话——它必须走 `tools-for`
  按会话答；基座那份 plain 定义原地不动，会话的答案落在它之后。
- **config.edn 的顶层分节是严格校验的**（`#{:default :providers}`），加 `:tools` 要连失败话术
  与用例一起改。
- `specs` 按 `served?` 过滤（`kernel/tools.clj`）；被 `:disable` 的 **id** 留在表里
  （"deliberately no :removed"）。`cap.editing` 的 ns 注释明写了它为什么**只对编辑**推翻
  这条、拿什么付账（按名拒绝 + 说替代 + 说哪个键换回来）。
- **eval 默认停用会改写既有断言**：`tools_test/specs-expose-every-base-tool` 的两份名单，
  以及 eval-self-extension / tool-toggles / hook-engine 里走真调用形态的用例。

## 决策

- **一个能力 = 一层；目录是唯一的名册来源。** 目录（能力名 → 属于哪一组、默认装不装、setup
  贡献什么）住新 ns（建议 `harness.cap.tool-switch`），组合根按优先级逐层 `install!`，返回的
  dispose 全收进 stop fn。`@built-ins` 那张整表不再整张装进去——`register!` 攒的仍是
  `name → def`，装的时候**按目录逐个能力 push**（一个能力一个 setup），关掉的能力不 push。
  代价说清：启动多出十几次装（每次重折一遍表，量级是 map 折叠，可忽略）；换来的是
  **"去勾"只有一种语义**（dispose 那一个 ctx）。
  - **`config.edn :tools` 里的一格是一个"能力名"，不是工具名。** 基础能力的能力名就是它的
    工具名（`bash`、`edit`、`eval` …，一格一个）；增强能力的能力名是**那一层的名字**
    （`hashline`，**一格**，它的四个工具名不是独立能力、不在 `:tools` 里各占一格）。
    `anchor_grep` 那个既有的会话旋钮（harness.edn 的 `:editing {:anchor-grep false}`）
    **保留**，页面不列它——它是会话偏好，不是装不装。
- **config.edn 三节**：顶层新开 `:tools`（能力名的集合/列表，写"哪些能力装上"）；默认档在
  **代码里**（除 `eval` 外全装），家里没有这个文件、没有这一节，答案一样。校验的失败话术
  与用例跟着改成**三节**（`:default` / `:providers` / `:tools`）。派生的结果（`edit` 在不在、
  `read` 用哪张脸）**一个字都不许写进配置**；开关写盘只动 `:tools` 那一节，
  `:default` / `:providers` 写前写后逐字相同。
- **两套实现分开写，只共用注册名**（2026-09-19 定）。两个 `read` 不共用一个体、一个
  `:describe`：基座那份是 plain（`slurp`，参数只有 `path`），hashline 那份是它自己的
  （`serve/read!`，参数多 `offset`/`limit`），各自带描述、参数与 `:park-reason`；`write`
  同理（基座 `write-file!` / hashline `hashline.write/perform!`）。**同一个名字、两份完整
  定义，靠层的折叠后装赢**——`specs` 里最终只有一份。要做的搬迁：删掉 `cap/tools.clj` 的
  `t-read` / `t-write` 里的 mode 分支、`read` 的 `:describe`、`write-face`；`anchored-read`
  与四把锚点工具的注册体搬去 `harness.cap.hashline.*` 那边的层；`cap.tools` 只留 plain 那两份
  与其余基础工具。四把锚点工具本来就是独立实现，只是今天顺手注册在同一个 ns 里。
  - **hashline 这一层的工具全走 `tools-for` 按会话给**（四把锚点 + 两张锚点脸），静态 push 里
    它基本什么都不放——理由见上（模式是会话偏好）：锚点会话答 `{"read" 锚点版 "write" 锚点版
    + 四把锚点}`，str-replace 会话答 `{}`（于是基座那两张 plain 的脸与 `edit` 原样透出来，
    也不需要 `:narrow` 再替这四把名字挡一道）。断言里逐会话跑一遍。
  - **`fence` / `fenced-path` 挪到 `cap.project`**，两边 require 它；"两条脸都过栅栏"的
    行为不变，既有用例一条不改地过。
- **`cap.editing` 从"运行期的模式判断"退成"hashline 那一层的策略"**：`families`（名字 →
  话术）仍是数据，`served?` / `unserved-message` 成为 hashline 那一层经 `tools/narrow`
  贡献的口（这个会话不服务 `edit`，话照旧）；名单那一半不需要屏蔽（上一条：那四把锚点
  本来就只有锚点会话才答）。`:anchor-grep false` 也只是同一个函数少答一个名字。读
  `harness.edn` 两级 `:editing` 与那套校验（坏块按名失败、未知键按名失败）**留在
  `cap.editing`**——那是会话偏好，不是这一层的内容。
- **优先级 = 安装顺序。** 基础能力在前、增强能力在后；"后装赢"因此让增强层盖过同名的基础
  定义，也能撤掉一个基础名字（`edit`）。三处供给最后都落到同一条规则上：静态的在折叠里
  （push 的表，后装赢）、每会话的在 `effective-tools` 里（基座先、会话答案后）、现算的由
  `tools-for` 按会话给；三处都按 **id** 记账，名字只是解析的入口。
- **接管要声明，不声明就响一声（2026-09-19 定）。** 层点名它要接管的 id：
  `(tools/replaces ctx ["base/read" "base/write"])`。push 装配时算两条，只有这两条：
  1. 这一层**静态 push** 的 id 里，有哪个的名字**已经有人供着**、而它没在 `:replaces` 里
     声明 ⇒ warn。话里必须有两个 id（`hashline/write` 接管 `base/write`）与那个共同的名字、
     装它的层，以及补救——"若是有意接管，先 `(tools/replaces ctx ["base/write"])` 再 push
     自己那份"；**要连旧的那份一起收走**，`(tools/disable ctx [(tools/resolve nil "write")])`
     （或者直接把 id 写上）。这就是"先禁用再安装"的落地形状：**先把名字从旧的那份手里收过来，
     再落自己的定义**。hashline 今天不需要后半句——它的接管按会话现算，基座那份留着正好让
     str-replace 会话回落（见下一条）。
  2. 一层**没有 `tools-for`** 时，它声明的 id 必须都能在静态表里找到；找不到 ⇒ warn（声明里
     写错了 id 是常见手滑，push 那一刻正好抓得住）。有 `tools-for` 的层，声明里那些不静态
     供的 id 算**现算接管**，装配时不查——查不了，见下一条。
  **行为一个字不改**：该后装赢还是后装赢。这条守卫只让人看见，不替人决定。**默认栈零
  warn**：hashline 声明了 `read`/`write`（现算供），基座各能力名字互不相同——今天那条
  "静态同名没声明"的警告在默认栈里**不会被触发**（hashline 的接管是现算那一半），它一半是
  为下一层写的、一半是给假层用例钉住的，别把它当成今天就有用户在跑的路径。
  判断做成**纯函数**（层 + 当前账 → 一串要报的话），push 只负责把它交给
  `harness.infra.log/warn!`（kind `:tools/taken-over`，`key=value` 照 `log/context-str`
  那套）：这样"会不会响、响的是什么"在用例里不必抓日志就能断言，日志那一条只补一次真调用。
- **现算的接管由声明 + 该能力自己的用例钉住。** `tools-for` 是函数，装配时问不出它将来会
  答出哪些工具。所以规则写成"**它答出来的那些定义、id 必须都在 `:replaces` 里**"，钉在
  hashline 自己的用例中（逐会话跑一遍）。这是这条守卫的**边界**，写进 docstring——不许假装
  装配那一步查到了。
- **`replaces` / `disable` / `narrow` 三件事的分工按 01 那张表来**：`:replaces` 是**接管**
  （本层给替代，名字还在、换了供它的那一份）；`:disable` 是**撤掉一份定义**（**id**，不给
  替代——名字会回落到更早那份，没人接手才轮到"这个名字不在服务"那句）；`:narrow` 是
  **这个会话不服务一个名字**（不可见、按名拒、拒话给替代与键）。`edit` 走**第三条**，
  因为它撤不撤是**每会话现算**的（项目级 `harness.edn` 可以选回 str-replace），静态的
  `:disable` 说不这句话。
- **副作用属于层的 setup，撤销属于 dispose。** hashline 这一层装上时带来三样：四把锚点工具、
  `read`/`write` 的锚点脸、一笔提交的 planner；外加一样**对别人的副作用**：`edit` 不被服务。
  卸下它，四样一起没。ctx 让这条靠得住：`tools-for` / `narrow` / planner / 锚点定义的 push
  各是一笔记账，dispose 逆序全撤。02 在折叠里加 **`:owners`**（id → 层名，随层名一起留
  `tools-for` / `disabled-for`），`owner-for`（01 的读口）在静态那份之上叠每会话那份——
  设置页"由哪一层提供"与被撤名字的拒绝话术共用这一份，不许各写一遍判断。
- **不服务 vs 停用 vs 没装上：`edit` 走"不服务"**——不进 `specs`、调用按名拒、拒绝里说清拿
  什么替代、怎么换回来（`cap.editing/unserved-message` 今天的三个短语照留）。理由写在
  `cap.editing` 的 ns 注释里，本票照用：看得见就会去试。变的是**理由**：从"配置里的 mode 是
  hashline"变成"hashline 那一层把它撤了"；而它留在这一条路上、不进静态 `:disable`，是因为
  它撤不撤是每会话现算的。
- **`eval` 不是被停用，是没被装上**：它不在默认栈里，因此不在表里；调用它按名拒，话说清是
  哪个开关（`config.edn :tools`）、怎么写回来。**不是** `unknown tool`，也不说成"被某一层
  停用"。拒绝话由目录答——目录知道"eval 是一个能力、这格现在关着"。
- **进程开关压过会话偏好。** 进程没装 hashline ⇒ 任何会话都没有它，拒绝要指名"这个进程把它
  关掉了"（不能说成"你的项目没选它"）；装了而某个会话自己选了 str-replace，那不是冲突，
  是那一层的 `tools-for` 在该会话里让位。**四种组合**（装了/没装 × 两种 mode）的名单与拒绝
  各一条断言。
- **MCP 不由这一节管**（服务器级启停自己有路：`disabled-for` / `tools-for`）；一个名字不在
  目录里 ⇒ 默认服务。MCP 层自己把 `mcp/<服务器>/<名字>` 的 id 盖在 `tools-for` 答出的定义上
  （01）。
- **既有 eval 用例的处置**：夹具把它们所在的 home 配成装 `eval`（config.edn `:tools` 加一格），
  或在用例里写一句配置——**不许**把断言删掉了事。`specs-expose-every-base-tool` 的两份名单
  按默认栈改写（`eval` 不在里面），并各加一条"装上它就在里面"。
- **三条正交的路一个不动**：进程级 `:disable`（看得见、拒调用）、会话级 `session-disable!`、
  以及这一票动的"没装上 / 不服务"。三条的"怎么说"仍要分得清，一个名字被两条理由关掉时
  两条都要说得出来。`:replaces` **不是第四条"不在"的路**——名字还在，只是换了供它的人；
  它带来的是归属与那一声警告。
- **不做**：MCP id 命名细则；hooks 门的 ctx 化；协议、前端、设置页（03）；
  词汇与文档（05）。落点：目录与开关解析在 `harness.cap.tool-switch`（新）；层化搬迁在
  `cap/tools.clj` 与 `harness.cap.hashline.*`；config 校验与写盘在原处跟着三节改。

## 验收

- [ ] 默认栈（家里什么也没说）：`tools/specs` 里**没有** `eval`、其它内建名字都在；调用
      `eval` 按名拒，拒绝说清是哪个开关、键在哪、怎么写回来；**不是** `unknown tool`，
      也不说成"被某一层停用"
- [ ] 装一次 hashline：`edit` 不在 `specs`、四把锚点工具在、`read` 的答案是锚点行、planner
      是 `plan-turn`；**卸掉它**（调那次 `install!` 返回的 dispose）⇒ 工具表与"从来没装过
      hashline"**逐字相同**（名单、`read` 的答案、planner 全对一遍）——这是"算出来的"最硬的
      一条钉子
- [ ] 同一个能力装两遍再卸两遍：幂等，名单不变（照 `kernel/install_test.clj` 既有的 teardown
      用例写）
- [ ] **两套实现真的分开了**：`cap/tools.clj` 里 `editing/editing-mode` 与 `(= :hashline`
      **一处不剩**（grep 断言）；hashline 的 `read` / `write` 与四把锚点工具的定义与注册都在
      `harness.cap.hashline.*`
- [ ] **搬走不是改写**：两条 `read` 脸的描述、参数、`:required` 与今天逐字相同（搬迁前后各读
      一遍比对），`write` 的两个描述同理；`read` / `write` 仍然各只有一个名字（`specs` 里
      不出现第二份）
- [ ] hashline 的 `read` / `write` 各自带 `:park-reason`：项目外的路径两条脸都照样 park
      （两种模式各一条用例）
- [ ] 后装赢：造假的两层（基础 + 增强）push 同名定义（`base/widget` / `enh/widget`），
      增强在时名字归它，dispose 之后名字回到 `base/widget`
- [ ] **接管要声明**：造假的两层，B push 了同名的 `enh/widget` 而**没声明** ⇒ push 的那一刻
      响一条，话里有**两个 id**（`enh/widget` 接管 `base/widget`）、那个共同的名字与装它的层，
      而这一刻名字归 B——**警告不改行为**；把 `(tools/replaces ctx ["base/widget"])` 补上再
      装 ⇒ **零警告**
- [ ] 声明里写了没人供的 id（一层没有 `tools-for`）⇒ 也响一条，说这个 id 没人供、声明大概是
      写错了
- [ ] 装一遍默认栈（基座各能力 + hashline）⇒ **一条警告都没有**
- [ ] 归属算得对：只有基座时 `owner-for t "read"` 答 `"base/read"`；装了 hashline 之后，
      **锚点会话**答 `"hashline/read"`（它的 `tools-for` 供了那张脸）、**str-replace 会话**答
      `"base/read"`；卸掉 hashline 之后两边都答 `"base/read"`（`:owners` 与每会话那份都是从
      层栈重算的，不是记下来的）
- [ ] hashline 的 `:replaces` 与它 `tools-for` 真答出来的那些定义对得上（逐会话跑：锚点会话答
      `hashline/read`、`hashline/write` + 四把锚点，str-replace 会话答 `{}`）——这条就是
      "现算那一半查不了"的替代品
- [ ] 去勾 hashline ⇒ `edit` **不问配置就回来了**：config.edn 的 `:tools` 只列基础能力，
      装卸各跑一遍，配置文件**前后逐字相同**；勾上 hashline ⇒ `:tools` 里多的**只有
      `hashline` 这一格**，`replace` / `insert` / `anchor_grep` / `undo_last_replace` 一个
      都不在里面
- [ ] 会话偏好仍生效：项目级 `harness.edn :editing {:mode :str-replace}` ⇒ 那个会话里
      `edit` 在、锚点四把不在，另一个会话不受影响；**四种组合**（装了/没装 × 两种 mode）的
      名单与拒绝各一条断言，且"这个进程把它关掉了"与"你的项目没选它"两句分得开
- [ ] 编辑家族既有的**行为**断言一条不改（`editing-mode-tools-test`）：话术里"拿什么替代、
      哪个键换回来"两句逐字留，新增的只是"谁关的"
- [ ] config.edn 顶层多一个键 ⇒ 失败话术说的是**三节**（`:default` / `:providers` / `:tools`）
- [ ] `tools_test/specs-expose-every-base-tool` 的两份名单按默认栈改写（`eval` 不在里面），
      并各加一条"装上它就在里面"
- [ ] 开关写盘不许动别的节：`:default` / `:providers` 写前写后**逐字相同**
- [ ] `node scripts/test.mjs --backend` 全绿（本机基线差照先例记录）
