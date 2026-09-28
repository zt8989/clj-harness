# spec: 从压缩前的会话 fork 一场新会话

**需求**：压缩不可逆——被折掉的那段从此不再进模型视野。给人一条退路：可以从「**压缩前一刻的
完整记录**」fork 出一场新会话，由它接着那段 history 往下走；原会话不受影响，照样继续、照样再次压缩。

**语义（主人定的）**：

- fork 点 = 某次 `context/compacted` **之前**的完整记录（被折掉的那段都在）。
- **不预存快照**：压缩不改原始行，所以「压缩前」随时可以从记录重建；fork 时现截。
- **记录层与服务端是一件事**，一票交付：截出一条自洽的记录 + 同一步把它登记成能跑的新会话。
- 新会话是一个全新的 thread-id、独立文件、独立 sessions 行；继承项目绑定。
- **标题 = `[fork] ` + 原标题**，两个入口都带（不是 UI 自己拼）。
- 原会话的后续（含它自己的下一次压缩）与 fork 出的会话互不相干。
- **fork 这个动作只能在一轮结束、run 不在跑的时候做**：工具调用中途既没有干净的截点，
  也讲不清一个人为选择的中间态。

## 信封：message 不许脱出事件对

**信封 = 一对 start/end 事件之间的那段区间**（主人，2026-09-27）。记录里每一条 `message` 都必须落在
某个信封之内：

- `model/start` … `model/end`（一次模型调用）
- `tools/pre-execute` … `tools/post-execute`（一次工具调用）
- 一个 run 的第一条 `message` … 该 run 的终局帧
- `step/start` … `step/end`（step-events 落地后的第三级）

脱出信封的 `message` = 坏记录。今天已有的同类机制：`open-runs` / `ensure-complete!`（run 层）、
`closing-frames`（收尾）、`adjacent-answers`（tool_calls 配对）；**step 层由 step-events 补**。

**加载闸门（主人的裁决）**：一场会话在能接受新提问之前，先校验它的记录（每条 `message` 都在信封内）。
任何一条不满足 → **拒绝这一轮**，点名是哪条 `message`、脱在哪个信封外；该会话进入「只能 fork」状态，
只有 fork 重整出来的新会话才能继续提问。**正在跑的 run 不算坏**（文件分不出它和被杀死的 run，
闸门先问进程）。见票 02。

**这道闸门是临时的（主人，2026-09-27）**：它今天存在，只为兼容已经写下的旧 JSONL 记录。等 JSONL 的格式稳定、旧记录退役之后，**整段拆掉**——它不是长期契约。实现时把它圈在一处（一个纯函数 + 一个调用点），并在旁边写下这条退场条件，好让它以后能被干净地拔掉。

## 界面（主人描述）

被 fork 的那场会话里，消息动作条有三个按钮：**复制、刷新、更多**；「更多」的下拉里加一项 **Fork**。
点它 → Project 栏（侧边栏）新增一个 Thread ID，标题与原来的**一致**，前面加 `[fork]`。

## 机制（已摸清）

- 记录文件 = `<projects-dir>/<workspace>/<sanitize(thread-id)>.jsonl`（`harness.infra.home/log-file`），
  `harness.edge.replay/find-log` 按 stem 递归找。
- 第一行是 `record/header {:format :thread :created}`；新文件自己会拿到一份 header，
  `:thread` 必须是新 stem、`:created` 是新时间。
- 新会话必须在 sessions 表里有行，否则 run 入口以 `refuse-unknown-session!` 拒；
  `harness.cap.project/bind!` 一步完成「创建 + 绑定」，`begin-subagent!` 是现成的
  「从另一个会话派生一行」的先例。
- 标题今天唯一的写入口是 `harness.cap.project/remember-send!`（`COALESCE(title, ?)`，只写一次）——
  fork 要立刻带上 `[fork] 原标题`，就得加一个写口或直接写列。
- 列表靠 `harness.edge.host/ring!` 推送（不是轮询）；新会话建好后 ring 一次即可。
- **压缩发生在一次 run 的中途**（`relieve-pressure!` 在每次 model 调用前跑），所以「截到
  `compaction/start` 之前」常常让新记录的最后一个 run **没有终局**——截出来的记录必须先自洽，
  否则 `ensure-complete!` 会拒收。这是本 feature 最容易踩的一格。
- 仓库里目前**没有**任何 clone / fork / derive 会话的代码。

## 票

| # | 票 | 依赖 |
|---|---|---|

## 落地（票 02，2026-09-27）

**加载闸门：message 脱出信封就拒绝继续提问，只允许 fork。**

- `harness.edge.replay/envelope-violations`：按信封报出脱出的地方——**只问 run 这一层**（有头无终局，连同它没答的调用）。**不数 `model/start` / `step/start` 的配对**：那是被停或被杀的一步的样子，而收口（`closing-frames`）写的是终局帧、不写 `model/end`，数它就会把闸门本来要救的会话永久拒掉（2026-09-27，全量 16 红全是这一条）。文件分不出「被停的一步」与「被杀的一步」，run 能。
- `harness.edge.http/torn-record` + `refuse-torn-record!` + `handle-run` 里的一道路（第四道门之后）：**先修再拒**——先 `close-off-open-run!`（进程没在跑时），再读一遍记录；补完仍然脱出（坏记录）才以 409 拒，并点名 `rebuild` 与 `fork` 两条出路。一个正在跑的 run 不是坏的（先问 `running?`）。
- 这是**临时闸门**：只为兼容旧的 JSONL 记录；格式稳定、旧记录退役后整段拆掉（代码里写明退场条件，收在一处）。
- 判据：`harness.edge.fork-test` 的 `the-envelope-gate-sees-only-what-fell-out`（含「被停的那一步不算坏」）；`harness.edge.fork-http-test` 的 `a-crashed-record-is-closed-off-before-the-next-run`（崩过的记录被收口后能继续）与 `a-closed-record-is-not-refused-by-the-gate`。

## 落地（票 03，2026-09-27）

**消息动作条的「更多」菜单里多了一项 Fork。**

- `ui/src/lib/threads.ts/forkThread`：POST `/api/threads/<id>/fork`（可选 `compactionId`），成功答新 thread id，失败抛服务端自己的句子。
- `thread.aui.tsx` 的 `AssistantActionBar`：从 `ThreadIdContext` 取当前会话（复用它，不新造 context——它就是「composer 为哪场会话而作」）；有会话时才画 Fork 项，点了调 `forkThread`，拒绝用 `errors` 目录里的句子说出来（服务端的原话），不静默。
- 新会话由服务端登记，侧边栏靠 host 推送自己出现；原会话那一行不动，也不切走当前视图。
- 文案：`elements-thread` 的 `message.fork` / `message.forkTitle`（中英各一份）。
- 判据：`npm run typecheck`、`npm test`（171 用例）、`npm run build` 全绿。**真机走查（`node scripts/dev.mjs --scripted` + 浏览器）还没做**——机器门挡不住布局，这一条留着给人过。

## 落地（票 01，2026-09-27）

**记录层与服务端是一次交付，落了。**

- `harness.edge.replay/fork-cut`：回答「最近一次**成功**压缩的 `compaction/start` 落在哪一行」
  （`{:cut n :compaction-id id}`）。失败的压缩（`compaction/start` 后没有 `context/compacted`）
  不是 fork 点；指定的 `compactionId` 不存在就拒绝，不猜。
- `harness.edge.http/fork-session!` + `POST /api/threads/<stem>/fork`：把父记录**逐字**复制到截点
  之前，写新 header、一条 `session/forked` 审计；被截断的 run 按 `replay/closing-frames` 收口
  （每个 run 一条 `session/closed-off` + 它的终局帧），继承父的项目绑定，标题当场写成
  `[fork] 原标题`（父没有标题就是 `[fork]`），最后 `host/ring!`。父记录一个字节没动。
- `harness.cap.project/title` 与 `set-title!`：会话名字的读，以及「直接写一列」的口——今天另一个
  写口是 `remember-send!` 的 `COALESCE`，它只写一次，等不到第一次 send 的 fork 需要这一个。
- 路由的门：会话有 run 在跑 → 409；未知会话 → 404（`refuse-unknown-session!`）；没有压缩点 → 400。
- 判据：`harness.edge.fork-test`（cut 落点、失败压缩不是 fork 点、未知 id 拒绝）与
  `harness.edge.fork-http-test`（端到端：新记录只到压缩前、读得出来、run 中途的截断被收口、
  标题 `[fork]`、父不动、无压缩点 400、未知会话 404）。
