# 面板的数据：先拉一次存量，之后由推送走

**屏幕上一栏里的一串东西——左边 Projects、右边子代理与作业、composer 上下的条子——都是同一条路：
第一次拉一次存量（一次 GET，答「现在是什么」），之后一律由服务端的推送改它。
不许拿定时器去问一个已经能被推的东西。**

## 为什么

1. **一个答案只能有一个时钟。** 轮询的间隔是一段「可能已经变了、也可能没有」的时间；两栏各自按自己的
   滴答换内容，读起来就是同一时刻的两个事实。这条最早的写法在 `.scratch/composer-status/spec.md` 决策 1。
2. **「和刚才一样」不值得问一千遍。** 一秒一问的面板，一秒钟里有 999 次答案与上一份逐字相同——
   一次请求、服务端的一次读、以及一段谁也没要过的延迟。
3. **推送会丢，存量不会。** 推送那一半**可以被漏掉**（socket 断了、页面睡了、这一帧发出时没人在听），
   存量那一半不会。所以两半缺一不可，而分工是死的：**存量负责「现在是什么」，推送负责「刚刚变了什么」。**

## 存量那一半

- **挂载时一次 GET**，以及**看的东西变了时一次**（换会话、换项目 = 换了一份存量）。
- 同一栏里几段读**同一个时刻**的，共用**一次**读与**一个**中止——不许每段各发一份、各自落地。
- 迟到的答案**不许盖到**新选的那一场上（`ui/src/components/composer-numbers.tsx` 那个 `live` 旗子）。
- 读**不写**：一次看一眼就写一行审计的路由，会被这一半灌满。

## 推送那一半

- **一条 socket，按 `threadId` 路由**：`ui/src/lib/mux.ts`（ADR 0004）。今天的家族是五个：
  window（这一页手里那份对话）、run（正在跑的 run 的 AG-UI 事件）、fact（`turn/*` `model/*` `step/*`）、
  task（右栏那一份载荷）、trajectory（模型每一轮看到了什么）。
- **推的是「变了」，不是「变了之后的全部」**：谁要画它，谁把自己那一格状态改掉。
- **一个新的可推对象 = 服务端要有一个写点**挂在它上面（一轮 run 的边、一次工具调用、一个进程的结局）。
  没有写点就推不了——那就先问「它凭什么没有写点」，**而不是先开一个定时器**。

## 断了怎么办

推送会丢，所以**重连之后必须补一次存量**：socket 重开时重新声明订阅并带上游标
（`ui/src/lib/mux.ts` 的 `declaredSet`：window 带 `since`，fact 带 `factSince`，run 带 `runSince`，
trajectory 没有游标——重新声明就是重发一次开场快照，那份快照就是它的存量），
而**游标之外**的事实由下一次快照补齐——`ui/src/components/composer-numbers.tsx` 里那句
「一页打开一场会话就问一次 `/stats`」就是这半条。**没有这一段，这条原则只是把轮询换成了丢帧。**

## 例外：只有一种

- **服务端给不出的「此刻」**——比如一条进程内正在走的秒表（「这条作业跑了 4.2 秒」）。
  推送推得出「它变了」，推不出一个连续的量。画那个数字的组件**自己滴答**，但它**不发起任何请求**。
- 除这一类，**没有第二种例外**。

## 今天谁这么做了（这份文件就是它们的规矩）

**没有欠账了**——五处都按上面那两半走（2026-09-29 复查过每一条都落在代码里）：

- **左侧清单**：挂载一次 `GET /api/projects`，之后每一个 host 级变化（run 起止、别窗发送、项目增删、
  归档）由 `events.host` 推来（`ui/src/components/sidebar.tsx` + `ui/src/lib/host.ts`，ADR 0004）。
  **唯一还会再问一次的只有本页自己刚写的那些**（懒创建那一格：「这个 id 我铸了、库还没答」），
  别的行一律交给推送——listing 里别人的行曾经也被算进那次追问，那就是每 400ms 一次轮询的来路，
  2026-09-29 删掉（`ui/src/lib/sidebar-refetch.ts` 与 `.scratch/sidebar-ws-and-run-state/spec.md` 的追记）。
- **右侧任务视图**：挂载一次快照，之后作业的出现与结局、委派的开始与结束走同一条 socket 的 `task` 帧
  （`ui/src/hooks/use-task-pane.ts` + `harness.edge.http/task-send!`）。原来那个 `setInterval(read, 1000)`
  没了；留下的每秒一次只推进**正在跑的**行的时长，**它不发起任何请求**——就是本文件那一条例外。
- **composer 的项目目录选择器**：**不自己读列表**（2026-09-29）。它要的是「这个家有哪几个项目」，
  而页面手里已经有那一份（侧栏那次存量 + 每条推送），于是它从 `components/composer-chrome.tsx` 的
  `SidebarProjectsContext` 取——由 `app.tsx` 的 `onListed` 填，绑定时也不重拉（`POST /api/project`
  自己 ring 一次 host 流）。在它之前，每一个还没开聊的会话的 composer 都会各发一次 `GET /api/projects`。
- **composer 下面那条统计条**：挂载一次 `GET …/stats`，之后 `model/end` 推着走，
  `ui/src/components/composer-numbers.tsx` 明写 `THERE IS NO POLLING`。本文件是从它开始写的。
- **trajectory 那一栏**（`.scratch/memory-hygiene/` 票 02，2026-09-29；**改走下行 socket**：票见
  `.scratch/trajectory-on-the-downlink/`）：存量与推送都走**同一条 socket 的第五族帧**
  （`{:type "trajectory"}`）——挂载即订阅（订阅声明里带 `trajectory: true`），开场帧带 `:snapshot`
  （整份折），其后每帧只带「自上次以来最终化的轮 + 当前那一轮」，头（`:incomplete` / `:behind`）
  每帧都到。`ui/src/components/trajectory-view.tsx` 是唯一的读者；它**不再有自己的长响应**，
  也不再靠 `onDownlinkOpen` 重开——重连时握手重新声明整份集合，服务端照发开场帧，那就是补的存量。

**没有欠账了**——上面五处都按那两半走。把前四处从欠账改过来的票在 `.scratch/panel-data-push/`（右栏与
左栏那一半）；目录选择器那一处是 2026-09-29 随手合上的，trajectory 那一处同一天随
`.scratch/memory-hygiene/` 票 02 一起（它本来就在推，缺的是「重连之后补一次」）。
**trajectory 现在正从那两半的例外改回正常的一格**（第五族帧，`.scratch/trajectory-on-the-downlink/`
票 01–03）：上面那一条按**目标状态**写，落地之前它的读者还是 `GET …/trajectory` 那条长响应。
