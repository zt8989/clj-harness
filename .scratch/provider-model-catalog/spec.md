# spec: provider → model 目录（配置结构重做）

## 问题（用户判定 + 实测）

现有 `providers.edn` 的形状是**扁平的「档位表」**：每个名字（`:cheap` / `:smart` / `:local`）直接是一个完整的
provider，`:model` 只是其中一个字符串字段。

```edn
{:cheap {:protocol :openai-completions :base-url "https://openrouter.ai/api/v1"
         :model "nvidia/nemotron-3-nano-omni-30b-a3b-reasoning:free"}
 :smart {:protocol :openai-completions :base-url "https://openrouter.ai/api/v1"
         :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}}
```

四处错：

1. **名字不是 provider，是档位。** `:cheap`/`:smart` 是「贵/便宜」的别名，底下可以是任何厂商。因此
   「换 provider」这个动作在配置里根本无处表达——换厂商必须换名字，而名字里没有厂商的信息。
2. **model 不是一等公民。** 它是一条字符串，没有 id/能力/来源之分。系统无法回答「这个模型能收图片吗」。
3. **`config.edn` 的 `{:provider :cheap}` 与 README 承诺的解析层级互相矛盾。** 实测（`clojure -M -e` 直调
   `resolve-provider`）：

   ```
   session override {:provider "smart"} -> {:protocol :openai-completions :base-url "https://a/v1" :model "small"}
   request {:provider "smart"}           -> :model 仍是 "small"
   ```

   **`:provider` 不在 `fields` 向量里**（`[:protocol :base-url :model :reasoning-effort]`），所以第 3/4 档
   写 `:provider` 会被静默丢弃。`session-configure {:provider "…"}` 因此是个死字段——工具接受它、审批通过它、
   什么都不改变，而 README 声称它「可改 provider」。这类静默无操作正是本仓最讨厌的失败形态。

4. **model 的能力（输入/输出模态）无处安放。** 用户要求 model 声明「输入 输出，输入类型支持文本、图片」，
   而 AG-UI 的入站 part 形状（`{:type "image" :source {..}}`）与 OpenAI 兼容端点的
   `{:type "image_url" :image_url {..}}` 并不相同——当前 `inbound` 原样透传，图片 part 会被直接发到厂商接口
   换回一个 400。

## 目标形状

**多 provider，每个 provider 下多 model；model 声明 id / 输入 / 输出。**

### `providers.edn`（用户家目录）

```edn
{:openrouter {:protocol :openai-completions
              :base-url "https://openrouter.ai/api/v1"
              :model    "anthropic/claude-sonnet-4.5"        ;; 该 provider 的默认 model id
              :models   {"anthropic/claude-sonnet-4.5" {:input #{:text :image} :output #{:text}}
                         "deepseek/deepseek-chat"      {:input #{:text}       :output #{:text}}}}
 :ollama     {:protocol :openai-completions
              :base-url "http://localhost:11434/v1"
              :model    "qwen3"
              :models   {"qwen3" {:input #{:text} :output #{:text}}}}}
```

- `:models` 是 **map，键为 model id**；`(get-in cfg [:models id])` 是唯一查找路径，merge 天然按 id 覆盖。
- `:protocol` / `:base-url` 属于 **provider**（厂商），不下沉到 model；`:model` 是 provider 的默认 id。
- `:input` / `:output` 是**必填**的关键字集合，词汇表就是本 harness 真能搬运的类型：
  - `:input` ⊆ `#{:text :image}`
  - `:output` ⊆ `#{:text}`（今天只搬得动文本；声明 `:image` 会是一句谎话，见「非目标」）
  - 集合外的值 → 指名报错（`unknown input type :audio; this harness carries :text, :image`）。

### `config.edn`（默认档）——就是用户要的三个旋钮

```edn
{:provider :openrouter :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}
```

### 解析（仍是四档，逐档只填它要动的旋钮）

低→高：**内置目录 ∪ 用户 `providers.edn`** → `config.edn` 默认档 → 本 thread 会话覆盖 → 本 run 请求。

- 选的是**三个旋钮** `:provider` / `:model` / `:reasoning-effort`，不是四个字段。
- 第 N 档只写 `:provider`（换厂商）→ 该 provider 的默认 `:model` 生效，**endpoint 随之重算**。这修掉了问题 3。
- 第 N 档写了一个该 provider 未声明的 model id → **指名报错**并列出已知 id。不回落、不猜。
- 解析结果是**装配出来的**：`{:protocol .. :base-url .. :model <id> :reasoning-effort .. :input #{} :output #{} :api-key ..}`。

## 决策

- **`harness.models` 持有内置目录，数据与解析分家。** 内置 = 厂商 endpoint + 主流 model 及其模态，让
  `config.edn` 只写三个旋钮就能跑起来（`.env` 里放 key 即可，`providers.edn` 可以不存在）。merge 规则：
  用户文件赢（同 provider 逐字段覆盖，`:models` 按 id 合并——内置有、用户没提的 model 存活）。内置表带
  `:as-of` 日期，实现时逐条核对厂商现网 model 列表，不凭记忆写。
- **内置只收 OpenAI 兼容 chat-completions 的厂商。** Anthropic / Google 的原生协议不是 OpenAI 兼容，内置
  `:anthropic` 会是错的；它们经 `:openrouter` 到达（`anthropic/claude-sonnet-4.5` 就在 openrouter 的 model
  表里）。这是诚实的边界，不是遗漏。
- **不做兼容读、不做迁移工具。** 旧形状（provider 级 `:model` 字符串）遇到就**指名报错**，与 config-home
  特征「不留别名、不做兼容读」的既定立场一致。示例文件与 README 改写；开发机真实家目录里的
  `providers.edn` 由 01 落地后手工改写。
- **模态声明必须有人执行，否则是装饰。** 「声明了就能收图片」要在边缘拦住：解析出的 model 声明
  `:input #{:text}` 而入站消息带图片 part → **指名 RUN_ERROR**（点名 model id 与越界的 part 类型），不打给厂商。
  「未声明」（inline provider / 没写 `:input` 的 model）→ 一律放行：没声明就没承诺，拦反而是猜。
  这是流程纪律不是安全边界——写 `:input #{:text :image}` 糊弄过去照样打得出去，与围栏的既有定性一致。
- **API key 仍只有一个解析点。** 模态、provider 名、model id 都可以进日志与自省面，key 永不。
- **图片输入的翻译落在 `harness.ag-ui/inbound`。** 理由不是分层美观而是**日志诚实**：`message` 行的契约是
  「LLM 真实看到的东西，逐字」，若把 AG-UI part 原样写进日志、到 `llm` 层才翻译，日志就撒谎了。真实
  AG-UI 入站形状是 `{:type "image" :source {:type "url"|"data" :value .. :mimeType ..}}`，
  出网形状是 `{:type "image_url" :image_url {:url ..}}`（`data` source 前缀成 `data:<mime>;base64,`）。
  第二个协议出现时，这里是拆分的接缝——不是在 `llm.clj` 里提前想象一个多态层。
- **`active-provider` 改为回答「选择 + 能力 + endpoint」**：`:provider` / `:model` / `:reasoning-effort` /
  `:input` / `:output` / `:protocol` / `:base-url`。内部一律用集合，**线上一律用排序后的字符串数组**
  （日志行、HTTP 端点、工具结果），序列化只发生在写线的那几处。
- **审计切片从「四个解析字段」改成「三个选择字段 + 解析结果」**：`provider/init` 与 `provider/changed` 记
  `[:provider :model :reasoning-effort]` 的选择 + 当时的 `:resolved`（protocol/base-url/model/input/output）。
  日志会活得比目录久，内置表改了之后旧日志仍须自解释——这正是 `:override` 字段当初存在的同一条理由。

## 非目标

- **不做图片输出。** `:output` 词汇表今天只有 `:text`；`llm/consume-sse` 只折叠文本/推理/工具调用，响应里的
  图片会被丢掉。声明一个搬不动的东西是谎话，所以先不声明。
- **不做 UI 图片选择器。** 能力查询端点（05）是 UI 将来要用的接缝，本特征不实现选择器；CopilotKit
  `CopilotChat` 自带输入区的附件能力未在本仓验证过，不猜。
- **不做 Anthropic / Google 原生协议**（见决策）。
- **不做健康检查 / 自动 fallback / model 的增删注册管理界面**（改文件 + 热读即可，与 config.edn 同规矩）。
- **不改 kernel**（loop/event）、不改 AG-UI 帧形状。JSONL 只新增/改写 provider 两行的字段，不动 `input` /
  `message` / `event` / 审计行的形状。
- **不把模型能力当安全边界**（见决策）。

## 验收主线

离线全量 `harness.test-runner` 全绿（基线 131 tests / 655 assertions，其中 `bash-runs-git-bash-not-wsl`
在 macOS 上恒红，与本特征无关）。端到端：删掉 `providers.edn`，`config.edn` 只写三个旋钮，run 起得来；
换 provider 不换 model 时 endpoint 随之改变（打给本地 stub 的服务端能观察到 base-url 真的换了）。

## 落地后收尾

- 开发机真实 `~/.clj-harness/providers.edn` / `config.edn` 手工改写成新形状（测试永不碰真实家目录，这一条
  是人做的）。
- README「配置」整段（家目录文件树、解析优先级、示例）与 `providers.edn.example` / `config.edn.example`
  随 01 改写。

## 状态

六张票全部落地并验证（2026-09-14，见下）。票已删除——本仓的票目录不是 changelog，做了的事记在这里与
git 历史里。

## 已验证到什么程度（2026-09-14）

**全量**：`harness.test-runner` **175 tests / 835 assertions**，连续两轮同样结果。唯一红的是
`bash-runs-git-bash-not-wsl`（`uname` 断言 MINGW，macOS 上恒红，与本特征无关）。基线为
130 tests / 642 assertions，**净增 45 tests / 193 assertions**。

**提交**：`cefab3d`（01+02）、`80f9f4d`（03+04）、`0959805`（05）、`414de48`（06），另加文档/措辞收口。

### 01 / 02 —— 目录形状与内置表

- **实测到的原始 bug**：旧形状下 `session override {:provider "smart"}` 解析后仍是
  `{:model "small" ...}` —— `:provider` 不在 `fields` 向量里，第三、四档写它会被静默丢弃。
  现在 `switching-provider-alone-really-switches-vendors` 钉住「只写 :provider = 该厂商 endpoint +
  该厂商默认 model + 该 model 的模态」，三样一起动。
- **内置表**（`harness.models/builtin-raw`，`:as-of 2026-09-14`）覆盖 openrouter / deepseek / ollama，
  **model id 逐条读自厂商现网列表**：openrouter 的 445 条 `/api/v1/models`、DeepSeek 官方文档、
  ollama library 页。这一核对直接改掉了凭记忆会写错的部分——`deepseek/deepseek-chat` 已是旧 id，
  直连 API 现在答 `deepseek-flash` / `deepseek-v4-pro`。模态以**本 harness 搬得动的类型**记
  （`:input` ⊆ `#{:text :image}`、`:output` ⊆ `#{:text}`）：nemotron 那个 omni 模型在厂商处还收
  audio/video，表里只记 text/image，因为那才是能被送出去的。
- **未收 openai / xai / groq**：它们的 model 列表对未鉴权请求答 403，本表不带没核对过的 id——这是
  诚实边界，写进表本身的文档串。
- 用户 `providers.edn` 按字段、按 model id **merge** 在内置之上（不是替换）。有测试钉住
  「给内置厂商加一个 model，原有 model 仍在」——整体替换会要求重抄整张表，而重抄的表就会漂移。
- 开发机真实家目录已手工改写并实测：`(mem/active-provider "real-check")` →
  `{:provider :openrouter :model "nvidia/nemotron-...free" :reasoning-effort "low" :protocol
  :openai-completions :base-url "https://openrouter.ai/api/v1" :input #{:image :text} :output #{:text}}`。

### 03 —— 图片输入端到端

**最强证据（真子进程 + 真 HTTP 厂商 stub）**：起一个 http-kit stub 当厂商，把
`home/*root-override*` 指向一份 inline 声明 `#{:text :image}` 的临时家，POST 一条带 `url` 图与
`data` 图的 AG-UI run。stub **实际收到**的 messages：

```
{:role "user"
 :content [{:type "text" :text "what is this"}
           {:type "image_url" :image_url {:url "https://x/a.png"}}
           {:type "image_url" :image_url {:url "data:image/jpeg;base64,AAAB"}}]}
```

而该 run 落盘的 `message` 行**逐字相同**——「日志如实记录 LLM 看到了什么」这条契约被实测钉住，
不是靠断言自证。测试里另有 `an-image-part-reaches-the-model-translated-and-the-log-says-so`
（含 replay 重建路径与 live 逐字一致）。

认不出的 part 类型（`:document`）与认不出的 image source（`:file`）均指名报错，测试覆盖。

### 04 —— 模态守卫

- 单元：`undeclared-input` 读的是**用户消息**（模型的输入），只认 user 角色——assistant 自己的历史
  turn 带图不是对模型的请求。
- 端到端（pinned）：声明 `#{:text}` 的模型收到图片 → `RUN_ERROR`，消息点名 model id 与模态；
  **scripted provider 的 script 未被消费 = 厂商一次都没被调用**，这是「拦在调用之前」的直接证据。
- 端到端（真解析路径）：`the-guard-reads-the-catalogs-declaration-not-a-pin` —— 同一份带图输入，
  `:beta`（纯文本）被拒、`:alpha`（收图）通过。守卫读的必须是目录声明，否则「pinned 测试全绿、生产
  什么都不拦」。
- **未声明即不拦**：`nil` 声明 → 空越界集，有测试（inline 无声明配置照常跑图）。真实家目录实测：
  `deepseek/deepseek-v4-pro` 声明 `#{:text}` → 图片被拒；nemotron 声明 `#{:image :text}` → 放行。

### 05 —— 能力端点

`GET /api/model?threadId=` 实测（真家目录、真 HTTP）：200 + `{"provider":"openrouter", ...
"input":["image","text"],"output":["text"]}`，无 api-key。测试覆盖：会话切到纯文本厂商后答案随之改变
（含 endpoint）、未知 thread 仍是 200（默认档解析）、无 threadId 走进程级槽、**只读不落审计行**
（该 thread 的日志文件根本不存在）、inline 稀疏配置如实报 absent。

### 06 —— session-configure 与 provider/changed

- `an-approved-vendor-switch-moves-the-endpoint`（provider 层）与
  `a-vendor-switch-land-as-a-changed-line-that-moved-the-endpoint`（真边缘层）钉住换厂商真的搬 endpoint。
- `a-vendor-switch-with-no-model-lands-on-the-new-vendors-default`：旧 model id 属于旧厂商，不跨厂商携带。
- **不改不动的写**：`a-configure-naming-a-model-the-provider-cannot-serve-is-refused` 与
  `...-a-provider-that-does-not-exist-is-refused` —— 指名失败、`override-for` 仍为 nil、outbox 空
  （既不写配置也不落变更行）。
- `a-vendor-switch-is-not-recorded-as-an-empty-change`：旧形状下换厂商会写成 `{:before {} :after {}}`
  （`:provider` 不在审计切片里），现在 `:after` 带着厂商名。
- 工具描述与结果都改了：描述说明 provider 是厂商、model 是该厂商的 id；结果报出「现在服务的是哪个
  model」。

### 落地中的判断（记在案）

1. **`:model` 随 `:provider` 作用域化，是折叠里唯一的例外。** 折叠本是纯逐旋钮覆盖，但 model id 的
   含义是「该厂商服务的 id」：一档换了厂商而没同时给 model，旧 id 在新厂商名下**什么都不指**。
   于是换厂商时 model 被丢弃、落到新厂商默认档——这是**明示的**动作（记录在案、有测试），
   不是这条形状要杀的静默丢弃：那种是**调用方明确写了却被丢**、还报告成功。
2. **provider/changed 的 `:before/:after` 语义从「四个解析字段」改成「三个选择旋钮」。** 否则换厂商
   会被记成空变更——审计行记录了「什么都没有」。
3. **`resolve-override` 在写之前先解析。** 改不动的配置不该成为 session 的配置：先写后败会让每轮都
   跑不起来，而报错要到下一次 run 才出现，离按下它的那次调用很远。
4. **翻译放 `ag-ui/inbound` 而非 `llm.clj`**：`message` 行的契约决定，实测两处逐字一致（见上）。
5. **内置表在 ns 加载时验证一次，用户的文件每次读都验证。** 编译进去的数据运行时改不坏，每次重验买不到
   什么；而表里一个 typo 应该让进程**在加载时**停下，而不是在第一次命名该 provider 的 run 上。
6. **测试夹具的厂商名不能用内置名**（`alpha`/`beta`/`gamma`）：撞名会让「测试文件里的条目」被内置表
   满足，测试于是绿得没有意义。这条是写测试时才发现的。
7. **`wait-for-recorded` 修了一个偶发红**：它把「读到正在增长的文件的半行」当成损坏日志
   （`the-log-the-server-writes-is-one-replay-can-read` 会偶发报 "the log is truncated or corrupt"）。
   现在只有**非最后一行**解不开才算损坏——严格判定留给 `replay/lines->records` 那个真正的读侧。

### 未做 / 未验证

- **UI 侧未动**：能力端点（05）与图片输入是给 UI 将来用的接缝，本特征不实现选择器。UI 的
  `verify-*.mjs` / vitest 套件未跑（工作树里没有 `ui/node_modules`，且这些脚本在本分支上是未跟踪的
  WIP，不属于本特征）。
- `:output` 仍只有 `:text`（见非目标）。

- `prompt.md` 的 secrets 纪律条款里「四个描述字段」的措辞随 01 更新（它是冻结的代码资产，改它必须走提交）。
