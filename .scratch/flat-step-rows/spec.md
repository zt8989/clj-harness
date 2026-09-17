# spec: 平铺的步骤行（flat-step-rows）

**参考**：给的截图——一个把「工具调用」与「思考」平铺成清单的界面：每行 `[图标] 名字 · 一行字`，
工具行与思考行 13px，正文 14px，字体 `-apple-system, BlinkMacSystemFont, "Segoe UI", "PingFang SC",
"Hiragino Sans GB", "Microsoft YaHei", "Helvetica Neue", Helvetica, Arial, sans-serif`。
参考的是**行上读到什么**与**字号**，不是像素级复刻。

**一句话**：把助手消息里那层组壳和行上的 chevron 去掉，让一次 run 做过的每一步都只是**一行**——
`[图标] 名字 · 摘要`，不点开就读得出这一步在做什么；字号收成正文 14 / 步骤行 13 两档，字体换系统栈。

## 问题（对今天页面的实测）

1. **每个工具调用前面挂着**一行**「1 tool call」。** 一次工具调用在线上就是**一条独立的助手消息**
   （`.scratch/assistant-ui/issues/04` 第 8 节实测：4 个调用 = 4 条消息，各有各的 `parentMessageId`），
   而 `groupPartByType` **总是**把工具调用归进 `group-tool`（上游注释原话：*Always groups tool calls
   … even if there's only one*），所以每个调用各自成组、`count` 恒为 1。页面读起来是：

   ```
   ▾ 1 tool call
      read · Done 0.1s ⌄
   ▾ 1 tool call
      anchor_grep · Done ⌄
   ```

   组默认打开的理由（04 第 3 节：「把组也折上，一次 `read` 在页面上就只剩一行 1 tool call，
   连工具名都看不见」）在真实页面上**不成立**——它展开之后露出来的正是它自己那一行。
   它今天不是「把 4 个调用收成 1 行」，是**每个调用前面多一行**。
2. **行上 `·` 后面是状态词，不是这一步在说什么。** `read · Done`——`Done` 是每行都有的、
   扫两行就知道的东西；「读的是哪个文件」得展开才看得见。
3. **思考行上一个字都没有。** `Reasoning ⌄`（12.3 之后它已经是与工具行同形的一行），内容全在抽屉里。
4. **字号三档参差、字体是装进来的。** 正文继承 16px（`aui_assistant-message-content` 没写字号）、
   步骤行 `text-sm` 14px、参数与结果 `text-xs` 12px；字体是 `@fontsource-variable/geist`。

## 决策

1. **组头删掉，不留替代。**
   `THREAD_COMPONENTS.ToolGroup` 换成一个**只渲染 children 的透传**（组节点还在——见第 8 条的代价说明，
   而这一层什么都不画）。删掉 `ToolCallsGroup` 与它用到的三个导入。组内那 4px 的 `gap-1` 随组一起去，
   相邻两行由各自的 `py-1.5` 隔开（12px）。
2. **一行 = 类型图标 · 名字 · 摘要。** `[图标] read · /Users/…/CONTEXT.md`。
   图标说**这是哪一类调用**（读文件 / 写文件 / 搜索 / 跑命令 / 求值 / 加技能 / 改配置 / 认不出来），
   名字是工具名逐字（不做美化、不起别名），**摘要**是从这次调用的参数里**投影**出来的一行字。
   最后那个 `·` 只在有摘要时才出现——没话说的时候不写一个空分隔符。
   - 摘要**不是参数 JSON 的截断**，是**投影**：只取「这次调用在说什么」的那一个字段（表见下）。
     截断一坨 JSON 读起来还是 JSON。
   - **半截的 JSON 不显示**：流式途中 `argsText` 还没闭合，解析失败时这一瞬间只有名字，
     不显示半截参数、不显示 `{}`、不显示 `undefined`。
3. **认不出来的工具落到兜底投影**：第一个字符串参数；一个字符串参数都没有就不写摘要。
   这条是为 MCP 动态注册的工具与以后新加的工具留的——**新增工具不改前端就能看见它的调用**，
   这是 04 立下、要保住的性质。
4. **状态从「词」挪到「行尾的标记」。** `·` 后面的位置让给摘要之后，状态挪到行尾：
   一个图标（转圈 / 对勾 / 叉 / 感叹号）+ 已有的耗时段。**两件事各有位置，谁也不用兼职**：
   图标说类型、标记说状态。
   **状态不许因此变得看不见**：失败仍用 destructive 色、被否决仍划掉（`line-through`）、
   待批准仍是感叹号且**底下那张审批卡照旧整幅宽地挂在抽屉外面**——那是踩过死锁坑的设计
   （两张卡悬置的那一批），一个字节都不动。
5. **思考行把想法首行摊出来**，与工具行同一形状：`[大脑图标] 思考 · <想法首行>`。
   取**第一行非空**文本（模型常常以换行开头），**截到 120 字**加 `…`，再交给 CSS 单行省略兜底。
   JS 截一刀不只是好看：行上那个字符串**截满之后就不再变**，于是流式途中这一行不再每个 token
   重渲染一次（`useAuiState` 按值比较）。
   标签用 `思考`（与参考界面一致）；工具名照旧是 `read` 这样的原文。
6. **折叠还在，chevron 从静息行上去掉。** 每行仍是它自己的 disclosure：点行展开参数与结果，
   思考行点开是整段想法。chevron 去掉（参考界面没有）——留着它，一行行排下来就是一片 chevron，
   像一串待办事项；`cursor` + hover 变色已经说清它能点。
   `data-slot` 的名字**照旧不动**（`tool-call-trigger` / `reasoning-trigger` 等）：
   真机走查是照着它们量的。
7. **字号：正文 14px、步骤行 13px，参数与结果 12px 不动。**
   正文那个 div 是抄来的（`thread.aui.tsx` 里只有 `data-slot="aui_assistant-message-content"`，
   没有 `aui-*` class 可挂），所以 14px 从 `styles.css` 用它的 `data-slot` 改；步骤行是我们自己的
   组件，所以 13px（`text-[13px]`）写在行上。思考的**正文**跟着正文走 14px。
   **用户气泡不动**（它今天 16px）——「正文」指助手那一侧的正文。
8. **字体换系统栈，全局。** `styles.css` 的 `--font-sans` 由 `'Geist Variable', sans-serif`
   换成参考界面那一串；`@import "@fontsource-variable/geist"` 与 `package.json` 里的依赖一起删
   ——不留一个没人引用的字体（`grep -rn geist ui/src ui/package.json` 零命中）。
9. **抄来的 12 份文件一个字节不动。** 「组不画东西」用**槽位覆盖**表达，而不是去改 `thread.aui.tsx` 里
   那个 `groupBy` 数组（改它也能达到目的，但那是改抄来的文件；槽位能表达的事就不动它，
   对账仍是「不重装直接 diff」）。
   **代价如实记下**：`group-tool` 的组节点还在树上（只是不画），`ToolGroup` 这个槽位从「只为
   `defaultOpen` 一个 prop 覆盖」变成「覆盖成一个透传」——两处都只是形状，不是行为。

## 摘要投影表（**闭表**）

行上那半句只许从这张表来。表里没有的工具走第 3 条的兜底；表里有、参数里却没有那个字段时
（模型漏填、半截 JSON）**不写摘要**。

| 工具 | 摘要取什么 | 例 |
|---|---|---|
| `read` | `path` | `/Users/…/CONTEXT.md` |
| `write` | `path` | `notes.md` |
| `edit` | `path` | `src/harness/tools.clj` |
| `replace` | 锚点区间 + 换回来几行：`remove_from`（有 `remove_to` 就 `…` + 它）+ `→ n 行` / `→ 删除` | `a3f9…b71c → 3 行` |
| `insert` | `direction` + `anchor` + 几行 | `after a3f9 · 2 行` |
| `undo_last_replace` | `path` | `notes.md` |
| `anchor_grep` | `pattern` | `minimumReleaseAge\|supplyChain` |
| `bash` | `command` 的**首行** | `pnpm why minimatch` |
| `eval` | `code` 的**首行** | `(harness.tools/specs)` |
| `skill` | `name` | `writing-for-agents` |
| `session-configure` | 这次要改的东西（`provider` / `model` / `reasoning-effort` 拼一行） | `model=anthropic/claude-sonnet-4.5` |
| 其余（含 MCP） | 第一个字符串参数的值 | —— |

`replace` / `insert` 没有 `path` 参数（它们按锚点寻址，路径是把锚点反解出来的），所以这两行的摘要
是锚点而不是文件——**这不是破例，是它们本来就是这么寻址的**。

## 非目标

- **不碰后端、不加/不改任何 AG-UI 帧**：`argsText` / `result` / `status` / `timing` 客户端手里都有，
  本特征是纯渲染层。`harness.frames` 折出来的对话逐字节不变。
- **不做每工具专用卡**：仍只有兜底渲染器一张（04 的非目标，继续不破例）。图标与摘要是**行**的
  两个字段，不是「给某个工具画一张卡」。
- **不改审批卡的形态与流程**：唯一相关的是它仍在待批准行下面整幅宽地出现。
- **不引入字号/字体的主题层**：不造 `text-step` 之类的 token——13 / 14 / 12 三个数字写在各自的
  使用处，理由写在 `message-parts.tsx` 与 `styles.css` 的注释里。
- **不动用户气泡、composer、侧边栏、设置面板**的字号。
- **不给行加背景色或分隔线**：参考界面是一张无框的清单，hover 只变颜色。

## 本特征动哪几处（**闭表**）

| 位置 | 谁的 | 改什么 |
|---|---|---|
| `ui/src/components/message-parts.tsx` | 自建（注入点） | 组 → 透传；行改形（图标 + 名字 + 摘要 + 行尾状态标记）；思考行加首行；新增两张表（类型图标 / 摘要投影） |
| `ui/src/styles.css` | 自建（主题入口） | `--font-sans` 换系统栈、正文 14px 一条、删 Geist 的 import |
| `ui/package.json` | 自建 | 删 `@fontsource-variable/geist` 依赖，`package-lock.json` 跟着 `npm install` 走 |
| `ui/src/components/assistant-ui/elements/*` | 抄来的 12 份 | **一个字节不动**（对账仍是直接 diff） |
| `docs/architecture/client.md` | 文档 | 05 那一票跟上现状（注入点段落、抄来的清单） |

## 验收主线（真机，浏览器）

后端用 `dev/harness/e2e-server.clj` + 假 `CLJ_HARNESS_HOME` + `harness.fake` 的脚本 provider，
前端 `cd ui && npm run dev`（5173 是 CORS 契约），脚本给三个工具调用（`read` / `anchor_grep` / `bash`
失败一次）与两段思考；另起一份**悬置**脚本（两个调用 park）走审批那一路。截图落
`.scratch/flat-step-rows/evidence/`（命名照旧 `tNN-MM-<slug>.png`）。

逐条**量**（`getComputedStyle` / `getBoundingClientRect` / `querySelectorAll`），不靠看图：

1. 页面上 `[data-slot="tool-group-trigger"]` **零命中**；一次 run 的 3 个调用是 3 行。
2. 每个工具行的文字是 `名字 · 摘要`，摘要与投影表逐字对得上；认不出的工具走兜底。
3. 工具行与思考行 `font-size: 13px`，正文 `font-size: 14px`；`font-family` 量到的是系统栈，
   `document.fonts` 里没有 Geist。
4. 点一行展开参数与结果、再点收起；思考行点开是整段想法。
5. 五种状态各出现一次：跑着的转圈 + shimmer、`Done` 对勾、失败 destructive 色的叉、
   被否决的划掉、待批准感叹号 + 审批卡在行下面。
6. 悬置的那一批（两个调用 park）答得完也续得上——`cd ui && npm test` 的 `approval` 套件照旧全过，
   真机再复现一次「两张卡同时在、都能点」。

## 状态

**2026-09-16 落地，五票全过**（分支 `flat-step-rows`，从 `d0fedf1` 切出）：

| 票 | 提交 | 一句话 |
|---|---|---|
| 01 工具调用平铺 | `d976f81` | 组壳变成透传，页面上零个 `tool-group-trigger` |
| 02 行的形状 | `6c2c159` | 类型图标 + `名字 · 摘要`，状态挪到行尾，两张新表（图标 / 投影） |
| 03 思考行首行 | `d52c1aa` | `思考 · <首行>`，截 120 字，selector 返回字符串 |
| 04 字号与字体 | `0d0c87e` | 13 / 14 / 12 三档 + 系统字体栈，Geist 三处一起删 |
| 05 文档与验收 | 本节的最后一次提交 | `client.md` 跟上、04 旧票追加复议、本节 |

**动过的文件（闭表成立）**：`ui/src/components/message-parts.tsx`、`ui/src/styles.css`、
`ui/package.json`、`ui/package-lock.json`、`docs/architecture/client.md`、
`.scratch/assistant-ui/issues/04-message-parts.md`（追加复议）。
`ui/src/components/assistant-ui/elements/` **零改动**——
`git diff --stat d0fedf1..HEAD -- ui/src/components/assistant-ui/` 空。

### 真机量到的数（真 Chromium，脚本化后端 + 假 `CLJ_HARNESS_HOME`）

一个回合：`read`（Done）、`anchor_grep`（Done）、`bash`（长命令，Done），三段思考
（139 字、130 字、86 字）。

| 量的是什么 | 数 |
|---|---|
| `[data-slot="tool-group-trigger"]` 命中数 | **0** |
| 工具行 / 思考行数 | **3 / 3**（一次 run 的 3 个调用就是 3 行） |
| 行上的文字 | `read · CONTEXT.md`、`anchor_grep · minimumReleaseAge\|…\|supplyChain`、`bash · rg -n "…" ~/.m2/repository …`（**逐字等于脚本给的那个参数**） |
| 行高 | 三行都是 **28px**（长命令那一行没有折行） |
| 行上的 chevron | **0**（`tool-call-trigger-chevron` / `reasoning-trigger-chevron` 都不在） |
| 字号 | 工具行 **13px**、思考行 **13px**、正文 **14px**、参数块 / 结果块 **12px**、用户气泡 ~~**16px**（没动）~~ **2026-09-17 推翻：用户气泡改成 14px**（见文末复议） |
| 字体 | `font-family` 开头是 `-apple-system`，串里有 `PingFang SC`；`document.fonts` 里 Geist 的 face **0** 个，资源条目里 `.woff2` 的 **0** 条 |
| 思考行预览 | 两段长的各 **124 字符**（` · ` 3 + 截 120 + `…`），短的那段不截；点开后是**整段**（191 字符全文） |
| 展开后的参数 / 结果 | `{"path":"CONTEXT.md"}` 逐字；结果是真的 `CONTEXT.md`（带锚点：`SHhm│# CONTEXT.md`），说明这一跑走的是真工具、真 `:hashline` 模式 |
| 悬置那一批（两个 `session-configure`） | 两行都是 `Needs approval`（`lucide-alert-circle`）+ **两张**审批卡各自完全可见；两张都批准后 run 续上、两行转 `Done`、最终答复到达——**没有死锁** |
| 中止一个跑着的 `bash` | 行变 `Cancelled`（`lucide-circle-x`、`line-through`、标记仍是 muted 色而不是 destructive） |
| 跑着的时候 | 标记是 `lucide-loader … animate-spin`，行文字带 `shimmer` |

截图在 `evidence/`：`t01-01-rows-flat.png`（平铺的整条对话——01 / 02 / 03 / 04 的数都在这一张里）、
`t02-05-rows-expanded.png`（参数 12px + 结果 + 整段思考）、
`t02-03-needs-approval-two-parked.png`（两张卡同时在）、
`t02-04-cancelled-struck-through.png`（划掉那一支）、
`t04-01-type-scale-chinese.png`（正文 14px + 中文 + 代码块 13px mono）。

### 与票面的四处差异（都是实测结果，不是漏做）

1. **相邻两行的间距是 8px，不是票面预测的 12px。** 票面是按「同一个组里的兄弟节点」算的，
   而真实页面上**每个工具调用是各自的助手消息**（04 第 8 节），两行之间是 turn 内步距
   （消息组 `gap-y-6` 减去中段的 `-mt-4` = 8px）。组里那 4px 的 `gap-1` 确实随组一起没了。
2. **`CALL_STATES` 没有按票面字面「拆开」成两张表。** 词与图标仍在一张表里——04 复议当初正是为了
   「一个状态不能只拿到词、拿不到图标」才合并的。改的是**渲染**：词进 `sr-only` 与 `title`，
   不再画在行上。票面要的效果（行上没有状态词、状态在行尾）成立。
3. **「半截参数不显示摘要」这条在真机没有走到，也没法截帧。** 用 `MutationObserver` 逐帧记录那一次
   调用的行文字：**只出现过两个值**，第一个就已经带着完整摘要（446 字的参数一次到齐），第二个是
   `… Done`。也就是说这一行从来不会先画成「只有名字」。保底逻辑改由投影用例覆盖（16 条，含
   `{"path": "/Users/zh` 这类 5 条半截 JSON，全部判为 null），不是靠真机截帧证明的。
4. **`failed` 这一支没走到**——中止现在报的是 `cancelled`（见上表），而 `failed` 要 `incomplete`
   且 reason 不是 `cancelled`、或者 part 带 `isError`，界面上没有入口。这一条同时修正
   `.scratch/assistant-ui/issues/04` 第 8 节「只有 `Failed` 这一支」的旧话（那边已追加日期复议）。

### 走查的场地与两处 rig 事实

- **5173 被另一个 agent 的 vite 占着，而 CORS 只放行 `http://localhost:5173`。** 所以这次把页面起在
  **5199**，让页面本来发给 `localhost:8080` 的请求走**同源代理**：临时 vite 配置里
  `server.proxy["/__harness"] → http://127.0.0.1:8099`（`rewrite` 去掉前缀），fetch 补丁把
  `http://localhost:8080` 改写成 `/__harness`。同源请求不触发 CORS，于是这次走查没碰后端一个配置。
  配置落在 `ui/node_modules/.acceptance-vite.config.mjs`（在 gitignore 之内，跑完已删）。
- **一次「run 一直 Running」的观察**：第一个会话跑完之后侧边栏那一行仍标 `Running`，于是 `New task`
  被拒绝（「A run is in progress; a new session waits until it settles.」）。刷新页面即恢复，
  后面四个会话都没再出现。**服务端是干净的**：那条会话的 jsonl 末尾有 `RUN_FINISHED`
  （189 行，最后三条是 `TEXT_MESSAGE_END` → `RUN_FINISHED`），请求在 Playwright 里也是 200 完成。
  这既不是本特征的渲染改动造成的（本条不碰 run 状态），也没有归因到具体原因，如实记在这里。

### 复议（2026-09-17）：用户气泡也是 14px；正在流的那段思考自己展开

两条都是**推翻本文件里的话**，按仓库规矩追加而不是改写旧段；上面那张表里被推翻的那一格已就地划掉。

1. **「用户气泡 16px（没动）」不再成立。** 对话**两侧**现在同一档：答案、问题、以及问题在编辑态的
   那只输入框都是 **14px**（步骤行 13px 与载荷 12px 没动，决策 4 照旧）。规则仍在
   `ui/src/styles.css`，但从 `@layer base` **挪到了层外**：编辑框自带上游的 `text-base`，
   而 Tailwind v4 把 utilities 声明在 base 之后，在层里写多高的特异性都压不过它。
   命中的钩子两侧**不同名**——答案的内容 div 带 `data-slot="aui_assistant-message-content"`，
   问题气泡带 `aui-user-message-content` class（上游自己两侧就不一致），都不是我们能挑的。
   **composer 的输入框仍是 16px**，输入框的惯例，这次没动。
2. **决策 5「思考行把想法首行摊出来」照旧成立，但它只描述**已经想完**的那段。** 正在到达的思考
   在流式期间自己展开、滚动显示最新 token，最后一个 token 落下才折回行上留首行。判据取组的状态，
   所以「想 → 读 → 再想」会开合两次；历史会话永远是折的。理由与代价见
   `.scratch/assistant-ui/issues/04` 第 14 节（同日复议）——本文件那张表里「点开后是整段」照旧成立：
   只有**流式期间**用那扇 256px 的窗口，停下来之后整段摊开。
3. **决策 1「组头删掉，不留替代」说的仍然是**每个工具调用**前面那一行，它没有回来。** 同日晚些
   添的是一行**轮**级的摘要（`N 次工具调用 · M 条消息`，见 `.scratch/assistant-ui/issues/04` 第 15 节）：
   一轮停下来之后步骤收起来、只留最后一条消息，那一行是这一轮的入口。两者的差别就是决策 1 的
   理由本身——**那一行在「一个调用」前面、计数恒为 1，这一行在「一整轮」前面、数的是这一轮做了多少**。

