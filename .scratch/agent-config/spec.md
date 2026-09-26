# spec: agent-config（按层分的主 agent 与子 agent 配置）

给"谁手里有什么工具、什么 hooks"一个**按 agent 种类分家、按三层分**的配置。

- **agent 种类** —— **主 agent** 一个，**子 agent** 每个一份。种类是**线程的一个事实**（`sessions.subagent`
  为空 ⇒ 主 agent，否则就是那个名字），不是新机制，也不需要谁"记着现在是谁在跑"。
- **三层**（从下往上装，每层可以盖住上一层的同名定义）：
  1. **基础层**：逐个工具勾选（`read` / `write` / `bash` …），加这一层的基础 hooks——**指令文件（AGENTS.md）的注入就是其中一条内建行**（见 14）。
  2. **增强层**：**一组一选**，比如编辑那一套（`hashline` / `str-replace`）。组合里的名字能看见，但没有各自的格。
  3. **复合层**：skill 与 MCP，**最后提供工具**。技能可以带 command 与 hooks，在**用户发送**的时候注入。

配置写进 `config.edn` 的第四节 `:agents`；设置面板多一页「Agents」，主 agent 一个 tab、每个子 agent 一个 tab，
每页三层分栏。它**取代**子 agent 的 `baseline`/`exclude` 与 `harness.edn :editing {:mode}`，
并**配合**已有的可见性三态（在服务 / 不在服务 / 被关掉）把结果如实说出来。

## 决策

1. **两个维度合起来才是"谁有什么"：agent 种类 × 层。** 层是同一份配置里的三段，**从下往上装**：
   基础 → 增强 → 复合，后装的同名定义盖住先装的（"增强"二字就是这个意思：它在基础之上接手一个名字，
   而不是加一个新名字）。三层都只是**同一份配置的三个区段**，不是一个新门。今天 `kernel/tools` 的层叠
   （`install!` 的贡献 map、`@layers`、后装赢、`:narrow`、`:tools-for`、`:disabled-for`、会话 overlay）
   一个字不改——本特性只决定**每一层往里放什么**。

2. **配置住在 `config.edn` 的第四节 `:agents`**（与 `:default` / `:providers` / `:ui` 并列）：

   ```clojure
   {:agents
    {:main     {:base    {:tools ["read" "write" "bash" "glob" "grep" "skill" "agent" ...]
                          :hooks {:off ["notify-on-stop"]}}
                :enhance {:editing :hashline}
                :compose {:skills {:load ["review"]}
                          :mcp    {:servers ["playwright"]}}}
     :subagents
     {"explore" {:description "…" :read-only? true
                 :base    {:tools ["read" "glob" "grep" "web_search" "skill"]}
                 :enhance {:editing :str-replace}
                 :compose {:mcp {:servers []}}}
      "general" {:description "…"
                 :base    {:tools [...]}
                 :enhance {:editing :hashline}
                 :compose {}}}}}
   ```

   - `:base :tools` 是**能力名**的集合，**一格一个名字**；缺席 ⇒ 代码里的默认档（见 6）。
   - `:enhance` 是**一组一选**：今天是 `:editing`，取值 `:hashline` / `:str-replace`。再多一组是加一行。
   - `:compose` 是复合层：`:skills :load`（哪些技能的正文在用户发送时注入）与 `:mcp :servers`
     （用哪些**已声明**的服务器）。
   - 顶层由**闭集变四节**：`providers/config-sections`（`src/harness/cap/providers.clj:433`）加上 `:agents`，
     那句"三节"的失败话术（`:461`）与 `written-header`（`:1854`）一起改。

3. **声明与选择分开。** `hooks.edn` / `mcp.edn` / `SKILL.md` 仍然是**声明**住的地方；`:agents` 只说**选哪些**。
   理由：设置表单**整份重写** `config.edn`（EDN 留不住注释），把可执行的 hook 命令行与 MCP 启动命令搬进
   一个会被表单重写的文件，等于让表单成了那些文本的主人；而"一份声明、多处选择"本来就是今天 `hooks.edn`
   ＋每会话开关的形状（`cap/hooks.clj`、`cap/mcp.clj`）。

4. **`harness.edn :editing` 拆成两半，各自归层；其余编辑旋钮留原处。** `harness.edn :editing`
   （`src/harness/cap/editing.clj:74` 起）今天装三样东西：
   - `:mode` —— **进增强层**（`:enhance :editing`）。留着它的家**按名拒绝**，话里指向
     `config.edn` 的 `:agents .. :enhance :editing`。
   - `:grep` —— **进基础层的工具格**（`grep` 是一个基础能力）。它今天的作用正是"把这个名字从工具表里拿掉"，
     那就是基础层在管的事。
   - `:require-path` / `:strict-input` / `:boundary-dedup` / `:diff-context-lines` —— **留原处**。
     它们是"一个工具怎么做事"（会话偏好），不是"装哪一套"。
   - 这一条**推翻 `tool-switchboard/03` 的那格决定**（那里决定 `:editing {:mode}` 继续是会话偏好、
     页面只读显示）。理由：本特性要的"增强层＝一组一选"，编辑那一套正是这个形状的**样例**；
     留两处真相（页面写 config.edn、项目写 harness.edn）比丢掉一个按项目分档的能力更坏。

5. **`config.edn` 只有家这一层；项目级的覆盖走 `harness.edn`。** `home/config-file` 就是家目录那一份，
   没有项目级；而 `harness.edn` 有 user ⊕ project 两级（`home/config-files`，`infra/home.clj:343`）。
   所以 `:agents` 允许 `<项目>/.harness/harness.edn :agents` 覆盖**同名键**（project 胜，与 `hooks.edn`
   的整键替换同一规则）。**这一条是本次新增的判断**，为的是不丢掉"两个项目各选一种编辑模式"这个今天成立的
   能力（`CONTEXT.md` 的**编辑模式**词条明写"按会话解析"）。不认同就把 02 验收里项目覆盖那几条删掉。

6. **默认档在代码里，不在文件里。** 家里没有 `config.edn`、`:agents` 缺一个键 ⇒ 答案与今天**逐字相同**：
   主 agent 装上除 `eval` 外的全部基础能力、`:enhance :editing :hashline`、全部已声明的 MCP 服务器、
   技能按今天的方式（只有 `/name` 注入）；内置子 agent `general` 同理（减去 `eval` 与委派工具），
   `explore` 只勾**能证明只读的**那些。**界面不自己编一份默认**——新装的家看见的就是这个答案。

7. **可见性三态，一个名字只有一个状态，三态都要说得出来。** 这是"配合可见性"那一句的落点：

   | 状态 | 谁说的 | 名字在表里吗 | 调用时 |
   |---|---|---|---|
   | **在服务** | 这个 agent 的层选到了它 | 在 | 照常跑 |
   | **不在服务** | 某个 agent 的 `:base :tools` / `:enhance` / `:compose` | **不在** | 按名拒，拒绝里说**哪个 agent、哪个键**、怎么写回来、拿什么替代 |
   | **被关掉** | 层的 `:disable` / 会话的 `session-disable!` | **在**（可见） | 按名拒，说谁关的、怎么打开 |

   **没勾 = 不在服务**（走着 `:narrow` 那条路，与今天编辑模式收窄同一处），**不是**"可见但被拒"。
   "可见但被拒"仍只属于**关掉**那一轴（`tool-toggles` 定的），且照旧只由 `:disable` 与会话开关说。
   三态在拒绝话里**必须能分开**——这是本特性最容易糊掉的一格。

8. **子 agent 不再继承父会话的活表。** `baseline` 退役的代价说清：子 agent 的表**由它自己的配置算出**，
   父会话这一轮新注册的工具、父会话这一轮 MCP 拿到的名单，**不再自动出现在子 agent 手里**。
   要在子 agent 里用就写进它的配置（MCP 写**服务器名**，所以那份名单照旧是现算的）。
   理由：`baseline` 让"子 agent 有什么"从父会话**推**出来，读一个配置项要算两处；本特性要的是
   "一个 agent 的配置就是它的能力"。

9. **`eval` 与委派工具永不进任何子 agent 的表**——任何配置、任何层都做不到，保存就拒。
   这不是默认档，是解析的**后置条件**（照 `subagents/spec.md` 原样保留，`cap/subagents.clj:103` 的 `forbidden` 演进成它）。

10. **只读是一个开关，不是一套派生规则。** 子 agent 的定义多一个 `:read-only? true`：置真时解析只留
    **能证明只读的**能力（`:source :builtin`、`:read-only true`、且非本会话 `session-register!` 进来的），
    勾了写能力保存就被拒。`explore` 默认真，`general` 默认假。这样"宁缺勿滥"那条保证还在，
    而退役的是 `baseline`/`exclude` 那套**范围派生**（`cap/subagents.clj:330` 的 `provable-read-only?`
    与 `:355` 的 `table-for` 演进来）。**这条是本次新增的判断，不认同就删掉 `:read-only?`**，
    让"只读"退化成"默认只勾了只读的那些"。

11. **一个能力可能带好几个工具，那不叫一格。** 第一层一格是**一个能力名 = 一个工具名**；
    增强层一格是**一套组合**（`hashline` 名下四个锚点工具 + `read`/`write` 的两张锚点脸），
    组合内的名字**显示出来但没有各自的格**（照 `tool-switchboard/03` 的"灰行"规则）。
    所以"两套编辑不能同时上"不需要界面约定：那一层只有一个勾。

12. **复合层最后装，它就是"最后提供工具"那一层。** 技能与 MCP 的工具在基础层与增强层之后进入表；
    技能还可以带 `command` 与 `hooks`：`SKILL.md` 的 frontmatter 从"读而忽略"（`cap/skills.clj:111`）
    长出**真的**两个键，由复合层替这个 agent 装上。
    - **`UserPromptSubmit` 要接线。** 它在 `points` 表里已经登记（`kernel/hooks.clj:78`），`src/` 里却
      **一处 emit 都没有**。技能"在用户发送的时候注入"今天走的是 `derived-injections`
      （`cap/skills.clj:519`）这个特例；本特性把它变成一条**真的** hook 点，于是"技能带 hooks"才有地方触发。
    - **注入的规矩不变**：技能正文排在提问**之后**（`context-frames` 从 7 起的读法），
      模型自己调 `skill` 拿到的**不是注入**（那是工具结果，`CONTEXT.md` 的**注入**词条）。复合层的
      `:skills :load` 只决定哪些技能**在发送时被自动注入**，不改这条位置规矩。

13. **拒绝话是这份配置的对外说法。** 三态的话都由**一份**解析结果答（不是页面、不是执行缝各算一遍）：
    哪个 agent、哪个键、当前值是什么、怎么写回来、拿什么替代。这与 `cap.editing` 今天的三个短语
    （"拿什么替代、哪个键换回来"）是同一条要求，本特性把它推广到三层。

14. **基础 hooks 层管两类行：kernel 自己的内建行，与 `hooks.edn` 的声明。** `:base :hooks {:off [...]
    :on [...]}`：**默认每一行每个 agent 都跑**（今天的行为），把一个名字写进 `:off` 才替这个 agent 拿掉它。
    - **内建行**里第一条是**指令文件（AGENTS.md）的注入**。那个"会话出生时折进一段 `<instructions>`"的动作
      **就是一条基础 hook**，不是藏在 `cap/preamble.clj` 的 `gather`（`:109`）里的特例——收进这一层之后，
      页面列得出、每个 agent 能不同（一个探索子 agent 可以不读 AGENTS.md）。内建行按名字点（`instructions`）。
    - **`hooks.edn` 的声明**要能按名点：声明多一个**可选** `:name`（kernel 的 built-in 行本来就叫 `:name`）；
      没写名字的仍按今天的位置 id（`stop#0`）寻址。
    - **层只决定"折不折"，注入的机制与时序不动**：开场仍然在**会话出生时写进对话一次**
      （`CONTEXT.md` 的**注入**词条、`.scratch/session-opening`），不是每轮重来；"折不折"问的是"这场会话开局
      有没有那一块"，不是"每轮要不要再贴一遍"。
    - **复合层技能带来的 hooks 不走这里**，由复合层替这个 agent 装上（见 12）。

15. **一场会话的开场块，两半各归层。** 出生时折进的那份开场有两半：**指令文件**（基础层，见 14）与
    **技能清单**（复合层——它是技能的入口，"有哪些技能"由复合层决定）；子 agent 那条 `<subagent>` 内建行
    仍是它自己的。判据是**谁提供它**，不是它长什么样。

## 非目标

- 不做用户手选"这一场会话用哪个子 agent 跑"（照 `subagents/spec.md`）。
- 不做子 agent 之间的并行、团队、消息互通（`TeammateIdle` 仍只是一个登记过的点）。
- 不改 AG-UI 协议：本特性只改**表与 hooks 怎么算**，客户端没有新帧要认。
- 不给某个 agent 单独的模型 / 预算 / 时限旋钮（旋钮仍然是会话的三个，子 agent 继承父会话这一次的解析结果）。
- 不改 `mcp.edn` / `hooks.edn` 的声明格式（`SKILL.md` 多认两个键见 12），不删 `harness.edn` 的其它键。
- **不实施 `tool-switchboard/01`**（ctx 门与 id）。本特性在**已落地的缝**上做（贡献 map 的 `install!`、
  `:narrow`、`:tools-for`、`:disabled-for`、会话 overlay）。01 落地之后：页面"提供它的是哪一份"那栏
  从层名换成 `owner-for` 答的 id，而 `:base :tools` 仍写**名字**（模型说的话），id 只作显示。
  这是**非阻塞**的：两条路都能答出这一页要的"谁在管这一行"。
- 不做每层的加载顺序按钮或拖拽（顺序由层的**种类**定，不由人排）。

## 票

| # | 票 | Blocked by |
|---|---|---|
| 01 | 能力目录与三层解析（纯数据 ＋ 纯函数） | 无 |
| 02 | `config.edn` 的 `:agents`：第四节、默认档、校验、写盘 | 01 |
| 03 | 按 agent 档生效：主 agent 与子 agent 各自的工具表 | 02 |
| 04 | 按 agent 档生效：hooks 层、`UserPromptSubmit`、技能带 command/hooks | 02 |
| 05 | 设置面板的「Agents」页：三层分栏，主 agent 一个 tab、子 agent 各一个 | 03、04 |
| 06 | 收口：词汇、ADR、文档、`prompt.md`、全量 | 03、04、05 |

01 是唯一起点（无阻塞）；02 只依赖 01；03 与 04 都只依赖 02，可并行；05 要 03 与 04 都落地；06 收口。

## 验收主线

离线全量 `clojure -M:test -m harness.test-runner` 全绿。**动过 `ui/src/` 的票（05）合之前走一次
`node scripts/dev.mjs --scripted`，并自己开浏览器走一趟**——三层分栏、灰行、tab 的开合，只有真浏览器说得清
（AGENTS.md 那条铁律）。

端到端那一票落在 **03 与 04**：一个家配出"主 agent 有 `bash`、`explore` 没有、`explore` 的编辑是
`str-replace`"，跑一次委派，两侧的工具表、`read` 的脸、被拒的名字各自说得清；再加一条"技能在用户发送时
被注入，且它的 hook 真的跑了"。

**兼容性总账**（写测试时逐条对照）：`node scripts/test.mjs` 里既有的
`editing-mode-tools-test`（编辑家族的话术）、`session_tools_test`（会话开关）、`subagents_test`
（`baseline`/`exclude`）、`subagent-view` / `subagents` 的 UI 套件，都会碰到本特性。**不许删断言了事**：
`baseline`/`exclude` 的用例改写成"逐层勾选"的等价断言，`harness.edn :editing {:mode}` 的用例改成
`config.edn :agents` 的等价断言。

## 状态

- 本 spec 与六张票落盘（2026-09-26），全部 `ready-for-agent`，尚无代码。
- **关系**：
  - **取代** `tool-switchboard/02` 的 `config.edn :tools` 单节（那里是"进程装不装"的一个平面集合；
    这里升级成"每个 agent 每种层选什么"）。
  - **取代** `tool-switchboard/03` 的单一「Tools」页（两组的形状保留：基础能力逐格、增强层一个总开关；
    多了第三层与按 agent 分 tab）。
  - **取代** `subagents/spec.md` 的 `baseline`/`exclude`（`harness.edn :subagents` 的写通道随之退休）。
  - **推翻** `tool-switchboard/03` 关于 `harness.edn :editing {:mode}` 继续当会话偏好的那一格（见决策 4）。
  - **不等** `tool-switchboard/01`（见非目标）。
- 词表提醒：`CONTEXT.md` 的**技能层**指技能的根分档（系统级 / 项目级），与本 spec 的"层"**不是一回事**；
  06 落词时两个词要分得开（建议本特性的叫**配置层**：基础层 / 增强层 / 复合层）。
