# spec: LLM 空闲超时、三次重试，和那帧「不落盘只展示」

主人 2026-09-25 要的：**LLM 超过 500 毫秒没有出数据，就当作它超时断开；每次超时有三次重试机会；
每次超时作为自定义帧显示在会话中；不落盘，只展示。**

## 拍定（主人回答的三个问题）

| # | 问题 | 答案 |
|---|---|---|
| 1 | 已经吐出一部分内容（文字/思考片段）才卡住，要不要也重试？ | **只在「一个字都还没吐出来」时重试**；已经出了半截内容再卡住就**直接报错结束** |
| 2 | 500ms 与 3 次写死还是可配？ | **可配置**：harness.edn `:llm` 可覆盖，默认 **500ms / 3 次** |
| 3 | 判定放哪一层？ | **两层都要**：kernel 层判定重试，真厂商的流式读取层负责**真的把连接断开** |

问 1 的答案不是洁癖，是协议本身：`:text/delta` 已经变成 `TEXT_MESSAGE_CONTENT` 挂在客户端那条
消息上了，重试会往**同一条消息**后面接——「两个答案，而模型只给了一个」。所以「已经出过内容」是
一个**不可重试**的判据，它必须由发出过帧的那一层来记（`model-call-watched` 的 `seen?`）。

## 形状：一层断开，一层判定

**读取层（`harness.kernel.llm/idle-guarded-lines`）——真正的断开。** 厂商的 SSE body 按行读，
两行之间允许 `idleMs`，超过就把 **HttpResponse 的 body 关掉**（`HttpResponseInputStream/close`
会取消这次 exchange，阻塞中的 `readLine` 随即以 `IOException("closed")` 返回），然后抛
`:llm/idle-timeout`。**不是关 `BufferedReader`**：`BufferedReader` 把整条读路径锁在同一个监视器上，
关它只会等那把锁——blocked 的 `readLine` 正握着它。deadline 是**跟着每一行走**的，靠一个守护线程
轮询（`last-at` 是它唯一的输入），所以「每秒吐一行」的流永远不会被掐：被看的是**沉默**，不是时长。
`IOException` 因此不总是失败——watchdog 关的 body 就是这次调用自己的 deadline。

**判定层（`harness.kernel.loop/model-call-watched`）——给不给下一次机会。** 模型调用照旧跑在自己的
线程上（`model-call-stoppable` 的旧职责：stop 不用等一个还在说话的厂商），这里同时等三件事：它、
这个 run 的 stop 开关、以及**空闲 deadline**。第三个是「两层都要」里 kernel 那一半的意义：**一个
什么都说不出来的 provider**（脚本回放卡在一步里、厂商的流开着但没内容）不会抛任何异常，只有这里的
deadline 能把它救出来。它赢了 `alts!!` 之后，这次尝试被**放弃**（`gate` 关掉，之后它说的任何帧都不
转发），判定如下：

- 什么都没发过 + 还有预算 → 写一条 `:model/end`（被放弃的那次调用自己的段要闭合，**记录按顺序把
  `model/start` 与 `model/end` 配对**），发一帧 `:model/timeout`，**重试**；
- 已经发过东西 → 发一帧 `:model/timeout`（`retrying false`、`emitted true`），**本 run 结束**；
- 预算用完 → 同上，只是句子不同。

预算是**一次模型调用**的：`attempt < 1 + limit`，所以默认 3 次重试 = **最多 4 次尝试**。

### 一个必须写下来的边界：deadline 由「调用开始」上弦，不由「这次尝试开始」

`model-call!` 在 emit `:model/start` **之前**先 resolve 这个会话的工具表，而这一 resolve 正是
**启动会话的 MCP server** 的时刻（`harness.cap.mcp`）：命令不存在的 server 要花 OS 说不存在的
那段时间，慢机器上超过半秒，和厂商一点关系都没有。**实测被这条打过一次**：一条 broken MCP server
要 ~600ms 才失败的 run，在一个字节都还没发出去之前就死在「the model produced no data for 500 ms」
（`mcp-wired-test/a-server-that-will-not-start-does-not-break-the-run`）。

所以 `last-at` 一开始是 **nil（未上弦）**，`:model/start` 才把它点上（`alive` 那个集合），而
`await-call` 里**未上弦的钟照旧会被轮询**（`poll-ms` 50ms）——只挂「已上弦才设 timer」的写法会
睡过那次上弦，永远不响（实测就卡在那里）。上弦之后钟覆盖的正是该覆盖的：建连、厂商 prefill、以及
流里两个 token 之间的每一段沉默。

## 那个自定义帧：会话里看得见，记录里一个字都没有

- 事件：`harness.kernel.event/model-timeout`（`:idle-ms` / `:attempt` / `:limit` / `:retrying` /
  `:emitted`），与 `model/start`、`model/end` 并列的第三件事——它说的是「这次调用因为沉默被杀掉」，
  那两件说不出来。
- 帧：`harness.edge.ag_ui` 的 `:model/timeout` 分支，CUSTOM 帧 `llm-timeout`（`timeout-part-name`），
  值就是上面那几个字段。**没有 `messageId`**：适配器把 CUSTOM 帧的 id 扔掉（`injected-frame` 早就
  把这条测量写下来了），带上就是骗人。
- **不落盘**：`harness.edge.http/wire-only-frame?`。两个帧 sink（主 agent 的 `runner`、subagent 的）
  都跳过它：**不写 jsonl**，但仍然 broadcast、仍然进会话内存（`settle!` 折叠时被
  `harness.kernel.frames/apply-frames` 当作不认识的 CUSTOM 丢掉）。它**故意不在**
  `harness.edge.replay/wire-custom-names` 里——那张表是「一行可以携带的帧名」，而这一帧**永远不成
  为一行**。
- **与 `event-persistence`（text/snapshot）合流时的那一处冲突**：两个特征都改「哪些帧进记录」这
  一小段。**两边都保住**——两条 sink（主 agent 的 `runner`、subagent 的）现在都先问
  `text-lines`（哪些 **行**），再对每一行问那两个例外（`reasoning-frame?`、`wire-only-frame?`）。
  顺序有意义：`text-lines` 有状态（一张消息一个快照），**每一帧都必须过它一次**；例外判在它的**结果**
  上，因为「这一帧根本不进行」说的是整帧，而快照是它答出来的那一行。
- UI：`ui/src/lib/llm-timeout.ts`（算术纯函数）+ `ui/src/components/llm-timeout-card.tsx`
  （`makeAssistantDataUI`「挂载即注册」，在 `app.tsx` 里挨着 `<ContextCards />`）。折叠一行，
  标题左侧「模型超时 · 500ms 没有数据」，右侧「重试 1/3」或「不再重试」，展开是一句说明。
- 刷新之后这张卡不会回来，这是**特性不是缺陷**：它是关于一次在飞行的调用的事实，不是会话的一部分。

## 配置（`harness.edge.llm-timeout`）

```clojure
{:llm {:idle-timeout-ms      500   ; 0 表示不设守卫
       :idle-timeout-retries 3}}   ; 首次尝试之后的次数
```

`harness.edn` 的 `:llm` 块，项目层盖用户层，**逐键合并**（`:editing`、`:compaction` 同一套）。
每次调用现读，不是分数或负数就**指名拒绝**（0 是值不是笔误）。默认值住在 kernel
（`harness.kernel.llm/default-idle-timeout-ms`）：只有一处说「这个数是什么意思」。

**没有 `:llm` 块的 run 就不设守卫**——「没人说」不是「0 毫秒」，所以离线 run 与单测的桩不会被
500ms 掐掉。边子（`run-agent!`、`run-subagent!`、压缩摘要那条路）总是把值递下去，生产永远有守卫。

## 非目标

- **不给「已经出过内容」的那次超时做重试**（问 1 的答案）。
- **不改 stop 的语义**：stop 仍然最高优先（`(:stopped? answer)` 在空闲判定之前），
  被放弃的调用仍然只是「不再听它」。
- **不把这一帧写进记录、也不在 rebuild 里恢复它**。
- **不动 `model/start` / `model/end` 的配对规则**：每次尝试自己一对，被放弃的那次由 loop 补上它
  自己那个 `:model/end`（空 telemetry，正是「死在半路的调用什么都不报」的写法）。

## 走查

```bash
node scripts/dev.mjs --scripted .scratch/llm-idle-timeout/walk-timeout.json
```

脚本带 `pace-ms: 800`：每一块文本要 800ms 才到，而守卫是 500ms，所以**四次尝试全部超时**。
这同时是「两层都要」的证据：脚本 provider（`:fake`）**根本不走 HTTP 读取层**，掐它的只有
kernel 的那个 deadline。

**已走过（2026-09-26，真浏览器）**：真 WS 上收到的帧是

```json
{"type":"CUSTOM","name":"llm-timeout","value":{"idleMs":500,"attempt":1,"limit":3,
 "retrying":true,"emitted":false},"seq":2,"threadId":"533c3a4c-…"}
```

会话里那一行是「**模型超时 · 500ms 没有数据**」+ 右侧「**重试 n/3**」，展开是那句说明
（「模型已经 500ms 没有吐任何数据，服务端按连接断开处理，把这次调用掐掉了。这是第 1 次尝试，
最多允许重试 3 次。」），见 `evidence/walk-timeout-card.png`；同一条会话的 jsonl 里
`llm-timeout` **一处都没有**（`rg -c llm-timeout` 答 exit 1）。

### 走查量出来的一个边界，写在这里而不是留给下一个人发现

**这一帧是「正在看的那一眼」的事实，记录一接手它就没了。** 会话栏由**记录**（窗口）供数，
而窗口给出的那一轮 message 与直播那条**同 id**，于是窗口一刷新就把这张卡换掉——这正是
「不落盘」的另一面，不是 bug。实测采样（同一场 run，每 100ms 看一次 DOM）：卡片**随着每次超时
一张张加上去**（1 张 → 2 张 → …），run 一结束、窗口把那一轮换成记录里的版本，就一起消失，
只剩 terminal 那句话里的同一件事。要让卡片活过刷新，只能让它进记录，而主人明确说了不落盘。
