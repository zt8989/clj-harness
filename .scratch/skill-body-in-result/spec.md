# spec: 技能正文由工具结果直接返回

**一句话**：模型调用 `skill`，**这次调用的工具结果就是那份 SKILL.md 的正文本身**，末尾跟一行说这个技能住在哪个目录。
不再有「工具回一句确认、正文由每轮 LLM 调用前的派生注入补成一条 `<skill name=…>` 的 user 消息」那套。

| | 改后 | 改前 |
| --- | --- | --- |
| `skill` 的结果 | SKILL.md 全文 + 一行技能目录 | `[skill-loaded] X is now in this conversation … (N chars)` |
| 模型侧的历史 | `assistant(tool_call) → tool(正文)`，与 `read` 一样 | 那句确认，末尾另补一条 `<skill>` user 消息 |
| 会话栏 | 一张普通的 `skill` 工具卡（正文在卡里，卡默认折着） | 工具卡 **+ 第二张注入卡** |
| jsonl | 正文在 tool 结果的 `message` 行（`role` = `tool`） | 正文在那条派生出来的 user `message` 行 |
| 人的 `/name` | **不变**：仍由派生注入一条 `<skill>` 消息、仍画一张卡 | 同 |

**客户端从此持有正文**（它在 tool 结果里，刷新与重建走既有那条路），`sessions/model-view` 不必再用 `data` part 把它还原回模型读过的那条消息。
`[skill-loaded]` 那个共用常量连同它的三个用户（工具、`loaded-summary`、`load-confirmations`）一起退休。

## 为什么

三笔代价，一次结清：

1. **客户端从不持有正文**——模型读过、记录里有、而客户端手上没有的另一半材料。
2. **一次调用画两张卡**：工具卡说「加载了 X」，会话栏里另一张注入卡才是正文本身。
3. **工具与派生函数必须共用一句字符串**来判断「到底加载成功了没有」——`loaded-prefix` 整个存在理由就是这个。

工具回答它读到了什么，这三笔一起没了。

## 决策

1. **工具结果 = 正文 + 一行技能目录，正文永不截断。** 目录那一行不是装饰：技能正文常写「读 `references/x.md`」，那是相对**技能目录**的路径，
   也是围栏放行技能根的由来（`.scratch/skills-and-instructions` 决策 12）。
2. **工具路径不再是注入。** `derived-injections` 只剩人的 `/name` 这一半；`loaded-prefix` / `loaded-summary` / `load-confirmations` 删除，
   `harness.cap.skills` 也不再 require `clojure.data.json`。
3. **「同名只加载一次」不再跨来源成立——两条路是两个 ask。** `loaded-names` 认的是本命名空间写的那对标签，工具结果没有标签，
   所以「模型先加载 alpha，人再 `/name alpha`」会追加**第二份**。选它而不是「让派生认得 tool 结果」，是因为后者要重新发明一个判据
   （哪条 tool 结果算加载），而这正是刚退休那个常量存在的理由；代价（罕见的一次重复）写在代码注释、`CONTEXT.md` 与
   `skills-and-instructions` 的更正里。人的 `/name` 是一次新的开口，这个仓一贯宁可少一处判据。
4. **失败仍是失败**：未知名字 / 坏技能照旧**指名拒绝**（`:error` 为真），绝不用一段不是指令的文字冒充指令。
5. **文案与文档同步。** 工具自己的描述、清单块那句「its full text then joins this conversation」、`CONTEXT.md` 的**注入**条目、
   以及 `.scratch/skills-and-instructions/spec.md` 决策 9 / 10 处的更正，都跟着改——留着旧说法的注释就是下一个读者照着实现的依据。

## 落地的东西

- `src/harness/cap/tools.clj`：`t-skill` 的结果与文档；`skill` 工具的描述。
- `src/harness/cap/skills.clj`：删三个定义、收缩 `derived-injections`、清单块文案、几处注释，去掉 `clojure.data.json`。
- `src/harness/cap/project.clj`（`before-llm`）与 `src/harness/kernel/session.clj`（重建那张表）：说明哪一半还从这里走。
- `CONTEXT.md` 的注入条目 + `.scratch/skills-and-instructions/spec.md` 的更正。
- `docs/architecture/*.md` 与 `docs/architecture.md`：那套设计文档里凡是「技能正文也是注入物之一」「正文由派生扫 `tool_calls` 补上」「工具回一句确认」的地方，主语都收到**人的 `/name`** 一路；`load-confirmations` / `loaded-prefix` 那对共用常量写成已退休，`skill` 工具那一节的输出示例换成新形状。
- 测试：`skills_test`（工具两端 + 派生收缩 + 两条路两份正文）、`loop_test`（走真循环：正文在 tool 结果里、这一轮**零** `:context/injected`）、
  `http_test`（端到端：正文在 `role=tool` 的 `message` 行上、这一轮**没有** `-ctx` 的 CUSTOM 帧）、
  `trajectory_test`（模型那条路落在 tool 行；`/name` 那条路仍是 `context` 行）。
- **`ui/` 一行未改**：注入卡那套机制留着给开场块、作业通知与 `/name` 用，技能这一路只是不再走它。

## 撞出来的

1. **`skill-md` 夹具的 `extra` 必须以换行收尾。** 它把 `extra` 拼在 description 行与闭合 `---` 之间，不带换行就把闭合栅栏吞成正文的一行，
   于是「超长正文」那条断言拿到的是 `no-frontmatter` 的拒绝。既有那条同名用例本来就这么写（`(str "x" … "\n")`），照抄才对。
2. **手写 docstring 里多一个 `"` 会炸在别处。** `loaded-names` 的说明里多写了一个引号，字符串提前闭合，Clojure 报的是**下面一行正则**的
   `Metadata must be Symbol,Keyword,String,Vector or Map`。
3. **删一整行时别忘了那行末尾的 `]`。** `card-text` 那行同时是 let 绑定向量的闭合括号所在，整行删掉就少了一个 `]`；
   症状是文件末尾「Unmatched delimiter: )」。改完顺手把 `]` 挂到前一个绑定上。
4. **一次被替换掉的 `testing` 块，末尾括号要自己数。** 多一个 `)` 会提前关掉外层 `let`，症状是后面 `testing` 里的 `root`
   「Unable to resolve symbol」——编译器指的位置离错处很远。
5. **正文从此可能被 `prune` 掐掉中间一段 —— 这是本次唯一一处能力下降，写在这里而不是假装没有。**
   `harness.edge.prune` 只动 `role = "tool"` 的消息：超过 6000 码点的结果会被换成「头 2000 + `…[pruned N characters]…` + 尾 2000」。
   改前正文是一条**派生出来的 user 消息**，prune 碰不到它；改后它就在 tool 结果里，所以够大又赶上请求超压时会被掐。不修的理由：prune 是压到极限前那一步免费动作，
   标记本身写着「这里被掐了 N 字」（不是静默），而 `skill` 是**可再调用**的 —— 要全文再调一次就有。这一点还比改前好：改前那条派生注入会因为「这个名字已在对话里」而拒绝再补。
   要把技能正文排除在 prune 之外是另一票的事（得按 `tool_calls` 的名字判，`prune-plan` 今天只看 role 与长度）。
6. **我漏了 `docs/architecture/` 那一整套设计文档。** 第一遍只按「代码、测试、`CONTEXT.md`、feature 的 spec」搜旧说法，而 `rg` 的路径没带上 `docs/`——
   而 `docs/architecture/skills-and-instructions.md` 正是这个特性的常驻说明书（`load-confirmations` / `loaded-prefix` 都在里面点名）。
   **下一次改机制时：`rg '旧说法' docs/ src/ test/ ui/ CONTEXT.md`，路径一个都别省**——注释与文档是这个仓的说明书，漏了它们等于让下一个读者照着作废的机制实现。

## 验证

离线全量 `clojure -M:test -m harness.test-runner`：**1277 tests / 13768 assertions，0 errors**；唯一一条红是既存的、与本次无关的那一条（见下）。

### 一条既存的、与本次无关的红

`harness.edge.pressure-test` 的 `the-endpoint-answers-the-pressure-section-and-the-run-leaves-it-on-the-record`
在 `(>= (:pressureTokens p) 50000)` 上失败（实测 `49087`），**在 `54e017d` 的干净检出上逐字复现**（临时 worktree 跑同一个命名空间：
19 tests / 57 assertions / 1 failures，数值一模一样）。它还是**间歇**的：本轮全量跑了两次，一次报 2 failures、一次报 1 failures，失败点与数值相同。
按仓库纪律，这不是本特征改出来的，也不在本次范围内假装绿掉 —— 记在这里，留待它自己的一票。

`ui/` 一行未改，所以按仓库规矩不触发 `node scripts/dev.mjs --scripted` 的浏览器走查（工作树里也没有 `ui/node_modules`）：
技能这一路在界面上就是一张普通工具卡，点开是正文，且**没有**第二张卡——后者由 `http_test` 的
`an-opening-block-reaches-the-model-and-the-client-can-see-it` 在真实 HTTP 边上钉住（CUSTOM 帧数为零、正文在 `role=tool` 的 `message` 行上）。
