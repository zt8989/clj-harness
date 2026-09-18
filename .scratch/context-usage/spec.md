# spec: 上下文占用 —— model 左边那颗圈

**参考**：牛总给的一张截图——model 名左边一颗小圆环，悬停一行「上下文已用 29%」，点开一个面板：
头部左边「上下文已用 29%」、右边「~76.3K / 262K」，下面一条按占比分三段的堆叠条，三行图例
（系统提示词 `~1.8K` / 工具定义 `~12.9K` / 对话消息 `~50.7K`）。参考的是**要回答的问题**与那几个格的位置，
不是像素。

**一句话**：composer 的 action row 里、model **左边**多一颗圈，说这场会话**最近一次模型调用**把模型的
**上下文窗口**填到了多少，以及填进去的三样各占多少。它读的是**记录**（`~/.clj-harness/...jsonl`），
不是客户端手里的那份对话。

## 问题

「这个模型还能装多少」有三个数，客户端一个都没有：

1. **分子**只有厂商知道——`usage.prompt_tokens`，记在那一次**模型调用**的 `model/end` 行上（`composer-status`
   已经落地）。客户端估一个（`chars ÷ 4`）就是编。
2. **分母**是目录为那个 model 声明的 `:context-window`（`model-limits` 已经落地），但它**不在记录里**：
   `provider/init` 只写一次，而且**脚本替身（pin）伺候的会话一条都不写**——`--scripted` 走查正好全是那种会话。
3. **三样各占多少**没有任何人报过：厂商只报总数，不报这个三分。

## 决策

1. **分母记在**那一次调用自己**的行上**（`model/start` 多一个 `:context-window`，目录没声明就不写这个键）。
   与「解析结果是记下来的，不是回头现推的」同一条：换过 model 之后现解出来的是**新** model 的窗口，
   而分子是**旧**那一次的。这也让 pin 伺候的会话第一次有了分母可看。
2. **一个端点，不是两个。** 那一节并进 `GET /api/threads/<stem>/stats` 的载荷（`context`），
   **一次读盘、两个折**（`stats/records->stats` 与 `context/records->context`）：条子与那颗圈因此是
   同一个瞬间的同一条日志。读侧是新 ns `harness.edge.context`——记录的第**四**个读者，
   与 `stats`（数数）/ `trajectory`（看内容）并排。
3. **分子与分母来自同一次调用。** 分子 = 最近一次**报过** `prompt_tokens` 的调用的用量（没报的那次跳过，
   不把上一次的数抹掉）；分母 = 那一次调用自己行上的窗口，老日志（写在票 01 之前）回退读
   `provider/init` / `provider/changed` 那一半时间线；两处都没有 → 没有百分比。**不从目录现解。**
4. **三分是摊出来的，不是各自估的。** 厂商报的是总数，所以三个篮子按**记录的字符数**（UTF-8 JSON、
   `:escape-unicode false`，三处同一口径）去摊那个总数——它们因此**恰好加起来**等于分子。一条画不满的
   堆叠条是在说一句假话，而第四格「其它」会是一格没人量过的颜色。**代价照实写**：工具表是 JSON、
   token 密度高于对话正文，摊法会低估它。
5. **缺就是缺。** 没有分子（一次调用都没报过）、没有分母（目录没声明）、或者没有会话（新会话没有日志，
   404）——`contextCells` 一律答 `null`，那颗圈**整个不渲染**。环本身就是「占了多少」，画一个没有分母的
   环是在画一个不存在的数。面板挂在圈上，所以它的缺席跟着这一条走。**最后那次调用所在的 run 还没写完**
   （没有终帧、或返回侧还没落盘）时只缺三分：厂商的数已经在记录里了，不拿半份消息凑一个三分。
6. **百分比服务端算完**，客户端不自己除——与 `cacheHitPercent` 同一条先例。
7. **`~` 照参考图来，但只在描述这次 prompt 的数上**：头部的**用量**与三行图例各一个，**窗口那个数不带**。
   三个篮子是估算；头部那个用量虽是厂商自己数的，它与那三个数说的是同一件事，标法就得一致——
   一个面板里混两种语气，等于要人在一行字里读出「哪个是量的、哪个是估的」。这个 `~` 说的是
   「token 数按厂商的分词器算」，不是「这个数不准」。
8. **数字写法与状态条不同，而且是刻意的**：`formatContextTokens` = 千以上一位小数、末位 `.0` 去掉
   （`1.8K` / `12.9K` / `262K`）。状态条的 `formatTokens`（`812k` / `2.9M`）是会话累计、百万级，取整就够；
   这条是单次调用的上下文、千级，三个篮子的可比性正是要点。两个函数放在 `lib/format.ts` 一处，
   `formatTokens` 一个字不改。
9. **取数只有一份实现。** 状态条与那颗圈共用 `components/composer-numbers.tsx` 里那一处：挂载 / 切会话 /
   助手消息多一条 / run 结束。**外加两次按需的追一问**——run 结束后隔一拍再问一次（记录的写者比它自己的
   终帧晚一拍：run 的 message 尾巴落在 `:run/done`），打开面板时再问一次（那一下正是有人在问）。
   两次都不是轮询：一次 run 只多一次，不开面板不问。
10. **颜色一处表**，与轨迹的颜色表对齐：系统提示词 = `primary`（轨迹的 `system`）、工具定义 = amber
    （轨迹的 `tool`）、对话消息 = sky（人的消息）。圈与面板读同一份 `parts`，不可能各说各话；
    篮子缺席时圈只用一色画出份额。
11. **文案两种语言**（`format.json` 的 `context.*`），界面文案英文/中文按页面语言走，docs 仍中文。

## 非目标

- **不数 token、不拦超窗、不做 tokenizer**：这一节全是**报告**，与 `model-limits` 那条纪律同一条。
- **不改 AG-UI 协议**：不加帧、不进库（记录不是状态）。
- **不做历史趋势**（「一轮一轮涨上去」的样子）、不做按轮/按调用的分解、不做成本推算。
- **不碰** `components/assistant-ui/elements/thread.aui.tsx` 那份抄来的源码。
- **不重命名** composer 上方那条**上下文条**（项目/分支）：`CONTEXT.md` 里把两个词分开写清楚了。

## 验收主线

1. `model/start` 行带上那一次调用的 `:context-window`（目录没声明就没有这个键）。
2. `GET /api/threads/<stem>/stats` 的 `context` 一节：分子、分母、百分比、三个篮子；三块之和**恰好**等于分子。
3. 圈画在 model **左边**、悬停说出百分比、点开是三段的堆叠条与三行图例；没数就一个都不画。
4. 一轮跑完，数字自己动。
5. 全量绿 + 真浏览器走查（`.scratch/context-usage/evidence/`）。

## 票清单（`.scratch/context-usage/issues/`）

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 记录：这一次模型调用跑在多大的窗口里 | — | `model/start` 的 `:context-window`；替身能声明（走查与套件才有分母） |
| 02 | 词与读侧：三分与百分比（并进 `/stats` 的载荷） | 01 | `harness.edge.context` 的纯折 + 载荷那一节；`CONTEXT.md` 立「上下文占用」 |
| 03 | 前端前置：composer 的数字只问一次 | — | `components/composer-numbers.tsx`：取数与「什么时候取」只有一处 |
| 04 | 圆圈与 hover | 02, 03 | model 左边的环（按三样分色）、悬停的句子、`contextCells` 与它的套件 |
| 05 | 点击后的面板 | 04 | 头部的两个数、三段堆叠条、三行图例；`ui/popover.tsx` |
| 06 | 收口：文档与全量走查 | 05 | 现状文档、两套全量、真浏览器证据 |

## 落地记录（2026-09-18，分支 `context-usage`，工作树 `.worktrees/context-usage`）

**六张票一次落地**（票 03 与票 01/02 无依赖，并行写在同一个工作树里）。

### 与票面不同的地方（都是实现时定的，理由在现场）

1. **票 04 说的 `lib/context.ts` 没有出现**：那一节的载荷已经由票 03 的 scope 送到手边，再包一层取数
   只是多一个没人用的文件。类型与纯函数都进了 `lib/format.ts`（`ContextPayload` / `ContextCells` /
   `contextCells` / `formatContextTokens`），与 `StatsPayload` / `statsCells` 放在一起——「这一节的形状」
   本来就该与载荷的形状同一处。
2. **票 03 的 `hooks/use-session-numbers.ts` 变成了 `components/composer-numbers.tsx`**：状态条与那颗圈
   在 DOM 上是两棵子树（一个在 `ComposerFrame` 的 children 之后，一个在 action row 里），一个 hook
   各调一次就是**两次请求、两个瞬间**。所以取数下沉到 `ComposerFrame`，用 React context 交给两个读者——
   一个 scope、一次请求、一个时钟。
3. **多了两次按需的追一问**（决策 9）。第一次走查当场撞上它：一轮跑完，圈停在上一次调用的数上——
   记录里 run 的 message 尾巴落在 `:run/done`，比客户端看见的终帧晚一拍，而那一拍里三分还数不出来。
4. **`trajectory/run-segments` 提成了公开**（`defn-` → `defn`）：`context` 需要「哪些记录是一个 run、
   一条 message 在哪一侧」这条规则，第二份实现就是第二次机会在「run 从哪开始」上各说各话。
5. **`harness.edge.stats/log-stats` 在端点里换成了 `read-records` + `records->stats`**，因为一次读盘要喂
   两个折；`log-stats` 自己原样留着（测试与别的调用点照旧）。

### 撞出来的两个坑（都不在票面上）

- **docstring 里的裸引号会把整个 ns 读坏**：`shares` 与 `records->context` 的文档串里写了
  `[["system" …]]` 与 `{:key "system" …}`——Clojure 在第一个 `"` 处收尾，于是 `defn-` 的参数表
  变成一个字符串。报错是 `Syntax error macroexpanding clojure.core/defn-` + `vector? at :params`，
  离现场很远。改法是文档串里写字面（键写成 `system`），套件与全量都跑得出来。
- **测试里同一处差一个右括号**（`context_test` 的端点用例），报错是
  `EOF while reading, starting at line 295`——也是跑到才看见的。

### 证据（`.scratch/context-usage/evidence/`）

- `ring-hover.png`：composer 那一块，圈在 model **左边**、悬停着的句子（`上下文已用 55%`）。
- `panel-open.png`：点开后的面板（头部 `上下文已用 55%` / `~70K / 128K`，三段堆叠条，
  三行 `系统提示词 ~7.2K` / `工具定义 ~49.7K` / `对话消息 ~13.1K`）。
- `ring-in-the-browser.dom.html`：那一块的 DOM（`data-slot="composer-context-usage"` 的
  `aria-label` 与三段 `stroke-dasharray`，`data-slot="composer-context-panel"` 的三行图例）。
- `model-call-lines.txt`：同一条会话记录里的行——`model/start` 带 `:context-window 128000` 与工具表
  （字符数），`model/end` 带 `prompt_tokens 60000` / `70000`，`message` 行的角色与字符数。
  那一对一对就是分子与分母的来源。
- 走查用的是 `node scripts/dev.mjs --scripted`（临时家、脚本替身、两个家退出即删）：
  **没有跑活厂商**，替身那个 128000 是**替身的**数字（`test/harness/fake.clj`）。

### 报数

- 后端（分支上的最后一次，合并前）：`node scripts/test.mjs --backend` → `Ran 871 tests containing 11321 assertions. 0 failures, 0 errors.`，
  退出码 0；`developer home store before this run: present (19050496 bytes, left alone)`，
  **没有 ISOLATION FAILURE**。（上一个可用基线是本次落地中途的 860 / 11269；`main` 上的对照见提交说明。）
- 前端：`node scripts/test.mjs --ui` → `Test Files 1 passed (1)` / `Tests 44 passed (44)`
  （`EXPECTED_CASES` 39 → 44，新增 5 条在 `test/suites/context.ts`）。
- `node scripts/test.mjs`（三条腿一起）→ `test.mjs: ok`，退出码 0。

### 合并进 main 之后的一条修复（`9767c8e`，同一天）

牛总在真机上问「5173 上为什么没有那颗圈」，顺着查出了读侧的一个真 bug——**票 02 那条「老日志回退」
从来没给出过任何值**：

1. `timeline-window` 的过滤器写成 `#({"provider/init" "provider/changed"} (:kind %))`。`#(` 是匿名函数
   的读宏，里面的花括号因此是 **map 字面量**而不是集合：拿它当函数调用，只有 `provider/init` 是它的键，
   `provider/changed` 永远匹配不上（它返回的那个真值还是 map 的**值**，所以 init 那一半看着像是好的）。
2. 两条 provider 行的形状本来就不同：`provider/changed` 把解析结果嵌在 `:resolved` 下，
   `provider/init` 的**载荷就是**那份结果（`harness.edge.http/provider-line` 把 wire map 平铺在顶层，
   `http_test` 里钉着这个形状）。原来只读嵌套那一处，对 init 行一律答 nil。

两条叠起来，任何 `model/start` 还没有 `:context-window` 的会话都会得到「这个 model 没声明窗口」——
而那正是这条回退存在的理由。改法：读法提成一个 `window-of`（docstring 写明两种形状，以及为什么读侧
不该关心碰到的是哪一种），过滤器写成 `#(contains? #{...} (:kind %))`；`context_test` 里两个手搓夹具
换成真形状并各加一条断言（init 平铺 / changed 嵌套），定向跑 11 用例 / 45 断言全过。

**真机上的那条「没有圈」与它无关**：牛总那份 `~/.clj-harness/config.edn` 里 `:kongming` 的
`deepseek-v4.1-flash-expires-on-0910` 只声明了 `:input`/`:output`，没有 `:context-window`——
分母缺席，圈按「没数就不画」不渲染；分子是有的（那条会话最后一行 `model/end` 报 `prompt_tokens 364010`）。
这是配置的事：给那个 model 条目补上它真正服务的窗口，下一次调用成行圈就有。

### 合并（`b7fe690`）

`context-usage` 合进 `main`（`--no-ff`）。合并时 main 已经往前走了四个提交（`immutable-data` 十一张票、
`job-output` 三张票、`dev.mjs --tmux`），五个文件两边都动过（`CONTEXT.md`、`README.md`、
`docs/architecture.md`、`src/harness/edge/http.clj`、`test/harness/test_runner.clj`）——都自动合上了，
没有冲突。**合并后的树上重跑了一遍全量**（这才是这条分支的最终报数）：

- 后端 `node scripts/test.mjs --backend` → `Ran 895 tests containing 11493 assertions. 0 failures, 0 errors.`
- 前端 44 个用例，`npm run build` 过，`test.mjs: ok`（退出码 0）。

### 落地时**没有**做的事，如实写在这里

- **没跑活厂商**：走查与套件全程脚本替身（与这个仓库的既有纪律一致）。所以「厂商报的
  `prompt_tokens` 与目录声明的窗口在真模型上对不对」没有证据——**有的**是：那份真录下来的 fixture 里
  四个数字读得回来（`llm_test`，`composer-status` 留下的），以及这一次调用对分母的断言
  （`context_test` 里那几条手搓记录）。
- **摊法没有在真机上调过**：三个篮子的和是**算**出来的（有一条恒等式钉着），但它们各自离真值多远
  （工具表 JSON 的 token 密度、中文正文字符数与 token 数的比）**没有量过**。要量需要真厂商报的
  prompt_tokens 与同一份请求的字节，这台机器上没做。
