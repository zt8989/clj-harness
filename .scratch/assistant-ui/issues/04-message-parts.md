# 04 — 消息部件：工具调用卡与默认折叠的 reasoning

**What to build:** 助手消息里不再只有文字。工具调用渲染成一张卡（工具名、参数、状态、结果），没有为
某个工具注册专用 UI 时走同一张通用卡；reasoning 有独立渲染。文本、工具、reasoning 按真实顺序交替出现。

对位的是 `ui-tool-cards` 与 `cljs-ui` 里已经验收过的那套行为：渲染器不是可选的装饰，没有渲染器的工具
调用在界面上等于不存在——细节在线上跑得好好的，只是没人画。

官方 `Thread` 元素已经留好了槽位，这一票是**覆盖槽位而不是从零搭消息区**：`components` 上有
`ToolFallback`（无专用 UI 的工具调用）、`ToolGroup`（一串连续工具调用的包壳）、`ReasoningGroup`
（一串连续 reasoning 的包壳）三个槽，另有按工具名注册的 renderer 优先于 `ToolFallback`。
`components` 是模块级常量——它是 props，每次 render 换一个新对象会让整棵消息子树白重渲染。

**票面原意与最终落地的一处差异：** 本票初稿要求「reasoning 在流结束后仍然展开」（那是有意与官方默认
分道的地方）。落地时改为**工具卡与 reasoning 一律默认折叠**，只有点开才有内容——即回到官方默认的
折叠姿势，并把「进行中」的信息挪到折叠头上。理由与代价见落地说明第 3 节。

**Blocked by:** 03

**Status:** done

- [x] 工具调用 part 渲染成卡：工具名、参数、状态（运行中 / 成功 / 失败）、结果；结果为空时显示
      「No result」这类明确状态，而不是一块空白
- [x] 参数以原始 JSON 呈现，不被折叠、截断或摘要——人要看的是将要执行的确切内容
- [x] 未注册专用 UI 的工具走 `ToolFallback`：新增一个工具不需要改前端就能看见它的调用
- [x] 需要专用呈现的工具按**工具名注册 renderer**（不是去堆 `ToolFallback` 里的条件分支），且注册的
      renderer 确实优先于兜底。**今天没有任何工具需要专用呈现**，所以一个都没注册；优先级是实测的，
      不是读代码读出来的（见落地说明第 5 节）
- [x] 同一轮里并发的多个工具调用各自成卡，不合并成一张、不丢其中任何一个；`ToolGroup` 只做包壳，
      不吞掉组内调用。实测这一轮的 4 个调用在**线上就是 4 条独立助手消息**（见落地说明第 8 节），
      四张卡四份状态，一张不少
- [x] reasoning part 独立渲染：流式期间显示进行中（折叠头上的 shimmer + `aria-busy`），内容默认折叠、
      手动可展开也可再收起；新的一段思考同样不自动展开
      ~~**2026-09-17 部分推翻**：第 14 节的复议让**正在到达**的那段思考自己展开（滚动显示最新 token），
      最后一个 token 落下才折回去。「新的一段思考同样不自动展开」只对**已经想完**的那些成立~~
- [x] `showThinking`（默认 true）确实开着——它是 `THINKING_*` / `REASONING_*` 事件渲染成可见推理的开关。
      本票不动它，验收时确认它没被误关（reasoning 部件确实出现在页面上）
- [x] 文本、工具卡、reasoning 在一轮内按 part 的真实顺序交替出现，不重排、不合并
- [x] 「默认折叠」与官方默认（流式中自动展开、流结束自动收起）不一致，这一处差异**在代码里注明原因**
      ~~**2026-09-17 部分推翻**：这一处差异只剩下**一半**——「流式中自动展开、流结束自动收起」正是今天
      的行为，与官方一致的是它；不一致的是**行**（官方是一张卡，本仓是一行，行上有摘要与状态标记）~~
- [x] 抄来的组件**一处都没改**：全部靠覆盖槽位解决，零本地修改
      ~~**2026-09-15 部分推翻**：第 12 节的复议改了 `thread.aui.tsx`（`LOCAL:` 标注，与 `thread-list.aui.tsx`
      同一套约定）。槽位覆盖仍是主力路线，但「零本地修改」这句不再成立~~
- [x] 真 Chromium 验收：脚本化会话让模型调 `read` / `write` / `bash`，卡片与 reasoning 按上述行为
      出现，截图留档；另跑一轮**真模型**（真 provider）端到端
- [x] 验收方式是逐条对位，不是「看着差不多」：工具名与参数**逐字**与线上帧比对，reasoning 在 run
      结束后确实没被展开

---

## 落地说明

### 1. 代码落在哪

| 文件 | 状态 | 内容 |
|---|---|---|
| `ui/src/components/message-parts.tsx` | **新增** | 三个槽位的实现，外加模块级常量 `THREAD_COMPONENTS`。整票只有这一个新文件 |
| `ui/src/app.tsx` | 改 | 一行 `<Thread components={THREAD_COMPONENTS} />`，加文件头一段说明 |
| `ui/src/components/assistant-ui/elements/*` | **一处未动** | 20 份抄来的文件保持与上游可逐字节对账（同日由第 12 节推翻其一：`thread.aui.tsx` 现带 `LOCAL:` 改动） |

三个槽位的定位：`ToolFallback` 是「没有专用 UI 的工具调用长什么样」；`ToolGroup` 与
`ReasoningGroup` 是「一串连续同类 part 的包壳」。它们在 `thread.aui.tsx` 的 `AssistantMessage` 里
被消费，路径是 `MessagePrimitive.GroupedParts` 的 `groupBy`（`groupPartByType`）——reasoning 与
tool-call 都被归进 `group-chainOfThought`，再各自归进 `group-reasoning` / `group-tool`。

### 2. 工具卡：信息契约

卡片头是 `工具名 · 状态 · 耗时`，展开是 `Arguments`（原始 JSON）+ 结果。四件事各自写死了：

- **工具名**：`<b>{toolName}</b>`，逐字来自 part，不做任何美化或映射。
- **参数**：`argsText`——模型**流出来的原始 JSON 文本**，不是我们反序列化后再序列化的结果。原样、
  不裁剪、不加 `max-height`（长得离谱的 `write` 会把卡片撑高，这是有意的：打开它的目的就是看清将要
  执行的确切内容）。用 `whitespace-pre-wrap` 折行而不是横向滚动条。
- **状态**：`running` / `done` / `failed` / `cancelled` / `needs-approval` 五个，写成词而不是只画图标
  （官方只画图标：转圈的 loader、对勾、叉；对勾和叉很容易看漏，而状态被隐含的卡就是会被读错的卡）。
  判定顺序是 `isError` 优先，其次 `status.type`，`incomplete` 再按 `reason` 分流 cancelled / failed。
- **结果**：空（`undefined` / `null` / 空串 / 纯空白）时**明写 `No result`**。官方此时什么都不渲染，
  与「结果渲染失败」在界面上长得一模一样；一次已经结束、又确实没话说的调用是一个事实，就照事实写。
  调用还在飞（running / needs-approval）时**不写 `No result`**——那时答案是「还没有」，不是「没有」。
  唯一的例外是**已经解释过为什么没跑完的调用**：上游的 `ToolFallbackError` 已经把 `status.error` 说过
  一遍时，这块只做「载荷」不做「判决」——标题从 `Error:` 降回 `Result:`，`No result` 也不再印（一次
  没跑完的调用没有结果可缺，在原因下面再补一句「没有结果」读起来是对同一件事的第二个、更轻的结论）。
  判定收在 `statusErrorText()` 里，与错误块的显示条件逐条对齐；由来见第 11 节。

**一个必须点名的边界**：本仓的**工具失败不会让调用变成 failed**。`harness.tools/run!` 把失败当成
给模型的信息（结果文本）返回，不是 run 的失败；所以 `bash` 报 `[exit 1]`、`edit` 报
`old_string not found` 时，卡片上是 **`Done` + 一段错误文本**，不是 `Failed`。`Failed` 在这里的含义
是**这次调用本身没跑完**（被中止、被取消、流断）。这不是取舍，是内核的既有语义，卡片如实反映它。

### 3. 默认折叠：本票最终的行为，以及它与官方默认的差异

**工具卡与 reasoning 一律默认折叠**，只有点开才有内容。差别在与上游的关系：

- **工具卡**：与上游同向（上游的 `ToolFallbackRoot` 本来就 `defaultOpen = false`）。差别只在于官方
  会在 part 进入 `requires-action` 时自动弹开卡片——本票**不接管**这一步：停泊待批的调用这里仍保持
  折叠，由 05 决定它该不该自己冒出来（审批卡是 05 的地盘）。
- **reasoning**：与上游**相反**。上游的 `ReasoningRoot` 收 `streaming`，流式期间把抽屉钉在打开状态
  （还带一个跟随最新 token 的实时预览），run 一结束回到 `defaultOpen`。本票**根本不传 `streaming`**，
  于是 `isOpen = userOpen ?? false`：思考到达时是折的，跑完了也还是折的，开合权完全归读者。

**「进行中」的信息没有丢，只是换了地方**：折叠头上。工具卡是转圈的图标 + `Running` 这个词，reasoning
是官方 trigger 自带的 `shimmer` 类 + `aria-busy`（实测运行时 `label` 的 class 里确实有 `shimmer`，
`reasoning-content` 的 `aria-busy="true"`）。两者都不用展开就能看见，且随工作结束而停止。

**为什么这么改**：票面初稿要的是「reasoning 跑完仍展开」，落地时的指令改成全部默认折叠。方向是统一——
一个消息流里有的抽屉自己弹开、有的不弹，读者会去找一条并不存在的规律；统一折叠 + 头部信号，规则只有
一条。代价如实记下：**reasoning 的实时预览随之失效**（上游那个「边流边跟最新 token」的视窗只有在
展开时才存在），看思考内容要先点一下。

**唯一没折的是工具组的包壳**，这不是自相矛盾：组自己不装内容，只装着卡；把组也折上，一次 `read`
在页面上就只剩一行「1 tool call」，**连工具名都看不见**。而本票要对位的那套旧界面（CopilotKit 的
通配卡）恰恰是逐个调用列出名字与状态、只把细节折上。所以组默认打开（`defaultOpen`），组内每张卡仍是
折的：名字与状态一眼可扫，参数与结果各一次点击。组仍可手动收起。

```
默认状态一览
  Reasoning              → 折叠（点开看思考，可再收起）
  1 tool call（组）       → 打开（这一层只看得见"有几张卡"）
    read · Done ⌄        → 折叠（点开看参数与结果）
```

### 4. 三个槽位各覆盖了什么

- `ToolFallback` → `ToolCallCard`：完全自写的卡（头 / 参数 / 结果 / 空结果四块）。复用了上游的
  `ToolFallbackRoot`（折叠壳 + 动画 + 滚动锁）与 `ToolFallbackContent`（动画内容壳）与
  `ToolFallbackError`（错误块），因为这三块是纯机械的、改了就要跟上游对账；卡里**信息**部分全部自写。
- `ToolGroup` → `ToolCallsGroup`：**只为 `defaultOpen` 一个 prop 覆盖**。其余照搬官方默认的形状
  （`variant="ghost"`、`count={group.indices.length}`、`active` 取组状态），一行不多。
- `ReasoningGroup` → `ReasoningBlock`：**区别只有「不传 `streaming`」一件事**，其余（`ReasoningRoot` /
  `ReasoningTrigger` / `ReasoningContent` / `ReasoningText`）与官方默认逐字相同。

**没有覆盖 `AssistantMessage` / `Welcome` 两个「整段替换」槽位**：它们要抄一整份 `thread.aui.tsx` 的
`AssistantMessage` 进仓库，那是给上游代码建 fork，与「靠槽位解决」相违背。

### 5. 按工具名注册 renderer：今天一个都没有，但优先级是实测的

本仓六个工具（`read` / `write` / `edit` / `bash` / `eval` / `session-configure`）的形状完全一样：名字、
一段参数、一个结果。旧界面对它们用的是**一个通配渲染器**，所以今天的正确回答是「谁都不需要专用 UI」，
通用兜底就是唯一被注册的渲染器——新增工具不改前端就能看见它的调用，这才是要保住的性质。（另外，
`session-configure` 的特殊呈现是**审批卡**，那是 05 的地盘，不是工具卡的形状问题。）

但「注册的 renderer 优先于兜底」这句话不能靠读代码交差（`thread.aui.tsx` 里是
`part.toolUI ?? <ToolFallbackComponent {...part} />`，读起来当然是对的）。所以在真 Chromium 里做了一次
一次性实测：临时注册一个只认 `read` 的 renderer（渲染一个标记 div），重跑脚本会话，结果——

```
probes: ["PROBE: the named renderer won", "PROBE: the named renderer won"]
cards:  ["write · Done", "bash · Done"]
```

两个 `read` 调用画的是注册的 renderer，`write` / `bash` 照旧落到兜底卡。**优先级成立**。探针在验收后
已整体移除（`app.tsx` 里现在 grep 不到 `Probe` / `useAssistantToolUI`，类型检查与构建均在移除后重跑）。

### 6. 抄来的组件改了几处

**0 处。** 20 份 `elements/` 与 `ui/` 下的文件一个字节没动，本票新增的只有 `message-parts.tsx` 一份。

### 7. 验收记录

命令（`ui/` 下）：

| 命令 | 结果 |
|---|---|
| `npm run typecheck` | 0 error |
| `npm run build` | 全绿，CSS 83.85 kB / JS 1244.98 kB（gzip 353.52 kB）。比 03 的 1240.87 kB 多 4.1 kB，就是这张卡 |
| `npm test` | **11 passed**，四组用例的内容一行未改（第 11 节修正后重跑，同样 11 passed） |

真 Chromium（1440×900、light），两台后端都跑过：

**(a) 真 provider（`~/.clj-harness/config.edn` 的 inline openrouter reasoning 模型）**

| 项 | 结果 |
|---|---|
| 模型调工具 | 两轮都调到了：`read` → `write`（不是被脚本逼出来的） |
| 工具名 / 参数逐字对位 | 线上帧 `{"path":"/Users/zhouteng/Documents/workspace/clj-harness/AGENTS.md"}` vs 卡上 Arguments，**逐字相同**；`write` 的 `{"path":"/tmp/t04-real-note.md","content":"AGENTS.md"}` 同 |
| 结果 | `read` 的 509 字节文件内容与 `Wrote 9 chars…` 全量显示；副作用对得上（`/tmp/t04-real-note.md` 落盘 9 字节 = `AGENTS.md`） |
| 折叠态 | 流式期间与 run 结束后，三个 reasoning 抽屉全是 `data-state="closed"`；两个工具组 `open`，组内卡 `closed` |
| 进行中信号 | 流式中 `reasoning-trigger-label` 的 class 带 `shimmer`、`reasoning-content` `aria-busy="true"` |
| 手动开合 | 点卡片头 → `open` 且内容 452px 高；再点 → `closed`。点 reasoning 头 → 展开显示完整思考文本；再点 → `closed` |
| 控制台 | 零报错、零页面错误（只有 vite HMR 的 debug 行与 React DevTools 提示） |

**(b) 脚本化后端（`dev/harness/e2e_server.clj` + `harness.fake` 的脚本 provider，`--port 8080`）**

一回合内 4 个工具调用（`read` 空文件 / `read` 有内容文件 / `write` / `bash`（`sleep 6`）），脚本内容
写在 `turns` 里，所以每一条都能逐字对：

| 项 | 结果 |
|---|---|
| 并发 4 个调用各自成卡 | 4 张卡：`read · Done`、`read · Done`、`write · Done`、`bash · Running`（6 秒后 `bash · Done`） |
| 状态可分辨 | `Running`（转圈 + shimmer）与 `Done` 同屏出现过，不需要展开就能看出谁还在跑 |
| 空结果 | 空文件的 `read` 卡：`Arguments` + **`No result`**（线上帧该调用 `content` 就是 `""`） |
| 参数 / 结果逐字 | 三条结果与脚本里的 `content` 逐字一致（`hello from the scripted acceptance` / `wrote 35 chars…` / `slept`）；副作用对得上（`written-by-acceptance.txt` 35 字节） |
| part 顺序 | 第一条消息内的顺序实测为 `reasoning-root` → `TEXT: Let me read the two files first…` → `tool-group-root`，与脚本顺序一致，未重排未合并 |
| 新一段思考 | run 结束后两个 reasoning 抽屉都是 `closed`——没有任何一段自己弹开 |
| `Failed` 分支 | 在 `bash sleep 6` 跑着的时候点 **Stop generating** → 该卡变 `bash · Failed`，展开是 `Error: BodyStreamBuffer was aborted` + `Arguments` + `No result`；composer 从 `Stop generating` 复位到 `Send message` |
| 按名注册的 renderer | 见第 5 节，实测优先于兜底 |

截图（`.scratch/assistant-ui/evidence/`）：

| 文件 | 内容 |
|---|---|
| `t04-01-reasoning-collapsed-while-streaming.png` | 流式期间的折叠 reasoning（真 provider） |
| `t04-02-real-model-cards-collapsed.png` | 真模型两轮之后：组打开、卡折着、reasoning 全折 |
| `t04-03-cards-expanded-args-result.png` | 两张卡展开：原始 JSON 参数 + 全量结果 |
| `t04-04-scripted-cards-args-result-no-result.png` | 脚本回合：四张卡、`No result`、`Result:` |
| `t04-05-reasoning-manual-open.png` | 手动点开的 reasoning（完整思考文本） |
| `t04-06-named-renderer-precedence.png` | 按名注册的 renderer 胜出、其余落到兜底 |
| `t04-07-aborted-call-failed.png` | 被 Stop 中止的调用：`Failed` + 一条 `Error:` + 参数（第 11 节复议后重截） |

### 8. 两件读代码读不出来、只有实测才知道的事

1. **一发多调用在线上就是多条助手消息，不是一个消息里的多个 part。** 脚本回合里 4 个调用
   （`call-read-empty` / `call-read-note` / `call-write` / `call-bash`）在日志里是 4 个
   `parentMessageId` 各不相同的 `TOOL_CALL_START`：`ftOFT4L-m1` … `m4`——`ag_ui.clj` 的 `:tool/call`
   分支每个调用开一条自己的 TEXT_MESSAGE 当父级。所以页面上是 4 条助手消息、4 个「1 tool call」的组、
   4 张卡，而不是一个「4 tool calls」的组。**这也是 `ToolGroup` 的 `defaultOpen` 非改不可的原因**：
   照官方的折法，页面上每次工具调用都只剩一行「1 tool call」，工具名全被藏起来。
2. **`Failed` 是能被走到的，而且路径有点反直觉。** 本仓工具自身报错走的是结果文本（第 2 节），
   所以 `Failed` 只能由「调用没跑完」触发；实测按 Stop 中止正在执行的 `bash`，客户端把该 part 标成
   `incomplete` 并把 abort 的错误挂在 `status.error` 上——卡片显示 `Failed` + `Error: BodyStreamBuffer
   was aborted`。**没有被走到的分支是 `cancelled`**（我们的映射里 `incomplete` + `reason === "cancelled"`
   那一支）：这一轮里客户端给的是非 cancelled 的 incomplete，所以「取消」在界面上当前显示为 `Failed`。
   两个分支都留着（上游也这么分），但今天说得出证据的只有 `Failed` 这一支。

另：**真 provider 那轮又碰到一次 03 记过的模型侧空返回**（`RUN_STARTED` → 立刻 `RUN_FINISHED`、助手
内容空串、无 `RUN_ERROR`），换一句提示即正常。前端无关，这里只是再确认一次。

### 9. 这一票之后页面还缺什么

| 功能 | 状态 | 谁接 |
|---|---|---|
| 文本 / 工具卡 / reasoning | ✅ 本票完成 | — |
| 审批门 | ❌ 没有。停泊调用现在显示为 `… · Needs approval` 的折叠卡，**没有批准/否决的按钮** | 05 |
| 会话列表 / 恢复 / 新建 | ❌ 完全没有 | 06 |
| 项目目录面板 | ❌ 完全没有 | 07 |

**05 需要知道的两点**：① 停泊调用在本票里**不自动展开**（`ToolFallbackRoot` 保持 `defaultOpen=false`，
也没有 `requires-action` 的自动开合），05 要么沿用（点一下才看见审批按钮），要么自己控制 `open`；
② 上游 `tool-fallback.aui.tsx` 里已经有一整套 `ToolFallbackApproval`（选项、二次确认、自由文本答复），
它是**抄来的组件自带的**，05 可以先读它再决定是复用还是自写。

### 10. 语言：卡片上的字为什么是英文

`Arguments` / `Result:` / `No result` / `Running` / `Done` / `Failed`，以及官方的 `Reasoning` /
`1 tool call`。理由是与它落进去的那套元素一致：抄来的 `thread.aui.tsx` 整棵子树都是英文（欢迎屏
「How can I help you today?」、composer「Send a message...」、`Used tool:`、`Result:`）。自建的两个面板
（06/07）按仓库旧例用中文，但工具卡与 reasoning 是**替换**抄来组件的位置，混语言比统一英文更难读。

### 11. 复议（`/code-review` 两条轴）与一处跟进修正

本票的改动过了 `/code-review` 的两条轴：**Standards 轴**无仓库既有标准的硬违规，**Spec 轴**抓到一处
真问题。改掉了两处，另两处判为保留，理由都在下面。

**（已改）同一张卡可能把同一次失败说两遍。** 卡同时渲染上游的 `ToolFallbackError`（它读 `status.error`）
和自写的 `ToolCallResult`。两者分工本来是清楚的：前者说「为什么没跑完」，后者说「拿到了什么」。但自写
那块在 `state === "failed"` 时会把标题写成 `Error:`——于是当一次调用**既**带 `status.error` **又**带一段
结果文本时，展开卡会看到两个 `Error:` 标题，读起来像两次失败。这正是票面第 8 节记下的那条反直觉路径
（`Failed` 只能由「调用没跑完」触发）的展示面。

修法的方向是**让两块分工，而不是让它们重复**：新增 `statusErrorText()`，与 `ToolFallbackError` 的显示
条件（`status.type === "incomplete"` 且 `status.error` 排版后非空）逐条对齐；错误块开口时，结果块降级为
纯载荷——标题回到 `Result:`，`No result` 不再印。改完在真 Chromium 里**重跑了中止场景复测**（同一套
脚本化后端、同一个 4 调用回合，只把 `bash` 的 `sleep` 拉长以便稳定点中 Stop）：`bash · Failed` 展开后
**只有一个 `Error:`**，且没有 `No result`，截图 `t04-07` 已按新行为重截。

这一支只影响「没跑完」的调用。**工具自身报错不受影响**：那是第 2 节那条反直觉的边界（`Done` +
一段错误文本，`status.type` 是 `complete`），错误块此时根本不开口，标题仍是 `Error:`——与改动前逐字一致。

**（已改）状态的两个映射是一对并列的表，且图标那张的类型名指向了一个具体图标。** 原写法是两张同键的
`Record<CallState, ...>`（词一张、图标一张），图标那张的类型写成 `typeof LoaderIcon`——拿 `LoaderIcon`
一个图标的名字当五个图标的类型，说不通。两处并成一改：五态合成**一张**
`CALL_STATES: Record<CallState, { label, icon }>`，一个状态不再可能只拿到词、拿不到图标；类型随之落到
`ElementType`，顺带与上游自己的 `statusIconMap: Record<ToolStatus, React.ElementType>` 写法一致。
（查过 `lucide-react@1.46` 并**不**导出 `LucideIcon` 这个类型，所以那不是个能选的选项。）这次是纯结构
改动，词与图标逐个照搬而不是重打：`git diff` 可逐字核对，构建产物的 CSS 哈希未变、JS 只差 0.02 kB，
所以没有为此重截任何截图。

**（未改）`ToolCallsGroup` / `ReasoningBlock` 各有一行同形的 `const running = group.status.type ===
"running"`。** 看着像重复，实际是分属两个槽位的两个独立组件，为一个两行的推导抽公共件，换来的间接层
比省下的两行贵。判为保留。

**（未改）耗时显示与 `needs-approval` / `cancelled` 两个状态算轻微范围蔓延**（票面只点了「运行中 / 成功 /
失败」三态）。都留下：耗时是折叠头上「还在动」的最省事信号，成本一行；那两个状态是**本仓内核真会产生
的状态**——`requires-action` 就是 05 的停泊调用，`incomplete` 的 `cancelled` 是取消——映射里少一支，
就等于把两个真实状态显示成别的状态，那是缺陷不是精简。

### 12. 复议（次日的三条要求）与第一次改抄来的文件

2026-09-15 提出三条：

> 从用户会话开始到 reACT 循环结束为一个 turn，turn 结束才展示 copy refresh more 按钮，而不是每个 step
> 都展示；reasoning 的样式和 read 等工具调用保持一致，不要使用卡片

前两条是本票的展示面，第三条推翻了本票第 1 节表格里那句「20 份抄来的文件保持与上游可逐字节对账」——
`thread.aui.tsx` 现在带改动，按 `thread-list.aui.tsx` 立下的规矩逐处标 `LOCAL:`。票面首行与第 1 节表格
已就地划掉，不留下两个都自称成立的版本。

#### 12.1 为什么一票有 N 个动作条：N 是这一轮的 LLM 轮数

答案本票第 8 节第 1 条早就写下了，只是当时读它的人不是今天的我：**一发多调用在线上就是多条助手消息**，
不是一条消息里的多个 part。第 8 节记的是「4 个调用 = 4 条消息」；同一个机制管着**文字轮**——
`harness.ag-ui/open-text` 每开一条新助手消息就铸一个新 `messageId`（`(str run-id "-m" n)`），
适配器据此 `adoptServerMessageId(id, /*startNewMessage*/ true)`，把上一条定为 `complete` 再插一条新的。
所以「想一下 → read → 再想 → 回答」这一轮是 **4 条助手消息**，而它们在消息列表里**彼此相邻**。

上游的动作条是**每条消息一份**（`ActionBarPrimitive.Root` 的 `autohide="not-last"` 只在 run 跑着时藏，
`isLast` 只认列表最后一条），于是这一轮显示 4 份 Copy / Refresh / More，后三份贴在答案的碎片下面。

#### 12.2 turn 的边界：两个关于邻居的问题

```
isTurnEnd(s)          = s.thread.messages[s.message.index + 1]?.role !== "assistant"
isTurnContinuation(s) = s.thread.messages[s.message.index - 1]?.role === "assistant"
```

一个 turn = 一串**相邻的**助手消息；两个 turn 之间隔着开启后一轮的那条用户消息。于是「我后面没有自己人」
就是 turn 的末尾，「我前面有自己人」就是 turn 的中段——两个问题都由**线程自己的消息列表**回答，
不需要内核给出新的边界信号（内核语义一个字没动，这是纯页面的事）。

落到两处：

- **动作条**：`AssistantActionBar` 包进 `<AuiIf condition={isTurnEnd}>`，且 footer 的高度占位
  （`ACTION_BAR_HEIGHT`）也只在 turn 末尾留——否则每个 step 下面都空着 30px。中段的消息因此
  不留位、不画条。
- **步距**：中段那条加 `-mt-4`，把消息组的 `gap-y-6` 抵掉大半，一轮之内读成一整段回答；
  turn 与 turn 之间保留原间距。

#### 12.3 reasoning 不再是卡片：一行，且是与工具调用**同一行**

上游 `ReasoningRoot` 的默认 `variant` 是 `outline`，即 `rounded-lg border px-3 py-2`——那圈边框就是
「卡片」。改法两步，正好对上「样式和 read 等工具调用保持一致」：

1. `variant="ghost"`（去框、去圆角、去内边距）；
2. 行本身写在 `message-parts.tsx` 里，**逐条照 `ToolCallTrigger` 的形状**：`BrainIcon` + 粗体名 +
   chevron、`py-1.5 text-sm`、`group-data-open/trigger` 转 chevron。原来用的抄来的 `ReasoningTrigger`
   不再导入，行是自写的（`ReasoningRoot` / `ReasoningContent` / `ReasoningText` 这三个**壳**仍抄来：
   滚动锁、淡出、动画不重写）。
3. 顺带把 `ReasoningText` 的内层 `max-h-64 overflow-y-auto` 用 `max-h-none` 抵掉。工具的结果是整段
   摊开的，思考若自己滚在一个 256px 的窗口里，就成了这一轮里唯一一条阅读规则不同的步骤。

#### 12.4 实测（真 Chromium + 脚本化后端，一个 turn 两步、两个 turn）

新增截图 `.scratch/assistant-ui/evidence/`：

| 文件 | 内容 |
|---|---|
| `t04-08-one-bar-per-turn.png` | 两个 turn：每个 turn 末尾一份动作条（第一轮那条悬停可见），中段 step 一份都没有 |
| `t04-09-reasoning-row-is-a-tool-row.png` | 同一轮里 reasoning 展开 + `read` 卡展开：两行同左缩进、同形、reasoning 无边框无底色 |

量出来的数（`getBoundingClientRect` / `getComputedStyle`）：

- 两个 turn 共 **4 条助手消息**；`footer` 的子元素数逐个为 `[0, 0, 0, 1]`（只有整个 turn 的最后一条有），
  footer 高度 `[0, 30, 0, 30]`——一份条 30px，只在 turn 末尾。
- 中段的消息 `margin-top: -16px`，turn 首位 `0px`。
- **两行几何逐字段相同**：`reasoning-trigger` 与 `tool-call-trigger` 都是
  `x=456, h=28, padding 6px/6px, fontSize 14px`，且 `border-width 0px, border-radius 0px,
  background rgba(0,0,0,0)`——reasoning 一行不再是卡。
- reasoning 根：`data-variant="ghost"`，`class` 里没有 `border` / `rounded` / `shadow`；展开的内容块
  是满宽流式块（`[456,116,656,35]`），不是面板。

#### 12.5 一处必须说清的语义变化

**Refresh（`ActionBarPrimitive.Reload`）现在重生成的是这一轮的**最后一条**消息，也就是答案。**
这正是「重新生成」在读者心里的意思，所以判为改善而非副作用——但它确实是对上游语义的改动：
上游每条消息各自可重生成，中段那几条从此不可单独重生成（它们的按钮没了）。Copy 同理：复制的是**答案**，
不是某个 step 的碎片。**中段的 step 本就不是可单独复制/重生成的东西**，这是本条要求成立的前提。

#### 12.6 三关

`tsc --noEmit` 0 error；`vite build` 绿（CSS 89.60 kB / JS 1 277.17 kB，gzip 361.13 kB）；
`npm test` **11/11 passed**（含 05 的停泊/恢复与 06 的多轮用例——动作条与步距的改动没有碰审批门，
`t05-*` 的行为不在本节的改动面内，未重截）。测试跑起来的那轮页面控制台除 vite 连接日志外零输出。

### 13. 复议（2026-09-16）：组头被删；`Failed` / `Cancelled` 的实测修正

两处都是**推翻本文件里的旧话**，按仓库规矩追加而不是改写旧段。都指向
`.scratch/flat-step-rows/spec.md`（「平铺的步骤行」）。

**（一）第 3 节「默认状态一览」里 `1 tool call（组）→ 打开` 这一行不再成立。** 那个头已从页面上删掉。
它当初的理由在真实页面上站不住：`groupPartByType` 连只有一个 part 的 run 也会分组（上游注释原话
*Always groups tool calls … even if there's only one*），而一次工具调用在线上就是一条独立的助手消息
（本文件第 8 节实测），所以那个头**恒为 1**——页面读起来是「每个调用前面多一行」，不是「把 4 个调用
收成 1 行」。它展开之后露出来的正是它自己那一行，「默认打开以便看见工具名」因此是多余的一步。
现在：`THREAD_COMPONENTS.ToolGroup` 是个**透传**（槽位不能空着——空着抄来的 `thread.aui.tsx`
会画它自己那个头），状态从行中间挪到**行尾**（图标 + 耗时），状态词进 `sr-only`。
本节第 3 节那张「默认状态一览」图里的组那一层，读的时候请按这一条修正。

**（二）第 8 节第 2 条里「没有被走到的分支是 `cancelled`……今天说得出证据的只有 `Failed` 这一支」
要修正。** 2026-09-16 在真 Chromium 里重跑同一件事（脚本化的 `bash sleep 45`，跑着的时候按
Stop generating）：行上得到的是 **`Cancelled`**——状态标记的 `title` 是 `Cancelled`、图标是
`lucide-circle-x`、行文字带 `line-through`——**不是 `Failed`**。所以今天走得出来的四支是
`running`（转圈 + shimmer）/ `done`（对勾）/ `cancelled`（划掉 + 叉）/ `needs-approval`
（感叹号 + 审批卡）；`Failed` 反而**没有界面入口**（它要 `status.type === "incomplete"` 且 reason
不是 `cancelled`，或者 part 上带 `isError`；从界面上能造的只有中止，而中止现在报 cancelled）。
这是当时的客户端行为与今天的差异，不是当初写错；但「只有 Failed 这一支」这句话今天会把人引偏。

### 14. 复议（2026-09-17）：正在流的那段思考自己展开；视口只跟末尾；用户气泡 14px

三条都是**推翻本文件里的旧话**，按仓库规矩追加而不是改写旧段。验收清单上那两条已就地划掉。

**（一）第 3 节的「本票根本不传 `streaming`，于是 `isOpen = userOpen ?? false`」不再成立。**
~~现在是 `streaming={group.status.type === "running"}`：**正在到达**的那段思考自己展开，
用上游那扇「跟随最新 token」的窗口（`max-h-64` + 上下渐隐 + 内部滚到底）滚动显示；
最后一个 token 落下就折回去，行上留**首行**——也就是本文件第 3 节当年为「实时预览」标的那个代价，
今天不再要付。判据取**组**的状态而不是消息的状态，所以一段 run 里「想 → 读 → 再想」会开合两次，
而不是从第一个 token 起全程开着。开合权照旧归 `userOpen ?? streaming`，于是**恢复出来的历史会话
永远是折的**，手动开合过的面板也不再被自动改动。~~ **2026-09-22 推翻一次（见第 16 节）：不再展开**——
流式那段在**行上**滚、停下来回到首行。窗口、渐隐、跟随都留着，但只给**点开它的人**：开合权收到行自己
手里（初值 `false`）。组的状态照旧是判据，所以「想 → 读 → 再想」现在换两次尾巴、再回到首行；
**恢复出来的历史会话永远是折的**这条一字未改。当年那条理由（「有的抽屉自己弹开、有的不弹，读者会去找
一条并不存在的规律」）也没有被丢掉，规律再换一次：**正在发生的那一步在行上滚，发生过的一律折着。**

**（二）`thread.aui.tsx` 的 `LOCAL:` 改动从一处变成四处**（第 12 节那次是一处）。第四处删掉了
viewport 的 `turnAnchor="top"`，理由与位置写在那份文件里：上游那个属性同时把自动跟随**关掉**
（`useThreadViewportAutoScroll` 里 `autoScroll` 默认 `turnAnchor !== "top"`），于是新内容在折线
下方生长、没人跟，scroll-to-bottom 那颗按钮从这一段的第一行起就一直挂着。去掉之后 `turnAnchor`
回到默认的 `bottom`：视口跟着末尾走，**只有读者自己往上滚才脱开**，回到末尾（自己滚回，或点那颗
按钮）就立刻重新跟随，按钮同时消失。

**（三）用户气泡 16px → 14px**（对话两侧同档）。规则仍在 `ui/src/styles.css`，但挪到了
`@layer base` **之外**，且命中的钩子两侧不同名——理由见同日追加在
`.scratch/flat-step-rows/spec.md` 的复议。

### 15. 复议（2026-09-17，同日晚些）：一整轮结束就折起来，只留最后一个 message

**这一条改的是本文件第 3 节那张「默认状态一览」的上一层**：那一节说的是**每一条消息里的东西**
（工具卡、思考）折不折，这一条说的是**一整个 turn 的消息**折不折。一轮 = 相邻的那串助手消息
（`isTurnEnd` / `isTurnContinuation` 划的正是这个边界），一轮停下来之后只留**最后一条**（答案）
在外面，上面一行 `N 次工具调用 · M 条消息`，点开把步骤放回来。停下来的判据取**这一轮最后一条
消息的状态**：`running` 还在写、`requires-action` 停在人身上（审批卡在步骤里，折起来会把它藏掉），
`complete` 与 `incomplete` 都算停。历史会话一律是停的，所以一律折着。

**这条同时给「抄来的文件」添了第五处 `LOCAL:`**（第 12 节那次是第一次改它）：`AssistantMessage`
读一次折叠钩子，据此把整条消息 `hidden`、或在轮首画那一行摘要。**这处与前三处不同，它不是插入点**——
上游的 `AssistantMessage` 没有「这一轮」这个概念，而折叠必须由它来落；所以逻辑一行都不写进那份文件
（`components/turn-steps.tsx` + 零 import 的 `lib/turns.ts`），它只问「我该被收起来吗」。
更干净的「槽位覆盖」路线这次走不通：`THREAD_COMPONENTS.AssistantMessage` 是**整段替换**，
要走它就得把上游那份 `AssistantMessage` 抄进仓库（第 4 节明确不做的事），而一个包在外面的 div 会
把 `-mb-7.5 pb-7.5` / `-mt-4` 那套步距算坏。**代价如实记下**：第五处比前四处更贴行为，
重装上游之后这一处要重看。

**那一行摘要不是当年「1 tool call」组头的回归**（见 `.scratch/flat-step-rows` 决策 1）：组头在
**每个工具调用**前面、计数恒为 1；这一行在**一整轮**前面，数的是这一轮做了多少——本轮实测
`3 次工具调用 · 4 条消息`。没有工具调用的那半句会省掉（`4 条消息`），一轮只有一条消息时**整行不出现**
（没什么可折，就不要一个只为装饰的标题）。

### 16. 复议（2026-09-22）：思考行不再自己展开；流式那段在行上滚，停下才回到首行

**这一条把第 14 节（一）整段推翻，只留其中的窗口本身。** 主人三句话：「不要默认展开思考，思考中那一行
文字滚动显示，思考完成再回到第一行」。三处落点：`message-parts.tsx` 的 `ReasoningBlock` 把 `open` /
`onOpenChange` 收到自己手里（初值 `false`），于是上游 `userOpen ?? (streaming || defaultOpen)` 再没有机会替
人点开；`lib/reasoning-preview.ts` 在流式期间把行上的摘要换成**已到达内容的最后 120 字**（不流式时照旧首行）；
`styles.css` 的 `.aui-reasoning-trigger-tail`（`direction: rtl` + 内层 `ltr`）把这一窗**从左边缘裁**，
最新到达的字因此留在右边、旧的从左边跑出去——滚动就是新来的字本身，没有动画、不复制文本。

**窗口、渐隐、`max-h-64`、跟随最新 token 的滚动一个都没删**：它们只对**点开它的人**生效（`streaming && open`）。
**代价如实记下**：`open` 被接管之后，上游那条「流式开始/结束自己播一次动画」的分支不再走——那正是这次要停掉的
东西；抄来的两份文件（`reasoning.tsx` / `reasoning.aui.tsx`）一个字未改，`LOCAL:` 标注数不变。
现场、代价、四条决策与被推翻的两处走查判据：`.scratch/thinking-row-tail/spec.md` + `evidence/README.md`
（真 Chromium，20 条 GREEN；81 个采样全在 `data-state="closed"`，窗左沿 507px → -218px）。
