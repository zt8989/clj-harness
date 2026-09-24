# providers：厂商、model、三档解析

`harness.cap.providers` 装着 provider 这件事的两半：**目录**（有哪些厂商与 model）与**谁赢**（本次用什么）。

## 一份配置，三节：`config.edn`

配置家**只有一个文件**：`~/.clj-harness/config.edn`，顶层恰好三节——`:default`（三个旋钮的默认档）、
`:providers`（厂商目录）与 `:ui`（界面自己的设置，今天只有 `:language`）：

```edn
{:default {:provider :openrouter :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}

 :ui {:language :en}                         ; 这个家说的语言：:en 或 :zh

 :providers
 {:openrouter {:protocol :openai-completions
               :base-url "https://openrouter.ai/api/v1"
               :model    "anthropic/claude-sonnet-4.5"        ; 该厂商的默认 model id
               :models   {"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}
                                                         :context-window 1000000
                                                         :max-output-tokens 64000}
                          "deepseek/deepseek-v4-pro"    {:input #{:text} :output #{:text}}}}}}
```

**一个空的 `config.edn` 是一个什么都没说的文件**，这个形状把它拼成 `{}`：沉默时内置表照旧当地板、
没有任何默认档，一轮跑起来遇到的是**那句教你写哪个形状**的话；而**说了话却不是 map**（一个向量、
一个字符串、一个数字）是另一回事，仍然指名失败。

**文件不存在也算什么都没说**，而且**服务启动时会替你写一份**：`harness.cap.providers/ensure-config!`
（由组合根 `harness.edge.http/start!` 在开机时调用）给一个没配置过的家写下一份带注释的空骨架——
一个刚装好的人应该有一份能打开编辑的文件。**读侧不写**：`harness.infra.home/config` 把一个不存在的
文件读成空的，所以离线工具、设置面板、任何「问这个家说了什么」的调用都不会改动这个家。
这也是那句 `no provider` 里带**绝对路径**的原因——它替下了从前那份「找不到 config.edn」的报错，
而「哪个文件、在哪里」是那份报错里值得留下的半句。

**顶层是闭的**：`config.edn` 从前**就是**默认档（三个旋钮写在顶层，或整个 provider 写在顶层），
那种文件现在读起来是**指名失败**，句子说清「把它们挪到 `:default` 下面」。**但家里已有的那种文件
不会被拒之门外**：开机时 `migrate-config!` 把旧顶层整体搬进 `:default` 写回去（旧的那份留在
`config.edn.bak`），并打印一行。这条是落地后补的，因为「指名失败」对一份**正在被人用**的配置来说
代价太大——第一个真的旧家就是这样一步步被逼成一个空文件的。判据收得很紧：顶层既没有 `:default`
也没有 `:providers`、**并且**带一个旧键（`:provider` / `:model` / `:reasoning-effort` /
`:protocol` / `:base-url`）才算旧形状；写下去之前用读侧同一个形状判据（`check-config`）核过一遍，
所以别的坏法仍然原样留着让读侧的句子去解释。两节都可以缺席：只有 `:providers` 的家里，
会话档照样能从中挑一个厂商（用户级配置**不进库**，见 [home-and-storage](home-and-storage.md)）。


**`:ui` 是有意开的一格**：语言不是「会话起点的旋钮」，塞进 `:default` 会让那一节的说明开始走样，所以它
自成一节、与厂商目录同住一个家——都是**这一个家**的事实。这一节自己也是闭的（今天只认 `:language`，
值是 `:en` / `:zh`），键或值不认识都是**指名失败**；整节缺席是日常情形，语言交给
`harness.infra.language` 那条链去定（`config.edn` → 系统语言 → 终端语言 → 英语）。
**`providers.edn` 退休了**，不再被读：目录搬进了 `config.edn` 的 `:providers`。家里还留着一份就是
**指名失败**——句子说清把条目挪进去、然后删掉那个文件。一个看着还权威、写进去却什么也不发生的文件，
正是这个目录存在的意义要杀掉的那种静默空操作。

用户条目**逐字段、逐 model** 合在内置表之上，所以 `{:providers {:openrouter {:base-url "…"}}}` 是一条
合法的「补丁」：只说自己要改的那一件事，其余借内置的。

**provider 是厂商（endpoint），model 挂在它下面。** 每个 model **必须**声明 `:input` / `:output`，
词汇表就是本 harness 真搬得动的类型（`:input` ⊆ `#{:text :image}`，`:output` ⊆ `#{:text}`）。
声明一个搬不动的东西是谎话。

两个数字（`:context-window` / `:max-output-tokens`）**可选**，都是**报告用，不是执行用**：

- 本仓**不数 token**，所以 `:context-window` 不拦任何 run——拿近似值去拦，是把谎话写进错误信息；
- `:max-output-tokens` **不写进请求体**，输出上限仍是厂商默认值；
- 读它们的人是**选模型的人**：`GET /api/model`、日志两行、`active-provider`。

一个能力位（`:instruction-updates`）也**可选**，取值是闭集 `:in-place` / `:replace`，**缺省 `:replace`**：
它说这个端点**收不收对话中途的 `developer` 消息**（`.scratch/instruction-updates` 决策 5）。
保守的一档是有理由的——不声明只是少省一次前缀，**错发一条厂商不认的消息是整个 run 起不来**，
所以它挑的是能恢复的那一半。缺省落在**解析**那一步（`fold-and-assemble`），不落在文件里：
报告（`model-row`）照文件说，没写就是没有这个键——「没说」与「说了 `replace`」是两件事，
设置页的三态控件靠这个分别（见 [client](client.md)）。这个值也是**闭集**，写别的名字在
`check-model` 里**指名报错**，不静默退化。

**校验会指名报错**：未知键（`:context_window` 这种拼错）、未声明的 model id、搬不动的模态、
非正整数的数字、`max-output-tokens > context-window`——四条都在加载时停下，不静默丢弃。
**旧扁平形状不读不迁移**（provider 自己就是一个裸 model 字符串而没有 `:models` 表）——指名报错并说明该写成什么。

内置表里的主流 model 带真实数字，**逐条读自厂商现网列表**（`:as-of` 标在表上）；核对不到的一律留空，
不写凭记忆的数——内置表里的 id 已经因为凭记忆写错过一次。

## 三个旋钮，三档

会话的 provider 由**三个旋钮**描述：`:provider`（厂商名）、`:model`（该厂商的一个 id）、
`:reasoning-effort`（provider 不认识的约定，见 `llm`）。

解析**低 → 高，逐旋钮合并**——一档只填它要动的旋钮，其余落回上一档：

| 序 | 档 | 来源 |
|---|---|---|
| 1 | 默认档 | `config.edn` 的 `:default` 一节：三个旋钮，或一个 **inline** 描述的 provider（逃生门） |
| 2 | 会话档 | `POST /api/model`——composer 的选档器写进来（`clear: true` 丢回下层） |
| 3 | 本次请求 | AG-UI 入参顶层的 `provider` / `model` / `reasoning-effort`，只影响这次 run |

**换 provider 不指定 model，就落在新厂商的默认 model 上**——endpoint 随厂商走，所以「只换厂商」是一个旋钮
就能表达的动作。

**写进档位的目录属性必须指名失败**：`:context-window` 这类字段写进 `:default` 是**指名报错**（并说明该写在
`:providers` 里那个 model 的条目下）；会话档更早一步就挡下了——`POST /api/model` 只认三个旋钮加 `clear`，
多一个键当场指名拒绝。`selection` 只取三个旋钮，从前静默丢弃正是要杀掉的那种失败形态。

`POST /api/model` **先解析后写**：provider 名不在目录里、或 model id 不是该 provider 声明的，当场指名失败、
session 保持原样、日志里一行不落。先写后败会把一个每轮都跑不起来的配置
钉在会话上，而报错要等到**下一次** run 才出现。

## api-key

- 密钥在**一个地方**解析（`harness.infra.home/env-value`；私有的 `api-key` 现在只是它的一行调用），
  在**一个地方**挂上（`resolve-provider` 的返回）。**那个取用口有两个用户**：provider 的
  `HARNESS_API_KEY` 与 `web_search` 的三个厂商键（`BRAVE_API_KEY` / `EXA_API_KEY` /
  `TAVILY_API_KEY`）——「一个人放在仓库外面的秘密」
  是同一类事实，两处各写一份查找，迟早会对优先级各有一套说法。
- **自省回答的任何深度都不出现它。** 序列化器有一张 `never-rendered`（`:api-key` 在里面），
  任何调用方——包括将来的调用点、写错的地方、或者一个「渲染一切」的 helper——拿到的形状里都没有它。
  日志里只有 `:api-key :stripped` 这个「被剥掉了」的事实。
  **这是防手滑的护栏，不是安全边界**（同一条列表也是「可渲染」与「可自省」共用的那份，一份名单两个用途）。
- `.env` 里的值**优先于**真实环境变量；`.env` 每次重读。
- **一家厂商一把钥匙，名字由 id 派生**（`credential-name`，公开）：id 全大写、非字母数字换成下划线、
  缀 `_API_KEY`——`:acme-gateway` → `ACME_GATEWAY_API_KEY`。查找顺序是**本 provider 的那个名字 →
  全局 `HARNESS_API_KEY` 兜底**，所以只写过全局钥匙的家、内置三家、以及 `:default` 里的 inline 描述
  （没有名字，因此只有全局那一档）都照旧能用。派生函数是**全函数且不单射**（`:a-b` 与 `:a_b` 撞名），
  这不是缺陷而是任何「能当环境变量名的规则」的固有性质；挡住它的是表单的 id 判据
  （`^[a-z][a-z0-9-]*$`，一个 provider 一种写法）。
- **顺序由 `harness.infra.home/env-source` 一处定**，而且是**先源后名**：`.env` 会对**每一个**候选名字
  问一遍，之后才轮到环境变量。名字的「具体优先」（本 provider 的名字压过全局）只在**同一个源内部**成立——
  否则一个导出在 shell 里的变量就会悄悄盖掉人明明编辑过的那个文件，而那正是 `.env` 优先这条规矩要防的事。
  `env-value` 是同一条顺序的单个名字版本（搜索键用它）；一个规则、两个问法，没有第二份会漂的实现。
- **`api-key-source` 多报一个 `:name`**：有钥匙时是**赢的那个名字**，没有时是**会最先去读的那个名字**
  （也就是人要加的那一行）。只报「有没有」会让人自己从 id 推名字，而那一步正是这个特征要消掉的动作。
- **这条纪律由 `prompt.md` 的 secrets 条款与测试守着**：Clojure 结构上挡不住 `eval` 的 var-quote / `resolve`，
  屏障是写下来的规矩，不是一堵墙。

## 写的一侧：设置表单改的就是这个文件

`config.edn` 从前是**手编**的，现在**两个界面页会写它**（General 写 `:default`，Models 写 `:providers`）。
三条规矩，每一条都是界面许给人的话：

- **先校验整份新配置，再落盘。** 校验用的就是读的那份代码（`user-catalog`），所以「它校验过了」
  不会随时间变成两件事。被拒的那次**一个字节都不写**（连 `.bak` 都不动），服务端那句原话回给表单。
- **原子替换 + 一代备份。** 临时文件 + rename（`harness.infra.home/spit-atomically!`），写坏一半的
  `config.edn` 是一个起不来的家；改写前那份留在 `config.edn.bak`。
- **只碰它要改的那一节。** 改 `:providers` 不动 `:default`；删一条 provider 也**不会**顺手清掉
  `:default` 里对它的引用（那是写一节没被要求写的东西）——所以**`:default` 指着它时删除被拒**，
  句子里说清「先把默认档指到别处（General），再删这条」。同理，删 provider **不删 `.env` 里那行密钥**：
  那是人手写过的可能，且留着无害。

**EDN 注释保不住**：一份 EDN map 是整份重写的，写入器不保留注释（也不为一个这么小的文件引依赖）。
所以写出来的文件**头部自带一段说明**，而改写前那份就在旁边。手编这个文件仍然可以——表单会把它读回来。

### 表单的入口：`GET /api/providers` 与三条写入

- `GET /api/providers` 是**只读**的一份现成目录：每条带**来源**（`:builtin` / `:user` /
  `:builtin-patched`，三种不同的编辑，页面得把它们分开）、endpoint、model 表、**凭据名**与密钥事实，
  另带可选的协议、思考档列表与 `:default` 现状（渲染过的：命名形状给三个旋钮，inline 形状给 endpoint）。
  每条都是这里自己拼的字段，**任何深度都没有密钥值**。
- `POST /api/providers` 新建或改写一条（**id 的判据只对新的 id 严格**：文件里已有的条目保留它自己的写法，
  改写不是重命名）；`POST /api/providers/<id>/remove` 去掉一条。
- `POST /api/providers/models` 问厂商它的 model 列表——**本特征唯一一次出网**，因为凭记忆打的 model id
  正是内置表自己注释里记着犯过的那种错。它**不写任何东西**（文件、库、日志都不动），
  密钥可以由表单临时带（试一把还没落盘的钥匙），否则按 `api-key` 的规矩解析。测试里用
  `providers/*list-models*` 这个缝把它换掉，**测试不出网**。
  
  **答案现在是「行」而不是裸 id**（`.scratch/instruction-updates` 票 04）：`{:models [{:id "gpt-x"
  :instruction-updates :in-place} …]}`。多出来的那半句是 catalog 的意见——**内置前缀表**
  （`instruction-updates-hints`）命中的家族带上一个建议值，没命中的**没有那个键**。前端只照搬，
  不做前缀匹配。**它不参与解析**：一个模型条目没写这个键、id 又命中规则，run 照旧走 `:replace`——
  「这次 run 按哪一档送」不许有一个不在 `config.edn` 里的主人。
  
  **前缀表逐行要有依据**，而且是有方向的那种（猜错的方向是整个 run 起不来）。今天的行全是
  `:in-place`，每一条都是**厂商自营的 OpenAI 兼容端点**——`developer` 正是那族规范里的角色：
  `gpt-` / `o1` / `o3` / `o4`（OpenAI 自己的 Chat Completions）、`claude-`（Anthropic 的兼容端点）、
  `deepseek-`（DeepSeek 自营）、`kimi-` / `moonshot-`（Moonshot）、`qwen`（阿里 compatible-mode 的
  整个家族，`qwen-` / `qwen2.5-` / `qwen3-` 都算）、`glm-`（智谱）。**最长前缀赢**，并且**斜杠后的那段也
  试**（网关常把厂商写进 id：`openai/gpt-4o-mini`、`moonshotai/kimi-k2` 照样命中）。
  
  **这张表按 model id 说话，而 id 名字是模型、不是端点**——已知的边界，收在这个表旁边：id 前缀相同的
  转发网关未必收 `developer`（2026-09-25 实测：一台 kongming 网关列着 `deepseek-*`，却拿 422 拒掉
  对话中途的 `developer` 消息）。所以建议**看得见、改得动**，而运行时那条规矩始终是文件说了算。
- `POST /api/defaults` 设默认档：**缺席 = 不动那一项，`null` = 清掉那个键**（前者是「别管我的 model」，
  后者是「别再选 model」，两件不同的事）。**命名一个 provider 是替换整档**，这也是 inline 描述唯一的出路。
  先解析后写，与 `POST /api/model` 同一条规矩。

## 思考模式：`reasoning_content` 的往返

**请求带 `reasoning_effort`（三个旋钮之一）时，厂商进入思考模式，并要求历史里每条 assistant 消息
都把 `reasoning_content` 送回来** —— 缺了就是 HTTP 400（`The reasoning_content in the thinking
mode must be passed back to the API.`），**即使那一轮模型根本没有推理**。这条不是一个厂商的脾气：
DeepSeek 系的网关都这么要求，而**空串也算「送回来了」**。

于是这条链上有两个各自成立的事实，缺一条就断：

1. **厂商说「这轮没有推理」用的是空值，不是一个缺席的字段。** 它的每一块 delta 都带这个键（值为 `""`）。
   我们的 `llm/consume-sse` 因此按「厂商**提到过**这个字段」保留它——空串也保留——而不是按「有没有文本」。
   这是历史上出过事的那一步：早先的写法是「有文本才带键」，于是那一轮的历史里没有键，下一轮被厂商拒掉。
   反过来，一个**从没提过**这个字段的厂商（OpenRouter 有时如此）仍然不会凭空多出一个键。
2. **历史不是只有我们组装的。** 下一轮的历史来自服务端手里那场会话——里面有工具结果、注入块、
   上一轮别处放进去的条目，它们都不必知道某个厂商的字段要求，
   所以 `llm/thinking-mode-history` 在**请求发出去之前**把缺的补成空串（**只补空串，不编内容**），
   并且**只对思考模式生效**。它由边在写 `message` 审计行之前调用——那行的契约是「LLM 真正看到的，
   逐字」，补在 `stream!` 里会让日志与发出去的不一致。

**两处证据**（真厂商，2026-09-16）：`.scratch/reasoning-round-trip/evidence/` 里同一份历史，
`r8` 缺键 → 400，`r9` 补一个空串 → 200。测试侧 `harness.fake` 的「严格思考模式」
（`scripted` 的 `:thinking`）就是那个厂商：句子照抄，供套件复现这类 400。

## 两条测试/工具缝

```
scripted-pins     thread-id → 一整个 provider（测试与 e2e server 用它顶掉真解析）
session-overrides thread-id → 会话自己的档（就是第二档）
```

两者都是 public 但**被 prompt.md 点名禁碰**（含 var-quote 与 `resolve`）。

## wire：出去的样子

`wire` 把 provider 或选择转成**JSON 可写、且集合有序**的形状（集合渲染成排过序的字符串向量），
因为「两次否则相同的 run 不该只因为集合顺序而产出不同的日志行」。
日志两行与 `GET /api/model` 都从它出来。

## 只读的生效配置：`providers/settings`

`GET /api/settings?threadId=..` 背后的那个函数，也是「设置」面板的全部内容。它回答四件事：
**在用什么**（三个旋钮 + 目录解析出的 endpoint 与模态）、**每个旋钮是哪一档选的**、**家目录与它的
规则**、**哪几份文件在**。key 只报 `{:present? .. :source :env-file|:environment}`。

- **值全部现读**：`config.edn`（两节一起）/ `.env` / 会话档每次调用重读，改一个文件再问一次
  就是新答案——不重启、不碰库。**这也是「配置不进库」在界面上唯一看得见的地方。**
- **key 的处置是「取差集」**：结果整棵树经 `postwalk` 减掉 `never-rendered`，不是逐字段判断
  「这个能不能显示」。白名单的失效方式是「加了个字段没人判过」，表现为面板悄悄少一行；减法的失效面
  只剩「加了秘密却没进那张表」。路由再经 `wire` 渲染一次，同一张表——所以哪怕有人把整个解析结果
  并进这个答案，序列化器也会剥掉它。
- **`api-key-source` 不调用 `api-key`**：它只解析出「有没有 / 从哪来」，值、长度、前缀都不过手。
  包一层再「记得不返回」是同一件事的另一种写法，区别是前者手里从来没有秘密。
- **「哪一档选的」从折叠本身反推**：对每个旋钮，赢家是**最后一个报了它、且它的前缀折叠出来仍等于
  最终值**的那一档。另写一遍 fold 就会把它那条唯一特例（`:model` 绑在 `:provider` 上）抄错，
  而抄错的表现正是**面板解释了一个解析没做过的选择**。没人报的旋钮答 `:catalog`——provider 的默认
  model 谁都没选，由那条目录项定的。
- **解析不了时失败就是答案**：未知 provider / 未声明的 model / `config.edn` 不存在都指名抛错，
  路由把它包成 400 的原话，面板原样显示。半编辑状态的配置是人遇到这件事最普通的方式。
- **只读到底**：不写文件、不碰库、不注册任何东西——所以 run 进行中调用它是安全的。
