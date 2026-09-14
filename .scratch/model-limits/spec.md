# spec: model 的上下文窗口与最大输出 token（目录补齐）

## 缺口

`provider-model-catalog` 落地时，model 条目只有 `:input` / `:output` 两个**必填**的模态集合。模型另外两个
被问得最多的数字——**上下文窗口**（能喂进去多少）与**最大输出 token**（它能吐多少）——无处安放。
`resolved-fields` 只有 `[:protocol :base-url :model :input :output]`，所以端点、日志、解析结果全都答不出
这两个问题；`llm` 发出去的请求体里也没有 `max_tokens`，实际输出上限是厂商默认值。

## 决策

- **两个字段都是可选，写在 model 条目里，与 `:input` / `:output` 同一层**：
  `{:input #{:text :image} :output #{:text} :context-window 200000 :max-output-tokens 8192}`。
  不写 = 与今天完全一致（不是必填，不能要求用户为内置模型抄一堆数字）。
- **它们是目录属性，不是 knob。** 三个旋钮仍是 `:provider` / `:model` / `:reasoning-effort`。
  一个数字的含义是「这个 model 是什么」，跟模态一样属于目录回答的问题，不属于某一档选择的东西。
  因此档位里写它们必须**指名报错**——`selection` 是 `select-keys knobs`，今天写进去会被静默丢弃，
  正是上一个特征要杀的那种失败形态。
- **严格校验**：正整数之外的一切指名报错（0、负数、小数、字符串）；两者都声明时校验
  `max-output-tokens ≤ context-window`；model 条目里没人读的 key 也指名报错（`model-keys` 今天定义了
  却没人用，`:context_window` 这种拼错会被静默丢弃）。宁可加载时停，不要 run 时撒谎。
- **内置表填数，逐条读厂商现网列表，带 `:as-of`。** OpenRouter 的 `/api/v1/models` 直接给出
  `context_length` 与 `top_provider.max_completion_tokens`，DeepSeek / ollama 查官方页面。
  上一轮凭记忆写 id 已经写错过一次（`deepseek-chat` 已作废），数字更不该凭记忆。核对不到的模型留空。
- **两个数字都是「报告用」，不是「执行用」。**
  - `:max-output-tokens` **不发给厂商**：请求体不带 `max_tokens`，输出上限仍是厂商默认。
  - `:context-window` **不拦任何东西**：本仓没有 tokenizer，任何 token 估算都是近似，拿近似值去拦
    run 是把一句谎话写进错误信息。
  - 它们服务于「选模型的人」：`GET /api/model`、日志里的 `provider/init` 与 `provider/changed`、
    `active-provider`。这与 `:output` 只有 `:text` 是同一条纪律——声明一个没人执行的东西前先问它
    有没有人读；这里有人读（端点和日志），但没人拿它当规则。
- **用户覆盖内置 model 时按字段盖**，不整条替换：只想改一个数字不该被迫重抄 `:input` / `:output`。

## 非目标

- 不做 token 计数、不做上下文裁剪/压缩、不做超窗拦截。
- 不把 `max_tokens` 加进请求体（见决策）。
- 不改三个旋钮、不改 JSONL 其它行的形状，只扩 provider 两行里 `:resolved` 的字段。

## 验收主线

离线全量 `harness.test-runner` 全绿；`GET /api/model` 与 provider 日志行如实给出这两个数字，未声明时
字段缺席而非 `null`；换 model 时数字随之改变（证明读的是目录）。

## 落地与验证（2026-09-14）

**代码。** `harness.models`：`counts` / `model-keys` / `catalog-fields` 三个字段集，`count-of`（正整数
否则指名失败）、`limits`（交叉校验 `max-output-tokens ≤ context-window`）、`check-model` 复用
`unknown-keys!` 让 model 条目的未知键指名失败；`resolved-fields` 加上两个数字，`assemble` 把 model 的
整个声明作为**一个单位**带出（`select-keys m model-keys`，不是逐字段抄——第三个数字将来加了不会漏）；
`selection` 对 `catalog-fields` 指名失败；用户覆盖 `merge-with merge` **按字段盖**（含单个 model）。
`harness.memory/active-provider` 与 `GET /api/model`（`models/wire`）因此自然带上，日志两行的
`:resolved` 同样（`provider-line` 用的就是 `wire`）。`harness.llm` 一字未改：**`max_tokens` 在全仓
grep 为 0**，`:max-output-tokens` 确实没进请求体。`session-configure` 的拒绝**必须留在工具里**：change
map 由三个旋钮重建，交给目录的档位守卫是拦不住的（见 `tools.clj` 里的注释）。

**测试。** 新增 22 条，全量 **189 tests / 930 assertions，0 失败 0 错误**（基线 176/848）。覆盖：正整数
之外的四种写法各指名失败（含 provider 与 model id）、越界指名报错并带出两个数、`:context_window` 拼错
指名失败、按字段盖只改一个数字、inline 只声明一个数字也算「声明了东西」、三处入口全部**指名失败而非
被忽略**（config 档 / run 入参 / 工具调用，且工具那条断言「不写 override、不落 changed 行」）、
`/api/model` 的答案是**数字**并随 model 改变、`alpha-bare` 未声明时 `contains?` 为假、`provider/init`
与 `provider/changed` 的 `:resolved` 带上数字、一次 run 命名 count 时终止为 RUN_ERROR 且不落 init 行。

**厂商数字（逐条读现网，非记忆）。** OpenRouter `/api/v1/models` 的 `context_length` 与
`top_provider.max_completion_tokens`：sonnet-4.5 1M/64000、haiku-4.5 200K/64000、nemotron-3-nano
256K/65536、gpt-4o-mini 128K/16384、deepseek-v4.1-flash 1048576/384000、deepseek-v4-pro
1048576/393216；DeepSeek 官方页 1M 上下文与 384K 最大输出；ollama library 页按 tag 给窗口。
**一处需要写下来的复核结论**：同厂商两个模型的输出上限确实不等（384000 vs 393216），两个都是厂商自己
的数字，不是 384K 的两种写法——已在 `builtin-raw` 的 docstring 里写明，防止下一个人「对齐」成同一个数。
ollama 不公布输出上限，所以那两条只声明 `:context-window`。

**偏离规格的三处，都是有意的：**
1. `selection` 的指名失败覆盖**全部** `catalog-fields`（不止这两个数字），与既有的「没人读的键要报出来」
   同一条纪律；规格只点了这两个，方向一致、范围更宽。
2. `:as-of` 留在 `builtin-raw` 的 docstring 里（`2026-09-14`），**没有**做成一个字段或 `def`——一个没人
   读的 key / var 正是本仓要杀的静默装饰，日期写给读这份表的人看。
3. 档位里其它任意未知键（如 `:temperature`）仍被 `select-keys` 静默丢弃，超出本特征范围，未动。

**开发机真实家目录。** `~/.clj-harness/config.edn` 用的是 inline 形式（`provider/init` 行里没有
`:provider`，只有 protocol/base-url/model，可证），已按新形状补上该模型的两个数字并实测：
`active-provider` 答出 `:context-window 256000` / `:max-output-tokens 65536`，且都是整数（`Long`）。
写入过程有个事故要记：验证时误把真实家目录的 `config.edn` / `providers.edn` 覆盖了，两份原文件没有
git 也没有快照可回滚；`config.edn` 按当天日志里的 `provider/init` 行还原（四个字段逐个对上），
`providers.edn` 无法还原，只能落回 `providers.edn.example` 的内容并在文件头注明是重建的。
