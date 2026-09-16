# spec: 自定义提供方 —— 在设置面板里加一家厂商，并把默认档定下来

参考：设置面板里的「自定义提供方」表单（Cherry Studio，截图见 `evidence/reference-provider-form.png`）。
参考的是**要回答的问题与要收的字段**，不是像素——**左边那一列导航照做，但它列出来的东西按这个仓库
真有的功能裁**：参考界面有十项（通用设置 / 模型 / 插件 / Agent 预设 / 侧边卡边 / Web 插件 / 皮肤 /
宠物 / 创意工坊 / 使用统计 / 会话归档管理），我们只有四页能填（General / Models / API key / Config home）。
参考表单收六样：Provider ID、显示名称、API 地址、API 协议、API 密钥、模型目录（加模型 / 获取可用模型），
外加两个按钮（取消 / 创建提供方）。它的 ID 帮助文案说了两件事：**小写字母开头的标识**、
**用于派生凭据名**。通用设置那一页收的是**默认档**：默认提供商 / 默认模型 / 默认 reasoning 强度。

**一句话**：今天加一家厂商要手编 `providers.edn`（形状、键名、每一条 model 的模态都得自己写对，
写错要等到跑起来才发现），换默认厂商也要手编 `config.edn`；这一特征是**把这些收进设置面板**，
写盘、校验、报错都在服务端做完，落地的配置仍然是一份人能读能改的文件。

## 决策

1. **配置文件收成一份：`config.edn`。**

   ```clojure
   {:default   {:provider :acme-gateway :model "gpt-x" :reasoning-effort "high"}
    :providers {:acme-gateway {:protocol :openai-completions
                               :base-url "https://gateway.example/v1"
                               :model    "gpt-x"
                               :models   {"gpt-x" {:input #{:text} :output #{:text}}}}}}
   ```

   `providers.edn` **退休**：不再被读，文件还在就当场指名失败并说清「把条目搬进 `config.edn` 的
   `:providers`，然后删掉这个文件」。今天的扁平形状（顶层就是三旋钮）同样当场失败，说清 `:default` 这一节。
   **不读两种形状、不自动迁移**——这是仓库里已有的立场（`check-provider` 对旧的「provider 就是 model」
   形状就是这么办的），照办。合并顺序不变：内置表 ← `config.edn` 的 `:providers`，仍是**逐字段、
   逐 model** 的 `over`，所以一条只有 `{:base-url "…"}` 的条目是合法的「补丁」，补丁内置厂商的做法保留。

   代价是**破坏性**：家里有 `providers.edn` 的人要自己搬一次，旧的扁平 `config.edn` 要加一层 `:default`。
   换来的是「找配置只有一个地方」，以及表单只需要认识一份文件。

2. **凭据名由 provider id 派生，全局那把仍是兜底。**

   规则一处定义（`harness.cap.providers`）：id 全大写、非 `[A-Za-z0-9]` 换成 `_`，缀 `_API_KEY`——
   `acme-gateway` → `ACME_GATEWAY_API_KEY`。查找顺序：**本 provider 派生的名字 → 全局 `HARNESS_API_KEY`**，
   每一档都是「`.env` 优先于真实环境变量」（老规矩，不动）。内联描述（config.edn 里没有名字的那种）
   只有全局那一档。

   参考界面的那句话因此是真的：ID 决定密钥叫什么名字。派生函数是全函数（任何 id 都能派生出名字），
   文档里写明它**不单射**（`a-b` 与 `a_b` 派生出同一个名字）；表单侧的 ID 判据把这个可能性关掉，
   见决策 3。

3. **ID 的判据在 catalog 里，只对表单严格。** 表单只收 `^[a-z][a-z0-9-]*$`，其余当场拒，原话照参考
   界面的意思写（「以小写字母开头的标识…用于派生凭据名」）。**catalog 不因此收紧**：手写的 `config.edn`
   里 `:My_Vendor` 仍是合法关键字（今天就是这样，收紧会打断谁也没让改的东西）。判据与派生是同一处的
   两个函数，表单是它们的调用方。

4. **目录纪律不动。** 一个 provider 至少声明一个 model，默认 model 必须是 `:models` 的键，目录外的
   model id 照旧**当场指名失败**（`assemble` 那条「静默回落等于报成功却没人要的模型」的规矩不放开）。
   于是表单的模型目录**至少一行**，第一行即默认 model（哪一行当默认可改）。

   参考界面空态那句「模型选择器中将不显示任何模型；目录外 ID 仍可直接发送」**不照抄**：这个仓库的选择器
   就是目录，而一个没有 `:models` 的 provider 切不过去，摆出来就是摆出一个拒绝。代价是建 provider 时
   必须顺手写一个 model id。

5. **显示名是可选的第二个名字，id 仍是对外的身份。** 条目可以带 `:display-name`；模型选择器与设置面板
   显示它，缺了回落显示 id。它是目录的答案，所以进 `resolved-fields`（`active-provider`、`wire`、
   audit 行里的 `:resolved` 都带上它），**同时必须进 `catalog-fields`**——那份名单是字面列出来的，
   不加进去就等于允许某一档写一个会被 `select-keys` 静默丢掉的字段。身份一侧不动：
   凭据名、`:provider` 旋钮、`GET /api/choices` 每行的 `name`、`selection` 的 `:before`/`:after`，
   **一律仍是 id**——显示名只影响人看的那一层（换显示名因此不是一次配置变更）。

6. **写盘：先校验，后写入，一次原子替换。**

   - `POST /api/providers`（新建或改写一条）与 `POST /api/providers/<id>/remove`（删掉一条）——
     verb 那一段走既有的 `/api/<collection>/<stem>/<verb>` 形状，不新造路由形状。
   - 读 → 改 → **拿新内容整份跑一遍 `catalog` 的校验** → 通过才落盘（临时文件 + rename，照
     `hashline/files` 那份写法）→ 失败什么都不写，服务端那句原话回给表单。
   - 只动 `:providers` 那一节：解析出来的 map 里 assoc / dissoc，`:default` 与将来别的键原样保留。
   - **`config.edn` 会被整份重排**（EDN 注释保不住，仓库里没有保注释的写入器，也不为这个引入依赖）。
     两道保险：写盘前把原文留一份 `config.edn.bak`（一次一代），以及文件头由服务端写一段
     「这份文件由设置面板维护」的注释。人手写的 `:default` 三旋钮**作为数据保留**，作为文字与注释不保留。
   - **密钥写 `.env` 的一行**：命中 `^<NAME>=` 就地替换，没命中就在末尾追加（末尾少换行先补一个），
     其余行——别的变量、注释、`export` 前缀——一个字节不动。值里有换行当场拒（`.env` 是行式的）。
     删掉一条 provider **不删** `.env` 里那行：密钥可能是人手加的，且留着是无害的。

7. **默认档可以从界面上改，但只在 General 那一页改。** `config.edn` 的 `:default`（三个旋钮）有自己
   的三个控件——默认提供商 / 默认模型 / 默认 reasoning 强度——写的是一条新路由 `POST /api/defaults`：
   **缺席 = 不动那一项，`null` = 把那个键清掉**（`:model` 清掉就落在厂商自己的默认 model 上，
   `:reasoning-effort` 清掉就是不送这个字段）。校验与 `POST /api/model` 同一条——**先解析一遍**，
   解析不出来就不是一个变更；落盘复用 05 那台机器（原子替换 + 一代 `.bak`）。

   **provider 表单仍不碰 `:default`**：新建/改写一条厂商只写 `:providers`。一个「设为默认」的勾选框正是
   这个仓库一直在删的那种「只确认你刚做的选择」的界面件——默认档有它自己的一页，两件事不混。

   两处如实：本会话自己的档盖着某一项时，页面要说清「默认档改了、本会话仍听自己的」
   （一个人改了默认发现当前会话没变，最容易以为没生效）；`:default` 是**内联描述**形状时三个控件表达不了它，
   就把那段描述只读显示出来并说清「保存会用命名形状换掉它」。

8. **四条新路由，都不留审计行。**

   - `GET /api/providers`：给厂商列表、表单与 General 那一页看的一份现成目录——每条带**来源**
     （`:builtin` / `:user` / `:builtin-patched`）、endpoint、它声明的 model、密钥有没有与从哪来、
     派生出的**凭据名**，另带默认档现状。
     每条都是这里自己拼的字段，不是把解析结果并进来（`choices` 的写法），且**任何深度都不出现密钥值**。
     General 那一页也读它（默认档现状 + 两个下拉的选项都在这一份答案里，不必再叫 `/api/choices`——
     那个是 composer 选择器的菜单，答的是**当前生效**的三个，不是默认档）。
   - `POST /api/providers` / `POST /api/providers/<id>/remove` / `POST /api/defaults`：写入侧（决策 6、7）。
   - 规矩照路由表那条：**审计行跟着日志走，不跟着写入走**。写 `config.edn` 不搬日志、不读日志，
     所以一行不写（与加项目/移除项目同类）。**不新增 jsonl 行种类、不动 AG-UI 帧、不动 CORS、不进库**。
     运行时的「我到底被谁服务」由既有的 `provider/init` 行回答，够用。

9. **「获取可用模型」是一次出站探询，缝在服务端。** `POST /api/providers/models` 拿表单此刻的
   endpoint / 协议 / 密钥去问厂商要列表（`GET <base-url>/models`，Bearer 头，取 `data[].id`），
   厂商的拒绝（401 / 404 / 超时）**原话回传**给表单。测试缝是一个 `alter-var-root` 的 var
   （照 `*directory-chooser*` 的先例），测试里不出网。厂商的列表里**没有模态信息**，所以勾进来的 model
   默认只声明 `:text`（声明得最少就是最诚实的默认），行仍可改。

10. **设置面板长出左侧导航，四页。** 照参考界面：**General**（本会话现在生效的三个旋钮与各自来自哪一档 +
    默认档那三个控件）、**Models**（厂商列表与表单）、**API key**、**Config home**。导航**只列这四页**——
    参考界面那一列里其余的（插件 / Agent 预设 / 皮肤 / 宠物 / 使用统计 / 会话归档管理）在这个仓库里没有
    对应的功能，**不给不存在的功能造页面**。表单仍在同一个 Dialog 里换视图（不是嵌套 Dialog，也不是
    第二个设置面）；**文案英文**（中文只在文档、ticket 与 `CONTEXT.md` 里）。编辑一条时 **ID 只读**——
    改名 = 删了重建，因为凭据名跟着 ID 走；界面上把这句话写出来。

## 非目标

- 不做参考界面里**没有对应功能**的那几页（插件 / Agent 预设 / 侧边卡边 / 皮肤 / 宠物 / 创意工坊 /
  使用统计 / 会话归档管理）：不给不存在的功能造页面，那是这个仓库一直在删的那类界面件。
- provider 表单里不做「设为默认」勾选框（决策 7：默认档有自己一页）、不做「测试连接」、
  不做 OAuth / 账号式供应商、不做多密钥与轮换。
- 不改协议实现：`API protocol` 是一个闭集下拉，今天只有 `openai-completions` 一个真实现（`llm/stream!`
  只有这一个 method），界面上如实只有这一项。
- 不做 provider 的导入导出、不做内置表的编辑（内置表仍是编译进去的地板）。
- 不动密钥的存储方式：仍是家目录 `.env`（不是库、不是钥匙串）。

## 验收主线

一次真机走完：**设置 → Models → Add provider → 填 `acme-gateway`**（endpoint、协议、密钥、一个 model）
**→ Create → 列表里出现 → composer 的选择器里立刻有它 → 选中它跑一轮拿到回答**，
**→ 设置 → General → 把默认档换成它 → 新会话里跑一轮**（`provider/init` 行是它、`:source` 是 `default`），
全程不手编任何文件；随后**编辑**它一条、**删掉**它，家目录里 `config.edn` 与 `.env` 的改动逐行看得见，
`config.edn.bak` 是改写前那份，jsonl 除审计行外一个字节不动。

## 分类

- **形状迁移**（01）：`config.edn` 收成一份，`providers.edn` 退休。破坏性，必须与所有种子/示例/文档同票落地。
- **凭据**（02）：派生 + 查找 + 面板按 provider 报。
- **目录与展示**（03、04）：显示名；只读的目录列表。
- **写入**（05）：provider 的三个动作 + 原子落盘 + `.env` 一行（默认档那条写入复用它）。
- **界面**（06、07、08）：导航壳；模型页与表单；General 页的默认档。
- **尾巴**（09）：出站探询那一下，砍掉这一特征仍成立。
- **收口**（10）：文档与全量验收。

## 交付顺序

| # | 票 | 依赖 | 一句话 |
|---|---|---|---|
| 01 | 配置收成一份 | — | `:default` + `:providers`，`providers.edn` 退休 |
| 02 | 凭据名由 ID 派生 | — | `<ID>_API_KEY` 优先，全局兜底 |
| 03 | provider 的显示名 | 01 | 可选 `:display-name`，选择器与面板显示它 |
| 04 | `GET /api/providers` | 01、02、03 | 给表单与 General 看的一份目录（来源 + 模型 + 默认档 + 凭据事实） |
| 05 | 写入：新建 / 改写 / 删掉 | 01、02 | 校验先于写入，原子落盘，密钥一行 |
| 06 | 设置面板的导航壳 | 02、03 | 左列四页，先把现有的报告搬进页里（只搬不添） |
| 07 | 模型页：列表与表单 | 04、05、06 | 加一家厂商、改一条、删一条，真机截图 |
| 08 | General 页：默认档三个旋钮 | 04、05、06 | 默认提供商 / 模型 / reasoning，写 `:default` |
| 09 | 获取可用模型 | 07 | 出站探询 + 测试缝（尾巴） |
| 10 | 文档与全量验收 | 01–09 | 全仓文档跟上，端到端走一遍 |

01 与 02 互不阻塞（一个动配置文件的形状与合并，一个动 `.env` 与查找），可以并行；03 要 01 的条目形状；
04/05 要 01、02，彼此并行；06 要 02、03（它把面板里那两处改动搬家，先落地再搬省一次冲突）；
07 与 08 是兄弟（都挂在导航壳上，彼此不阻塞，03 不做的话少一栏显示名）；09 是尾巴。

## 状态

票面立于 2026-09-16，`main` = `c0fe0ec`（当天 `skill-picker` 与分层重排 `layer-layout` 刚并进来，
所以票面里的路径一律是分层后的 `src/harness/{cap,edge,infra,kernel}/`）。**一份都还没做。**

动工前的基准（同一天在这台机器上量的，票面里的「失败名单逐条相同」都指这一次）：

- 后端：`clojure -M:test -m harness.test-runner` → **Ran 626 tests containing 9960 assertions**。
  **失败数不稳**：同一天两次跑，一次 **2 failures**、一次 **6 failures**，多出来的四条是竞态
  （第二次跑的时候另一个会话正在 `.worktrees/mcp` 里跑它自己的套件，共用一个 worktree 的机器）。
  所以逐条记下**名字**，而不是记数：

  | 用例 | 位置 | 性质 |
  |---|---|---|
  | `a-binding-survives-a-real-restart`（两条断言） | `test/harness/cap/project_test.clj:344,347` | 常驻：fork 出来的 JVM 把 JDK 25 的 `System::load` 警告混进了 stdout，与断言里的路径比对不上 |
  | `a-model-that-declares-nothing-is-not-guarded`（两条断言） | `test/harness/edge/http_test.clj:594,596` | 竞态：流里还没等到 `RUN_FINISHED`（body 为空），脚本也就还没被消费 |
  | `the-projects-listing-joins-the-store-with-the-disk`（两条断言） | `test/harness/edge/http_test.clj:1205,1206` | 竞态：读文件长度/ mtime 的时候 run 还在往 jsonl 追加（差 180 字节 / 1 毫秒） |

  **验收判据因此是「名单与之逐条相同」，不是「全绿」，也不是「同一个数」**；量之前先确认没有别的会话
  在同一个仓库上跑套件。
- 前端：`cd ui && npm run typecheck` 0 error；`npm test` → **Tests 14 passed (14)**，1 个测试文件。

一处**动工前就存在的**文档不一致，顺手记在这儿（02 的验收里改掉）：`.env.example` 写着
「真实环境变量总是赢过这个文件」，而 `api-key` 与 `api-key-source` 都是 `.env` 赢。

## 落地记录

按仓库的规矩**追加**，不改上面的字。

### 01 — 配置收成一份（做完了）

分支 `custom-providers`，worktree `.worktrees/custom-providers`，起点 `5b0c342`（票面立于 `c0fe0ec`，
中间 `tool-parity`（`bdf4af7`）并进了 main，所以**实测基准与「状态」一节记的那组数不是一回事**——
下面给的是本分支自己量到的数）。

**量的数（这台机器，同一天，而且当时机器上另外两个会话也在跑各自的套件）**：

| 跑的是哪份 | 测试 | 断言 | 失败 | 失败名单 |
|---|---|---|---|---|
| 本分支（做完 01） | **690** | **10297** | **2** | 只有常驻的 JDK 25 那两条（`cap/project_test.clj:344,347`） |
| 干净的 main 检出（`5b0c342`，没我的改动） | **686** | **10276** | **4** | 那两条 **加上** `edge/http_test.clj:1205,1206` 那条已知竞态 |

所以：**净增 4 条测试 / 21 条断言，没有多出一条失败**，那条竞态在本分支这次没触发（它在「状态」一节的
已知名单里）。前端 `cd ui && npm test` → **14 passed**，`npm run typecheck` 0 error。

**做出来的样子**（与票面逐条对上）：

- `config.edn` 顶层闭成两节：`:default` 与 `:providers`。第三种键（也就是**旧形状**：三个旋钮写在顶层）
  指名失败，句子会说「move them under :default」。
- `providers.edn` **不再被读**：文件还在就是指名失败，句子说清把条目挪进 `:providers` 再删掉它。
  `harness.infra.home/providers-file` 保留下来只做两件事——让那句失败能点名一个文件，让测试能种一个。
- `GET /api/settings` 的家目录文件表从五项变四项（`config.edn` / `hooks.edn` / `.env` / `harness.db`）。
- 内置表仍是地板，用户条目仍**逐字段、逐 model** 合并；补丁内置厂商、给内置加一个 model 这两条老用例
  一个字没改就过了（它们本来就写在「目录」这个位置上，只是现在那个位置是 `:providers` 一节）。

**动手时才知道的几件事**（票面里没写到，写下来免得下一票重踩）：

1. **`config` 函数得往前挪。** `catalog` 现在要读 `config.edn`，而 `config` 原来住在文件后半段——
   不是加个 `declare` 就完事：它属于「the file」那一节，跟 `builtin-raw` / `over` / `catalog` 住一起才是
   读得通的顺序。挪了，没加 `declare`。
2. **`test/harness/session_tools_test.clj` 里有一条看不见的旧形状依赖**：它 eval 了一句
   `(keys (harness.cap.providers/config))` 断言结果里有 `:protocol`——那是旧扁平形状的键。
   这是全套里唯一一条因为形状变化而红的用例（第一次跑 690/10297/3 条失败里的一条）。
   现在改成读 `(:protocol (:default (harness.cap.providers/config)))` 并断言值是 `:fake`：
   断的东西比原来更强（值，不只是键名），而且说的是新形状。
3. **两个测试命名空间要写同一份文件**，所以新增了一个共享助手
   `harness.test-support/config-text`（默认节与目录节 → 那一个 `config.edn` 的文本）。
   它返回**文本**而不是写文件，因为有几个调用方写的是自己的目录（fresh home、子 JVM），
   而且有的用例要的正是**会失败的那种字节**。`providers_test` 的 `with-home` 两个参数
   （默认档、目录）因此一个字都不用改——一百来个用例的写法保住了。
4. **`api-key` 已经不是我票面里写的那个样子**：它现在是 `harness.infra.home/env-value` 的一行调用
   （`tool-parity` 那次把三家搜索厂商的键也并了进来，取用口合成一个）。02 要在它上面加「按 provider 派生
   的名字」，别照票面把它写成新的私有 `parse-dotenv` 版本。

**票面里说错/说漏的**：`config.edn.example` 那一项，票面写「新形状 + `:providers` 那一节的注释」，
实际做的时候是把 `providers.edn.example` **整份**折进了 `config.edn.example` 的注释里（形状、模态词汇、
两个数字是报告不是执行、合并规则、补丁示例、本地 endpoint 不需要 key），因为那份文件里值钱的正是注释——
它删掉了，它的解释得有个去处，而 `config.edn` 就是去处。

### 02 — 凭据名由 id 派生（做完了）

**量的数**：`clojure -M:test -m harness.test-runner` → **694 tests / 10318 assertions / 2 failures**，
两条都是常驻的 JDK 25 那对（01 结束时是 690/10297）。`harness.cap.providers-test` 单跑
**75 tests / 322 assertions / 0 failures**（01 结束时 71/301，本票 +4 条）。前端 `npm test` 14 passed、
`npm run typecheck` 0 error。

**做出来的样子**：

- `credential-name` 公开、一处定义：`:acme-gateway` → `ACME_GATEWAY_API_KEY`。它的 docstring 把两件事
  写成事实：**全函数**（手写的 `:My_Vendor` 也能解析出钥匙，不因为标点就失败）与**不单射**
  （`:a-b` / `:a_b` / `:a.b` 撞名），并用一条用例把不单射钉住——挡住它的是表单的 id 判据，不是这个函数。
- `api-key` 收下**provider 名字**（inline 描述传 `nil`），查找顺序：本 provider 的名字 → 全局兜底；
  名内的顺序（`.env` 先于环境变量）不变。`resolve-provider` 因此先 `fold-and-assemble` 再挂钥匙
  （原来是一行 `(assoc (fold-and-assemble folded) :api-key (api-key))`，现在需要那个名字）。
- `api-key-source` 多报 `:name`：赢的那个名字，或**没有时最先会去读的那个**。面板的 key 一节多一行
  `from` / `would read` + 那个名字（`data-slot="settings-key-name"`），并且解释「名字由 id 派生」。
- `.env.example` 那句**说反了的话**（「真实环境变量总是赢过这个文件」）改掉，并把派生规则与
  一家一把钥匙写进注释。

**动手时才知道的几件事**：

1. **「先源后名」是必须选的**，而且要在 `harness.infra.home` 里定。两个名字 × 两个源是四格；
   名字优先（name-major）读起来更直觉，但它会让一个导出在 shell 里的 `ALPHA_API_KEY` 盖掉 `.env` 里
   人明明写了的 `HARNESS_API_KEY`——而「`.env` 是唯一说了算的地方」是 `env-value` 早就写下的承诺。
   所以顺序是**源优先**：`.env` 对每个候选名字问一遍，之后才轮到环境变量；「具体优先」只在同一个源内部
   成立。这一条新增为 `home/env-source`（返回 `{:name :source}`，**不带值**），`env-value` 留作
   单名字版本（搜索键用），于是「顺序」只有一份实现。两个子 JVM 用例把这条规则的两半各钉了一次。
2. **`spawn-child` 的签名改了**：`env` 从一个值变成 `{NAME value}` 映射（nil 值 = 从子进程环境里**删掉**
   这个名字），因为「哪个名字从哪个源来」必须在子进程里造。两个既有调用点跟着改。JVM 的环境启动后改不了，
   所以这些只能在真子进程里量——这条老规矩没变。
3. **`sentinel` 与 `with-dotenv` 从 settings 一节搬到了 fixtures 一节**：凭据名的用例也要用它们，
   而一个被两节共用的 def 不属于任何一节。
4. **`providers.clj` 之前没有 require `clojure.string`**（它一直只用 `str` 这个 core 函数）；
   `credential-name` 要用 `str/upper-case`，于是加上了——这是本票唯一一处新增 require。

**票面里说对但值得记一笔的**：票面说「设置面板那一行跟上（截图按 07 那套缝走，本票只保证它显示对）」。
本票只改了那一行与类型定义，**没有**真机截图——真机的缝（`harness.e2e-server` + 假家目录）是 07 的
验收内容，本票的证明是 typecheck 与 14 条前端套件。这一条留给 06/07 落地时一起看。


### 03 — provider 的显示名（做完了）

**量的数**：`harness.cap.providers-test` 单跑 75 → **85 tests / 430 assertions / 0 failures**。

- `:display-name` 进 `provider-keys`、`resolved-fields`，并且——**这是票面点名的那处陷阱**——
  `catalog-fields` 从「字面列出来」改成 **`(disj (set resolved-fields) :model)`**：
  一份字面名单不会自动跟上，而漏掉的那一项正是「某一档写了、被 `select-keys` 静默丢掉」。
  改成派生之后这个陷阱就结构性地不存在了，用例同时钉住「某一档写 `:display-name` 当场指名失败」。
- 非空字符串才对（`""`、`"   "`、`42`、`:alpha`、`["A"]` 各一条用例指名失败）。
- `active-provider` / `wire` / 两条 audit 行带上它（**真机的 `provider/init` 行里能看到
  `"display-name": "Acme Gateway"`**，见下面 10 的证据）；`GET /api/choices` 每行带
  `:name`（id）与 `:display-name`（标签）**两个键**——"显示什么"是客户端的决定，"发什么"必须是 id。
- 选择器的分组标签用它：真机读出来的 `<optgroup>` label 是 `Acme Gateway` / `Local ollama`
  （声明了显示名的两条）与其余四条 id，选中的 `value` 仍是 `model-1`。

### 04 — `GET /api/providers`（做完了）

`registry-report` 一处拼出来：每条带 `:origin`（`:user` / `:builtin` / `:builtin-patched`，三种编辑方式
在界面上分开）、endpoint、model 行（模态是**排过序的字符串向量**）、`credential`、`key` 事实；
外加 `:protocols`、`:reasoning-efforts`、`:default`（**按文件写的样子的那一节**，命名形状给三个旋钮、
inline 形状给 endpoint）。**任何深度都没有密钥值**（用例对着整棵渲染结果搜 sentinel、前缀与长度）。

**一处只有全量跑才看得见的缺陷**：`protocols` 一开始是 `def`，于是它在**加载时冻住**——
全套跑的时候别的命名空间（`llm_test` / `loop_test`）先后来 `defmethod`，报告里的集合却还是加载那一刻的。
也就是说「第二个实现不必第二次编辑就自动可offer」这句只近乎为真。改成**调用时读**
（`implemented-protocols` 是个函数），于是它对进程此刻的样子说话。这条是 10 的全量跑抓到的，
单跑 providers/http 都绿。

### 05 — 写入三条动作（做完了）

- `put-provider!` / `remove-provider!`（另一条路：`POST /api/providers` 与 `.../<id>/remove`）。
  **先校验整份新配置再落盘**，用的是读侧那份 `user-catalog`；失败**连 `.bak` 都不动**（用例逐条断言）。
- 落盘是 `harness.infra.home/spit-atomically!`（兄弟临时文件 + rename），改写前那份进 `config.edn.bak`，
  写出的文件头自带一段说明（EDN 保不住注释，那句话必须有人写在文件里）。
- **id 的判据只对新的 id 严格**（文件里已有的条目保留自己的写法）：手写的 `:My_Vendor` 能被改写、
  不会被"顺手规范化"，因为它同时是凭据名的来源。
- `.env` 是**行级手术**：命中就地替换（保留 `export` 前缀），没命中就追加，**其余字节一字不动**
  （用例里带着注释、别的变量、`export` 前缀与引号的值）。值里有换行当场拒，且**拒在写 config 之前**。
- 删一条 provider **不删 `.env` 那一行**；**并且：`:default` 正指着它就拒**——这条是**真机走查
  抓到的**：删掉一个正被默认档指着的 provider，两个各自合法的动作合起来是一个每一轮都跑不起来的家。
  句子里说清「先把默认档指到别处（General），再删这条」，而**不**顺手去写它没被要求写的 `:default`
  （那会是另一种意义上的越界）。用例覆盖拒绝与"改完默认档就能删"两步。
- **一处只在烟测里才看得见的缺陷**：写入的条目原本是 `entry-from-wire` 的产物，也就是 JSON 的
  `:input ["text"]`（字符串向量）**原样落进文件**。而目录说的是集合（`#{:text}`），下一轮读回来
  会得到与校验时不同的形状，模态守卫（拿目录的拼法比对）会读成"这个 model 什么都不声明"。
  现在写进去的是 **`check-provider` 规范化过的**那份。教训写在这儿：先 `put` 再 `cat` 一次文件，
  比读一遍代码便宜。

### 06 — 设置面板的导航壳（做完了）

四页：General / Models / API key / Config home，页面选择是组件 state（**不引路由依赖**），
旧的三节报告各自进页，「Re-read」挪到弹窗页脚；弹窗放宽到 `sm:max-w-3xl`。旧的 `data-slot`
（`settings-provider` / `settings-key-present` / `settings-home-path` …）一个没丢，新增
`settings-nav*` / `settings-page-*`。

**真机抓到的两处**：(1) 面板的两半（`GET /api/settings` 要解析、`GET /api/providers` 只读）在
「`:default` 指着刚删掉的 provider」时**表现不同**——报告拒答、目录照答——所以它们**各自失败、各自清空**；
一开始共用一份失败态，结果是"读失败还留着上一次的解析值"，正是面板文档说的那种一次说两件事。
(2) 侧边栏那颗按钮的 `title` 还写着"read-only"，一并改掉。

### 07 — 模型页与表单（做完了）

列表（显示名/来源徽标/endpoint/几个 model/凭据名与密钥与否）+ 表单（ID / Display name / API address /
API protocol / API key / 模型目录：每行 id + 输入模态 + 默认单选 + 删除，计数收在 `Limits` 里）。
编辑时 **ID 只读**并写明「改名 = 删了重建」；**删除不弹二次确认**，界面上写明 `config.edn.bak` 是退路。
真机走完：新建 → 列表立刻有它 → composer 选择器立刻有它 → 选中跑一轮拿到回答 → 编辑 → 删除，
每一步的截图在 `evidence/`。

**一处真机才暴露的选择**：新建 provider 时协议下拉默认选中的是 `protocols[0]`，而在 e2e 进程里那个集合
还包含测试替身 `:fake`——于是真机第一次建出来的 provider 协议是 `:fake`。现在默认取
`openai-completions`（在集合里时），其余仍可选：**「这个进程会说什么」是服务端该答的，
「哪一个是新建时的合理默认」是界面的事**。

### 08 — General 页：默认档三个旋钮（做完了）

`POST /api/defaults`：**缺席 = 不动那一项，`null` = 清掉那个键**；**命名一个 provider 是替换整档**
（inline 描述唯一的出路）；**先解析后写**（解析不出来就不是一个变更）；落盘复用 05 那台机器。
前端三个下拉 + Save；inline 描述时把那段只读显示出来并**禁用 Save 直到选了厂商**（否则"看起来没动过
的表单"按一下就会删掉那段描述）。

**真机的完整链路**：表单里填的密钥 → `.env` 的 `ACME_GATEWAY_API_KEY=…` → 默认档指向它 →
`GET /api/settings` 报 `provider: acme-gateway`、`tiers.provider: config`、`key.name: ACME_GATEWAY_API_KEY`
→ **新会话**用它解析。另外在**没有 pin 的普通服务**上跑了同一份家目录，`provider/init` 行是
`{"provider":"acme-gateway","source":"default","display-name":"Acme Gateway","api-key":"stripped",…}`。
（e2e rig 会**抑制**这行——被脚本服务的会话没有档可记，见它的 docstring——所以这条证据来自
`clojure -M:run` 起的那台，不是 rig。）

### 09 — 获取可用模型（做完了）

`POST /api/providers/models`：**精确路由**（collection 之后只有一段，通用形状要求两段，
掉进兜底就是那个"body 根本不存在的 500"）；密钥可由表单带（先试一把还没落盘的钥匙），
否则按 `api-key` 的规矩解析；`:id` 单独给出时用目录里的 endpoint 与该 id 的凭据名。
测试缝 `providers/*list-models*`（`alter-var-root`，照 `*directory-chooser*`），**测试不出网**：
stub 的列表、厂商 401 的**原话**、问不出来的两种情形、以及"什么都不写"。

**真机改了消息**：连不上时 JVM 的原话是「Remote host terminated the handshake」——不说是哪个地址。
一半填好的表单里有好几个地址，所以现在包成「could not reach <地址> — <原话>」（截图见
`evidence/t09-probe-refusal.png`）。

### 10 — 文档与全量验收（做完了）

**全量**：`clojure -M:test -m harness.test-runner` → **707 tests / 10497 assertions / 2 failures**，
两条都是常驻的 JDK 25 那对（`cap/project_test.clj:344,347`）。`cd ui && npm run typecheck` 0 error、
`npm run build` 全绿、`npm test` **14/14**。基线（本分支起点 `5b0c342`）是 686/10276/4（含一条已知竞态，
那次没触发）。**净增 21 条测试、221 条断言。**

**文档**：`providers.md`（一份两节、凭据、写的一侧与三条写入、探询）、`client.md`（四页、两页会写、
两个请求两份失败、首次跑通的顺序）、`edge.md`（六行路由 + 审计栏 + "写配置不搬日志" + 精确路由那条坑）、
`home-and-storage.md`（家目录树、`.env` 一行、`.bak`）、`README.md`（加厂商不必手编文件、四页各干什么）、
`config.edn.example`（头一段写明会被重排）、`CONTEXT.md`（**提供方** / **凭据名** / **默认档** /
**三个旋钮**四个词）、`docs/architecture.md` 的「在办」记下这个分支。

**真机走查**（`harness.e2e-server` + 假家目录 `/tmp/cp-root` + `vite` + Playwright），
13 张截图在 `evidence/`：`t06-general` `t06-key-page` `t06-home-page` `t07-list` `t07-form`
`t07-created` `t07-edit` `t08-defaults` `t08-recovered` `t09-probe-refusal` `t10-round-trip`
`t10-unresolvable`（+ 那张参考界面）。走查覆盖：四页各一眼、新建（含密钥落 `.env`）、
编辑（ID 只读）、删除、探询失败的原话、默认档保存与新会话解析、**以及一次"坏家"的完整往返**
（删掉被默认档指着的 provider → 面板把服务端那句原话当内容显示、控件仍在 → 用控件指回 openrouter
→ 恢复正常）。

**票面里说错/说漏的**：
1. 03 的 `:display-name` 要同时进 `resolved-fields` 与 `catalog-fields`——照做了，
   但**更好的做法不是"两处都加"，而是让后者由前者派生**（已改）。
2. 04 的 `:protocols` 若写成 `def` 会在加载时冻住（见上）。
3. 08 的验收写「真机看一眼那条会话的 `provider/init` 行」——**e2e rig 会抑制这行**（pin 是测试的
   机制），所以这条证据换了一条路：普通服务 + 同一份家目录（见上）。
4. 我原以为"删一条 provider"是单步动作；真机证明它与 `:default` 是一对（见 05）。

## 复议（2026-09-16，落地之后，主人看过界面）

落地的十张票**不改写**，上面照旧是当时的决策与验收。这一节记主人看过后改掉的地方。

### 面板从四页收成两页

决策 10 那一段（「设置面板长出左侧导航，四页……**API key**、**Config home**」）由主人当场推翻：
**只留 General 与 Models**。理由不是审美：那两页报的东西——密钥有没有、从哪来、是哪一行、
家目录在哪、哪几份文件在——**Models 每一行与 composer 那边已经在眼前**（`ACME_GATEWAY_API_KEY` 与
`key ✓` 就在 provider 行里），一页只装已经看得见的东西就是一步多余的路。
07 的列表行因此成了密钥事实唯一的界面出处，02 那一条验收（「面板把凭据名显示出来」）由它继续满足。

配套的三处改动：

- **弹窗的 `DialogDescription` 整段删掉**（「What this session is running on…」那四行）：
  标题就是 `Settings`，其余的话在页里。
- **默认档的模型必须从列表里选**：没有「— 厂商自己的默认 —」这个空选项。厂商**一定**有默认 model
  （目录不接受没有 model 的 provider），所以那个选项除了把同一句话再说一遍没有别的内容；
  换厂商时控件直接落在**新厂商的默认 model** 上——服务端本来也会解析到它，控件只是把它说出来
  （`modelFor`：不在列表里就落回 `provider.model`）。代价是保存会把那个 model **显式写进
  `:default`**，于是面板的档报告从 `catalog` 变成 `config`——这是"控件显示什么就存什么"的直接后果，
  不是缺陷。
- **弹窗尺寸定住，滚动发生在页里**：`h-[min(30rem,62vh)]` + 右侧 `overflow-y-auto`。
  一份 provider 表单比面板高，会自己长大的 modal 会在人打字时把导航和按钮挪走。
  真机量到：弹窗 561px 不动，内容区 672 > 454 可滚。

### 模型目录不再勾选默认

07 的验收里那个 `settings-provider-model-default` 单选钮**删掉了**，主人的话是
「Model catalog 不需要勾选默认，在 General 已经设置了」。目录仍然要求每个 provider 声明一个默认
model（`:model` 必须是 `:models` 的键），所以那个位置由**第一行**顶上，控件旁边写明
「第一行就是这家厂商的默认 model；一轮跑在哪个 model 上是 General 的事」——两件事本来就不同：
provider 自己的默认是**目录的一条事实**，而人说的「默认模型」是**一轮从哪儿开始**。
真机验过顺序而不是字母序：加两行 `zeta` 再 `alpha`，落盘的 `:model` 是 **`zeta`**，而 model 表
按 id 排序存成 `["alpha" "zeta"]`。

真机复核（同一套 rig，假家目录换了名字）：`evidence/t06-general.png`（两页导航、无描述、
默认模型是 `qwen3`）、`evidence/t06-scroll.png`（滚到底：模型两行 + Add model / Fetch /
Save / Cancel / Remove 都在，弹窗没长）。

### 10 的落地记录里那两处随之作废

- 「**文档**：…`client.md`（四页、两页会写…）、`README.md`（…四页各干什么）」——现在两边都写**两页**。
- 「走查覆盖：**四页各一眼**」——现在是两页，另补一次滚动复核（`t06-scroll.png`）。

`docs/architecture.md` 的「在办」那一条、`client.md` 与 `README.md` 的正文都已经按两页改过；
`t06-key-page.png` 与 `t06-home-page.png` 那两张截图**留着不删**——它们记的是那两页存在过的样子，
而 `.scratch/` 是历史。
