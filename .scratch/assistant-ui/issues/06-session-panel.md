# 06 — 会话：列表、恢复、新建

**Status: done** — `ui/src/components/session-panel.tsx`（新）、`ui/src/lib/threads.ts`（新：两个管理端点的
瘦封装 + `AGENT_URL` 归一处）、`ui/src/app.tsx`（threadList 适配器 + threadId 归主变更 + 布局改列）。
**服务端零改动**（e2e server 本就复用 `harness.http/start!`，`/api/threads` 与 rebuild 是生产 handler 自带）。
三关全绿，真 Chromium 全主线走完，截图 `t06-01..03`。

## 路的取舍：adapters.threadList，理由一次说清

两条路里 `adapters.history` 是「一个线程、页面加载时恢复」的形状，多线程来回切要在它上面手搓一切；
`adapters.threadList` 就是「多个线程、来回切」本形——runtime 负责清场、调出、灌回，我们只供
「id → 消息」一个函数。代价是它标着 experimental（core 类型注释原话 "might change without notice"），
已按票面写进 spec.md 已知风险。

- 列表**不走** adapter 的 `threads` 字段：面板要显示最后活动时间与体积，adapter 的线程形状没有这两个
  字段。砍显示凑组件是本末倒置，所以面板自己 `GET /api/threads`，adapter 只当切换的脊椎。
- 恢复的转换是 `fromAgUiMessages` → `fromThreadMessageLike` 两步，与 runtime 自己的快照导入路径
  （`importMessagesSnapshot`）逐字相同，引上游而非另写。

## threadId 归主变更（决策 6 的续篇）

id 的主人从 agent 挪到了 **`App` 的 React state**：新建时 `crypto.randomUUID()` 铸新 id（与 HttpAgent
自铸的形状一致），agent 被写回——`adoptThread` 在**任何 await 之前**同步写 `agent.threadId` 并
setState，这是 adapter 文档的硬规则（先设 id 再等历史，被取代的切换其消息会被丢弃）。运行时每个
run 仍从 agent 读 id（`buildRunInput`），线上的形状一个字没变。spec.md 决策 6 已补终稿段。

## 验收主线（全在日志上对账）

| 步 | 结果 |
|---|---|
| 起一轮、再起一轮 | 一个 agent 一个文件，页面 id 与文件名逐字相同 |
| 新建会话 | 新 id、空消息，下一轮落在**新文件**（7 KB，旧文件未动） |
| 恢复旧会话 | 两条历史回屏，服务端 rebuild 落 `session/rebuilt` 审计行（日志 +294 B） |
| **续聊追加** | 恢复后发一句 → **旧文件** 15 800 → 28 127 B（bash 帧 13 处），新会话文件未动——验收主线达成 |
| run 进行中点恢复 | 行下原话拒绝："A run is in progress; switching is refused until it settles."（`t06-01` 顶部可见） |
| 损坏日志 | 服务端 400 的原话逐字显示在该行下："line 25 of the log is not valid JSON (JSON error (end-of-file inside string)) -- the log is truncated or corrupt"；其余行仍可点、点后仍能恢复（`t06-03`） |
| 刷新 | Refresh 后新文件出现、审计行使体积与顺序跟着变；切换与线程变化也各自触发刷新 |
| 当前会话可辨认 | 当前行高亮、标 `· current`、禁点；头部横排当前 id 全文 |

三关：tsc 0 error；build 绿（CSS 84.72 kB / JS 1 253.41 kB，gzip 355.58 kB）；11 tests passed。控制台
除 vite 连接日志外零输出。

## 恢复 parked 会话：实测不支持，按票面如实记录

票面预期 `fromAgUiMessages` 会把审批状态读回来。**实测不成立**，链条每一环都验过：

1. 服务端重建时 `frames.clj` 的 `apply-frames` **忽略 RUN_FINISHED**——中断只存在于该帧的 outcome 里，
   重建出的消息不带 `metadata.custom.agui.interrupts`。折进去是 JVM 侧改动，被非目标
   「JVM 侧一行不动」禁止，所以不是客户端能补的洞。
2. 恢复后：parked write 的卡显示 `Needs approval`（由「part 无 result → requires-action/tool-calls」推导，
   恰好读起来是对的），但**审批卡不出、composer 不 hold**——中断缝是空的，卡拒绝假装有门可批
   （`t06-02` 截图）。
3. 恢复后发送，两道真实的防线接住：本地 runtime 把这个无结果调用按未决客户端工具调用自动取消
   （卡翻 `Failed`），且 **@ag-ui/client 拒发该 run**——agent 层记着 park（页面没换，agent 活着），
   `onInitialize` 抛 "Thread has 1 pending interrupt(s) not addressed by resume: <id>"，即上游错误横幅。
   没有任何假续聊发生；但「恢复即无法再批」是事实，修法是服务端折叠中断，属下一特征的决定。

一个推论（未实测，明确标注）：换新页面（新 agent）再恢复同一 parked 会话，agent 层的这道防线不在，
run 大概率照常起、park 悬在服务端进程内存里——与 05 落地说明「刷新即弃决定」一致。

## 两个已知的形状代价（都试过、都记录）

- **失败恢复清空当前视图。** wrapper 先清后取（上游行为）：rebuild 被拒时当前 transcript 已经空了。
  行上原因仍在，重开别行或重试同一行即可回来（失败后恢复 T1 实测正常）。
- **列表是快照不是流。** 切换线程那一刻刷一次；新会话的第一轮 run 在刷新之后落地的话，要点一次
  Refresh 才出现。自动跟平需要订阅日志目录，那不是这张面板的事。

## 给 07 的接点

面板列占了 `flex h-dvh flex-col` 的头部，thread 的 `h-full` 根在 `min-h-0 flex-1` 里——07 的项目面板
照这个模式加一行即可，别再嵌套 `h-dvh`。另外 `/api/project/pick`（原生对话框）与 `/api/project` 的
形状我已在 `http.clj` 读过：pick 返回 `{threadId, dir}`（在 `project-post` 里绑定并落审计），07 的
「只填字段不直接改绑定」要从 pick 的 200 里拿路径自己填，别替用户点绑定。
