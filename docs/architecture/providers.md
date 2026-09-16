# providers：厂商、model、三档解析

`harness.cap.providers` 装着 provider 这件事的两半：**目录**（有哪些厂商与 model）与**谁赢**（本次用什么）。

## 一份配置，两节：`config.edn`

配置家**只有一个文件**：`~/.clj-harness/config.edn`，顶层恰好两节——`:default`（三个旋钮的默认档）与
`:providers`（厂商目录）：

```edn
{:default {:provider :openrouter :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}

 :providers
 {:openrouter {:protocol :openai-completions
               :base-url "https://openrouter.ai/api/v1"
               :model    "anthropic/claude-sonnet-4.5"        ; 该厂商的默认 model id
               :models   {"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}
                                                         :context-window 1000000
                                                         :max-output-tokens 64000}
                          "deepseek/deepseek-v4-pro"    {:input #{:text} :output #{:text}}}}}}
```

**一个空的 `config.edn` 是一个什么都没说的文件**，这个形状把它拼成 `{}`：`touch config.edn` 正是
「按缺失那句话的指路造出一个文件」之后的样子，而拿「must be a map … not nil」回它，是把一个沉默的
文件说成坏掉的文件。沉默时内置表照旧当地板、没有任何默认档，一轮跑起来遇到的是**那句教你写哪个形状**
的话；而**说了话却不是 map**（一个向量、一个字符串、一个数字）是另一回事，仍然指名失败。

**顶层是闭的，而这个判据本身就是迁移**：`config.edn` 从前**就是**默认档（三个旋钮写在顶层），
那三个键现在是**指名失败**，句子说清「把它们挪到 `:default` 下面」——不读第二种形状，
与目录对更早的扁平 provider 形状是同一条立场。两节都可以缺席：只有 `:providers` 的家里，
会话档照样能从中挑一个厂商（用户级配置**不进库**，见 [home-and-storage](home-and-storage.md)）。

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
| 2 | 会话档 | `session-configure` 工具调用（**经人工审批**后写入） |
| 3 | 本次请求 | AG-UI 入参顶层的 `provider` / `model` / `reasoning-effort`，只影响这次 run |

**换 provider 不指定 model，就落在新厂商的默认 model 上**——endpoint 随厂商走，所以「只换厂商」是一个旋钮
就能表达的动作。

**写进档位的目录属性必须指名失败**：`:context-window` 这类字段写进 `:default`、写进
`session-configure` 调用、写进一次 run 的入参，三处都是**指名报错**（并说明该写在 `:providers` 里那个
model 的条目下）。`selection` 只取三个旋钮，从前静默丢弃正是要杀掉的那种失败形态。

`session-configure` 的 body **先解析后写**：provider 名不在目录里、或 model id 不是该 provider 声明的，
当场指名失败、session 保持原样、`provider/changed` 一行不落。先写后败会把一个每轮都跑不起来的配置
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
- `POST /api/defaults` 设默认档：**缺席 = 不动那一项，`null` = 清掉那个键**（前者是「别管我的 model」，
  后者是「别再选 model」，两件不同的事）。**命名一个 provider 是替换整档**，这也是 inline 描述唯一的出路。
  先解析后写，与 `session-configure` 同一条规矩。

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
