# providers：厂商、model、三档解析

`harness.cap.providers` 装着 provider 这件事的两半：**目录**（有哪些厂商与 model）与**谁赢**（本次用什么）。

## 目录的形状

```edn
{:openrouter {:protocol :openai-completions
              :base-url "https://openrouter.ai/api/v1"
              :model    "anthropic/claude-sonnet-4.5"        ; 该厂商的默认 model id
              :models   {"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}
                                                        :context-window 1000000
                                                        :max-output-tokens 64000}
                         "deepseek/deepseek-v4-pro"    {:input #{:text} :output #{:text}}}}}
```

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
| 1 | 默认档 | `config.edn` 的三个旋钮，或一个 **inline** 描述的 provider（逃生门） |
| 2 | 会话档 | `session-configure` 工具调用（**经人工审批**后写入） |
| 3 | 本次请求 | AG-UI 入参顶层的 `provider` / `model` / `reasoning-effort`，只影响这次 run |

**换 provider 不指定 model，就落在新厂商的默认 model 上**——endpoint 随厂商走，所以「只换厂商」是一个旋钮
就能表达的动作。

**写进档位的目录属性必须指名失败**：`:context-window` 这类字段写进 `config.edn`、写进
`session-configure` 调用、写进一次 run 的入参，三处都是**指名报错**（并说明该写在 provider 目录里那个 model
的条目下）。`selection` 只取三个旋钮，从前静默丢弃正是要杀掉的那种失败形态。

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
- **这条纪律由 `prompt.md` 的 secrets 条款与测试守着**：Clojure 结构上挡不住 `eval` 的 var-quote / `resolve`，
  屏障是写下来的规矩，不是一堵墙。

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

- **值全部现读**：`config.edn` / `providers.edn` / `.env` / 会话档每次调用重读，改一个文件再问一次
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
