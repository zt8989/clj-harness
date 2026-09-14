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
- [x] `showThinking`（默认 true）确实开着——它是 `THINKING_*` / `REASONING_*` 事件渲染成可见推理的开关。
      本票不动它，验收时确认它没被误关（reasoning 部件确实出现在页面上）
- [x] 文本、工具卡、reasoning 在一轮内按 part 的真实顺序交替出现，不重排、不合并
- [x] 「默认折叠」与官方默认（流式中自动展开、流结束自动收起）不一致，这一处差异**在代码里注明原因**
- [x] 抄来的组件**一处都没改**：全部靠覆盖槽位解决，零本地修改
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
| `ui/src/components/assistant-ui/elements/*` | **一处未动** | 20 份抄来的文件保持与上游可逐字节对账 |

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
| `npm run build` | 全绿，CSS 83.85 kB / JS 1244.96 kB（gzip 353.51 kB）。比 03 的 1240.87 kB 多 4.1 kB，就是这张卡 |
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
真问题。改掉了一处，另两处判为保留，理由都在下面。

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

**（已改）图标映射的类型名指向了一个具体图标。** 原写成 `Record<CallState, typeof LoaderIcon>`——拿
`LoaderIcon` 一个图标的名字当五个图标的类型，说不通。改为 `ElementType`，顺带与上游自己的
`statusIconMap: Record<ToolStatus, React.ElementType>` 写法一致。（查过 `lucide-react@1.46` 并**不**导出
`LucideIcon` 这个类型，所以那不是个能选的选项。）

**（未改）`ToolCallsGroup` / `ReasoningBlock` 各有一行同形的 `const running = group.status.type ===
"running"`。** 看着像重复，实际是分属两个槽位的两个独立组件，为一个两行的推导抽公共件，换来的间接层
比省下的两行贵。判为保留。

**（未改）耗时显示与 `needs-approval` / `cancelled` 两个状态算轻微范围蔓延**（票面只点了「运行中 / 成功 /
失败」三态）。都留下：耗时是折叠头上「还在动」的最省事信号，成本一行；那两个状态是**本仓内核真会产生
的状态**——`requires-action` 就是 05 的停泊调用，`incomplete` 的 `cancelled` 是取消——映射里少一支，
就等于把两个真实状态显示成别的状态，那是缺陷不是精简。
