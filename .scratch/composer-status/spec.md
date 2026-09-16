# spec: 会话统计 —— composer 之下的那条状态条

**参考**：牛总给的截图两条并排——左边一个秒表图标 `1 轮 42 步 · 242 tok/s`，右边一个库图标
`2.9M tok · 缓存命中 98%`，两行字都灰，贴在输入框**下面**。参考的是**要回答的问题**与那五格的位置，
不是像素：截图是中文的，本仓库的 UI 文案一律英文（见决策 9）。

**一句话**：输入框下面多一条状态条，把这条会话的**记录**折成五个数——几轮、几步（模型调用）、
输出速度、总用量、缓存命中——它读的是 `~/.clj-harness/logs/<会话>.jsonl`，不是客户端内存里的那份对话。

## 问题

五个数里，客户端**一个都没有**：

1. **缓存命中**只有厂商知道。响应里本来就带着（`usage.prompt_tokens_details.cached_tokens`），
   而 `harness.kernel.llm/consume-sse` 是**读到就丢**——它今天只从每个 chunk 里取
   `choices[0].delta`，`usage` / `finish_reason` / 回声的 `model` 一眼都不看。
   仓库里那份真录下来的 fixture（`test/harness/fixtures/deepseek_sse.txt`）末块写着
   `prompt_tokens 769 / completion_tokens 324 / total_tokens 1093 / reasoning_tokens 296`，
   **这些数字到今天一个都没被读过**。
2. 客户端能自己算的只有 `chars ÷ 4` 那种估算（assistant-ui 的 run-aggregator 就是这么算
   `tokensPerSecond` 的）。拿它当「242 tok/s」，是**编**一个数冒充厂商报的数——本仓库不干这个。
3. 轮与步客户端数得出来，但那只说明「屏幕上现在有几条消息」，不说明这条会话**发生过什么**
   （换台机器打开、日志被重建、列表里别的会话，客户端手里什么都没有）。

于是数据源只有一个：**记录**。后端折好，条子只画折好的东西。

## 决策

1. **数据来源是记录，读侧在后端，客户端不算。** 与 `.scratch/trajectory/spec.md` 的决策 1 同一条。
   条子上五格**全部**来自 `GET /api/threads/<stem>/stats` 那一个端点，不做「三格从客户端拿、
   两格从服务端拿」的混源——混源意味着同一件事有两个时钟，评审时要问「哪个才是对的」。
2. **步 = 模型调用**（牛总 2026-09-16 选定）。一次 `llm/stream!` 就是一步，记录里 `model/start` 一行一步。
   轮 = `.scratch/trajectory/spec.md` 决策 2 的定义：一条用户消息，以及它引起的**全部**输出。
   **中文正文一律写「模型调用」，不写「步」**——`flat-step-rows` 那个特征里的「步」是**行**
   （一次工具调用一行、一次思考一行），与模型调用差着倍率，留着「步」当第二个名字就是留一次改名扫描。
   条子上那格写英文 `step`（UI 文案的规矩见决策 9）。
3. **记录侧的活由本特征自己补**（牛总 2026-09-16 选定）。两条审计行 `model/start` / `model/end`，
   加 `consume-sse` 不再丢用量。字段名与读法**照抄** `.scratch/trajectory/` 已经定下的（`model/end` 的载荷
   `{:usage … :finish-reason … :model …}`，字段**原样**，不重命名厂商的键），所以两个特征不会各说一套。
   `trajectory` 的 04/05 两票因此缩水：`04` 只加 `model/start` 的 `:tools`，`05` 只剩读侧与界面，
   这件事以**加注的复议**记在它的 spec 上（决策 5、票 01 的验收），不去改写它的旧话——`.scratch/` 是历史。
4. **AG-UI 帧一个不加。** 协议不动是这个仓库的硬约束：客户端在对话里一个字都看不到这些数，
   两条新事件在 `harness.edge.ag_ui/convert` 里明确落在「没有帧」那一组。也不落 sqlite
   （记录不是状态，进库要有新表就会被 `harness.infra.db-test` 的两条元断言拦住），不加管理端点之外的读取口。
5. **五个格子都是会话累计**，不是本轮、不是最近一次调用。参考图 `1 轮 42 步 · 2.9M tok` 正是这个读法：
   42 次调用摊下来约 69k 一次，而 2.9M 是这一串调用各自重发上下文的合计。
6. **缺就是缺，不填 0。** 这次调用没报用量，它就不进用量、不进速率、不进缓存的分母；
   厂商一次都没报缓存字段（或整份日志是老的、没有 `model/*` 行），那一格**不画**。
   写 0 是把「没报」说成「报了且是 0」。
7. **轮的判据只有一个实现。** 端点里折的「哪个 `input` 开了新的一轮」就是
   `.scratch/trajectory/` 决策 3 那条（`input` 的客户端消息里出现**新的** `role: "user"` id）；
   悬置恢复那种「同一个 `runId` 的第二个 `input`、没有新用户消息」**归给当前轮**。
   它必须**能用手搓的记录断言**，不是「跑一遍看看像不像」。
8. **条子只在有数时存在。** 新会话、只有 `project/bound` 的日志：一个格都不画（整条不渲染），
   与 composer 上方那条上下文条「对话一开始就收起来」是同一种纪律。
9. **文案英文**（仓库规矩：UI 文案一律英文，只有 `subjectOf` 的步骤行短语是中文）：
   `1 turn 42 steps · 242 tok/s` ／ `2.9M tok · 98% cached`。docs、票面、`CONTEXT.md` 仍是中文。
10. **不做实时流式。** 一次调用的数只有在它 `model/end` 之后才存在，所以条子在**每次调用结束时**刷新
    （客户端看得到助手消息多了一条，就够了），不轮询、不为它加协议。
    一次很长的调用进行中，条子停在上一格——这是诚实，不是卡顿。

## 记录清单（条子的每一格从哪来）

**这张表是闭的**，与 `.scratch/trajectory/spec.md` 的「记录清单」同一条纪律：每一格要么指得到记录里的
某个字段，要么是**本特征新补的那两行**。不许在客户端算、不许拿别的行凑、不许用今天的值冒充当时的。

| 条子上的格子 | 读哪一行、哪个字段 |
|---|---|
| `N turn(s)` | `input` 的 `:messages` 里 `role: "user"` 的 **id 差集**（决策 7） |
| `N step(s)` | `model/start` 的**行数**（一次调用一行） |
| `242 tok/s` | Σ `model/end.usage.completion_tokens` ÷ Σ（`model/end.ts` − `model/start.ts`）秒 |
| `2.9M tok` | Σ `model/end.usage.total_tokens`（该键缺了的那次按 prompt + completion 补，两者都缺就不计） |
| `98% cached` | Σ cached ÷ Σ `prompt_tokens`（cached 的拼法以**真机回来的**那份为准，见票 02） |

条子的值全部由 `harness.edge.stats` 从**行**折出来，端点的 `usage` 里缺的键就是没报（决策 6）。

## 非目标

- **不做每轮/每次调用的条子**（那是 `.scratch/trajectory/` 的 `轨迹` 视图），本特征只有一条会话累计。
- **不做实时流式**（决策 10），**不做**成本/计价推算，不做跨会话聚合、对比、导出。
- **不改 AG-UI 协议**：不加帧、不改任何客户端已经收到的东西。
- **不进 sqlite**：统计是记录的读法，不是状态。
- **不画「上下文窗口用了多少」**：那是 `model-limits` 的地盘，本特征不碰。
- **不给客户端加协议之外的取数口**：条子读的就是那一个 GET。

## 验收主线

一条会话，跑两轮（一轮里含一次工具调用），第三轮故意让 provider 中途失败：

1. 日志里每次调用一对 `model/start` / `model/end`，失败那次也有 `model/end`（载荷里没有用量）。
2. `GET /api/threads/<stem>/stats` 回来的五个数与手算一致：轮 = 3，步 = 调用数，
   `outputTokensPerSecond` 与缓存命中按决策 6 的分母算出来，且**失败那次不在分母里**。
3. 条子画在 composer **下面**、同一个圆角框里；一次调用结束就刷新，句子是英文。
4. 老日志（没有 `model/*` 行）：轮数照旧算得出，其余格**空着**，不画 0。
5. **对照**：两条新审计行落地后，`dev/harness/wire` 的帧校验全过，
   `harness.kernel.frames` 折出来的对话与今天**逐字节不变**。
6. **不轮询**：条子只在一次调用结束与 run 结束时各取一次（网络面板上数得清请求次数），
   一次很长的调用进行中它就停在上一格。

## 跨特征对照

- **`.scratch/trajectory/`（未开工，本特征先落地）**：重合两处——两条 `model/start` / `model/end` 审计行
  （它的 04/05）、以及轮的判据（它的 02）。本特征把它们**先做掉**，读侧的 ns 是 `harness.edge.stats`；
  它那张票写的是 `harness.trajectory`，与 `layer-layout` 之后的实际分层不符（读侧的 `edge.replay` /
  `kernel.frames` 已经分层）。这两处的重新分配以**加注的复议**记在它的 spec 上，旧话不动。
- **`reasoning-round-trip`（已落地）**：`consume-sse` 上一个刚立的规矩是「空值也留着」——
  本特征动的是同一个函数的返回形状，那条规矩一个字不动，改的是**多带一段遥测**。
- **`flat-step-rows`（已落地）**：本特征借它的「步」字面，但数的是**模型调用**（决策 2）。
- **`composer-chrome`（已落地）**：条子挂在它那块 `ComposerFrame` 里、`{children}` **之后**
  （同一个圆角框内、输入框下面），**不碰** `components/assistant-ui/elements/thread.aui.tsx`
  那份抄来的 registry 源码。上下文条在输入框**上方**、对话一开始就收；状态条在**下方**、有数才画。
- **`custom-providers`（已落地）**：`config.edn` 那两节与本特征无关；条子读的是记录，不是配置。
- **`docs/architecture/edge.md:127`** 今天写「读日志的代码只认 `input` / `event` 两种行」——
  本特征的读侧多认两种（`model/*`），那句要改（落在票 04）。

## 交付顺序

`01 → 02 → 03 → 04`，每一步都真的挡住下一步：没有 `model/*` 行就没有可折的用量；没有端点条子没地方取数；
没有条子就无从收口。**不为了「有并行边」把它们并成一张大票**——并起来就是一次要在一个上下文窗口里
做完记录、读侧、视图与文档。

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| ~~01~~ | ~~词与记录：一次模型调用的边界与用量~~ | — | ~~`CONTEXT.md` 立词；`model/start` / `model/end` 两条审计行；`consume-sse` 留住用量；假 provider 也能报；真机日志证据~~ **已落地，见文末「落地记录」；票面已按仓库约定删掉** |
| ~~02~~ | ~~会话统计的折法与端点~~ | 01 | ~~`harness.edge.stats` 的纯折（手搓记录可断言）+ `GET /api/threads/<stem>/stats`；curl 证据~~ **已落地，见文末「落地记录」；票面已按仓库约定删掉** |
| ~~03~~ | ~~composer 之下的状态条~~ | 02 | ~~五格、英文、调用结束刷新；格式化是纯函数并进 UI 套件；真机截图证据~~ **已落地，见文末「落地记录」；票面已按仓库约定删掉** |
| ~~04~~ | ~~收口：文档与全量验证~~ | 03 | ~~`CONTEXT.md` 补齐、`docs/architecture` 跟上、两套全量 + 真机证据~~ **已落地，见文末「落地记录」；票面已按仓库约定删掉** |

## 状态

**四张票全部落地（2026-09-16）。** 分支 `composer-status`。

**收尾时在主仓要做的一件事**：本特征的「在办」那条是**立票当天加在主仓未提交的 `docs/architecture.md`** 上的（与 `session-context` 那条同处一个未提交改动），而 worktree 是从 HEAD 切出来的、**没有这一条**——所以合回主仓时把那一条删掉（主仓那份是权威）。

**基线（立票当日实测，2026-09-16）：`main` @ `492ed0d`。**

- 后端：`clojure -M:test -m harness.test-runner`
  → `Ran 719 tests containing 10585 assertions. 4 failures, 0 errors.`（退出码 1）。
  这 4 条**全是本机既有的、与本特征无关**的失败，名字是：
  - `project_test/a-binding-survives-a-real-restart`（`project_test.clj:344`、`:347`）——它逐字比较
    fork 出来的 JVM 的 stdout，而这台机器的 JDK 25 在 sqlite-jdbc 加载原生库时会往 stdout 打
    "a restricted method in java.lang.System has been called"，与本仓库代码无关。
  - `http_test/the-projects-listing-joins-the-store-with-the-disk`（`http_test.clj:1210`、`:1211`）——
    它拿 Post-run 之后 store 里的 `:bytes` / `:lastActivity` 与磁盘上的文件比，而**终帧之后**服务端还要
    写返回侧那几行 `message`，于是这是一场真竞赛（这次差 433 字节、3 毫秒）。同样与本特征无关。
  条数**每次跑都不一样**（另一次跑是 2 条，只有上面那两条 `project_test` 的）：比对时看**失败的名字**，不看条数。
- 前端：`cd ui && npm test` → `Test Files 1 passed (1)` / `Tests 15 passed (15)`，
  退出码 0（11.4 秒）。`EXPECTED_CASES` 今天钉在 **15**，票 03 会长，要一并改那个数。
  另有 `cd ui && npm run build`。报数一律带上**分支与提交**。

## 落地记录

### 01 — 词与记录：一次模型调用的边界与用量（2026-09-16，分支 `composer-status`，提交 `d60b59f`，票面已删）

**落地了什么**

- **词**：`CONTEXT.md` 加 `轮` / `模型调用`（新一节「轮与模型调用」）、`会话统计` / `缓存命中`
  （「状态住在哪」那一节）；顺手把 `批` 与 `任务清单` 两条里那个指同一件事的「回合」改成「模型调用」。
- **记录**：`harness.kernel.event` 加 `:model/start` / `:model/end` 两个构造器（docstring 十一种 → 十三种），
  并把它们归进「没有帧」的那一组；`harness.edge.ag_ui` 的 `convert` 明说这两种不上 wire；
  `harness.edge.http/lifecycle-record` 两条映射落 jsonl。
  - `:model/start` 载荷 `{:model … :base-url … :reasoning-effort …}`，**只写有的键**——
    替身（或任何没报身份的 provider）留下的是一条只有类型的行，不是一个 `null`。
  - `:model/end` 载荷就是厂商那几样，**逐字**；什么都没报时是**空对象**。
- **用量**：`consume-sse` 留住 `usage` / `finish_reason` / 回声的 `model`（新增一个私有的
  `telemetry-fields`，last-wins 折叠，`finish_reason` 只在非 nil 时写——大多数 chunk 都是 `null`），
  返回形状变成 `{:message … :telemetry …}`；`harness.kernel.llm` 的 ns docstring（契约）跟着改。
  **四个 `stream!` defmethod 一起改**：`:openai-completions`、`harness.fake` 的 `:fake`、
  `session_tools_test` 的 `:tool-spy`、`replay_test` 的 `:recording`。
- **边界**：`harness.kernel.loop` 里新增私有 `model-call!`——每次 `llm/stream!` 之前 emit `:model/start`、
  之后 emit `:model/end`，**中途抛也发**（载荷空）并把异常原样重抛，所以 run 仍以 `:run/error` 收尾。
  **遥测只上事件、不进历史**：它属于这次调用，不属于对话。
- **替身**：`harness.fake` 的脚本每一轮可带可选的 `:usage`（照 `:reasoning` 那条「说了 vs 没说」的分别，
  `contains?` 判在场）；`scripted` 的 provider map 补上 `:base-url` / `:model`
  ——仓库里其他每个脚本 provider（种子配置、http_test 的 pin、providers_test 的夹具）本来就都有，
  少了它 `model/start` 会一条身份都不报。
- **测试**：`event_test` 加两个构造器的形状（含「不抄 api-key」「没有就留空」）；
  `llm_test` 加四条——契约形状、**fixture 里那四个数读得回来**（769 / 324 / 1093 / 296 与
  `cached_tokens 0`、`finish_reason "tool_calls"`、回声的 model）、什么都没报时是空 map、
  后一个 `finish_reason: null` 不覆盖前一个 `"stop"`；`loop_test` 加两条——
  「每次调用一对行、每个 end 带**那一次**的用量」与「没报用量的轮留下空的 `model/end`」，
  并把 `transport-failure-ends-the-run` 改成断言**失败也闭合**（`[:run/start :model/start :model/end :run/error]`）。
  `loop_test` 的 `without-lifecycle` 改名 `without-audit` 并收进这两种事件（它是五条审计行，不是三条）。
- **现状文档**（本票当天就会撒谎的两处）：`docs/architecture/kernel.md` 的事件表 + `drive!` 伪代码
  与 `docs/architecture/edge.md` 的 jsonl 行表跟着改（含「两条按次序配对、序号与时间戳都不记」那条）。
- **复议**：`.scratch/trajectory/` 的 `spec.md` 与它的 `01` / `04` / `05` 各加一段加注的复议
  （旧话不动，被推翻的行打删除线），说清它那三票各缩水成什么。

**证据**：`.scratch/composer-status/evidence/` —— 一张**可重跑**的脚本（真 HTTP 边 + 脚本替身 + 临时家目录）
与它的输出，四件事看得见：一对行按次序配对、每个 `model/end` 带**那一次**的用量、
身份与用量逐字、没答上来的调用留下**空的 `model/end`**。厂商真回来的数字由 fixture 的断言负责
（同一目录的 README 里写明了这条分工，也写明了**没跑活厂商**这件事）。

**报数**：`clojure -M:test -m harness.test-runner`
→ `Ran 726 tests containing 10614 assertions. 2 failures, 0 errors.`（退出码 1；
立票基线是 719 / 10585）。两条失败是 `project_test/a-binding-survives-a-real-restart` 的 JDK 25 那对，
**没有新的失败名字**（另一次跑还出现过 `http_test/the-projects-listing-joins-the-store-with-the-disk`
那两条真竞赛——见「状态」那节的说明：条数每次都不同，看名字）。
本票没动 `ui/`：`cd ui && npm test` 与 `npm run build` 照旧（`EXPECTED_CASES` 仍是 15）。

### 02 — 会话统计的折法与端点（2026-09-16，分支 `composer-status`，票面已删）

**落地了什么**

- **新 ns `harness.edge.stats`**，与 `edge.replay` / `kernel.frames` 并排（记录的两个读侧）。
  两个入口：`records->stats`（**对记录序列的纯函数**，手搓记录可断言）与 `log-stats`（接一个 File，
  与 `replay/rebuild` 同一个立场：**目录是调用者的**，这个 ns 永远不知道 home 在哪）。
- **端点 `GET /api/threads/<stem>/stats`**：`thread-verbs` 那个闭集加 `"stats"`，
  dispatch 里长出这条形状上的**第一个 GET 分支**，那句「每一个动词都是 POST，因为每一个都有副作用」
  跟着改成「方法说有没有副作用」；定位走 `replay/locate`（**不新造寻址方式**），
  找不到是 404（定位器自己那句），读不回来是 400——与 rebuild 同一条缝。
- **产出形状**（比票面示例多一个键、少两个键，理由见下）：

      {"threadId": "…", "turns": 3, "steps": 42, "stepsWithUsage": 41,
       "usage": {"totalTokens": …, "promptTokens": …, "completionTokens": …, "cachedTokens": …},
       "cacheHitPercent": 91, "outputTokensPerSecond": 242, "incomplete": false}

  - **多 `cacheHitPercent`**：票面的示例里没有它，而条子第五格要画「98% cached」——
    客户端不许自己除，所以比例的分子分母在这一处取齐、在这一处算完。
  - **少 `calls` / `callsWithUsage`**：`calls` 就是 `steps`（同一件事实的第二份），
    覆盖数只留一个名字 `stepsWithUsage`。
- **纪律逐条落在函数上**：轮按**新的用户消息 id** 数（一条用户消息一轮，与 `CONTEXT.md` 的 `轮` 一致；
  悬置恢复的第二个 `input` 不开新轮）；步 = 模型调用（`model/start` 的行数，**按次序**与 `model/end` 配对，
  没有任何 id）；每个用量键**各自求和**、没人报就**整个键不出现**（不是 0）；
  `total_tokens` 缺了才按 prompt + completion 补，且**要求两半都在**（半个和不是总数）；
  缓存命中与速率**只在同时报了两半的调用上**取分子分母；速率的分母是那几次调用的 `:ts` 差，
  **不拿别的行的间隔冒充**；`incomplete` = 最后一帧不是终帧。
- **容忍半截的最后一行**：`read-records` 用 `replay/lines->records` 严格解析**除最后一行之外**的行，
  最后一行解析不出来就丢掉——条子最常被问的时刻正是会话在跑的时候，而这不是重建
  （重建宁可整个拒绝，因为少一截的对话比没有更坏）。中间那一行坏了仍然照严格的那条报错。

**测试**：`test/harness/edge/stats_test.clj`（8 个用例 / 49 条断言，已注册进 `harness.test-runner`）：
手搓记录断言折法（一轮一调用、新用户消息开新轮、悬置恢复不开新轮、一个 input 带两条新用户消息算两轮、
没报的调用不进任何分母、没有 `model/*` 的老日志只剩轮数、空日志、半截的 run），
外加三条走真 HTTP 的端点用例（一轮真会话折出来的数、不在这里的会话 404、`GET .../rebuild` 仍是 405）。

**证据**：`.scratch/composer-status/evidence/` 的 `stats-endpoint.clj` / `.txt` —— 起真边、真跑一轮、
**真的 curl 一次**，把 curl 的字节与「每个数是由什么加出来的」并排打出来
（2248 / 2200 / 48 / 2000 / 91% / 2667 tok·s⁻¹，与日志里那两对时间戳对得上）。同一目录的 README 写明了
**没跑活厂商**、以及**缓存字段的拼法还没在真机上核过**（那一处是 `harness.edge.stats/number-at`，
核出第二种拼法就在**同一个函数**里加，并写明是哪家厂商）——这两条是留给你的。

**报数**：`clojure -M:test -m harness.test-runner`
→ `Ran 734 tests containing 10666 assertions. 2 failures, 0 errors.`（票 01 落地时是 726 / 10614）。
两条失败仍是 `project_test/a-binding-survives-a-real-restart` 的 JDK 25 那对，**没有新的失败名字**。

### 03 — composer 之下的状态条（2026-09-16，分支 `composer-status`，票面已删）

**落地了什么**

- **`ui/src/components/composer-stats.tsx`**：五格。挂在 `ComposerFrame` 里、`{children}` **之后**
  ——同一个圆角框内、输入框下面。上下文条在**上面**且对话一开始就收，状态条在**下面**且**有数才画**，
  两条各在各的位置。`thread.aui.tsx`（抄来的 registry 源码）**一个字节没动**。
- **`ui/src/lib/stats.ts`**：`statsFor(threadId)` 一个薄取数；**404 是普通答案**（新会话没有日志），
  一律返回 null 交给「没数就不画」，不抛、不画错误行。
- **刷新时机**（决策 10）：挂载、会话切换、**助手消息多一条**（一轮 ReAct 在这个客户端就是一条助手消息，
  所以这约等于「一次模型调用结束了」）、run 结束。**不轮询**：一次长调用进行中条子停在上一格。
- **`ui/src/lib/format.ts` 加三个纯函数**：`plural`、`formatTokens`（409 / 812k / 2.9M，与 `formatBytes`
  同一档精度）与 **`statsCells`（payload → 格子）**——**缺的格子是 `null`，不填 0**，
  整条在「轮数为 0 且没有调用」时不画。这个模块**一个 import 都没有**，就是为了让 UI 套件能测它。
- **文案英文**（决策 9）：`1 turn` / `2 turns`、`42 steps`、`242 tok/s`、`2.9M tok`、`98% cached`；
  数字用 `tabular-nums`（9 → 10 时整行不跳）。
- **UI 套件**：新 `ui/test/suites/stats.ts`（4 条：格子与量级、缺数不画、**真 HTTP 折一轮真会话**、
  没跑过的会话是 404），注册进 `SUITES`，`EXPECTED_CASES` **15 → 19**。

**顺手改的两处（都不是本特征引起的）**

- `ui/test/suites/client.ts` 有两处 `m.reasoning_content` 在 `m: Payload | undefined` 上取值，
  **`npm run build` 在 main 上今天就是红的**（`npx tsc --noEmit` 在主仓同一处报同样的两条）。
  按仓库惯例顺手修掉：把那个 `filter` 写成**类型谓词**（守卫即窄化），并在这里记一笔。
- `ui/vitest.config.ts` 与 `suites/skills.ts` 里「套件不从 `src/` 里 import 任何东西」这句
  **不再完全成立**：`stats.ts` 要用那个纯格式化模块。那句话改成实际规则——
  **凡是要浏览器（React / DOM / `@` 别名）的都不 import，零导入的纯模块走相对路径**。

**验收对照**：`cd ui && npm test` → `Test Files 1 passed` / `Tests 19 passed`；
`cd ui && npm run build` **过**（tsc --noEmit + vite build，见上面那条修好的）。

**证据**：`.scratch/composer-status/evidence/`
—— `strip-in-the-browser.{png,md}` 与 `strip.dom.html`：真 vite（:5173）+ 真 e2e 后端（:8080，脚本厂商，
临时家目录），条子读作 `2 turns · 2 steps · 1371 tok/s` ／ `2k tok · 91% cached`，
与脚本报的数（2000/2200、1040+1208）对得上。
**顺带撞上一条真的缺数现场**：那次会话的第一次发送因为临时家里没有 `config.edn` 根本没到模型，
条子当时只画 `1 turn`——一个 `model/start` 都没有，所以其余四格一个都不画。那不是设计出来的演示，
是撞上的，正好是决策 6 在真页面上的样子，留在那份 README 里。

### 04 — 收口：文档与全量验证（2026-09-16，分支 `composer-status`，票面已删）

**文档**（都在 worktree 里，跟着代码一起合回）：

- `docs/architecture/edge.md`：路由表加 `GET /api/threads/<stem>/stats` 一行；
  **改掉那句现在不成立的**「读日志的代码只认 `input` / `event` 两种行」——改成「**重建对话的**代码只认两种行，
  审计轨迹有自己的读侧」；「GET 打在这个形状上一律 405」改成「不该被服务的动词答 405，
  方法说有没有副作用」；另加一段讲 stats 折的是哪一半、缺席为什么是缺席、以及它容忍半行而 `replay` 不容忍。
- `docs/architecture.md`：`harness.edge` 的表加 `edge.stats` 一行（并把 `edge.replay` 那行点明是
  **对话那一半**）；快照点钉到收口这次的提交（下一个提交）。
- `docs/architecture/client.md`：装配树加 `composer-stats.tsx` 与 `lib/stats.ts`（`format.ts` 那行也说清它零 import）；
  「状态的归属」加两条——五格读的是**记录**（客户端不数、不估）、以及**什么时候问**（挂载 / 切会话 /
  助手消息多一条 / run 结束，**不轮询**）；测试那节把「套件不 import `src/`」改成实际规则
  （要浏览器的一律不 import，零 import 的纯模块按相对路径引），并把 `stats` 套件列进清单。
- `docs/architecture/overview.md`：状态表加一行**会话统计 = 不存**（它是记录的读法）——
  核过之后确认这是**唯一**要动的地方（没有新增状态、也没有进库）。
- `README.md`：验证那节的报数改成今天实测的（734 / 10666，UI 19 个用例 6 组），
  并把 `http_test` 那条**真竞赛**写进去（失败条数每次可能不同，比对看名字）。
- `CONTEXT.md`：核过票 01 立的四个词与落地后的代码一致（`轮` / `模型调用` / `会话统计` / `缓存命中`），
  没有改词条——代码里用的就是它们。

**记录清单逐行核**：`.scratch/composer-status/evidence/record-ledger-review.md` ——
每一格指到那一行、代码里的**唯一实现**、以及用 `grep` 核过「有没有第二处」的结果
（用量三个键与轮数全仓只有一处读）。

**报数**（收口这次实测）：

- `clojure -M:test -m harness.test-runner` → `Ran 734 tests containing 10666 assertions. 2 failures, 0 errors.`
  ——只有 `project_test/a-binding-survives-a-real-restart` 那对（JDK 25 的 stdout 噪音），
  **从头到尾没有出现过新的失败名字**（01 落地时 726 / 10614、02 时 734 / 10666）。
- `cd ui && npm test` → `Test Files 1 passed (1)` / `Tests 19 passed (19)`。
- `cd ui && npm run build` → 过（tsc --noEmit + vite build；顺带修掉了 main 上本来就红的两处类型错）。

**真机证据**：`.scratch/composer-status/evidence/` 六份——01 的日志两行（可重跑脚本 + 输出）、
02 的 curl（脚本 + 输出）、03 的浏览器截图 + 那个 DOM 片段 + 它的 README，外加这页检查表。
**两件始终没做的事写在 01 的 README 里**：没跑活厂商、缓存字段的拼法没在真机上核过。
