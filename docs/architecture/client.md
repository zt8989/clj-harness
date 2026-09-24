# 客户端：TypeScript + React + assistant-ui

`ui/` 是纯 TypeScript：`.tsx` 是 React 源码，`.ts` 是测试与非渲染代码，`.js` 只剩 Vite 配置。
**没有第二套工具链**——构建与开发都是 Vite（`@vitejs/plugin-react` + `@tailwindcss/vite`），
不需要 Java，也没有 shadow-cljs。

**它曾经是 ClojureScript + helix + CopilotKit。** 那次换语言换库的完整记录在
`.scratch/assistant-ui/`（历史文档，记的是当时）。协议侧一字未改——
AG-UI 帧的形状与 interrupt/resume 的语义，换客户端都没有碰。

## 装配

```
main.tsx            React root
app.tsx             **一场会话一份 runtime（一份 `SessionHost`）**，各带自己的 HttpAgent、
                    `useAgUiRuntime`、`ApprovalBatchProvider` 与 <Thread/>。App 持有的是
                    「现在看哪一场」（`shown`）、「哪些会话有活着的 host」（roster）、
                    每场自己的 `{:running? :parked?}` 注册表（侧边栏按 id 查）、
                    每场手里那段**窗口**（`windows`）与要画在它顶上的那颗按钮（`controls`）、
                    记录的降级态（`records`），以及打不开的那几场各自的错（`openErrors`）
                    侧边栏在 provider **之外**（它管全部会话），THREAD_COMPONENTS 仍在此注入
                    （附件适配器也在这里交出：`adapters.attachments` 一行）
components/
  sidebar.tsx       四段位：钉住的**品牌行**（logo + `clj-harness` + 收起那顆）、钉住的「新建任务」
                     与加项目 / 刷新、唯一滚动的项目区、钉住的「设置」
                     窄窗（< `lg`）时整列**浮在对话上**、配一层背板；`lg` 起才是并排的一列
                     **折叠有两种样子，`lg` 决定哪一种**：宽窗是一条 **48px 的 rail**（列还在、只剩
                     图标与 logo：顶格平时是记号、hover/聚焦才换成那颗「展开」，会话/项目列表 `hidden`、
                     设置那颗 32px 居中），窄窗仍旧 `display: none`。两种都由 `lg:` 类产出，不加监听
                     rail 与列表都是 `hidden` 而不是卸载（它是 `/api/projects` 唯一的读者，见下）
  app-brand.tsx     品牌行的记号（内联 SVG，`aria-hidden`）与产品名；产品名是常量不是词表条目，
                     所以它从 `lib/session-title.ts` 取（同一个词在 tab 尾巴上还要说一遍）
  session-title.tsx 对话列顶栏那行标题：读这一场的第一句用户消息，画出来，并让浏览器 tab 跟上
                     （只有 `visible` 的那个 host 渲染它，所以「哪一场可见」不用问页面）。
                     顶栏是 `app.tsx` 里一个 **`h-12`（= demo 的 `3rem`）的两行块**：
                     上行标题、下行 视图页签。这个高度是**与侧边栏品牌行对齐**用的——
                     两边各自 `border-b`，两条线因此落在同一个 y、看上去是整页一条，
                     加上侧边栏那条 `border-e` 就是 demo 顶左角那个十字（spec 二版一节有量到的数）
  sidebar-toggle.tsx  折叠/展开那几颗：收起在**品牌行末位**（`ms-auto`）；展开是同一个组件的两种
                    `shape`——`corner`（默认）是窄窗左上角那颗浮标，`rail` 是宽窗 rail 顶格里那颗
                    （平时 `opacity-0`，hover/`focus-visible` 才显形，所以键盘也走得到）。
                    浮标归 `app.tsx` 画（折起来的侧边栏在窄窗是隐藏子树，画不了要被看见的按钮），
                    且带 `lg:hidden`：三处共用同一个 `SIDEBAR_ID`（`aria-controls`），
                    任一窗口**同时只有一颗**在屏幕上。外加 `isWideWindow`：窄不窄只此一处 `matchMedia`，当场读
  settings-panel.tsx 「设置」：两页左导航（General / Models），**两页都会写**
  approval-gate.tsx 审批门（自建：上游的 approval seam 认的 reason 与本仓不同）
  message-parts.tsx 步骤行（工具调用与思考）的注入点（THREAD_COMPONENTS）；
                    行上那些字的规矩在 `lib/reasoning-preview.ts`
  turn-steps.tsx    一整轮的**折叠**：结束的那一轮把步骤收起来、只留答案，
                    行那条摘要（`N 次工具调用 · M 条消息`）与它背后的 store 都在这儿
  picker.tsx        四个选择器共用的那一份：可搜索的浮层（项目 / 分支 / model / 思考档）
  composer-chrome.tsx   composer 上下两条、三个 LOCAL: 插入点
                        （ComposerFrame / ComposerTools / ComposerAddAttachment），
                        以及附件那条判据在界面上的两处（禁用的 `+`、那句拒话）
  session-run-state.ts   「服务端说这一场在跑 / 悬置 / 结束了」那个词，以及驮着它从 host 到 composer
                        的 context（票 04 的 `session-run-notice` 只剩这半——那句「还在回答」已被票 09 的
                        那颗「停」取代）
  session-run-stop.tsx   composer 里那颗「停」：寻址当前显示的那一场、发
                        `POST /api/threads/<id>/cancel`（票 09）。**单开文件**只为一个原因：它要在 UI
                        套件里被渲染出来读回去，而 `composer-chrome.tsx` 进不了那个运行（它经
                        `lib/attachments.ts` 摸到 `lib/i18n.ts`，后者在加载时碰 `document`）
  composer-stats.tsx    composer **下面**那条状态条（会话统计的五格）
  composer-numbers.tsx  这场会话的数字**取一次**的地方：取数、「什么时候取」的四个触发条件，
                        以及把它们交给 composer 里两个读者（状态条与那颗圈）的那个 scope
  context-ring.tsx      model **左边**那颗圈（占了多少，按三样分色）与点开后的面板：
                        三段的堆叠条 + 三行图例（系统提示词 / 工具定义 / 对话消息）
  assistant-ui/elements/  12 份抄自 assistant-ui registry；**只有 thread 与 thread-list 两份带
                        `LOCAL:` 改动**，其余原样未改（见下）
  ui/               10 份 shadcn 基件，同样未改
lib/
  threads.ts        两个地址：`API_BASE`（`${HARNESS}api/`，管理调用挂的地方）与 `AGENT_URL`
                    （`${API_BASE}agent`，`HttpAgent` 用的那一个端点）。`HARNESS` 默认 `/`（本 origin），
                    `VITE_AGENT_URL` 可指绝对地址。外加 rebuild 调用
  projects.ts       GET /api/projects 的类型化薄封装，外加**动作**那几发：`bindThread`（换绑）、
                    `startTask`（按 id 登记一场任务，或让服务端铸一枚）、`startSessionIn`（在某个
                    目录里开一场）、`addProject` / `removeProject` / `pickFolder` / `setArchived`
  settings.ts       GET /api/settings 的类型化薄封装
  providers.ts      GET /api/providers + 三条写入 + 厂商探询的类型化薄封装
  provider-key.ts   「厂商被展示 ⟺ 这个家有一把钥匙指向它」这条规则的唯一一份：`hasKey`
                    与 `splitByKey`，外加服务端那个密钥事实（`ProviderKey`）的类型。
                    **零 import**，所以设置页、选择器与 UI 套件用的是同一个判据
  stats.ts          GET /api/threads/<stem>/stats 的类型化薄封装（`404` 也是普通答案）
  format.ts         给**人看**的数字：字节、时间、状态条那五格的字符串（`statsCells`），
                    以及那颗圈与它的面板要的一切（`contextCells`：份额、大小、三个篮子的名字，
                    缺一个就是 `null`）。**零 import**，所以 UI 套件能直接测它
  attachment-rules.ts  附件那两条判据（模型收不收图、源字节有没有过 2 MB）与它们各自的拒话。
                    同样**零 import**，同样被 UI 套件直接测
  turns.ts          一轮的**算术**：哪几条消息是同一轮、它停没停、它做了几次调用几条消息、
                    摘要那行写什么。**零 import**（`turnBounds` / `turnIsSettled` /
                    `turnCounts` / `turnSummaryLabel`），被 UI 套件当成数来测
  picker.ts         选择器那份清单的**过滤与分组**：查什么（标签 / hint / 组名）、
                    同组的连续段怎么并、顺序为什么不动。**零 import**，同样被 UI 套件直接测
  reasoning-preview.ts  思考行那一行字说的是什么（想完了说**首行**，还在想就把**已经到达的那一段**
                    整段交出去），以及**哪些部件是同一个想法**（`thoughtAt`：跨消息走一趟，工具调用是
                    分界、答案的正文不是，所以「答案开始之后还在想」仍画在上面那一行里）。
                    **零 import**，同样被 UI 套件直接测（`.scratch/thinking-row-tail/` 是另一半）
  attachments.ts    附件适配器（这一份就是「composer 有没有附件能力」这个开关本身）+
                    它往里写、界面往外读的那个小 store
  session-status.ts 一场会话的 `{:running? :parked?}`（host 报上去、侧边栏按 id 查）、
                    把两个读数并起来的那条规矩（`statusOf`：本页自己的 run **或**窗口说的
                    `running`），与仍然要拒的两句话（归档 / 删项目）。**改名自 `run-state.ts`**：
                    旧名字说的是「整页在跑」，而那个前提没了
  session-title.ts  一场会话的**标题**：原始一句人话变成标题的那条规矩（`titleOf`：空白折叠 +
                    按**码点**裁到 60 并加 `…`）、从运行时消息里取第一句（`firstUserText`）、
                    没说过话时用的那句词（`sessionTitle`，词由调用方传），
                    以及 tab 尾巴上的 `<标题> · clj-harness`（`documentTitle`）与产品名常量。
                    **零 import**（只有类型），被 UI 套件当成数来测。
                    **标题有两个来源、却只有这一条规矩**：库里 `sessions.title` 那份（第一次
                    收到消息的那次 run 写下的，列表给每一行都带着）与页面自己握着的运行时那份
                    （`firstUserText`，刚打完的那句立刻就在）。两份都过 `titleOf`，所以行与顶栏
                    永远不会对「什么是标题」有不同看法
  reveal.ts         「够着才出现」那串类（行上的归档 / 取消归档 / 删除、项目那行的「更多」）：
                    **一处定义**，因为两个调用点曾经以同一种方式错——`Button` 底座的
                    `disabled:opacity-50` 与揭示用的 `opacity-0` 是**同一个工具类的同一个变体**，
                    `cn`（tailwind-merge）只留最后一个，于是**侧栏一忙（比如等原生选目录框）**
                    满列表的归档按钮就一起冒出来（量到 `0.5`）。所以常量里必须带 `disabled:opacity-0`，
                    再用更具体的 `group-hover:disabled:opacity-50` 让「已揭示的禁用控制」仍旧发灰。
                    **零 import**（就是一条字符串），被 UI 套件渲染出来读回去
  session-memory.ts 「刷新回到刚才那一场」记的那个 id（`localStorage`，就是这里）
  agent.ts          `HttpAgent` 那几行：地址、`threadId` 交给谁、run 结束的回调，以及
                    **这一侧挂断的那条 run 报成中止而不是失败**——浏览器把掐断的流说成
                    `BodyStreamBuffer was aborted`，客户端库把它合成一条 `RUN_ERROR`，
                    于是按 Stop 会把那条工具卡画成「失败」加一句没人能处理的英文；
                    这里按它真正的身份报（`cancellationAware` / `onError`）
  record-health.ts  记录降级态那一个字段（`:record`）的类型与 `recordNotice`：
                    把它变成一句话，**没有问题时不出现**
  window.ts         窗口这个值本身：`{entries, baseSeq, hasMore, cursor, generation, state,
                    revision}` 与它的全部规则（`windowFrom` / `applied` / `prepended` /
                    `aligned` / `aheadOf`）。**全是纯函数**，所以 UI 套件直接测它；
                    `revision` 是「这份窗口变过几次」，与消息号 `seq` 不是一回事
  feed.ts           那条 SSE 连接：`fetch` + `AbortController`（不是 `EventSource`——它自己
                    重连、又看不见 409 的 body），加一个纯的 `feedFrames` 切帧
  window-scroll.ts  补页时的锚：`measure` / `restoredTop` / `correctedTop` 三行算术（用例测）
                    与 `registerViewport` / `withHeldScroll` 那两件只有真浏览器能验的事
```

**dev 里页面直连后端，跨域。** 整个后端在**一个前缀**下——run 端点是 `POST /api/agent`，其余都是
`/api/<什么>`——所以 `ui/vite.config.js` 只要**一条** `/api` 前缀规则（它还在，`npm run dev` 单独用时
仍然对）。`lib/threads.ts` 导出两个地址：`API_BASE`（管理调用挂的地方，`${HARNESS}api/`）与
`AGENT_URL`（`HttpAgent({url})` 用的那一个端点，`${API_BASE}agent`），而它们的**前缀**由一个变量说了算：
`VITE_AGENT_URL`。`node scripts/dev.mjs` 把它填成后端**自己报出来的**那个绝对地址（后端在 0 号端口绑，
OS 分配），所以源码里没有端口号，也不会有「8080 被上次忘了关的会话占着」这件事；**页面于是是跨域直连的**，
后端那边按**请求自己带来的 `Origin`** 判：本机的页面（`localhost` / `127.0.0.1` / `[::1]`，端口不参与）
一律放行并原样答回去，所以 vite 在哪个端口都行、没有谁要去对齐。**为什么不再走反代**：那条规则会丢 SSE 流的最后一个
chunk，把客户端永远卡在「运行中」——实测数字见 `scripts/dev.mjs` 的头注释与 `README.md` 3)。
构建产物里**仍然不带我们的地址**（`VITE_AGENT_URL` 只在 dev server 里注入：vite 的 `import.meta.env`），
换到任何部署自己的反代后面都一样。`strictPort: true` 留着：第二个 dev server 悄悄落到 5174，
比启动失败更让人意外。

## 状态的归属

- **一场会话一份 runtime，切换不再经过 runtime。** 每个开过的会话挂一份 `SessionHost`：它自己造
  HttpAgent、自己调一次 `useAgUiRuntime`，于是自己有一份 core。`threadList` 适配器里**只传 threadId**，
  `onSwitchToThread` / `onSwitchToNewThread` 两个回调退场——它们的效果是「先清空当前 core、再灌新消息」，
  而那份 core 现在正在流。host 一旦存在就**不再卸载、也不再重建**（core 归 hook 的 ref 所有，不归 DOM 子树），
  所以「这个会话没在显示」不等于「它的 run 死了」；`agent.threadId` 也不再有回写者（`adoptThread` 已删）。
- **会话归服务端，浏览器是只读副本，它只发动作**（ADR 0002）：发送、换模型、审批回答、停止、归档
  ——画什么由服务端给的帧与页决定（`append` / `update` 两个适配器因此是空的，见下）。
- **它手里是一段窗口，不是整场会话**（`lib/window.ts`）：
  `{entries, baseSeq, hasMore, cursor, generation, state, revision}`。`entries` 是 `{seq, message}`——
  **`seq` 是这条消息所在那一行在记录里的偏移**（服务端铸、谁都不许预测）；`baseSeq` 与 `cursor` 是
  这段窗口最老、最新那个号（`cursor` 就是副本对服务端说「我拿到哪儿」的那句话）；`generation` 是这段
  窗口属于哪一次服务（认领的 token，放掉或易主就换号）；`state` 是对面的 run 停没停；**`revision`
  是副本自己的**「这段窗口变过几次」——它不是一个位置，与 `seq` 混用是这一份专门防的错。
- **三扇门，两种读法**（`HistoryRead`）：`rebuild`（**侧栏点开**的那场：交给我，整段历史——它会合上
  断头日志、也会指名一份坏日志）、`window`（**这一页本来就在**的那场，比如刷新回来：看它，尾页 +
  一条 feed，一个字都不写）、`none`（本页刚铸、还没有会话的那场：没有可读的）。所以「侧栏点开」
  与「刷新回来」是两件事：前者接手，后者只看。
- **打开一场会话是拉尾页**（`GET /api/threads/<stem>/page`），增量走 **feed 一条连接**
  （`GET …/feed?since=<cursor>&generation=<G>`）。服务端**还持有**这场会话时尾页走**内存**（答案是
  `live: true`），记录的落后因此不会把人送回更早的一版——「刷新走内存」（ADR 0002 决策 8）就是这一条；
  不持有（进程重启过、会话被空闲放掉）就从记录折（`live: false`）。feed 连上之后帧都来自内存，
  游标由副本自己带着重连。
- **两种修理是两件事**（`lib/window.ts` 的 `Effect`）：**断档 / 连接断了** ⇒ 拉尾页**对齐**
  （接得上就合、接不上就重建并从尾页重来），**读者的位置保住**；**`end` 帧 / generation 作废**
  （会话被放掉、被接管、换了进程）⇒ **重开**，并把「重开了」这句话画出来。副本手里有服务端没有的
  条目时**说得出来**（`aheadOf`），不静默丢。
- **「显示更早」一次一页，而且必须锚定**：补页是 `prepend`，会把它下面的一切往下推，所以问之前量、
  答之后修（`lib/window-scroll.ts`），一次也只有一个在飞（按钮的禁用态就是这一条）。**锚点是一条
  消息的文本，不是它的 DOM 节点**：assistant-ui 按位置保留消息节点，用节点当锚会算错——量到过节点
  的 `top` 从 `83` 走到 `49`、上面多了四千多像素，而算出来的修正是 0。打字、打到一半的字、选中
  位置都因此不动。
- **记录的健康由谁来说**：有窗口的页面由 **feed 每帧带的 `:record`** 说；**没有窗口**的那几扇门
  （本页刚铸的会话、侧栏点开的、修好的断头记录）在自己驱动的那一轮结束之后读一次 `sofar`。
  从前那条每 1200ms 的 `sofar` 轮询没有了——它正是窗口要替掉的东西。
- **一次 host 只读一次，判据是「那份 host 在不在」**：读走运行时自己的 `history` 适配器，它每个 core
  只 `load()` 一次（`__internal_load`）。已经活着的 host 再显示多少次都不重读——它 core 里那份
  conversation 可能还在长，拿整段重建的结果盖上去就是又一次孤儿（而窗口那条连接还在往同一个 core
  里送帧）。
- **「现在看哪一场」是 App 的 state**（`shown`），带两个动作：`onShow`（这场有 conversation，第一次
  host 时重建）与 `onShowFresh`（这场是刚开的、还没有会话：没有日志可重建，host 空着起，名字由页面
  向后端要来 —— `GET /api/ids/new`）。
- **恢复的转换仍与 runtime 自己的快照导入路径逐字相同**（引上游，不另写）：`fromAgUiMessages` +
  `fromThreadMessageLike`，只是交给适配器的形状是 `{messages: [{parentId, message}]}`。
- **history 适配器的 `append`/`update` 是空实现**：日志归服务端所有，客户端一个字节都不往回写。
  host 的 `load()` 失败（截断 / 损坏的日志）走 `onError` 上报，句子仍旧落在**所点的行**上，
  而失败的那份 host 会被丢掉、页面退回上一场——所以点它一次就是重试一次。
- **侧边栏的数据是另一份**：`GET /api/projects`（不是运行时的 thread 形状——那个形状里没有项目、
  没有「谁是任务」）。**一份快照答两块**：`projects`（按目录分组）与 `tasks`（未绑定的会话，平铺不分组）。
  **这份列表只由库回答**：id、归属、归档、名字（`sessions.title`）、**上次发送时间**
  （`sessions.last_sent_at`）全是库里那几列，页面上唯一一个库答不了的是 `running`——它来自进程内的
  live-runs 注册表，也是主人说的那条例外（取舍在 `.scratch/store-backed-sidebar/spec.md` 一节）。
  于是行上不再有日志体积与 mtime，刷新也不再 walk 那棵树：一次 SELECT 加一次注册表查。
  **列表是快照**，切换会话 / 当前会话变化 / 按刷新键时重取；**发送之后不用等刷新**——侧边栏握着一个
  「有标题、不在列表里、也不在跑」的会话时会自己再问一次库（每个 id 每次页面加载最多一次，`asked` ref，
  所以成不了环）。
- **两个块，一个动词，而且它不立刻建会话。** 「新建任务」与项目行那颗「新建会话」**都只向后端要一枚
  名**（`GET /api/ids/new`：铸一枚 id，什么都不写 —— 2026-09-23 起名字归服务端，页面不再铸 id；
  `.scratch/server-named-sessions`）、**在页面里打开一场空会话**：不写库、不刷新、列表上什么都不出现
  ——**会话是第一次发送才诞生的**（主人这一版的原话：「点击新增不立刻会话，发送才新建」）——那条路
  见 `.scratch/store-backed-sidebar/spec.md`。名字通常**已经拿在手里**（`app.tsx` 的 `spareName`：
  下一枚在后台先要到手），所以点击没有往返；真要不来时，句子落在列表上方（新建的会话还没有行）。
  那一刻之前服务端什么都没听见，所以这枚 id 必须在那一场的第一次 run 请求**之前**登记一次：
  项目会话带着这枚 id 与目录走一次 `POST /api/project`（`bind!` 是 upsert，同时把它移进项目），
  任务走一次 `POST /api/sessions`（find-or-create，认调用方给的 id、幂等，已经有就原样不动）。
  **先登记、再发 run**，因为服务端那条规矩是：一轮 run 只继续这个家听说过的那场会话，瞄准陌生 id
  的一轮是 404（ADR 0002 决策 9；run 那条边从前那次静默创建没有了，页面不再依赖它）。**不登记就
  没有会话**——这正是懒创建要的：点一下不产生任何东西，有东西可留的时候才留。落在项目里的路只剩
  一条：**项目行自己那颗「新建会话」**。任务的记录落在 `projects/.unbound/`。
- **缩进那一条就是 spinner 的槽。** 会话行整体缩进到项目行**名字**的起点（走查量到的是 36px：
  项目行 `px-1.5`(6) + 图标(16) + `gap-1.5`(6)，会话行是 `ps-2`(8) + 槽(14) + `gap-1.5`(6)），
  槽宽就是 spinner 的 `size-3.5`，所以跑起来时 spinner 落在槽里、**标题的 x 一个像素都不动**
  （走查量过 36 → 36）——主人那句「留下的缩进刚好显示 loading 状态」就是这一条。
  行是**单行**：`[槽][标题 …][右端相对时间]`，hover 时右端才出归档/更多；`CURRENT` 那个词去掉了，
  当前会话只用底色。**`bytes` 与第二行整条退场**（主人：「去除文件大小」）。
- **一块最多画 5 行，其余的折起来**（`lib/sidebar-rows.ts`，唯一一处判据）：每个项目的会话列与
  顶部那块任务各自最多画 `ROWS_BEFORE_FOLD`（5）行，之后一行折叠控件说 `还有 N 个`（N 是**折起来的**
  行数，不是总数），点开画全、控件变 `收起`。**正在读的那一场排在第 6 个之后时，那一块画全、并且不画
  控件**——能收起的控件等于把正在读的那一行藏起来，而这正是这条规矩要防的（刷新页面时最容易看见：
  页面从记忆里恢复那一场，列表就得把它画出来）。两个块的展开状态各记各的、不落盘，与侧栏折叠、
  项目展开、归档块同一个理由。**「已归档」那块不限**：它本来就是「存起来的东西」，而且默认折起、
  要手动打开。上限是**画几行**不是**库留几条**：`GET /api/projects` 照旧把库里有的全给。
- **时间是相对的，而且是库里的那个时刻**：`刚刚 / N 分钟 / N 小时 / N 天`，超过 7 天给日期
  （`lib/relative-time.ts`：零依赖纯函数 + 阶梯）；完整绝对时间与 thread-id 一起进 hover 的 tooltip
  （两行）。`lastSentAt` 为 null 的行（这一列存在之前注册过、又没有日志的空行）说 `session.neverRun`——
  **老会话不在这条里**：迁移用日志 mtime 回填过一次，这正是要显示那一列的原因。
- **归档是一块，装两种。** 每个项目底部那个折叠组已经收掉：整个列表最下面一块「已归档」，
  里面既有归档的任务、也有归档的项目会话（默认折叠、空则不画；项目会话那一行用行上的 label
  写出它原来属于哪个项目——分组没了，行上不说就没人说得出）。归档当前会话时页面照旧会走开，
  落点是**同一类**里最近活动的那一场（项目会话 → 同一个项目；任务 → 另一条任务），
  一场都不剩就按那一类各自的「新建」三步落一个。
- **恢复（刷新回到刚才那一场）在那份 payload 的**两块**里找**：任务也是可以被记住的会话
  （`lib/session-memory.ts`），只在 projects 里找会让页面在会话失去项目的那一刻忘掉它。
- **添加项目有两个入口但只有一条路**：正常情况是一次点击直接开**原生选目录窗**，
  窗答什么就加什么（那份被删掉的表单见 sidebar 头部）；服务端答 501（这台机器上**没有**窗可开，
  见 [edge](edge.md#管理边路由表) 的三态）时才多一样东西——一行绝对路径输入框。
  两种入口走同一个 `addProject`，所以「选一个目录」与「填一个路径」不是两个功能：
  同一个 canonical 路径回填、同一行项目、同一份服务端校验（目录不存在 / 不是目录由服务端指名）。
  **取消永远静默**：`pickFolder` 答 `null` 就是什么都不做——人关了窗不需要被告知；
  「没有窗」是可说的另一件事，它必须说得出来，否则一颗点不动的按钮看起来只是慢。
  输入框**只**为这个原因出现，从不为别的失败出现：它是死路的路，不是第二扇门。
- **切换与新建不再被 run 拦住**（一场会话一份 runtime，切走不打扰任何一场的 run）。仍然拒绝的是**归档 /
  删掉一场没完（在跑或悬置）的会话**，判据是**那条会话自己**在不在跑（App 的注册表），不是当前页在不在跑；
  句子落在**那一行**上（归档）或**项目那一行**上（删项目，且点名是哪一场），说辞在 `lib/session-status.ts`。
- **侧边栏折没折是页面的临时状态**（`folded`，**不落盘**，跟 `view` 同一个理由：这是「这会儿怎么看」，
  不是「这份工作是什么」——在窄窗折起来、回到宽窗被记着藏起项目列表，是没人要的惊喜）。折起来之后**宽窄两重天**
  （`.scratch/sidebar-rail`）：宽窗是一条 48px 的 rail，列还在、出口长在它自己的顶格里；窄窗才整个消失、由
  左上角那颗浮标把它打开（那颗归 `app.tsx` 画，因为窄窗折起来的是隐藏子树，画不了一颗要被看见的按钮）。
  三处出口共用 `SIDEBAR_ID`（`components/sidebar-toggle.tsx`，它们与它们约定的事都写在那儿）。
- **折 ≠ 卸载**，而且这是正确性、不是省事：`sidebar.tsx` 是 `GET /api/projects` **唯一**的读者，挂载恢复
  正是从那一次读取里知道「记住的那一场还在不在」（`app.tsx` 的 `onListed`）。手机宽的窗口一开就是折着的，
  卸载它等于让**这些窗口恢复不了任何东西**，记住的 id 一直陈旧到有人把列表展开；列表自己那份状态
  （滚到哪儿、哪个项目是展开的）也会每折一下丢一次。
- **窄窗（< `lg`）是断点，不是第二份状态**：侧边栏 `absolute` 浮在对话上、盖一层背板，所以「多宽算窄」
  只有 `lg`（`64rem`）一个出处，JS 侧只有一处 `matchMedia`，且三处都是**当场读**（`isWideWindow`：
  初值、选一场会话要不要把抽屉收走、Esc 要不要收）——没有 resize 监听，也没有第二份宽度状态。
  抽屉另带两条礼貌：**选中会话就收**（否则刚选的那一场还盖在面板底下，点了像没反应；恢复不走这里，
  页面落到自己记住的那一场没有谁需要让路）与 **Esc 收**（Radix 的浮层先 `preventDefault`，
  所以它关自己的对话框时不会顺带把抽屉折了）。
- **状态条那五格读的是记录，不是客户端手里那段窗口。** 那段窗口确实让它数得出轮与步、
  也估算得出 tok/s（运行时的 `chars ÷ 4`），但**它不这么做**：缓存命中它根本不知道，而估算出来的用量
  冒充厂商报的量就是编。那五个数由 `GET /api/threads/<stem>/stats` 从会话的 jsonl 折出来
  （服务端见 [edge](edge.md#管理边路由表)），**缺的数就是缺的**，页面把它留空而不是写 0。
- **它什么时候问**：挂载、会话切换、**助手消息多一条**（一轮 ReAct 在这个客户端就是一条助手消息，
  所以这约等于「一次模型调用结束了」）、run 结束——**不轮询**。一次调用的数只有在它的 `model/end`
  行写下来之后才存在，所以一次长调用进行中这条就停在上一格，那是不撒谎的代价。
  **这套触发条件现在只有一份实现**（`components/composer-numbers.tsx`），状态条与那颗圈共用它：
  两个读者各问一次就是两个瞬间的同一条日志。**外加两次按需的追一问**，都写在那一处（run 结束后
  隔一拍再问一次，因为记录的写者比它自己的终帧晚一拍——run 的返回侧（`message` 行）落在 `:run/done`；
  打开面板时再问一次，因为那一下正是有人在问）。两次都不是轮询：一次 run 只多一次，不开面板不问。

## 上下文占用：model 左边那颗圈

**它画的是一个分数，所以没有分数就不画**（`components/context-ring.tsx`）。分子是厂商在那一次调用报的
`prompt_tokens`，分母是**那一次调用自己那行** `model/start` 上的 `:context-window`——服务端折好的
`context` 一节里的 `percent`，客户端**不自己除**。没有分子（这条会话一次调用都没报过）、没有分母
（目录没为那个 model 声明窗口），或者压根没有会话（新会话没有日志，`GET .../stats` 是 404），
`contextCells` 一律答 `null`，那颗圈整个不渲染。**这与状态条「有数才画」是同一条纪律**：环本身就是
「占了多少」，画一个没有分母的环是在画一个不存在的数。面板挂在圈上，所以它的缺席跟着这一条走。

**圈就是面板，绕成了一环。** 填满的那一段按同一份三个篮子分色——系统提示词（`primary`，与轨迹的
`system` 同色）、工具定义（amber，与轨迹的 `tool` 同色）、对话消息（sky，人的消息的那个色）——
所以看一眼圆环已经知道点开要说什么，两处也不可能各说各话：它们读的是同一个 `parts`。
**篮子缺席时（那次 run 的 message 尾巴还没落盘）圈只用一色画出份额**：份额是量出来的事实，
三分不是。颜色表只有一处（`PART_HUES`），类名写字面（Tailwind 扫的是源码里的字符串）。

**面板里的 `~` 加在描述这次 prompt 的每个数上**——头部的用量与三行图例各一个，窗口那个数不带。
三个篮子是摊出来的估算；头部那个用量虽是厂商自己数的，它与那三个数说的是同一件事，标法就得一致
（一个面板里混两种语气，等于要人在一行字里读出哪个是量的、哪个是估的）。**头部的两个大小本身是确数**
（厂商的 `prompt_tokens` 与目录声明的窗口），所以那半行不带 `~`。

**头部的两个大小与图例的三行用同一个格式化**（`formatContextTokens`：千以上一位小数、末位 `.0` 去掉），
它与状态条的 `formatTokens` **是两个函数，这是刻意的**：那条是会话累计、百万级，取整到 `k` 就够；
这条是单次调用的上下文、千级，`1.8K` 与 `12.9K` 的可比性正是要点。两个都写在 `lib/format.ts`，
与各自的读者放在一起说不清。

**堆叠条总画满**：三块加起来恰好等于头部那个分子（服务端摊的时候就保证了），所以不需要第四段
「其它」——那会是一段没人量过的颜色。

## 审批门

`approval-gate.tsx` 接的是 AG-UI 的 **interrupt 缝**：`useAgUiInterrupts` 读待决中断，
`useAgUiSubmitInterruptResponses` 把 `resume` 数组写回去。它按 `reason === "tool-approval"` 认领属于
自己的 interrupt，工具名与参数从**客户端自己的 `toolCalls`** 里读（不让服务端回显）。

- **不走上游的 approval seam**：`ToolCallMessagePart.approval` 那条路只收 `reason === "tool_call"` 的闸，
  本仓的 reason 不是它，接上去会画不出任何东西（该文件头注释完整记了这次核对）。
- **卡片要收成批**：AG-UI 恢复时一个 run 要为**每条开着的** interrupt 各带一条 resume，
  只回答一部分会被运行时按名拒绝；所以决定存在一张比单张卡活得久的 store 里，最后一张卡交完才提交。
- **审批门开着时 composer 由 `isSendDisabled` 关掉**：那时发的消息会被运行时静默吃掉
  （文本清空、哪儿都不落地），堵死发送是唯一不吞用户输入的处理。
  **第二个理由是同一道门上的另一半**（`.scratch/session-after-refresh` 票 04）：**服务端还在跑的那一场
  也不许发**。run 属于**进程**，所以刷新落进一场正在被回答的会话时，这一页的 `isRunning` 是 `false`
  ——没有任何东西是它起的——按钮亮着，发出去只换回 run 边那句 409（「this session already has a run in
  this process」）。判据因此是**窗口自己那个 `state`**（`useWindowFeed` 的 `onState` → `App` 的
  `runState`），**票 06 之后 `parked` 也关**（悬置的卡片刷新回来还在，它就是出路）；`unfinished`（进程死在半路）
  仍然不关——那一轮没有任何卡片可按。
  门关上时**原地画出来的是服务端的「停」**（`.scratch/session-after-refresh` 票 09）：Send 不再画，换成一个按钮
  （`components/session-run-stop.tsx`，`data-slot="session-stop"`，发 `POST /api/threads/<id>/cancel`）——那一场可以
  **真的被停掉**，所以不再用一句话解释为什么按不动（票 04 的 `session-run-notice.tsx` 已删）。
  **侧边栏那扇门也有同一半**（2026-09-22 修）：从侧边栏打开一场会话走的是 `rebuild`——它交的是一份
  **快照**、不开 feed，于是页面对「在跑」一无所知，`runState` 一直是 `null`，按钮亮着，按下还是那句 409。
  所以服务端在 `rebuild` 的回答上带上 `:state`（`harness.edge.http/live-state`，只在**本进程持有**时才有），
  客户端看见 `running` 就**改看**：读 tail page、跟 feed，和刷新那扇门一模一样的形状（`app.tsx` 的
  `sessionHistory`）——门关上，而且跑完自己开（feed 说 `settled`）。

- **同一个文件里还有第二张卡：提问。** `ElicitationCard` 读 `GET /api/elicitation` 的题面与 schema；
  **标题按谁在问分三种**——`server` 在场说「`{{server}}` 在向你提问」，只有 `askedBy` 说「模型在向你
  提问」，两个都不在场才是中立的那句。端点**不写 null 占位**：缺的键不出现，卡片靠**在场与否**分辨，
  `null` 会被读成「有个名字叫 null 的服务器」。拒绝不是失败，是一句模型能接着干的答案。
- **门是每场会话一份**：`boolean` 住在那份 host 里，`ApprovalBatchProvider` 也每份 host 一个（它读的正是
  它上面那个 provider 的待决中断）。所以 A 停在等人决定时，只有 A 的输入框关着，B 照常能发。
- **悬置不是「在跑」**：`isRunning` 在悬置时是 `false`（那一轮 run 已经以 interrupt 结束），
  所以注册表里的 `:parked?` 单独一格，侧边栏那一行在悬置时说 `Waiting on you`——在跑说转圈。
  **而「在跑」「悬置」两格都是两个读数的并**（`lib/session-status.ts` 的 `statusOf`）：本页自己的 run **或**
  服务端窗口说的 `running` / `parked`——谁都不是谁的超集（刚发出去那一瞬间只有前者，刷新回来那一种只有后者）。
  **`parked` 从服务端取是票 06 才成立的**：悬置的卡片刷新回来还在（服务端 `apply-frames` 把
  `RUN_FINISHED.outcome.interrupts` 折成最后一条 assistant 的 `metadata.custom.agui.interrupts`，客户端
  `fromAgUiMessages` 把它读成 `requires-action`/`interrupt`，`toThreadMessages` 不再把每条消息盖成 `complete`），
  所以为 `parked` 关的门**有出口**；在那之前 `parked` 只取本页自己的读数。
  合并只发生在**上报给页面的那一份**（`onStatus`，侧边栏那一行据此点灯）；`onOwnRun` 上报的仍是
  **本页自己的**读数，因为 `isOwnRun` 决定 feed 的帧能不能 import 进这个 runtime——正在**看**的那一场
  必须能接着长，把它并进去就等于让刷新回来的那一轮冻住。

## 样式体系：Tailwind v4 + shadcn，抄源码路线

- **Tailwind v4，CSS-first**：入口是 `ui/src/styles.css`（`@import "tailwindcss"` + 主题变量 +
  `@custom-variant dark`），**没有** `tailwind.config.js`——v4 的配置就写在 CSS 里。构建由
  `@tailwindcss/vite` 插件接进 `vite.config.js`，扫源码树里的工具类。
- **shadcn，抄源码路线**：`ui/components.json` 声明别名（`@/components`、`@/lib/utils` 等，与
  `vite.config.js` 的 `@` alias 对齐）与 registry：`@assistant-ui` 指向
  `https://r.assistant-ui.com/styles/{style}/{name}.json`。
  `npx shadcn@latest add "@assistant-ui/thread"` 由此把 `thread.aui.tsx` 连同 11 个
  registryDependencies **抄进仓库**。
- **字体是平台自己的那一串**：`--font-sans` 是 `-apple-system, BlinkMacSystemFont, "Segoe UI",
  "PingFang SC", …`，中文直接用系统字体（macOS 上是 PingFang SC）。它换掉了一个要先下载的 webfont
  ——构建产物里因此少了三份 `.woff2` 与那几条 `@font-face`。`--font-mono` 没动，代码块照旧是等宽。
- **对话区两档字号，第三个数字是载荷**：正文**14px**（对话**两侧**都是：答案、问题，以及问题在
  编辑态的那只输入框），步骤行（工具行、思考行）**13px**，参数与结果块 **12px**。正文那条规则写在
  `ui/src/styles.css` 里，**故意不放进 `@layer base`**——编辑框自带 `text-base`（上游的），
  而 Tailwind v4 把 utilities 声明在 base 之后，在层里写多高的特异性也压不过它。命中的钩子两侧
  **不同名**：答案的内容 div 带 `data-slot="aui_assistant-message-content"` 而没有 `aui-*` class，
  问题的气泡反过来带 `aui-user-message-content` class 而没有 `data-slot`——两边都是上游自己的命名，
  而改那份抄来的文件就破坏了「不重装直接 diff」。步骤行的 13px 写在 `message-parts.tsx` 的行上
  （行是我们自己的）。**没动的是 composer 的输入框**：它照旧 16px，输入框的惯例。
- **对话区只跟着末尾走，那颗 `arrow-down` 只在读者自己走开时出现**：viewport **不锚最后一段的顶部**
  （上游 registry 给的是 `turnAnchor="top"`，而那个属性同时把自动跟随**关掉**：新内容在折线下方生长、
  没人跟，按钮从这一段的第一行起就一直挂着）。`thread.aui.tsx` 里这**一处 `LOCAL:` 改动**把它去掉，
  `turnAnchor` 于是回到默认的 `bottom`：run 期间视口跟着末尾走，手动往上滚才脱开，回到末尾
  （自己滚回，或点那颗按钮）就立刻重新跟随，按钮同时消失。
- **一轮结束就折起来，只留最后一个 message**：助手那几条消息是这一轮的**步骤**，最后一条是**答案**，
  而长的对话里九成内容都是步骤。所以一轮只要**停下来**，页面上就只剩**答案**，上面挂一行
  `N 次工具调用 · M 条消息`，点开把步骤放回来、再点收起。**折着是默认、开着是例外**：记住的是
  「读者手动开过哪几轮」，于是「自动折叠」不需要任何 effect（停下来就不再是例外），刷新之后回到折着。
  停下来的判据是**这一轮最后一条消息的状态**：`running` 还在写、`requires-action` 停在人身上
  （审批卡就在步骤里，折起来会把它藏掉），`complete` 与 `incomplete` 都算停了——中断的一轮同样算结束。
  轮的**边界是数出来的**（相邻的助手消息，两端的邻居说话），算术全在 `lib/turns.ts`（零 import，
  UI 套件直接当数测），UI 在 `components/turn-steps.tsx`。**那一行不是当年删掉的「N tool call」组头回来**：
  那个头在**每个工具调用**前面、计数恒为 1，这一行是**一整轮**一行。
- **composer 的四个选择器是一个可搜索的浮层，不是原生 `<select>`**（`components/picker.tsx`）：
  项目、分支、model、思考档都是「点一下 → 弹出一个带搜索框的列表」。列表**可以按组，但只有一层**——
  model 按**供应商**一行一组、底下是它自己的 model，一条平铺的清单，不是「先选厂商、再选 model」；
  键盘是上/下/Home/End/Enter/Esc，焦点落在当前值上、关掉时回触发器。**搜索匹配三样东西**：
  标签、`hint`、组名——项目行的 `hint` 是完整路径（所以「workspace」也能找到一条只有末段做标签的项目），
  model 的 id 不含厂商名（所以「deepseek」要能找到它全部 model）。算术在**零 import** 的
  `lib/picker.ts`，UI 套件直接测。**思考档没有搜索框**（三个选项一眼读完，搜索框在那里是陈设），
  其余三个有。两个「画得出来」的口子也在这里：model 选择器允许一行**不在目录里**的当前 model
  （会话由 inline provider 服务时），正如项目选择器允许一个本 home 没登记过的目录。
- **会话开始之后，composer 底下不留空隙**：抄来那份 footer 带着上游的 `pb-4 md:pb-6`，于是停靠的
  composer 与窗口底边之间留着 16–24px 的页面底色——一段读起来像「剩下来的地方」的空白。
  规则写在 `ui/src/styles.css`：`.aui-thread-viewport-footer:has([data-started]) { padding-bottom: 0 }`，
  `:has()` 把范围钉在**已开始**那一态（`data-started` 由 composer 那圈框在会话有消息时挂上），
  首次会话居中的时候仍是上游的间距。

抄进来的清单（**对账是读 `LOCAL:` 标注**——i18n 那批落地之后这些文件就地改，逐字节 diff 不再是手段）：

| 位置 | 是什么 |
|---|---|
| `src/components/assistant-ui/elements/` | 12 份抄自 assistant-ui registry：thread、thread-list、tool-fallback、tool-group、reasoning、reasoning.aui、markdown-text、attachment、file、image、follow-up-suggestions、tooltip-icon-button。**九份带 `LOCAL:` 标注**——文案进了目录（spec 决策 5），另有结构性的几处（`thread.aui.tsx` 的五处见下，`thread-list.aui.tsx` 的重写见再下面）。**三份没有可译的文案，因此仍是原样**：`reasoning.aui.tsx`、`follow-up-suggestions.aui.tsx`、`tooltip-icon-button.tsx`。`tool-group.aui.tsx` 仍在清单里、仍只被抄来的 `thread.aui.tsx` 用（注入点已不再导入它，见下） |
| `src/components/ui/` | 10 份 shadcn 基件：button、dialog、dropdown-menu、input、textarea、tooltip、avatar、collapsible、skeleton、popover。**其中 `dialog.tsx` 带 `LOCAL:` 标注**：它的 `Close` 进了目录（`sr-only` 与页脚那颗按钮两处）；`popover.tsx` 是本特征加的那一份（读的那种浮层，与「选一个」的 dropdown-menu 各管一摊） |
| `src/hooks/` | 3 份 hook，不含文案：`use-copy-to-clipboard`、`use-attachment-src`，以及本特征加的 `use-document-title`（把当前会话的标题写进浏览器 tab，卸载时还原成产品名） |

**两份带改动，改动逐处标注**。`thread.aui.tsx` 不是被重写的，是被**加了三个 `LOCAL:` 插入点**
（`ComposerFrame` 套在 composer 外面、`ComposerTools` 画在动作行右侧、`ComposerAddAttachment` 顶替动作行
左侧那颗附图按钮），三处都只为让 `composer-chrome.tsx` 有地方可接；**第四处不是插入点，是删了一个
属性**——viewport 的 `turnAnchor="top"`（见上一节「只跟着末尾走」）；**第五处是消息级的**——`AssistantMessage`
读一次折叠钩子（`useStepFold` / `useTurnFolded`），据此把整条消息 `hidden`、或在轮首画那一行摘要，
**逻辑一行都不在这份文件里**（`components/turn-steps.tsx` 与 `lib/turns.ts`），它只问「我该被收起来吗」。
上面五处是**结构**上的改动；这份文件的**文案**也就地搬进了目录（spec 决策 5），所以它和 `thread-list.aui.tsx` 一样，不再与上游逐字节相同——**抄来的文件如今就地改，每一处有意改动都标 `LOCAL:`**。标记是逐字节对账的替代品：它说明「这里是有意改的」，不说明「上游改了什么」。`thread-list.aui.tsx` 则是**就地重写过**：上游那份是给另一种产品形态的扁平、
按日期分组的线程列表，本仓要的是按**项目**分组、单行、带缩进槽与相对时间的列表。保留的是行的骨架与
它那条 running 指示（**这一行的 `running` 是这一行自己的会话在不在跑**，不再是「当前页在不在跑」；
另加一格 `parked`，悬置时那行说 `Waiting on you`——`isRunning` 在悬置时是 `false`，两种说法是两件事），
删掉的是重命名 / 删除菜单项（本仓没有这两个动词）与把 Promise 丢掉的 `ThreadListItemPrimitive.Trigger`
（拒绝的句子必须显示在**所点的行**上）。**每一处改动在文件里都有 `LOCAL:` 标注**，对账就是读那些标注块。

其余的本地差异走**自建注入点**，不动抄来的文件：`message-parts.tsx` 的 `THREAD_COMPONENTS`
与自建面板（`approval-gate.tsx`、`sidebar.tsx`）。

`THREAD_COMPONENTS` 里分两组槽位。**步骤行那一组**三个各有分工：`ToolFallback` 是一种调用长什么样，
`ToolGroup` **什么都不画**（组的头「N tool call」已去掉，而槽位不能空着——空着抄来的
`thread.aui.tsx` 会画它自己那个头），`ReasoningGroup` 是思考。工具行与思考行是**同一形状的一行**：
`类型图标 · 名字 · 摘要`，状态（转圈 / 对勾 / 叉 / 感叹号）在**行尾**、词进 `sr-only`，
参数与结果仍在行里点开才见（**默认折叠是有意的差异**，实现与理由见该文件头注释）。
**那一处例外现在落在行上，不在抽屉上**：正在流式的那段思考，行上那一行字自己滚——流式期间行上装的是
**已经到达的那一段**（压成一行），装在一只 `overflow: hidden` 的窗里并**被往左拖**到末尾停在右边缘
（字从左边出去、新字从右边进来；**拖的是 `transform`**，所以是滑不是跳，见 `styles.css` 里那条注释），
最后一个 token 落下就回到**首行**。**抽屉不再自己展开**（2026-09-22 推翻）：`open` 由行自己持有、
初值 `false`，上游那条 `userOpen ?? (streaming || defaultOpen)` 再没有机会替人点开——窗口、`max-h-64`、
跟随最新 token 的滚动都还在，只给**点开它的人**。历史会话（不流式的）永远是折的一行首行，
手动开合过的面板也不再被自动改动。**一个想法一行，而分界是「一步」**：工具调用结束一个想法
（`想 → 读 → 再想` 仍是三行），**答案的正文不结束**。两侧各管一半：**新写下的记录里本来就只有一条**
reasoning 消息（后端不再在答案的第一个 token 上关闭它，见 [edge](edge.md#ag-ui-边) 与
`.scratch/reasoning-order`）；**这次改动之前写下的记录**（以及别的厂商怪次序）里可能是两条，
`lib/reasoning-preview.ts` 的 `thoughtAt` 于是跨消息走一趟（往回判「这条是不是续写」，往前把这一段的想法
收成一行）。两条文字规则在 `lib/reasoning-preview.ts`（UI 套件直接测），
拖动那一手在 `message-parts.tsx` 的 `ReasoningTail` ＋ `styles.css` 的 `.aui-reasoning-trigger-tail`；
现场与代价见 `.scratch/thinking-row-tail/`。
**轮那一层另有一行摘要**（`N 次工具调用 · M 条消息`，见上「一轮结束就折起来」）：它不是组头的回归——
组头在**每个调用**前面、计数恒为 1，那一行在**一整轮**前面、数的是这一轮做了多少。
「摘要是投影不是截断」这条是硬约束：认不出的工具落到「第一个字符串参数」，所以新增工具
（含 MCP 的）不改前端就能看见它的调用。
**composer 那一组**是上面那三个插入点（`ComposerFrame` / `ComposerTools` / `ComposerAddAttachment`），
属于 `composer-chrome.tsx`，与步骤行没有关系。

**两张按工具名开的表就是「认得它」的全部**（都在 `message-parts.tsx`；键是 `harness.kernel.tools` 注册的那个
名字，`CONTEXT.md` 说不许起别名——改了名，图标会**静默**丢回扳手）：

| 表 | 答什么 | 认得的名字 |
|---|---|---|
| `TOOL_ICONS` | **这是哪一只手**（kind，不是状态） | `read` `write` `edit` `replace` `insert` `undo_last_replace` `grep` `glob` `bash` `eval` `skill` `todo_write` `web_fetch` `web_search`；认不出的给 `WrenchIcon`，刻意不长得像其中任何一个 |
| `subjectOf` | **这一步在干什么**（只读参数，不做解析） | 同上一列。各自的形状：`glob` 是模式（给了根就带上根）、`todo_write` 是进度（`2/3 完成`，空清单是「清空」）、`web_fetch` 是 URL、`web_search` 是查询串；认不出的是「第一个字符串参数」 |

新增一个工具**不动**这两张表也能用（默认分支与扳手图标就是留好的口子）；动它们是**可读性**，
不是可用性：一行是「扳手 + 一段 JSON」还是「一眼看出这是按名字找文件、进度 2/3」。
真机证据（四条新工具的步骤行与各自展开后的参数、结果）在
`.scratch/tool-parity/evidence/`。

**`skill` 也是一次普通工具调用，工具卡那一套前端为它一行未改。** 服务端把技能清单与技能正文当 user 消息
塞进模型的上下文——**2026-09-18 起它们会以 `CUSTOM` 帧出来**、在会话栏里画成一张注入卡（下一节），
**但它们不进那场对话**：帧落成一个 `data` part，而会话那一份没有它（`sessions/messages` 把 `data` part 摘掉）——下一轮交给模型的向量里没有注入物的字节。
除此之外界面上只有一次普通的 `skill` 调用与它的返回。见
[skills-and-instructions](skills-and-instructions.md#看得见但仍然不是会话的一部分)。

**这句话有一个例外，只有一行**：`/name` 那条**人的**加载路径现在有输入面了——技能列表（下一节）。
而**注入本身**（不管谁触发的）在会话栏里就是下一节那张卡；多出来的是「有哪些名字可选」这一屏，
两者不是一回事：一个是模型看到什么，一个是人挑什么。

## 文案与语言（i18n）

界面说**两种语言**：英文与中文。机制是 i18next + react-i18next（为什么引库而不手写一份表、
代价是什么，见 `.scratch/ui-i18n/spec.md`），两份目录在 `ui/src/locales/<语言>/<面>.json`，
一个「面」是页面上的一处地方：**外壳 / 输入框 / 正文 / 审批 / 设置 / 轨迹 / 数字与时长 /
抄来的元素（三组）/ 本侧的句子**——十一份，**全部已落地**（2026-09-17，13 张票）。
今天界面上不再有硬写的界面文案；留在原文里的只有后端自己的句子与模型的词汇（见下面「边界」）。

- **两条守卫把「搬漏了」变成红的**：每个键在两种语言里都在、值都非空，且**语言各自的复数形式要与
  它自己的 CLDR 类别一致**（英文有 `_one`，中文没有）；反过来，**目录里不留没人命名的键**
  （一个拼错的键被补进目录、或一行删掉后留下的条目，都不会出现在屏幕上，只会越积越多）。
  两条都在 `test/suites/i18n.ts` 里，第一条是「故意弄坏会红」验过的。

- **语言是这台 harness 的设置，不再是浏览器的偏好。** 它住在 `config.edn` 的 `:ui :language`，由
  `harness.infra.language` 解析：**`config.edn` → 操作系统的用户语言（macOS 取 `AppleLanguages`，
  不是这个进程的 locale、也不是终端里的 `LANG`）→ 终端语言 → `en`**。页面只**读**它的答案
  （`GET /api/language`），再用一条零 import 的纯函数（`src/lib/language.ts` 的 `asLanguage`）把它
  收进两种语言之一——**基础子标签**决定，所以 `zh` / `zh-CN` / `zh-TW` / 旧的 `zh_CN` /
  `zh-Hans-CN` 都是中文，`fr` 这类落到英文。这样**界面与 `<env>` 说的是同一个值**：人在说中文时，
  模型不会用英文回答（`.scratch/agent-language`）。
- **开关在设置面板的 General 页，写 `config.edn`**（`POST /api/language`，与别的写配置一样：先校验整份
  配置、再原子写、留一份 `.bak`）；写完界面立刻切过去，**写失败就把服务端那句话显示在开关下面，且不
  切换**。它**不再是那个面板里唯一不写文件的一项**——语言是这个家的配置，和 provider / model 一样。
- **开关那一行在两个条件之外**，不是排版：config.edn 解析不出来的家画出来的是一页拒绝，而**被放进
  那页、又读不懂那门语言的人，必须还能把它换掉**。
- **`<html lang>` 是状态的一部分**，不是装饰：读屏软件靠它挑嗓音。所以它由 `src/lib/i18n.ts` 的
  `startLanguage` 在初始化时和每次切换后写上；`index.html` 里那个静态值只是兜底（静态文件写不出
  时和每次切换后写上；`index.html` 里那个静态值只是兜底（静态文件写不出正确值，原来那个 `zh` 与
  通篇英文的文案本来就不自洽）。
- **首屏取值**：语言要问一次服务器，所以 `main.tsx` 在创建根之前 `await startLanguage()`
  （`src/lib/i18n.ts`）——先取语言、再 `i18n.init`、再渲染。代价是首屏多一个往返；换来的是
  **不闪一帧 `view.conversation`，也不闪错语言**（语言已知之后，`initAsync: false` 保证 init
  本身仍是同步的，目录也照旧是打包进来的静态资源）。
- **键在调用处字面写**，不拼字符串（`` t(`status.${x}`) `` 这种不许）。两条守卫靠它成立：
  `npm run typecheck` 挡得住不存在的键（`src/i18next.d.ts` 把类型收到英文那份上，实测过一个错键会
  编译失败并列出可用的键），套件挡得住两种语言不一致（键集相同、值非空，缺一个就是红）。
- **纯模块仍是运行时零 import**（`src/lib/language.ts`、`src/lib/catalogs.ts`）——「套件按相对路径
  import 纯模块」这条既有性质没有被破坏，那两份文件只 import JSON 与类型。
- **数字与时长的词只有一处**（`src/lib/format.ts`，`TFunction` 是类型 import，运行时仍是零 import）。
  里面有三条分界，都是判据而不是口味：**单位不翻**（`B`/`KB`/`MB`、token 的 `k`/`M`、`tok`、`tok/s`
  两种语言同一个串，进目录只会多出第二个要改的地方）；**短语与量词翻**（`<1s`、`2m 15s`、
  `3 次模型调用`）；**时区取本机、标点取界面语言**——`formatTime` 因此收一个 locale 参数，而
  `formatBytes` 不收：一个是「读者坐在哪」，一个是「页面在说什么」。量词交给 i18next 的 `count`
  （英文有单数、中文没有，这条差别是**语言的**，所以和词一起放在目录里）。
  另外**拒绝句用另一位小数**（`formatMegabytes`）：`formatBytes` 的整 MB 四舍五入会让
  「这张图 2 MB，上限是 2 MB」读起来像 bug。
- **缺失的数字是调用方的话，不是格式化器的话**：「还没有日志」「还没跑过」原来是 `formatBytes` /
  `formatTime` 答的，现在由那两行自己说——只有它知道这是哪一种缺失（其中一种根本不是字节或时间）。

### 边界：只有界面自己的文案有语言

**后端的句子原样穿过，一个字节不翻。** 工具结果、HTTP 错误体、审批与 elicitation 的话术、工具描述、
`prompt.md`（轨迹视图里读得到）、MCP 服务器给的 prompt 与 description——都是这样。

理由不只是省事：**工具结果同时是模型的上下文**（它是模型读完才决定下一步的那份记录）。把它翻成随
界面变化的两种语言，等于让同一份记录不再唯一——同一段 run，在两个不同语言的浏览器里，模型看到的
东西会不一样。这与「会话归服务端、记录只有一份、浏览器只是画它」是同一个方向：**记录是记录，
界面是界面。**

界面上另一处不翻的是**模型的词汇**：工具名逐字（`read` / `bash` / `todo_write`，见
[CONTEXT.md](../../CONTEXT.md) 的「工具名的写法」），参数与结果的正文是模型写的，也不翻。
`src/lib/attachment-rules.ts` 那种**本侧自己抬起**的句子反过来必须翻——服务端给了 `error` 就原样用它，
只在服务端没说话时才说自己那句。

判据一句话：**界面里不该出现中文夹英文（或反过来）**，除了上面这两类刻意留在原文里的东西。

## 技能列表（输入框里打 `/` 弹出的那份菜单）

打 `/` 弹出的那张表是**上游的触发面板**驱动的：`assistant-ui` 自带
`ComposerPrimitive.Unstable_TriggerPopoverRoot` / `.Unstable_TriggerPopover` / `.Items` / `.Item`
一套，本仓接的是「挂在哪、名字从哪来、哪些能选、一行画什么」。

- **挂点仍是一个自建插入点，抄来的文件里没有为此加过一行**：`composer-chrome.tsx` 的 `ComposerFrame`
  本来就套在 composer 外面，而触发面板必须包住**输入框**（它给输入框发 combobox 的四个属性、并让面板
  在发送前吃掉方向键与 Enter），所以 `TriggerPopoverRoot` 就挂在那一层。这一节用到的
  `elements/` 文件（`thread.aui.tsx` 与其余各份）**没有为技能列表改过**——`thread.aui.tsx` 那三个
  `LOCAL:` 插入点是 composer 附件与选择器共用，与这份菜单无关。
- **三个默认值都换掉了**，因为它们是为另一种语义写的：`matcher`（上游默认「前面是空白就算触发」，
  本仓只认**消息开头**的 `/`，与服务端的 `slash-pattern` 同形状）、`formatter`（`serialize` 成
  `/名字`，上游补尾随空格并把光标放到空格后）、`search`（没有 categories 时上游那条回落路径会走空表，
  所以过滤是这里的：名字与描述、大小写无关、顺序照服务端给的）。
- **没有 categories，这是决定不是省事**：一张平铺的表、每行带自己的层徽标，不是「先选层再选技能」的两级。
- **状态只有三样**：正在取（一行说明）、取不回来（`role="alert"`，把服务端那句话显示在面板里）、
  什么都没有（**面板根本不出现**——把一个空盒子摆出来，比不摆更糟）。
- 数据与措辞在 `src/lib/skills.ts`（层关键词 → 屏幕上的词、坏技能的原因关键词 → 一句话），
  面板与行在 `composer-chrome.tsx`。

## 附件：composer 里的图

输入框里可以粘一张图（或拖进来、或用 `+` 从文件框里选），它显示成一个可删的缩略图，随这条用户消息一起
发出去，并在这条消息旁边**带着图显示出来**（点一下放大、Esc 关）。三条路今天都是活的，而**把它们一起
打开的是同一个东西**。

- **适配器就是那个能力位。** `capabilities.attachments` 在上游就是 `!!adapters.attachments`，而粘贴
  （`ComposerInput` 的 paste handler）、拖放（`Dropzone` 的 drop handler）与 `+` 三条路**都先问这个布尔**。
  不给运行时适配器，三条路会以同一个方式安静下来：粘贴不被消费、拖放被拒、`+` 弹出文件框然后什么都不落地。
  所以适配器不在 composer 里，它是 composer **能不能有附件**这件事本身——住在 `lib/attachments.ts`，
  在 `app.tsx` 的 `adapters.attachments` 上交出去，一行。
- **没有上传，字节躺在消息里。** 上游那份 `SimpleImageAttachmentAdapter` 的两个方法正好是这个界面要的：
  `add` 留下那个 `File`（草稿期间画的缩略图就是它），`send` 把字节读成 **data URL**。那个 data URL
  **就是 wire**，不是通往 wire 的一站：AG-UI 客户端把它转回
  `{type: "image", source: {type: "data", value: <base64>, mimeType}}`，服务端再翻成厂商的
  `image_url`（见 [edge](edge.md#管理边路由表) 那张表）。没有收字节的端点、没有中间存储、没有 URL、
  没有生命周期——所以它不进库、不落盘，也不从库里读回来（`CONTEXT.md` 的**附件**词条）。
- **判据与 `undeclared-input` 是同一条，这是这一节最要紧的一句。** 一个模型声明收不收图，
  服务端在**调用厂商之前**用它拦一次（`harness.edge.ag_ui/undeclared-input`）；界面在**文件变成附件
  之前**用它拦一次（`lib/attachment-rules.ts` 的 `acceptsImages`）。**两个读者、一条规则**，
  因为两个方向都错：比服务端**严**（把「没声明」当成「不收」）会把一个今天跑得通的配置挡在门外，
  而「没有声明就是没有承诺」是那条规则的原话；比服务端**松**则整条消息被 `RUN_ERROR` 吃掉——
  composer 已经清空，打的字和那张图一起没了，正是本仓「不许吃掉别人打的字」要防的那件事。
  **「缺字段」与「空集」是两个答案**：服务端对 nil 不拦、对 `#{}` 拦（`undeclared-input` 实测
  `nil → []`、`#{} → [:image]`），线上也分得开（没声明就不写这个键，声明了空集写 `[]`），
  所以界面照同一个分法读。
- **2 MB 的上限量的是源文件字节。** 一张图进了会话就留在历史里：**每一次模型调用都会把它再送一遍**
  （2 MB 的截图约 2.7 MB base64，一场二十轮的会话就是五十多兆的请求量），所以量的是**人手里那张源文件**
  的字节，而不是 base64 长度或解码后的像素——人看得见、也唯一能自己动手改的数就是它。
  **不许偷偷改字节**：不做客户端压缩、不做缩放、不做重编码——改掉别人给的字节再发出去，等于在记录与
  「模型到底看到了什么」之间多一层没人能复盘的东西。超限就是拒，并说清拒的是什么。
  判据只有一处（`overByteLimit`），所以不会出现一处量 `file.size`、另一处量 base64 长度。
- **拒绝在适配器里发生，所以三条路都绕不过去。** `add` 是三种入口唯一汇合的地方；被拒时**抛**
  （这是上游 `add` 自己的契约，三个调用方各自接住自己的 rejection），此刻什么都还没挂上去，
  所以**输入框里的字与已经挂着的附件一个都不动**。
- **拒话只画一处。** `ComposerFrame` 是画它的地方（`role="alert"`、`data-slot="composer-attachment-refusal"`），
  两条判据的句子都往那儿去——这是「拒绝长什么样」只有一份的意思。`+` 那颗按钮在不受图的会话里
  **留在原地、变成 disabled，理由挂在包着它的 `span` 的 `title` 上**（disabled 的按钮在值得在意的浏览器
  里收不到指针事件，挂它自己身上的 `title` 是一句没人看得见的提示）。**留着而不是拿掉**是决定：
  一个悄悄消失的按钮与一颗从来没做出来的按钮从外面看一模一样，而这两件事里更难查的那件不该是 bug 的产物
  ——与技能列表把坏技能仍列出来同源；而且 `+` 是人决定要不要试的那一刻，理由必须在那之前就在，
  粘贴与拖放只能在被拒之后才说得上话。
- **那个事实住在一个小 store 里，不在 React state 里。** 判据要的是「本会话的模型收不收图」，
  读它的有三处：适配器（拒）、`+`（自禁）、`ComposerFrame`（画句子）。而适配器是从**上游自己的事件
  处理函数**里被调的——那里够不着任何 React 树——所以这个事实落在 `lib/attachments.ts` 的
  subscribe/getSnapshot 上，适配器写、界面读。**谁刷新它**：`ComposerTools` 每次取（挂载、会话切换、
  模型改完）都顺带问一次 `GET /api/model?threadId=…`（那个端点存在就是为了这件事，它的 docstring 写着
  「for a client deciding whether to offer an image picker」），所以**换了模型不用重载页面，判据当次就变**；
  这个请求失败被折成「什么都没声明」（服务端自己的语义），不让它把选择器一起拖掉。
  `GET /api/choices` 的形状一个字节没改，附件这一问没有新增端点。
- **对话那一侧用的是抄来的两份元素**：缩略图与消息旁那张图是 `elements/attachment.aui.tsx`，
  点开放大、Esc 关闭是 `elements/image.tsx`（`ImageZoom`）。两份都在**原样未改**那一组里，
  今天真的被用上了——这一票没有为了它们改过任何抄来的文件。
- 真机证据（粘贴 / 拖放 / `+` 三条路、被拒的两句话、记录里那两条行）在
  `.scratch/composer-image/evidence/`。

## 注入物在会话栏里的一张卡

**服务端每轮算出来的注入物，人也能在会话栏里看见**——一张与工具卡同一套壳的折叠卡：折着只有一行
`上下文注入 · <首行> · N 字节`，点开是那段字节（等宽、可滚动），一次注入一张。

**它不是一个消息，而是一个 `data` part。** 每条注入在服务端是一条 `CUSTOM` 帧（见 [edge](edge.md)），
适配器把它按顺序落成 `{kind: "data", name, value}`；`lib/injections.ts` 从 part 里算出**标题**（首行的标签，
如 `<job-ended …>` → `job-ended`，认不出就用首行）、**预览**与**字节数**（UTF-8，中文一个字三字节）；
`components/context-card.tsx` 用 `makeAssistantDataUI({name: "injected-context"})` 画它——**注册就是那个组件
的挂载**（`app.tsx` 里挂在 `AssistantRuntimeProvider` 之内），`thread.aui.tsx` 那句
`case "data": return part.dataRendererUI` 是抄来的，一行未改。文案进 `thread` 命名空间（中英两份）。

**开场块的那张卡有两条路来。** 会话出生时写进对话的那几条 opening entry 自带**同一个**
`data` part（`harness.edge.ag_ui/injected-part-name`），所以画法一模一样，但它们**属于 user 消息**：

- **出生那一轮把对话本身交给页面**（2026-09-21 拍定；`ag_ui/conversation-snapshot`）：那一轮的
  `RUN_STARTED` 之后跟着一帧 `MESSAGES_SNAPSHOT`，带的是**这一轮写进对话的 entry**。**服务端发，前端画**
  ——这条分工是拍定的原话，也是票 05 那次改动的由来：最初写的是「每个条目发一张 `CUSTOM` 卡」，而
  `CUSTOM` 只是**一个 part**，适配器把它挂到**正在流的那条消息**上（`run-aggregator.js` 的 CUSTOM 分支
  不看 `messageId`），客户端手里没有那条 user 消息时，卡就落到答案底下、而不是人的那一栏；改成快照之后，
  消息、id 一起走，落位由消息自己决定。快照的 `content` 是**文本**（`ag_ui/wire-message` 投影）：AG-UI
  对它解析的每一帧做 schema 校验，`data` part 会当场把这一轮打死（实测：界面上一条 Zod 报错），而**卡
  由读者按 id 和文本自己画**——`thread.aui.tsx` 的 `UserMessage` 因此认两条：`isCardOnly(parts)`（这一条
  只带一张卡）或 `isOpeningEntryId(id)`（`session-opening-<i>`：快照把它变成一条带文本的 user 消息，part
  没了、id 还在）。**这条路上不再有任何「跑完读一次记录」**：`app.tsx` 的那次 import 已随这次拍定去掉，
  跑完只剩一次健康检查式的读数上报。
- **此后每一轮它只是历史**：随会话的窗口（feed / `sofar`）来，一次开场一张卡，而不是每一轮重复一遍。
  窗口里那一条是 user 消息、内容是「只有一张卡」，`UserMessage` 同样交给 `UserInjectionCard`——**不画成
  那个人的气泡**，这是 ticket 02 的修正。

**刷新靠重建带回来。** 重建（窗口的 entry + 记录里的帧）在 `harness.kernel.frames/apply-frames` 把派生注入
落成一条**只带那个 data part 的 assistant 消息**，id 就是帧自己的 `messageId`（确定性的，所以每次刷新是同一张卡）。
而**开场那一张是 user 消息**：记录里那几条开场 `message` 行（信封 `source: "opening"`）先折出条目（user + 两张 part），帧再按同一个 id
折一遍时被丢掉（`replay/append-new`、`sessions/append!` 都是先到先得）——所以重建之后开场卡在**人的那一栏**，
源出派生注入的卡在助手那一栏。适配器的 `fromAgUiMessages` 只取文本与 tool-call、会把这个 part 丢掉，
所以 `app.tsx` 的 `toThreadMessages` 让 `keepInjectionCards`（纯函数，**按 id 配对**，不是按下标——重建会把
下标的对应挪走）把它补回来。**同一条规则也接住了快照那条路**：part 丢在适配器里，而 id 与文本留着。

**回发时它被丢掉**，这是整件事干净的唯一依据：`toAgUiMessages` 只回 text / reasoning / tool-call，
`data` part 在那儿没有分支。于是卡片看得见、却进不了下一轮的请求——适配器升级时第一个要看的就是这条
契约（`test/suites/injections.ts` 第三条）。真机证据在 `.scratch/context-frames/evidence/`。

## 轨迹（`Conversation` / `Trajectory` 两个视图）

线程列上方有一条切换：`Conversation` 是今天这个页面，`Trajectory` 换成**轨迹视图**——
按**轮**列出**模型当时手里到底有什么**，上方一条 `input` / `model` / `tools` 三条 lane 的时间轴。

**默认只有那份列表**，点某一行才在右侧展开**那一条**（再点一次、或点 × 收回，宽度还给列表）。
这里**没有固定的第二列、也没有一对固定页签**——因为记录里本来就没有「这一轮的 system 提示词」这种东西：
有的只是二十条里的某一条，而那一条正是读者问的那一条。固定页签想显示的两样东西**都是条目**，所以都还在：
**工具跟着 system 消息一起给**：点开 `system` 那一行（第一轮出现，字节变了再出现一次），
面板里是**两个页签**——`system prompt` 与 `tools`。工具那一页是一张**可折叠的表**：一行一个工具，
折着只显示 `名字 + 描述的第一行`，展开是该工具的完整描述与它**照发出去的定义 JSON**。
两样东西共用一个面板但不是上下堆着：它们是**同一次请求的两面**——模型这一轮被告知的规矩，与它这一轮能用的手，
把提示词压在一大段 JSON 上面就没人看得到提示词了。页签是**条目级**的（只有 system 这一种条目有第二个面），
点开另一条 system 行会回到提示词那一页（`ItemDetail` 按条目 key 重建）。
它跟着 system 走不是排版上的巧合：两者本来就是**同一次请求**带出去的两样东西，
所以该一起看。某一次调用是否带了表、带了几张，在对应的 `assistant` 行上有一个计数
（表本身不在那里重复第二遍）。

- **它读的是记录，不是运行时。** 这是它与对话页签的根本区别：system 消息的字节、拼在它旁边的指令文件
- **它读的是记录，不是运行时。** 这是它与对话页签的根本区别：system 消息的字节、每次调用**照发出**的工具表，
  客户端从来没有过，AG-UI 帧里也没有（注入物是这里唯一的例外：它**会**以 `CUSTOM` 帧出来、画成上面那张卡——
  但卡只是**一段字节**，`items` 的来源与分轮、这次调用带了几张表，都只有记录才有）。所以这一半由服务端从
  jsonl 折出来（`harness.edge.trajectory`，
  见 [edge](edge.md)），从 `GET /api/threads/<stem>/trajectory` 吐出去，
  客户端只画折好的东西（`src/lib/trajectory.ts`、`src/components/trajectory-view.tsx`、
  `trajectory-timeline.tsx`）。**它不数、不算、不重排**：记录里没有的格子它说没有，
  绝不拿「这个会话今天有什么」去填。
- **注入物整场只画一次。** 服务端没有会话，所以每个 run 都会把开场块重新拼一遍、把历史里还留着的
  `/<名字>` 重新派生一遍——照搬「这个 run 扛了什么」，同一段字节就会画在每个 turn 底下，5 轮的会话看起来像
  开场发生了 5 次，**那是自造**。所以判据是**整段文本的字节**：没变就不再画（开场块只在第一轮），变了的那一轮再画一次
  （与 `system` 条同一条规矩）。技能的正文也一样：**用一次画一次**，画在用它的那一轮。
  折法在 `harness.edge.trajectory/add-context`（去重记在会话这一层），客户端照旧只画折好的东西。
- **切换住在 `app.tsx`，整列换掉，输入框也一起没有**——轨迹是读一份已发生的东西，不是一个能打字的地方。
  它**不写任何存储**：这是看会话的一种方式，不是关于会话的偏好。轨迹那半边只画**当前显示的那一场**
  （每份 host 只在 visible 时渲染整列），所以不显示的会话只挂着 runtime，不渲染消息。
- **取数时机与 composer 下面那条状态条同一个**（`composer-numbers.tsx` 里那一处）：挂载时、会话变化时、
  以及一次模型调用结束时（本侧一轮 ReAct 就是一条 assistant 消息，所以那个计数涨了就是有调用刚回来；
  `isRunning` 收尾）。读取它的 hook 必须**在 runtime provider 之内**——`App` 自己渲染那个 provider，
  在它的函数体里读会直接抛（浏览器里验过）。
- **两种看法是同一批标记的两种排法**，不是两份数据：`duration` 是真实时间轴（空档就是空档，
  等审批那两分钟看得见），`turns` 每轮等宽（长的安静的轮与短的吵的轮变得可比）。
  并发的工具调用在 lane 内**堆叠**，不排成首尾相接。
- **段与行是同一件东西的两次画法**：每条 lane 上的每个记号都能点，点的效果与点那一行**完全一样**
  （打开右侧详情；再点一次收回），因为记号本来就带着它对应条目在前面的位置。反过来也成立：
  点一行，条上那一段会被圈出来；从条上点进来的，那一行会被滚进视野——**两边对不上的时候，
  就是折法错了**，这是这个视图的自查手段。没有对应条目的记号（比如一次还没配对到回答的模型调用）
  照旧画出来，但**不是按钮**：它是一个事实，不是一扇通往空面板的门。
- **配色只有一份**（`trajectory-colors.ts`）：`input`/`model`/`tools` 三条 lane 的颜色
  就是 `user`/`assistant`/`tool` 三个标签的颜色，因为一条 lane 上的记号**就是**同一类条目
  ——lane 与条目说同一种颜色语言，读者不必学第二套。`LANE_KIND` 把这份对应写下来，
  而不是留给下一个加 lane 的人去猜。
- 一格里没有的都不编：老记录（早于 `model/*` 那两行）**没有** `calls`、模型 lane 空着并如实说；
  被否决的调用**没有** `startedAt`（它不是「0 秒」），画成空心记号。
- 文案与 UI 其余部分同语言（见[文案与语言](#文案与语言i18n)：这一面的文案进
  `ui/src/locales/*/trajectory.json`），`data-slot` 是它的挂点（`trajectory-view` / `trajectory-turn` /
  `trajectory-item` / `trajectory-pane` / `trajectory-segment` …），真机证据在
  `.scratch/trajectory/evidence/`。

## 测试

**怎么跑**用 `cd ui && npm test`（vitest；三条腿与定向跑的完整入口见 `AGENTS.md`）。整套测试的
**驱动只有一个文件**（`test/ui.test.ts`），
`test/suites/{frames,client,turn,approval,skills,stats,context,elicitation,elicitation-card,attachments,turns,injections,picker,i18n,restore,running,concurrent,sidebar,session-title,relative-time,sidebar-rows,record,window}.ts`
是被它 import 的普通模块（`sidebar` / `record` / `window` / `elicitation-card` / `running` 那五份是 `.tsx`：它们
`renderToStaticMarkup` 组件、把渲染出来的那句话读回来）：

- **一次运行一个后端。** vitest 给每个测试**文件**一份独立模块图，所以多一个测试文件就是多一个 JVM。
- **驱动里钉着用例总数**（`EXPECTED_CASES`）：它是一份契约，让「某个套件从清单里掉了」
  或「丢了用例」变成**失败**而不是静默变绿。
- **后端是真的**：`dev/harness/e2e_server.clj` 起真 `harness.edge.http`，在 `--port 0`（OS 分配）上，
  provider 是 `harness.fake` 的脚本替身，日志写进临时 `CLJ_HARNESS_HOME`。
  所以跑多少次结果都一样，也不会写进真实的 `~/.clj-harness`。
- **控制通道是文件不是端点**：服务端在遇到**新的 threadId** 时重读脚本文件。
  测试写这个文件就相当于说「模型下一句回什么」——**生产 HTTP 边因此一个测试专用路由都不长**。
- 套件驱动真的 `@ag-ui/client`，所以它测的是协议与运行时的真实行为，不是替身。
- **两个家目录都交到用例手上**（`configure` 的 `home` 与 `userHome`）。`userHome` 由 spawner 造好、
  用 `--user-home` 交给后端，所以一个用例能往 OS 家目录里**播一份系统级技能**——
  「机器上的技能是两层之一」这件事在界面上能验，靠的就是这一条缝。
- **套件不 import 任何要浏览器的 `src/`**（React、DOM、`@` 别名都不行——`vitest.config.ts` 只跑 node，
  也不加载 `vite.config.js` 的别名）。**唯一例外是零 import 的纯模块，按相对路径引**：
  `suites/stats.ts` 引 `src/lib/format.ts`，为的是把「`2.9M tok` 是这么写出来的」钉住
  ——不然那句话只有一个没测的格式化函数守着；`suites/attachments.ts` 引
  `src/lib/attachment-rules.ts`，为的是把**与 `undeclared-input` 同一条**的那个判据钉住，
  外加 2 MB 那个边界的两侧；`suites/turns.ts` 引 `src/lib/turns.ts`，为的是把折起来那条规则的
  **算术**钉住——轮的边界、什么时候算停、那一行数出来是几（这三件事在浏览器里只看得到结果）；
  `suites/picker.ts` 引 `src/lib/picker.ts`，为的是把「查什么」与「同组怎么并」钉住（同样是
  只在浏览器里看结果、看不出规则的那一类）；`suites/session-title.ts` 引
  `src/lib/session-title.ts`，为的是把**标题怎么从消息里派出来**钉住——哪条消息算、空白变成什么、
   60 个码点在哪切（一个 emoji 占两个 UTF-16 码元，`slice` 会把它劈成半个）、没说过话时是哪句词，
   以及 tab 那条 `· clj-harness` 的尾巴。
- **一个套件测什么，写在自己文件头上**：`suites/skills.ts` 断的是**端点**（两层、同名归谁、只读不留痕），
  它**不**断菜单怎么画、哪个键选什么；`suites/stats.ts` 断的是端点折出来的数**与那五格的字符串**，
  它**不**断那条灰线的位置与字号；`suites/attachments.ts` 两条**都是纯的**，它**不**断那颗按钮的
  disabled 状态与那句拒话画在哪——那些在真 Chromium 里量（下一段）。

**会话标题的两个来源**（`session-title.ts` 与 `lib/projects.ts` 的 `firstUserText`）值得单说，
因为它是这个仓里**唯一一处「库里存了对话内容」**：第一次收到消息的那次 run 把第一句 user 消息写进
`sessions.title`（`harness.infra.db/sessions-remember-their-title` 里有完整的取舍，包括它推翻了
`sessions-hold-no-conversation-content` 那条守卫、以及推翻后仍然付的代价），侧边栏每一行照它写；
同一个页面**自己握着**的那些会话另有更新的一份（host 上报、页面按 id 存、与 `statuses` 同一条路），
所以刚发出去的第一句当场就在行上，不用等下一次列表。老会话（这一列存在之前跑过的）没有这份，
**不回填**，显示 thread-id。

界面侧另有**真 Chromium 走查**，截图留在 `.scratch/<feature>/evidence/`：那是各票验收的一部分
（三段位、归档、移除、设置的哨兵搜索、技能列表的弹层与键盘、**设置两页与 provider 表单的整条路**、
**composer 下面那条状态条**、**附件的粘贴 / 拖放 / `+` 三条路与两句拒话**、
**顶部品牌行与会话标题**——后者量的是顶栏那两行（标题 / 页签）在不在同一个 48px 块里、
两条 `border-b` 落不落在同一个 y、折起来时两行让不让开浮标、点会话标题与 tab 换不换，
全是渲染看不到布局的那一格）、**侧栏行上的标题**（新建任务先是 thread-id、发第一句**不点刷新**
行上就变成那句话、第二句不改写它、hover 的 tooltip 是完整 id、**刷新页面之后还在**——最后这一格
才是库那一列在起作用）、**一块只画 5 行**（一个项目与任务那一块各造 6 条以上：只画 5 行 + `还有 N 个`、
点开画全、再点收回 5 行；控件文字与行标题同一个 x；选中第 6 条之后刷新，那一块仍画全、当前那一行还在、
且没有折叠控件；「已归档」6 条全画、没有控件）、**只读库的那份列表**（新建任务**一行业都不出现**、库里也没有新行、
第一句之后才出现且在**最上面**、项目里的新会话落在**那个项目**下且库里那行 `project_id` 正确、
行上没有体积、刷新之后行仍在；缩进是量出来的：槽 14px、标题 x 与项目名 x 相等、跑起来时 spinner
落在槽里而标题 x 不动）、**折叠的两种样子**（宽窗 48px rail 里那四颗图标是不是只有图标、
顶格 hover 换不换得出「展开」、列表 `hidden` 之后还在不在 DOM 里、窄窗那 48px 有没有整个消失
而浮标回到 (8,8)：全是渲染看不到布局的那一格），不是自动化套件。走查与量到的数在
`.scratch/brand-header/spec.md`、`.scratch/sidebar-rail/spec.md`、
`.scratch/session-titles-in-the-store/spec.md`、`.scratch/store-backed-sidebar/spec.md` 与
`.scratch/sidebar-five-rows/spec.md`。

### 设置面板：两页，两页都会写

**General**（本会话在用什么 + **默认档**三个控件）与 **Models**（provider 目录与表单）。
页面选择是组件里的一个 `useState`，**不是路由**——不引路由依赖，URL 指不到某一页。

- **两页都会写**：General 的 Save 写 `config.edn` 的 `:default`，Models 的表单写 `:providers`
  （以及，填了密钥时，`.env` 的一行）。
- **这一页的下拉仍是原生 `<select>`**，与 composer 那四个不一样：这里是一张表单，选项是四五条，
  一眼读完，而 composer 那边面对的是三十个项目 / 一年的分支 / 一整个厂商目录（见上）。
- **曾经还有两页**（「API key」与「Config home」），主人看过后删掉了：它们报的东西——密钥有没有、
  从哪来、是哪一行、家目录在哪、哪几份文件在——Models 的每一行（`ACME_GATEWAY_API_KEY` 与 `key ✓`）
  与 composer 那边已经在眼前，**一页只装已经看得见的东西就是一步多余的路**。
  （历史与理由在 `.scratch/custom-providers/spec.md` 的复议段，本目录只记现在没有它们。）
- **表单不问「哪一行是默认 model」**：没有单选钮，**第一行就是这家厂商的默认 model**（目录要求每个
  provider 声明一个默认 model，而「默认 model」在人心里指的是「一轮跑在哪个 model 上」——
  那件事在 General 设）。控件旁边写明这一条，免得有人以为顺序只是顺序。
- **默认档的模型必须从列表里选**：没有「— 厂商自己的默认 —」这一项。厂商一定有一个默认 model
  （目录不接受没有 model 的 provider），所以那个空选项除了把这句话再说一遍没有别的内容；
  换厂商时控件直接落在新厂商的默认 model 上——服务端本来也会解析到它，控件只是把它说出来。
- **弹窗尺寸是定的，滚动发生在页里**：一份 provider 表单比面板高，会自己长大的 modal 会在人打字时
  把导航与按钮挪走。所以高度定住（`min(30rem, 62vh)`），只有右侧那一页滚。
- **两个请求、两份失败**：`GET /api/settings` 要**解析**配置，`GET /api/providers` 只读它。
  「`:default` 指着一个刚被删掉的 provider」正是那个状态——报告拒答，目录照答——
  所以两边各自失败、各自清空，页面才能既**说出**坏在哪，又留着手把修好它的**控件**。
  读失败时**不留旧值**：一行陈旧的解析结果摆在拒绝句子旁边，是面板一次说两件事。
- **首次跑通的顺序**是它们各自的形状决定的：设置面板 → Models → Add provider → 填 → Create
  → 列表里立刻有它（`catalog` 每轮重读）→ composer 的选择器里也有它（分组标签用**显示名**，
  发出去的仍是 id）→ General 把默认档指过去 → **新会话**从它开始。

## 一条从后端来的注意

`harness.edge.http/*directory-chooser*` 这个测试缝用 `alter-var-root` 而不是 `binding`：
**服务跑在另一个线程上**，`binding` 只改当前线程的动态栈，stub 会被静默忽略。
凡是给「服务端在别的线程上调用」的缝注入替身，都得用 `alter-var-root`。
