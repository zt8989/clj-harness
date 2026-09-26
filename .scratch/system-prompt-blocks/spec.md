# spec: system prompt 的组装（固定开头 + hook 追加的文本）

`prompt.md` 不再是「一整份冻结的 system prompt」，而是**固定开头**：它只留下与任何会话无关的话——身份、
hook 自助、secrets 纪律、以及「其余自己读」。其余每一样**本会话的事实**都由 **hook** 在组装时追加，
按 append 顺序依次拼在那段开头之后。仍是**同一条 system 消息**：AG-UI 帧形状不动、客户端一个字都看不到、
jsonl 行种类不加。

> 2026-09-16 落地后补：上面那句里**「hook 自助」这一项没有留下**——`prompt.md` 的 Self-extension/eval
> 整节退场了，冻结开头实际只有身份、secrets 纪律、「其余自己读」和一句 Be concise。这是作者当场决定的
> 越界，理由与后果见文末「两处与本 spec 不符的地方」，**上文与本行以下保持原样不改写**。

```
[system  prompt.md 的冻结开头（逐字节）]
[追加    <tools>…</tools>      ]  ← 内核自己注册的 hook（source :built-in）
[追加    <project>…</project>  ]  ← 同样是 hook
[追加    <provider>…</provider>]  ← 同样是 hook
[追加    作者写什么就是什么     ]  ← hooks.edn 里 / 本会话声明的 hook，按声明顺序
[user    既有开场块：AGENTS.md / 技能清单 / 技能正文 —— 一字不动]
[...客户端带来的会话消息...]
```

今天 `prompt.md` 里那句 `Tools: read, write, edit, bash, eval.` 就是这条特征的理由：它**从写下的那一刻就在过时**
（`skill`、`session-configure` 不在里面；hashline 分支上 `edit` 退场；MCP 之后还会有 `mcp__<server>__<tool>`），
而它冻结着，改它要 `reset-prompt!` 或重启。**凡是本会话的事实，都不该被写死在冻结文件里。**

## 决策

1. **一条 system 消息，开头冻结，hook 追加其后。** `ag_ui/inbound` 的规则（首条是 system 就换成它，否则前插）
   不变，变的只是它拿到的那段文本由「prompt.md」变成「prompt.md + 各 hook 追加的文本」。`prompt.md` 仍是唯一的
   system 消息，追加的文本也仍是**服务端面向模型的那一侧**：不产生任何 AG-UI 帧，只进 jsonl 的 `message` 行。

2. **没有特殊机制：内建的行也是 hook。** 内核自己的那几条不另立一套——它们是**同一张表里的行、同一条缝、
   同一套退出码与审计行**，只是来源不同、跑的东西不同：

   | 来源 | `:source` | 说什么 | 跑什么 |
   |---|---|---|---|
   | 内核自己注册的 | `:built-in` | `<tools>` / `<project>` / `<provider>` | 进程内的函数（`:run`） |
   | 用户的 `hooks.edn`、项目 `.harness/hooks.edn` | `:config` | 作者说了算 | 命令（`:command`） |
   | 本会话 `session-add!` 加的 | `:session` | 作者说了算 | 两者皆可 |

   一条声明**说它跑什么**：恰好有 `:command`（非空字符串）或 `:run`（可调用）之一，两个都没有或都有都指名报错。
   **`hooks.edn` 里出现 `:run` 被指名拒绝**（文件里放不了函数）——这不是特例，是与 :timeout / :matcher 同一套
   逐字段校验。进程内的 hook 收到 payload 的 **map**（与 shell 那侧 stdin JSON 同一批键、保类型）；
   它返回的就是 `shell/run` 返回的那个形状 `{:exit :out :err :timeout}`，所以 `verdict-of` 之下的退出码语义、
   `:on-error`、first-block-wins、审计行**一个字都没改**。

   **先后由来源档位决定**（内建 → 文件 → 会话），同一来源内按各自的顺序。这条也是「加一个来源 = 加一行」，
   与「加一个 hook 点 = 加一行」同源。今天 id 的拼法（文件 `#0`、会话 `@1`）不动。

3. **每 run 现算，不按会话冻结。** 事实不动则文本逐字节不动，provider 的前缀缓存（prefill）照旧命中；
   事实动了（重新绑定项目、会话关掉一条 hook、改了 provider 档）就付**一次冷前缀**。
   **宁可冷一次，也不让 system 消息说一件已经不成立的事。** 这条与仓库处处「现读」的纪律同源
   （config.edn / harness.edn / providers.edn / AGENTS.md 都是每轮现读），也是它否掉「SessionStart 冻结一次」
   那个方案的理由：绑定会在会话中途变（`project/bind!`），冻结下来的就是一句假话。

>
> **2026-09-24 修订（决定 3 的落地方式）：** 组装**不是每 run 都跑**。先算一个**廉价的签名**——
> **hooks 的名字集合 hash + tools 的名字集合 hash**——与上一轮比；没变就**复用**上一轮那份 system 文本，
> hooks 一个都不跑；变了才重新组装。理由：hooks 可以是 shell 命令，每轮跑一遍是实打实的开销，而
> `<tools>` 块只报工具**名字集合**（决策 6），它的内容只在名字集合变时才变。**不算的**：工具描述改了
> 不管；`prompt.md` 不参与（进程内冻结，`reset-prompt!` 显式作废）；project 绑定不许动态变。这条判据
> 同时给 `.scratch/instruction-updates`（要不要送更新）与压力表的锚点用。
4. **`SystemPrompt` 是第 27 个 hook 点。** 名字与键：`"SystemPrompt"` / `:system-prompt`；时机「一次 run 的
   system 消息正在被组装，模型看到它之前」；payload 只有公共四个（`hook` / `thread_id` / `project_dir`）；
   没有匹配对象；`:on-error :block`。契约：

   - **退出 0**：stdout（trim 后非空）就是它追加的那段文本。
   - **退出 2**：**这次 run 不开始**，stderr 就是理由（客户端收到 RUN_ERROR，理由逐字）。
   - **超时 / 起不来**：照点自己的 `:on-error`（`:block`）走同一条，理由说清是哪一条声明。

   **这个点与门禁点的差别只有一条，且写在行上**：匹配到的声明**全部跑、全部追加**（顺序即上表），
   不是「第一条 block 获胜」——一条 hook 不该把另一条的文本吃掉。行的这一位（stdout 是内容）是点表里的一格，
   dispatch 照它把各条的文本收进有序的 `:blocks`；其它点的返回与审计行因此逐字节不变。

   **为什么失败是硬失败而不是 fail-open**：这条 hook 写的是**本该进 system 消息的话**，与 AGENTS.md 同一族
   （那个特征的纪律是「文件存在却读不出来就不开始，绝不静默按一套不是用户写的规矩跑」）。
   一个观察者（Stop / PostToolUse）坏了可以吞掉，一段本该进 system 消息的指令吞掉就是撒谎。

5. **追加的文本原样进 prompt，引擎不包装。** 内建的块自己就带 `<tools>` 这样的标签；作者写的 hook 写什么就是
   什么（文档给的约定是拿一个标签开头，说明这是谁的话）。块之间恰好一个空行，每块 trim。
   理由：包装规则会变成第二套「谁知道这是什么」的机制，而标签本来就是文本的一部分。

6. **内建的块报什么。** 工具清单**只报集合**，不复制每工具的描述（描述已经在 wire 的 `:tools` 里）：可用的是哪些
   （该 thread 的有效工具表）、**本会话关掉了哪些**（关闭不是隐藏：表里还在，模型能 `session-enable!` 打开，
   所以它必须看得见）、哪些来自外部程序（`mcp__<server>__<tool>` 不是本内核实现的东西）。
   工程目录块给绝对路径、相对路径往哪落、什么情况下停泊等人（没绑定就明说没绑定、不说围栏）。
   会话块给 vendor / model / 思考档，**永不含 api-key**。

7. **冻结开头留下的，是「与任何会话无关的话」。** 身份一行；hook 自助一节；secrets 纪律一节（**一字不减**：
   hook-engine 的收口把它定成那份收敛里「唯一不动」的部分，`harness.providers` 的多处注释与
   `docs/architecture/providers.md` 都指着它）；以及「其余自己读，读胜过被告知」。这些是**承诺**，不是**事实**，
   所以它们不进 hook 的手里——**hook 能被关掉（内建的也一样），规矩不能被关掉**。

8. **用户侧开场块不动。** AGENTS.md、技能清单、技能正文仍走 `role=user`：那是 skills-and-instructions
   已落地的决定（客户端持有会话 → 注入必须是派生的；user 角色对模型是明确的框架）。本特征**不重开**它，
   只是把「属于 system 的那一部分」从一份冻结文件变成组装出来的东西。

9. **不截断。** 追加的文本原样进 system 消息（与 AGENTS.md、技能清单同一条纪律）；重量由 `hook/SystemPrompt`
   审计行与 jsonl 的 `message` 行如实记录。

## 非目标

- 不改 UI、不加 AG-UI 帧、不加 jsonl 行种类、不动 CORS。追加的文本可见性止于服务端与日志。
- 不把用户侧开场块（AGENTS.md / 技能清单 / 技能正文）搬进 system 消息（决策 8）。
- 不做 per-session 冻结与失效表（决策 3 否掉了它）。
- 不改 hook 自助、secrets 两节的**内容**；不改 `prompt.md` 里任何**与任何会话无关**的话。
- 不新增除 `SystemPrompt` 以外的点；不接线其余未触发的点；不碰 MCP / model-limits / action-fusion 在办的票。
- 不做 `/api/prompt` 之类的自省端点、不做块的 UI 展示。

## 组装住在哪

**新 ns `harness.system-prompt`**：它拿冻结开头（`harness.llm/prompt`）、触发 `SystemPrompt` 点、把追加的文本拼好；
它同时**注册那三条内建 hook**（它们要工具表、绑定、provider，所以不能在 `harness.hooks` 里声明）。

**不并进 `harness.preamble`，因为那是 require 环。** `project` 已经 require `preamble`（要 `instruction-files`
那个纯函数），而内建 hook 要 `tools`（活的工具表）与 `project`（绑定），于是
`preamble → tools → project → preamble`；再往下，触发 hook 又要 `hooks.dispatch`，而 `hooks → project → preamble`
同样成环。第二处旁证是那面被点名的半边：`instruction-files` 之所以收 `(cfg, project-dir)` 而不自己去查绑定，
正是为了避开同一个环。**两半各有主人**——system 半是 `harness.system-prompt`，user 半仍是 `harness.preamble`
——而两半**不可能交错**（不同的 message role），所以「顺序只有一个决定处」这条纪律在每一半内部照样成立。

## 验收主线

离线全量 `harness.test-runner` 全绿。**基线：`main` @ `0ef17a9`，344 tests / 1933 assertions**
（2026-09-15 实测，0 failures / 0 errors）。

**要改写的既有断言逐条列明**（本特征不是「什么都不变」的搬迁票，改写是应当的）：

| 用例 | 今天断言 | 改成 |
|---|---|---|
| `http_test` 的 message-record 用例 | system 消息 `= (slurp "prompt.md")` | 以 prompt.md 开头 **且**含内建 hook 追加的文本 |
| `replay_test/history-is-shaped-for-the-provider` | system 以 `(llm/prompt)` 开头 | 以冻结开头开头、含该 thread 的追加文本 |
| `llm_test/prompt-is-frozen` | 措辞：`prompt` 是「system prompt」 | 措辞：它是 system 消息的**开头**；冻结纪律一字不动 |

「逐字节不变」在 01 之后**不再成立**（prompt.md 与 system 消息都变），所以验收是「拼出来的文本逐字可断言」，
不是「什么都没变」。反过来，**01 之前的行为零变化**必须成立：一条内建声明都没有时，任何点的任何调用与今天逐字节相同。

## 跨特征对照

- **修正 `.scratch/mcp/issues/06` 的一条**：那一票要求「`prompt.md` 那句工具枚举改成不枚举的说法」。
  本特征让枚举**由构造正确**（从活的工具表派生），比「不枚举」更强。按仓库纪律**不改写原文**：那一票若不落地
  就追加一条带日期的**复议**指向本特征；若已落地，正常往前推，只在本目录写现状。
- **hook 点数 26 → 27**：`harness.hooks/points` 的 docstring 与 `.scratch/general-harness/spec.md` 的 P2/P3 清单
  都要跟（历史处加一笔，现状处改掉）。
- **来源从两个变三个**：`harness.hooks` 的 docstring 今天讲「两级装配 + 会话 overlay」两层；多了内建那一层，
  以及「一条声明跑什么」（`:command` / `:run`）。`docs/architecture/hooks.md` 是现状描述，落在 05。
- **与 model-limits 的交界**：`<provider>` 块今天报 vendor / model / 思考档；那个特征落地后 limits
  （context window / max output）是往**同一块**里加一句，不新增 hook。
- **与 action-fusion 的交界**：`then_run` 进的是工具 schema，**不进** system 消息；本特征不碰 `tools/specs`。
- **与 composer 的模型选择器的交界**：会话中途换 model 会让 `<provider>` 那句变、system 消息跟着变，
  于是那一次调用付一次冷前缀。这是决策 3 的直接结果，也是它该有的样子。
- **推翻对照**：`docs/architecture/overview.md` 的铁律 2 今天写「per-run 的 context、指令文件、技能清单
  一律不进 system 消息」。本特征把它**收窄**成：**system 消息只有一条，它的开头冻结，hook 追加其后**
  ——「不进 system 消息」这句要改，指令文件与技能清单仍在 user 侧这条不变。

## 交付顺序

01 先（来源与运行缝——它自己就能演示：eval 塞一条进程内的门禁 hook 进去）；02 是点与追加（**内建要能存在，
   就得先有这两样**）；03 / 04 是内建的三行（加点 = 加一行）；05 是扇入。

## 票清单（`.scratch/system-prompt-blocks/issues/`）

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 声明的来源与运行缝 | — | 内建的行与声明的行进同一张表、同一条缝；`:command` 或 `:run`；来源档位定先后 |
| 02 | `SystemPrompt` 点与追加 | 01 | 第 27 个点：匹配的声明全跑全追加、原样进 prompt；退出 2 拒绝这次 run |
| 03 | 内建 hook：工具清单 | 02 | 第一个真块；`prompt.md` 那句枚举退场 |
| 04 | 内建 hook：工程目录与会话档 | 02 | 另外两行；`prompt.md` 那两段退场 |
| 05 | 收口：现状文档、跨特征对照、全量验证 | 03, 04 | 文档跟上，对照落下，全量 + UI + 真机绿 |

## 状态

**已落地**（2026-09-16，分支 `system-prompt-blocks`）。票 01–05 全部完成；**票面已按仓库约定删除**
（逐票记录归本 spec 与 git 历史，上面那张表就是当时的清单）。本目录现在只有 `spec.md`。

### 两处与本 spec 不符的地方，是作者当场决定的

写在前面，因为下面那两节都按**落地后的事实**写，而不是按上面那些**当时**的决策写。

1. **`prompt.md` 的 secrets 纪律里指向 `active-provider` 的那一颗退场了。** 票 04 括号里列的四项
   ——api-key 禁令、禁读 `scripted-pins` / `session-overrides`、禁 var-quote、`session-configure`
   审批路径——**逐字保留**；退场的是「想知道本会话由谁服务，问 active-provider」那一颗，它的内容
   由 `<provider>` 块接管。上文决策 7 那句「secrets 一节一字不减」因此应读作「那一节里的**禁令**一字不减」。
2. **`prompt.md` 的「Self-extension / eval」整节退场。** 这一条**超出本 spec 的非目标**
   （「不改 hook 自助、secrets 两节的内容」），是作者的决定：eval 的定位另有规划，那几段不再写在
   冻结文件里。后果如实说——**模型不再从 system 消息里知道 hook 这回事**，那节提到的四个入口也不再
   是「对模型的承诺」；`eval` 现在只有工具描述里那一句。这是本特征最大的一处越界，记在这里。

## 已验证到什么程度

**基线取的是落地时的 `main`，不是上文写的那个。** 上文说基线是 `main` @ `0ef17a9`（344 / 1933），
那是**写下本 spec 时**的数；`0ef17a9` 之后 `skills-slash-load` 与 `hashline-edit` 都并进了 main，
所以本特征实际开工的基线是 **`main` @ `63869d2`：569 tests / 9574 assertions 全绿**（0 failures / 0 errors）。

- **全量离线**：`clojure -M:test -m harness.test-runner` —— **607 tests / 9774 assertions 全绿，exit 0**。
  （基线 569 / 9574，所以本特征净增 38 个用例、200 条断言。）
- **前端**：`cd ui && npm test` **11/11 全绿**；`cd ui && npm run build` 全绿。
  本特征不加帧、不动 UI，前端因此是**回归**而不是新覆盖。
- **真机验收（有 api-key 的机器，人）**：**未做**。新会话里模型是否说得出手里有哪些工具、当前工程
  目录是哪一个，以及 hooks.edn 里声明一条 `:system-prompt` 命令是否真的出现在 system 消息里，
  **需要真模型判断，没有人跑过就不写「过了」**。

### 落了什么

- **`harness.hooks`**：点表 27 行（新增 `SystemPrompt`，带点表里第一格 `:stdout :content`）；
  声明现在**说它跑什么**——恰好 `:command` 或 `:run` 之一，两个都没有、两个都给都指名报错；
  `hooks.edn` 里写 `:run` 指名拒绝（`a file cannot hold a function`），`:timeout` 配 `:run` 也拒绝
  （没有 spawn 可限时）；**三个来源** `:built-in` / `:config` / `:session`，先后由来源档位定；
  内建用 `register-builtin!` 注册（同名替换，所以 `:reload` 是 no-op 而不是多一行）；
  id 三套拼法一眼分得开（`pre-tool-use#0` / `pre-tool-use@1` / `builtin:tools`）。
- **`harness.hooks.dispatch`**：`:run` 的行内函数拿到**同一批键的 map、值保类型**，返回
  `{:exit :out :err :timeout}`（抛异常 = `{:exit nil :err <消息>}`）；`verdict-of` 之下的退出码语义、
  `:on-error`、first-block-wins、审计行**一个字都没改**；`:stdout :content` 的点额外收一份有序的
  `:blocks`（trim 后非空才收），其它点的返回不含这个键。
- **`harness.system-prompt`（新 ns）**：组装（冻结开头 + 各块，块间恰好一个空行，**没有 sink 时返回
  `prompt.md` 的字节本身**），并注册那三条内建行；`builtin:tools` / `builtin:project` / `builtin:provider`。
- **`harness.http`**：`run-agent!` 里改调 `system-prompt/assemble`（在 sink binding **之内**）；
  `harness.replay/history` 以日志文件名当 thread-id 现算。
- **`prompt.md`**：工具枚举段、项目段、hook 自助/eval 段退场，secrets 里 active-provider 那一颗退场；
  身份、secrets 其余、其余自己读、Be concise 留着。措辞上把 `(harness.llm/prompt)` 说成
  **system 消息的开头**。
- **测试**：新增 `harness.system-prompt-test`（24 个用例，含 api-key 全文搜索、逐字节稳定性、
  未绑定/绑定/严格/rebind 的四种围栏形态）；`http_test` 新增三条端到端（三来源同时在场且**一个字节都不上 wire**、
  关掉再打开、退出 2 → RUN_ERROR 且没调模型）。

### 按上文那张表逐条改写的既有断言

- `http_test` 的 message-record 用例：从「system 消息 `= (slurp "prompt.md")`」改成「以 `prompt.md`
  开头 **且**含内建 hook 追加的文本」。（trim 之后比较：组装会把开头结尾的换行规范化掉再加空行。）
- `replay_test/history-is-shaped-for-the-provider`：从「以 `(llm/prompt)` 开头」改成「**就是**那份开头，
  逐字节」——replay 没有 sink，所以按票 02 那条「没有 sink 的调用方不跑 hook、不追加」，这里本来就不该
  有追加文本；追加那半在 `system-prompt-test` 与 `http_test` 里。
- `llm_test/prompt-is-frozen`：措辞改成「冻结的是 system 消息的**开头**」，冻结纪律与断言原样。
- **上面三个之外还有一处是那张表没预见的**：`hooks_wired_test` 那条「什么都不声明时一行 hook 都不写」
  不再成立——内建的三行在每个 thread 的表里，一次真触发的 `hook/SystemPrompt` 行会落。用例改名为
  `a-session-that-declares-nothing-fires-only-the-kernels-own-rows`，断言「唯一的 hook 行是内核自己那条、
  四个需要声明的点全静默」；「没有任何声明就一行都不写」这条性质改由 `system-prompt-test` 用
  **把三条内建关掉**来断言（那才是它今天唯一可达的状态）。
- `hooks_test`：点表 26 → 27；`effective-hooks` 不再可能是空的，所以「fresh install 什么都没声明」这条
  改成按来源过滤（`declared`）后断言，`(first (vals e))` 改成按 id 取；新增 `:run` 的四条校验用例与
  一条**经真实 eval 工具调用装一条进程内 `:pre-tool-use` 门禁**的端到端（票 01 点名的那个演示）。

### 落地时撞出来的三件事

1. **全局注册的内建行会改写「无声明即无痕」那条断言的形状。** 上面那条已经说了；值得补一句的是
   `hooks_dispatch_test` 里两个 `:system-prompt` 用例在**单独跑**时绿、在**全量**里红——因为
   `harness.system-prompt` 是随全量套件加载的，注册是进程级的。修法是让用例按 thread 把三条关掉
   （`session-disable!`，本来就是普通开关），不是给测试开后门。**教训：进程级注册 + 同进程测试，
   和「配置在磁盘上 + 同进程测试」是同一种通病**（hook-engine 的 spec 里记过一次）。
2. **`<tools>` 这个字符串现在也在 README 里**，而脚本化的 `read` 会把 README 当工具结果送上 wire，
   于是「追加的文本一个字节都不上 wire」那条断言被自己绊了一跤。断言改用只有这次组装会写出的标记
   （`A DECLARED BLOCK`、`not bound to any project directory`、`available: `），**不是**把 README 改掉。
3. **顺带修掉一条与本案无关、但挡着这条分支的既有 flake。** `providers_test` 的
   `settings-answers-the-live-configuration-without-ever-the-key` 里有一条断言，在整份渲染结果里
   搜哨兵 key 的**长度**（`"39"`）作为子串——而那份结果里永远带着本 home 的**绝对路径**，
   路径末段是 `System/currentTimeMillis`，一串任意数字。某次运行的毫秒数里恰好含 `39`，那条断言
   就红了，红的理由与 key 毫无关系（前面几次全量运行没红，只是没撞上）。改成**按值**搜（解析后的
   应答里没有任何一个标量等于这个长度）：路径是字符串，永远不可能 `=` 一个数字，而「长度没有出现在
   应答里」这条主张一字未减。**这属于「跑全量时撞上的、不是本特征引起的红」，按仓库惯例顺手修掉并记在这里。**

## 复议（2026-09-16，牛总）

**内建的行由三条变两条**，本 spec 的机制一条都没被推翻——被改的是**行的集合**。工作与逐条改写的断言
在 `.scratch/session-context/`（4 张票）。原文一字不改，只在这里指路。

- ~~`builtin:tools`~~、~~`builtin:provider`~~ 退场，`builtin:project` 保留，**新增 `builtin:env`**
  （平台 + 解析到的 shell + 声明名单里这个 shell 看得见哪些命令行增强工具）。
- **理由（牛总）：tools 在接口调用的时候就是自描述的**——wire 上的 `:tools` 数组每次都带着每个工具的名字
  与描述，所以一块「点名」是把同一件事说第二遍。`<provider>` 同理。

> **2026-09-24 边界（`.scratch/model-surface-and-meter` 票 04）：** 主人说「tools 应该写进 `role=system`」，
> 但紧跟着一句「正文不需要 tools 块」——所以**这里不加行，决策 6 也不改**：`<tools>` 块**不进 prompt 正文**
> （那正是「说第二遍」，模型白付 token）。整张表改由 `harness.edge.http` 写到 **system 那条 `message` 行的
> 信封** `:tools` 上（名字 + 描述 + parameters），`replay/payload` 把它挡在消息之外：记录里回读得到、模型
> 读不到。理由：wire 在接口调用时自描述，但 **wire 不留**，而那条行留。
- **这条推理的边界**（不写下来会被推远）：自描述的是**名册**，不是**技法**。技法不在 wire 上，
  所以它得有地方说——Action Fusion 的说明就是这样一块，那是 `.scratch/action-fusion/` 的票，不是本特征的。
- **决策 2 的那张来源表**（内建 / 文件 / 会话、先后由来源档位定）与**决策 7 的原则**
  （内建的行也能关掉，prompt.md 的冻结开头不能）**继续成立**，只是内建那一档今天少一行、多一行。
- **一处净损失，如实记在 session-context 的 spec 里**：`<provider>` 那块同时是
  「想知道本会话由谁服务，问 `active-provider`」的住处（本 spec 的落地记录第 1 条提到它从 prompt.md 搬过去），
  块没了，那颗配方就没有家。接受，不补位。

