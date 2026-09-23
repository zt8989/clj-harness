# spec: 新建的会话，出现在左侧的那一刻

2026-09-22 立。**要的是：从项目里新建一场会话，发送之后它立刻出现在左侧 —— 而且在那之前，
除了这一页自己的记忆以外，任何地方都没有它。**

主人的原话：

> 从项目里新建存在bug，会自动创建一个不存在的threadid，且发送消息后，在左侧不立即出现，刷新后才出现

对着代码与主人自己的库核过，这是**两件真的事**，不是一个症状的两种说法。

## 一、一个 pick 就写出了一场不存在的会话

`点击新增不立刻会话，发送才新建`（`.scratch/store-backed-sidebar`）落地时，改的是侧栏的两个
按钮：`新建任务` 与项目行的 `新建会话` 都只 mint 一个 id，交给页面（`onShowFresh` → `pendingBinds`），
第一次发送的 `registerPending` 才 bind。

**composer 里的「项目目录」选择器是漏掉的那扇门。** 它走的是 `bindThread`（`POST /api/project`），
而那个 route 的服务端实现是 **find-or-create**（`cap.project/bind!` → `touch-session!`，一条
`INSERT ... ON CONFLICT DO UPDATE`）。于是：在一个刚 mint、还没有人说过话的会话上选一次目录，
就写出了

* 一行 `sessions`（有 `project_id`、有 `path`，`title` 与 `last_sent_at` 都是 NULL），以及
* 一个 **160 字节的日志**，里面唯一一行是 bind 的审计行：

```json
{"ts":…,"runId":null,"kind":"project/bound","payload":{"before":null,"after":"/Users/…","via":"http"}}
```

**主人自己的 `~/.clj-harness/harness.db` 里就有这样的行**（只读核过）：

| id | project_id | title | last_sent_at | 日志 |
|---|---|---|---|---|
| `23732754-e353-4747-a29f-e93bafaf8cbd` | 6 | NULL | NULL | 1 行，只有 `project/bound` |
| `d62a2cd9-04b8-4eaf-b278-eafe40244882` | 6 | NULL | NULL | 首行就是 `project/bound`，之后才有 run |

第一行那种就是「一个不存在的 threadId」：库里有一行、盘上有一个文件（因此 `/api/threads/<id>/stats`
答 200），而后面没有任何对话。每一次刷新之后，它都会以「还没跑过」的样子出现在侧栏里 ——
正是 `发送才新建` 要消掉的那种行。

**决定：held 的会话，pick 只记不写。** 页面的 `pendingBinds` 本来就是第一次发送要读的那张表
（`onShowFresh` 写的），所以 composer 只是把目录交给它：`HeldSessionContext` 给出
`{dir, remember}`，`rebind` 对 held 的会话调 `remember`、不发请求（因此也没有失败可报）。
第一次发送照旧 bind —— 用记下来的那个目录。

## 二、行没出现，是因为等错了东西（而且只问了一次）

侧栏那一段「我手里这场会话库还没列出来」的补读（`components/sidebar.tsx` 的 effect）有两个错，
各自都足以造成「发送之后不出现，刷新才出现」：

1. **它在等 run 结束。** 条件是 `!(statuses[id] ?? IDLE).running`。而**行不是 run 写的**——
   是 run 之前那次 `registerPending` 的注册写的（run 的 `onReady` 要 await 它）。于是行被扣住
   的时间 = 模型答一轮的时间：主人真用起来是几十秒，期间刷新一下（库里那行早就在了）就看见了。
   这正是「不立即出现，刷新后才出现」。
2. **它一个 id 只问一次**（`asked` 是个 Set，`ONCE PER ID PER PAGE LOAD`）。而那次读是在写
   落地之前发出去的：一次读没拿到那一行，id 就烧掉了，这一页之后再也不会为它补读 —— 只有按钮、
   切换、刷新才救得回来。原注释写的是「省得 effect 一直问」，代价是**一次抢跑的读 = 一行永远不出现**。

**决定：问，而且问得对。**

* 一有标题就问，**不等 run**；
* 一个 id 给 `ASK_AGAIN_LIMIT`（5）次机会，第一次立刻，之后每次隔 `ASK_AGAIN_AFTER_MS`（400ms）
  —— 同一瞬间连问几次只会一起落空，所以第二次必须等；
* **「行在不在」不是「行写完了没有」**：行的名字与发送时间是 run 的输入到达时写的（第二次写），
  所以列表里那行 `last_sent_at` 还是 NULL、而这一页已经有它的标题时，**照样再问一次**。
  否则新行会以「还没跑过」定在屏幕上，直到别的事情顺带刷新；
* **行一旦被列表列出来，它的 id 就从「这一页 mint 的标题」里走了**（`forgetListedTitles` 把名字的
  所有权交还给库），所以这条规则要问的候选**不止那些标题**：列表里说 `running`、而这一页
  自己的注册表说不在跑的**行本身也是候选**。否则快照里那句 `running` 扣在行上的 spinner 永远
  没人来摘 —— 后台那一格（走查第 4 步收尾）就是这么红的。

规则本身是纯的（`ui/src/lib/sidebar-refetch.ts`，零 import），套件
（`ui/test/suites/sidebar-refetch.ts`，10 例）钉的是「几次、多久、下一个是谁」。
`components/sidebar.tsx` 进不了 vitest（它 import 到 `lib/i18n.ts`，摸 `document`），所以
「行真的在屏幕上、而且是在 run 还在跑的时候」只能走查：
`.scratch/new-session-appears/walkthrough.mjs`。

## 三、走查怎么把这两件事变成会红的格子

脚本的第一轮是一个 `bash` 调用 `sleep 6`（`.scratch/new-session-appears/script.json`），
于是 run 会**在飞了几秒**，格子才有机会说「行到的时候 spinner 还在」：

```
node scripts/dev.mjs --scripted .scratch/new-session-appears/script.json --ui-port 5221
node .scratch/new-session-appears/walkthrough.mjs http://localhost:5221/
```

* `composer 里选目录不写任何东西`（`/api/projects` 没有新行 + `/api/threads/<id>/stats` 还是 404）
* `第一次发送之后行自己出现，没有点刷新`
* `…而且它到的时候 run 还在跑`（那一行里 spinner 还在）
* `…库里那行落在 pick 记下来的那个项目里`

**改之前这三格是红的**（同一条走查、同一对进程，把三个源文件 stash 掉跑了一遍）：

```
RED  picking that project in the composer writes no row either -- […,"53d9ef60-…"]
RED  ...and still no log for it -- 53d9ef60-…
RED  ...and it arrived WHILE THE RUN WAS STILL GOING (the row carries its spinner) -- {"running":false}
```

顺带记一笔（**没动，也不属于这次**）：主人库里那两行有整场对话却 `title`/`last_sent_at` 仍为 NULL
的会话（`d62a2cd9-…`、`2754772f-…`），说明有一次 run 的 `remember-send!` 没有落到它的行上。
那是另一件事，需要它自己的现场。