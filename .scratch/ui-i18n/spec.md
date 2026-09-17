# spec: 界面说两种语言（ui-i18n）

**一句话**：把界面上**属于界面自己**的那约 350 条文案收进一份按语言分组的目录（英文 + 中文），用
i18next 管；语言首次按浏览器语言判定、在设置里可改并记住；**后端的句子一个字节不翻**——工具结果、
HTTP 错误、审批话术、工具描述、`prompt.md` 原样穿过，边界写下来。

## 问题（对今天页面的清点）

1. **界面自己的文案散在 28 个文件里，约 330–360 条。** 最大的几处：

   | 文件 | ~条数 | 代表 |
   |---|---|---|
   | `settings-panel` | 65 | `Save default tier` / `Reading the catalog…` / `Nothing was written — config.edn is exactly as it was.` |
   | `trajectory-view` | 42 | `no record for this session yet.` / `(no text)` / `rows that do not match are hidden.` |
   | `sidebar` | 35 | `Add a project first — a session belongs to a project.` / `Remove this project?` / `Keep it` |
   | `thread.aui` | 22 | `How can I help you today?` / `Send a message...` / `Scroll to bottom` |
   | `tool-fallback.aui` | 18 | `Used tool` / `Cancelled tool` / `Approved by user` / `Always allow` |
   | `message-parts` | 15 | `Running` / `Needs approval` / `Arguments` / `No result` |
   | `approval-gate` / `composer-chrome` | 14 / 14 | `This tool call needs your approval` / `Git branch` / `detached` |
   | `mcp-panel` / `trajectory-timeline` | 11 / 10 | `not used yet` / `click to open it` |
   | `elements/{image,attachment,file,reasoning,thread-list,tool-group,markdown-text}` | 12/9/6/2/2/1/1 | `Click to zoom image` / `Add Attachment` / `Unnamed file` |
   | `lib/format` | 9 | `no log yet` / `never run` / `tok/s` / `turn`/`turns` |

2. **有五处文案今天就是中文，夹在英文里。** `subjectOf` 的 `删除` / `行` / `完成`（`replace` /
   `insert` / `todo_write` 三支）、`turnSummaryLabel` 的 `N 次工具调用 · M 条消息`、思考行的 `思考`。
   而 `ui/index.html` 硬写着 `lang="zh"`，文案却几乎全是英文——**这本身就是一处不自洽**。
   另有一处服务端的中文：macOS 文件夹选择器的提示语（中英各半），它由后端拥有。

3. **没有任何机制。** 没有目录、没有语言来源、没有持久化；`navigator.language` 与 `Intl` 在 `ui/src`
   里一次都没出现，唯一与语言有关的是 `format.ts` 的 `toLocaleString()`。
   `format.ts` 的注释本身已经写着「这些是轮与模型调用的计数，**这个界面说的语言不需要第三种**」——
   这个界面一直在心里记着自己会说哪几种，只是没有地方把它写下来。

4. **尺寸与时长的写法有四份，不是一个。** `format.ts`（`B/KB/MB`、`<1s`/`1.4s`/`2m 15s`、`N tok/s`、
   `N% cached`、`plural`）、工具卡里的 `formatToolDuration`（与 `formatMillis` 同一套分档）、
   `file.tsx` 里的 `formatFileSize`（另一位小数）、`attachment-rules` 里的 `megabytes`。
   两种语言一进来，四份都要各自知道量词——所以顺手并成一族。

5. **「抄来的」12 份里有 10 份含文案，约 60 条**，而 `LOCAL:` 标记只在 2 份里（`thread.aui` 11 处、
   `thread-list.aui` 5 处）——其余 8 份今天与上游**逐字节相同**。

6. **后端的句子也上屏幕，约 150–250 句**：工具结果（`wrote N chars to …`、`[timed out after 120000ms
   — the command was stopped]`、任务清单与 job 的渲染、锚点拒绝与漂移的整句）、HTTP 错误体、
   AG-UI 的审批话术与 MCP 的提问、工具描述与 `prompt.md`（轨迹视图里可读）。

7. **套件今天把两种语言的文案都钉在断言里**：`attachments` 套件钉英文（`change the model`、`3 MB`），
   `turns` 套件钉中文（`72 次工具调用 · 25 条消息`）；`ui/test/ui.test.ts` 还钉着用例总数
   （`EXPECTED_CASES`，今天 31）。所以「文案有语言」这件事要先把这几处重新安排，否则第一批落地就会红。

## 决策

1. **两种语言：英文与中文。机制用 i18next + react-i18next。** 新增两个依赖——今天 UI 一个新依赖都没
   加过（`skill-picker` 那次专门记了「零新依赖」），所以 `ui/package-lock.json` 的改动属于本特征的
   diff，落地时单独看一眼。镜像可用性已验：`registry.npmmirror.com` 上 `i18next` 26.4.2 /
   `react-i18next` 17.0.14。
   **选库不选手写的理由**（牛总 2026-09-17 定）：复数、插值、命名空间、以后加语言都是现成的。
   **代价如实记下**：写法会与仓里「零 import 的纯模块」分叉——靠决策 6 兜住。

2. **判定链是一个纯函数**：本机记住的（`localStorage`）→ 浏览器语言（`zh*` 归 `zh`，其余归 `en`）
   → `en`。`<html lang>` 跟着语言走。开关在设置的 General 页，**只影响界面，不上线**：
   同一个会话在两个不同语言的浏览器里读到的后端句子完全相同。

3. **只有界面自己的文案有语言。后端一个字节不翻**——工具结果、HTTP 错误体、审批与提问话术、工具
   描述、`prompt.md`、MCP 服务器自己给的 prompt/description。
   **理由不只是省事**：工具结果**同时是模型的上下文**（它是模型读完才决定下一步的那份记录），
   把它翻成随界面变化的两种语言，等于让同一份记录不唯一，前缀缓存与模型看到的东西都会跟着变。
   这一条写进 `docs/architecture/client.md`，并改掉今天那句「文案与 UI 其余部分同语言（英文）」。

4. **工具名与模型词汇不翻**：`read` / `bash` / `todo_write` 逐字（`CONTEXT.md` 的「不给别名」照旧），
   参数与结果的正文是模型写的，也不翻。今天那三处中文进目录，两种语言各有说法。

5. **抄来的 `elements/` 就地翻，逐处标 `LOCAL:`。** 这**推翻** `flat-step-rows` 决策 9 的「抄来的
   12 份一个字节不动」——理由是另一半：一半英文一半中文的界面正是本特征要消掉的东西，而那几份里的
   句子（`How can I help you today?` / `Used tool` / `Add Attachment`）恰恰是最显眼的一批。
   **代价如实记下**：那 10 份与上游不再能直接 diff 对账；`LOCAL:` 标记是替代品——它说明「这里是有意
   改的」，不说明「上游改了什么」。
   **「与上游逐字节相同」这句话今天写在五处**（`docs/architecture/client.md` 与 `composer-chrome` /
   `composer-stats` / `styles.css` / `message-parts` 的注释里，`rg "byte-comparable|逐字节|与上游"`
   就是那份清单），其中前一处是**当前状态**、必须改，后四处是「所以我另开一处、不就地改」的**理由**
   ——理由照旧成立，但那半句结论不成立了。**另开一处而不是就地改**这条取舍没有被本特征推翻。

6. **纯模块仍是运行时零 import，收一个 `TFunction`。** `format.ts` / `turns.ts` / `attachment-rules.ts`
   今天能被 vitest 按相对路径直接 import（`vitest.config.ts` 那段注释就是为它们写的），因为它们
   不 import 任何东西。加了语言之后它们收一个 `t`（`import type { TFunction }` 是**类型**上的
   import，运行时仍是零），调用方用 `useTranslation()` 把 `t` 递进来。于是套件照旧在浏览器外测它们，
   并且**两种语言的输出都钉住**。

7. **键在调用处字面写**，不拼字符串（`` t(`status.${x}`) `` 这种不许）。两条守卫靠它成立：类型检查
   挡得住不存在的键（`react-i18next` 的 `CustomTypeOptions` 按英文那份收紧），以及「目录里不留没人
   用的键」扫得出来。

8. **目录按面分文件**（i18next 的 namespace）：`shell` / `composer` / `thread` / `approval` /
   `settings` / `trajectory` / `elements` / `errors`，每份语言一组 `locales/<lng>/<ns>.json`。
   一个面一张票，一个面只动它自己的两份 JSON。
   **这七八个名字原本是我一次写下的，落地时改了**（01 落地记录）：面是**按票一个一个加**的，因为
   `lib/catalogs.ts` 要 import 每一份 JSON（类型才收得紧，见决策 7），所以那个文件是各面**共用**的
   ——一次登记四个名字而文件还不存在，就是 import 一个不存在的模块。**代价如实记下**：那一个文件是
   各面唯一的交点，每加一面四行（两条 import、两条登记），顺序进行而不是并行。
   目录是**打包进来的静态资源**（不异步加载），所以没有 Suspense、没有「先闪一下原文」的窗口。

9. **中文文案由本特征一并写出，走查时请牛总过一遍**：不引入翻译流程、不接第三方翻译服务。
   文案的判据是「界面在说什么」，不是逐字对译。

## 非目标

- **第三种语言**。加一份是加文件，不是改机制；今天只交两份。
- **后端目录**（决策 3）：工具结果、HTTP 错误、审批话术、工具描述、`prompt.md` 都不翻。
- **RTL、时区、千分位**。今天手写的格式保持形状，只有词与量词进目录。
- **翻译的校对流程**。
- **把界面语言写进会话记录或 `config.edn`**：语言是浏览器的偏好，不是会话的事实。
- **`ui/index.html` 的 `<title>`**：产品名，不翻。

## 验收主线（真机，浏览器）

两种语言**各走一遍同一个主线**，每个面都在其中：

项目（加一个 → 列表 → 删一个的确认框）→ 新任务（侧栏空态、输入框、目录与分支、模型与技能两处浮层）
→ 问一句（工具行与展开的参数/结果、思考行、轮摘要折叠）→ 一次审批（批一次、拒一次）→
一次 elicitation 提问 → 设置三页 → MCP 面板 → 轨迹视图两种排法 → composer 那串数字。

**中英各一份整页截图**收进 `evidence/`，并记下与票面的差异（`flat-step-rows` 的「与票面的四处差异」
是这份文档的范例）。走查的场地与两处 rig 事实同 `trajectory` / `flat-step-rows` 记的那一套。

判据一句话：**界面里不该再有中文夹英文（反之亦然）**，除了决策 3 的后端句子与决策 4 的模型词汇。

## 票清单（`.scratch/ui-i18n/issues/`）

| # | 票 | 挡住它的 |
|---|---|---|
| 01 | 语言的底座：两个依赖、一份目录、一条判定链、一个开关 | 无 |
| 02 | 数字、量词与日期：四份写法归成一族，按语言说 | 01 |
| 03 | 侧栏与外壳 | 01, 02 |
| 04 | 输入框一圈 | 01, 02 |
| 05 | 正文与步骤行 | 01, 02 |
| 06 | 审批与提问 | 01 |
| 07 | 设置那扇门里的三页 | 01, 02 |
| 08 | 轨迹视图 | 01, 02 |
| 09 | 抄来的元素：对话外壳 | 01 |
| 10 | 抄来的元素：工具卡 | 01 |
| 11 | 抄来的元素：附件与文件 | 01 |
| 12 | 客户端自己抬起的句子 | 01, 02 |
| 13 | 收尾：两种语言各走一遍主线 | 01–12 |

**这是一次宽改动的 expand–migrate 序**：01 是 expand（机制进来，旧字面量一个不少，什么都不坏），
02–12 是按面分批搬（每批自己绿），13 是整合与验证。没有「contract」那一步——旧形式就是那些字面量
本身，每批自己把它们搬走。

## 状态

**票已出（2026-09-17）。** 落地在一个 worktree 里：`.worktrees/ui-i18n`，branch `ui-i18n`，从
`1d96a49` 切出（切出时 `bash-lifetime` 已被另一个会话提交干净，工作树里只剩本目录自己的未跟踪）。
~~一条都没落地~~ ——**01 已落地，02–13 还没**，见文末「落地记录」。

**基线**（切出时实测）：UI 套件 31/31 green（`cd ui && npm test`，14.6s，
`ui/test/ui.test.ts` 的 `EXPECTED_CASES`）；全量 Clojure 套件的基线是 `bash-lifetime` 落地时记下的
**852 tests / 11233 assertions / green**（2026-09-17，同一支线）——落地前重跑一次确认。

本特征不碰 `src/` 一行：所以第 13 张票那一次全量应当与基线**一模一样**，多一条少一条都要问为什么。

## 落地记录（2026-09-17）：票 01 语言的底座

**落了什么**：两个依赖（`i18next` 26.4.2 / `react-i18next` 17.0.14，`ui/package-lock.json` 一起改）、
`src/lib/language.ts`（判定链，零 import）、`src/lib/catalogs.ts`（目录登记，只 import JSON）、
`src/lib/i18n.ts`（i18next 初始化 + `<html lang>` + `setLanguage`）、`src/i18next.d.ts`（键类型的来源）、
`src/locales/{en,zh}/{shell,settings}.json`、`main.tsx` 里那一行副作用 import、设置 General 页的
语言行、视图切换那两条文案、`ui/test/suites/i18n.ts`（两条用例，`EXPECTED_CASES` 31 → 33）、
`docs/architecture/client.md` 新增「文案与语言」一节并改掉「文案…同语言（英文）」那句、
`docs/architecture.md` 的「在办」加一条。

**与票面／本文件的差异（如实记，五处）：**

1. **目录不是一次登记八个面**，而是按票一面一面加——理由与代价见决策 8 的就地更正。01 只登记了
   `shell` 与 `settings`。
2. **`tsconfig.json` 加了 `resolveJsonModule: true`**（票面没提）。没有它 `tsc` 不认 JSON 模块，
   键的字面量类型也进不来；Vite 本来就原生读同一批文件。
3. **纯逻辑拆成了 `lib/language.ts` + `lib/catalogs.ts`，没有都塞在票面说的 `lib/i18n.ts` 里。**
   理由是那条要保住的既有性质：`i18n.ts` 要 import `react-i18next`（等于 React）并在模块加载时碰
   `document`，套件碰不得。拆出去的两份分别「零 import」与「只 import JSON」，于是判定链与目录奇偶
   都能在浏览器外钉住。
4. **`language.ts` 多了一个 `isLanguage`**（票面没提）：开关的值是 DOM 给的 `string`，这是守卫而不是
   `as Language` 断言——闭表在 DOM 边界上最容易被破掉。
5. **`ui/index.html` 的 `lang` 从 `zh` 改成 `en`**（与兜底一致），并写明它为什么不能是静态的正确值。

**两处守卫是「故意弄坏再修好」验过的，不是推断的**——这是本票最要紧的两条断言：

- **类型闸**：临时写一个错键（`t("view.conversatoin")`）→ `npx tsc --noEmit` **失败**，信息里列出
  可用键并提示 `Did you mean 'view.conversation'?` → 删掉探针，回到干净。
- **奇偶闸**：临时删掉 `zh/settings.json` 的一条键 → 套件**红**，信息是
  `namespace "settings" has keys in one language only` → 恢复，绿。

**走查的场地（真机，浏览器）。** 与 `flat-step-rows` 同源：`harness.e2e-server` 脚本替身 + 假的
`CLJ_HARNESS_HOME`。两处 rig 事实与上次**不同**，记下来给下一票用：

- **5173 与 8080 都被别的会话占着**（5173 是另一个 vite、8080 是另一个 harness），而 5173 正是 CORS
  唯一放行的来源。所以页面起在 **5199**，与后端之间走**同源代理** `/__harness → 127.0.0.1:8099`
  ——同源不触发 CORS，后端一个配置都没碰。
- **改写 `http://localhost:8080` 那一段的注入方式换了**：不是 Playwright 的 fetch 补丁，而是临时
  vite 配置里一个 `transformIndexHtml` 钩子把它注进 `<head>`。理由是**它要活过一次 reload**
  ——「记住的语言」这条只能靠刷新证明，而 Playwright 的补丁刷新就没了。临时配置落在
  `ui/node_modules/.ui-i18n-vite.config.mjs`（gitignore 之内），跑完已删。

**观察到的事实（这台机器的浏览器是 `zh-CN`）**：`navigator.language` = `zh-CN`，没有记住的值时
`<html lang>` = `zh`、两个页签读 `对话 | 轨迹`，而页面其余部分仍是英文——**这正是「一票一面」应有的
中间态**；记住 `en` 后刷新 → 页签回 `Conversation | Trajectory`、`lang="en"`（**记住的压过浏览器的**）；
在设置里选「中文」→ `lang="zh"`、记住 `zh`、页签回中文；再刷新仍是中文。
五张截图在 `evidence/`：`t01-01-first-visit-browser-zh` / `t01-02-remembered-en` /
`t01-03-settings-row-en` / `t01-04-settings-row-zh` / `t01-05-remembered-zh-after-reload`。

**一处与票面的出入**：设置里的语言行**在视口之外**（那个弹窗自己内部滚动，要先滚进来才拍得到）。
不是缺陷，是弹窗既有的排版，但票面写「中英各一张真机截图」时没料到——下一票拍设置那几页时同理。

## 落地记录（2026-09-17）：票 02–13 全部落地

**十三张票全部落地**，`ui-i18n` 分支上 16 个提交（01 + 02–12 各一 + 03 与 11/12 各一个补充）。
最后**目录里的条目**：英文 355 条、中文 343 条（差的 12 条正是英文独有的复数形式），共 698 条。

| 面 | 目录 | 票 |
|---|---|---|
| 数字、量词与时长 | `format.json` | 02 |
| 侧栏、外壳、轮摘要之外的本侧句子 | `shell.json` | 03 |
| 输入框一圈 | `composer.json` | 04 |
| 正文与步骤行 | `thread.json` | 05 |
| 审批与提问 | `approval.json` | 06 |
| 设置与 MCP 面板 | `settings.json`（107 条，最大的一份） | 07 |
| 轨迹视图 | `trajectory.json` | 08 |
| 抄来的元素：对话外壳 / 工具卡 / 附件与文件 | `elements-thread` / `elements-card` / `elements-files` | 09 / 10 / 11 |
| 本侧自己抬起的句子 | `errors.json` | 03、12 |

**与票面／本文件的差异（一次记全，八处）：**

1. **每一面自己那张「中英各一张真机截图」合并到第 13 张票的那一次走查里。** 十张票各起一次
   rig（脚本替身 + vite + 浏览器）是不成比例的，而 13 的验收主线本来就要求「两种语言各走一遍、
   每一面都在其中」。所以那十张票的门禁是 `typecheck` + 套件，**截图在这里一次拍全**。
2. **`elements` 一个面拆成三个**（`elements-thread` / `elements-card` / `elements-files`）：三张票
   不写同一份 JSON，这样「一个面一张票」在文件层面才真的成立。
3. **多了一个 `format` 面**（数字与时长那一族的词）。本文件决策 8 那张清单里没有它，因为 02 的
   词是**所有面共用**的词汇，不属于任何一处地方。
4. **面的键是按票逐个登记的**（01 已就地更正过决策 8）：`lib/catalogs.ts` 是各面唯一的交点。
5. **票 02 保留了第二位小数**（`formatMegabytes`）：整 MB 四舍五入会让「这张图 2 MB，上限是 2 MB」
   读起来像 bug，所以「一族」是**一个文件里的两种精度**，不是一个函数。
6. **票 11 的 `download` 属性不进目录**：它是落盘时的**文件名**，不是给人读的话（跟着语言走只会存出
   一个叫「下载」的文件）。补一个提交修正。
7. **票 12 之后还剩两句本侧自己抬起的英文**（12 自己点名了但不在它的文件清单里）：`lib/skills.ts` 的
   兜底 `HTTP N` 与审批卡里「读不出这个问题是什么」那句。补一个提交修掉，两句都进 `errors`。
8. **中文文案由实现写出，请牛总过目这一条还没做**（决策 9）。走查的截图就是那一次机会——
   描述「界面在说什么」而不是逐字对译，但**没有经过母语者以外的评审**，如实留在这里。

### 真机走查（2026-09-17，两种语言）

场地与 01 那次同源；5173 / 8080 仍被别的会话占着，所以页面在 5199、后端走同源代理、
origin 改写的注入走 vite 的 `transformIndexHtml`（这样它活得过 reload）。
一条会话绑在 worktree 自己身上，脚本替身给三轮：一次 `read` + 一次 `bash`（并发两个工具调用）、
一次 `todo_write`、一句收尾——所以页面上同时有折叠摘要、工具行、思考行、参数与结果。

截图在 `evidence/`（中英成对）：

| 中文 | 英文 |
|---|---|
| `t13-zh-01-shell`（空会话的外壳） | — |
| `t13-zh-02-conversation-folded` / `-03-conversation-steps` | `t13-en-04-conversation` |
| `t13-zh-04-settings-general` / `-05-settings-models` | `t13-en-01-settings-general` / `-02-settings-models` |
| `t13-zh-06-trajectory` | `t13-en-03-trajectory` |

**观察到的事实：**

- 中文页面上**只有两类东西是英文**：工具名（`read` / `bash` / `todo_write`）与 model id（`seeded`），
  正是决策 4 留下的；轨迹视图里记录的正文（system 提示、`<instructions …>`、参数、结果）
  也原样是英文，正是决策 3 留下的。**其余每一处都读了中文**：侧栏、状态词「运行中」、
  折叠摘要「3 次工具调用 · 4 条消息」、composer 底下「1 轮 · 3 次模型调用」、
  工具的 `todo_write · 1/2 完成`、思考行、设置三页（「提供方」/「内置」/「没有密钥」/
  「2 个模型 · DEEPSEEK_API_KEY」）、轨迹的「时长 / 按轮 / 共 1.2 秒 / 第 1 轮 9 个条目 · 3 次模型调用」。
- **日期标点真的跟着语言变**（这是 `formatTime` 收 locale 参数的可见证据）：
  同一个会话行中文读 `2026/9/17 18:54:55`，英文读 `9/17/2026, 6:55:08 PM`。
- **控制台里只有两个既有的 404**（一个刚建的会话还没有日志，`/stats` 404；以及 `favicon.ico`），
  两者在 01 那次的日志里就有，**不是本特征造成的**——量过，不是推断。
- 切语言走的是设置里那个真的 `<select>`（不是写 `localStorage`），切完 `<html lang>` = `en`、
  `localStorage` = `en`、那一行自己的标题也从「语言」变成 `Language`。

### 门禁（第 13 张票那一次，全绿）

- `cd ui && npm run typecheck` 干净；`npm run build` 成功（837ms，只有既有的 chunk 体积提示）。
- `cd ui && npm test`：**35/35**（新增第三条 i18n 用例：目录里不留没人命名的键；
  用 `import.meta.glob` 把源码当文本扫，**故意加一个孤儿键验过它会红并点名**）。
- `clojure -M:test -m harness.test-runner`：**852 tests / 11233 assertions / 0 failures**
  ——与基线**逐字相同**，本特征不碰 `src/` 一行，这一点是量出来的。

