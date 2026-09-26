# spec: Agent 事件持久化 —— 把主人那份方案对着本仓读一遍

主人（2026-09-25）给了一份完整蓝图（关键事件同步组提交 / 有界队列背压 / JSONL 为真相 /
SQLite 投影 / 崩溃恢复 / 监控）。这一份**不是照抄它**，而是逐条对本仓现状与已有裁定的核对：
哪些已经是这样、哪些冲突、哪些是它确实有而本仓没有的。

## 一、已经是现状的（不用做）

| 蓝图里的东西 | 本仓今天 |
|---|---|
| 每会话一份追加式 JSONL | ✔ `projects/<workspace>/<thread>.jsonl`（`.scratch/jsonl-two-kinds` 两种行） |
| 一个写者 | ✔ 唯一写路径 `harness.edge.http/log!` → `harness.edge.record` 的写手线程 |
| 残缺尾部安全截断 | ✔ `rows-tolerating-a-torn-last-line`（读侧）、`record-state`（完整性判断） |
| 崩溃恢复（半行 / 坏行 / 前缀） | ✔ `replay/read-records`（宽松）与 `lines->records`（严格）两条读法 |
| 组提交的替身：有序前缀 + 降级 | ✔ `record.clj`：失败不跳过、队列停在原地、thread 标 DEGRADED、run 照跑 |
| 推给客户端的增量 + 缺口补齐 | ✔ `events.mux` 的 `runSince` + 窗口的 `seq`/`generation`/`since` |
| 「库只装可改写的状态」 | ✔ `harness.infra.db`（项目、会话归属、锚点、待办） |

## 二、与本仓成文裁定**冲突**的四条（每条都要落 ADR 才能改）

1. **「关键事件等 ack（fsync 后才继续）」** ↔ ADR 0002 决策 3（记录异步、允许落后）。
   **建议：同步 append，但不 fsync、不等 ack。** 同步是为了**当场拿到行号**（票 03 卡的就是它），
   不是为了一致性——run 不靠记录继续（内存是权威，ADR 0002 决策 1），所以「崩溃少丢一批」换
   每个关键事件几毫秒，不值。fsync 留在**会话放下 / 进程退出 / 每 N 批**三个时机。
2. **「SQLite 是可重建的投影，承担一切查询（messages / tool_calls 表）」** ↔
   `.scratch/project-sidebar` 决策 2 与 `docs/architecture/home-and-storage.md` 的
   **「库不是日志索引：jsonl 里的任何内容都不进库」**。
   **建议：只投影位置与统计，不投影内容。** turn/step/entry 的字节区间、以及 `stats`/`context`
   那种可增量维护的累加——这些是**可重算的索引**；而 `messages`/`tool_calls` 是内容的第二份，
   一旦建了，库与记录就得永远同步，且内容从此有两个真相。
3. **「非关键事件队列满可丢弃」** ↔ 记录是**无洞的有序前缀**（`record.clj` 原话：有洞的记录不是
   更短的对话，是另一种、放不回去的对话）。
   **建议：可丢的只有流式 delta，而丢了它就得同时改「重建靠 delta」这件事**——正是
   `.scratch/reasoning-out-of-the-record` 的方向（合并成快照，或干脆不进记录）。
   完整消息 / 工具调用 / 状态变更一律不可丢。
4. **「文件头 + 每行一个显式 `seq` 字段」** ↔ ADR 0003 决策 9（序号必须能从记录**重放**出来）。
   **反对在每行存 `seq`**：行号就是它（`replay/entries` 的 docstring），存一份是同一件事实的第二份
   ——`model/start` 已经因为这个理由拒绝过 call 计数器（「two copies drift」）。
   **文件头是好东西**（格式版本 / 会话身份），今天靠文件名与 `provider/init` 行承载，值得补。

## 三、你那套里本仓确实缺、且不与任何裁定冲突的（值得加）

- **拿着 handle + 攒批**：今天每一行是 `spit :append true` ⇒ **每行一次 open/write/close**。
  真正的省不是省 fsync，是**省 syscall**。这条同时满足「同步」与「便宜」，也是解开票 03 的那一把。
- **有界队列 + 背压**：今天队列是**无界**的，磁盘卡住时它涨内存而不是把压力顶回模型流。
  本仓对应的「上游」是 `harness.kernel.llm` 消费 vendor SSE 的那一处。
- **流式快照（50–100ms 合并）**：最大的一笔（本仓实测：推理 delta 占一份日志的 82%，
  而其中 98% 是帧的壳）。
- **降级四级（L0–L3）+ 那张监控表**：本仓今天只有 `log/info!` 与 `:behind`。
- **退出时 flush_all + 一个结束事件**：本仓有 exit hook，值得核一遍「等它写完」这一步在不在。

## 四、票

| # | 票 | 依赖 | 交付 |
|---|---|---|---|
| 01 | 记录同步写：handle + 攒批 | 无 | 每会话持有 handle；`log!` 在调用者线程上写完并**当场回答行号**；逐行 catch ⇒ 失败仍降级、run 照跑。**它解开票 03 的 `:seq`。** |
| 02 | 有界队列 + 背压 | 01 | 队列有上限；满了顶回上游（消费 vendor SSE 那一处），不是涨内存 |
| 03 | 流式 delta 合并成快照 | 01 | 50–100ms 一次快照；关键事件（消息 / 工具 / 状态）照旧逐条 |
| 04 | fsync 的时机与降级四级 | 01, 02 | 会话放下 / 退出 / 每 N 批；L0–L3 的判据与说出来的那句话 |
| 05 | 监控与退出收口 | 01–04 | 那张指标表 + 退出时 flush_all |
| 06 | 记录文件头 | 03 | 格式版本与会话身份（**不**在每行加 `seq`） |
| ? | SQLite 投影 | 待定 | **只投影位置与统计**，不投影内容 —— 要主人拍 |

## 五、ADR

**一条新 ADR（0007）**：记录**同步写**，反转 ADR 0002 决策 3 的「异步落后」，并且**明确它换来的是
什么**（行号当场可得）与**它没有换来的东西**（更少的写、更快的一致性）。

## 非目标

- 不照抄 asyncio 的形状（本仓是 http-kit 线程池，`docs/rules/concurrency.md` 那套纪律照旧）。
- 不在每行存 `seq`（见二.4）。
- ~~不把内容投影进 SQLite~~ → **主人拍了：连内容一起投影**（ADR 0008）。

## 主人拍的（2026-09-25）

| 问题 | 拍了什么 | 落在哪 |
|---|---|---|
| 记录的写入同步到什么程度 | **同步 append，不 fsync、不等 ack**（fsync 三个时机：会话放下 / 退出 / 每 N 批） | **ADR 0007** |
| SQLite 投不投影内容 | **连内容一起投影**（`messages` / `tool_calls`），推翻「库不是日志索引」 | **ADR 0008** |
| 流式 delta | **合并成快照**（50–100ms 一次），保住「无洞的有序前缀」 | 票 03 |

## 票 01 的落点，以及**必须原样保住**的东西

`harness.edge.record` 的形状要改：**队列、consumer 线程、`serve!`/`consume!`/`retry!`/`start-consumer!`
`/`stop-consumer!` 那一段退役**（ADR 0007 决策 5），`append!` 改成「拿句柄、写一行、就地交出偏移」。

**四样东西一个字都不能动，动了就是另一场事故**：

1. **`prepare`（`prepare-with!`）那步 seam** —— `carry-back!` 靠它「搬回来的那一段先于即将写的这一行」，
   而 `prepare` 会**重算基线**（`re-base!`）：行号的正确性挂在这上面。
2. **`sink`（`set-sink!`）那步 seam** —— 测试用它当探针（写进去的每个字节都看得见），
   去掉它 `record_test` 就没法证明「一行一次写」。
3. **文件行数（`file-lines`）与基线** —— 行号 = 基线 + 已写行数；同步之后它**当场**可用，
   而不是「写手知道、别人猜」。
4. **降级的读侧出口**（`degraded` + 报告出来的那句话）—— 失败现在在调用者身上被接住并记账，
   但**读侧还要能看见并说出来**，否则「写不进去」会变成沉默。

**要一起核的读侧调用点**（它们今天踩在 `pending-count`/`flushed-seq` 上）：`stats-get` 的 `:behind`、
`harness.edge.sessions/evictable?` 的 `pending?`、`replay/fold-sofar` 与条目编号那条路。
**判据**：`record_test` 里「一行一次写、顺序、降级、原地重试」那一族改成同步的等价说法；
`http-test` 与 `stats-test` 全绿；一次 run 的帧循环上多加的微秒数进 `evidence/`。

### 第五样：**锁要往下搬一层**（改之前必须知道）

今天 `harness.edge.http/log!` 自己在 `(locking log-lock …)` 里调 `record/append!`（513 行），
而 `lands` 是**写手线程**调的——也就是说它今天落在任何锁**外面**。

**同步之后 `lands` 会落进 `log-lock` 里面**：它调 `sessions/land!` / `land-at!`（那是会话的锁），
于是「先拿会话锁、再拿 log-lock」的那条路上就是**锁序倒置**。
（`log-lock` 的注释自己写着它管的是「一行写到哪」，而 `project-post` 也拿它。）

**所以票 01 的形状是**：`log!` 在 `log-lock` 里**只解决「写到哪个文件」**，出了锁再调 `record/append!`；
**锁搬进 `record.clj`**（它现在同步了，锁本来就该在这儿），由它**罩住 write+flush**，
写完**先出锁、再调 `lands`**。这与 0007 决策 1（同步）是同一件事的两半：
同步之后，「谁在什么时候拿锁」从写手线程的一个实现细节，变成了调用路径上必须说清的一件事。

## 票 03 的第一次尝试：读侧对了，写侧被**两条折叠**挡住（2026-09-25）——**已由下一节落地**

**读侧落了并量过**：`harness.edge.replay` 的折叠现在能从**run 自己那一行**取回推理——
`reasoning-row?` / `insert-entry-before` / `attach-reasoning`，按「这一 run 的帧造出的第 k 条
assistant 消息 ↔ 这一 run 写下的第 k 条 assistant 行」配对，把推理消息**插在它前面**（与帧当年
的位置、行号都一致）。拿一份手写的最小记录量过：

```
:ids   [u1 r1-m0-r r1-m0]
:roles [user reasoning assistant]
:content [hi THINKING answer]
```

**写侧（不再记录逐 token 的推理帧）没有落**，因为它撞上一条本仓的形状：**同一份记录有两条折叠**——
`harness.edge.replay/entries`（窗口/rebuild 那条）与 `records->messages`（provider 形状的历史：
`replay/history` 与 resume 那条）。配对只写进了前者，后者于是答 `reasoning_content: nil`
（实测：`the-log-the-server-writes-is-one-replay-can-read` 红了）。

**在两条折叠共享一份实现之前，写侧不能动**——再写一份配对正是本仓拒绝的那件事。所以这次只落读侧
（对旧日志零变化：`replay_test` 30 用例 / 175 断言绿）与 `runner` 里那段「为什么还没落」的注释。

**顺序因此是**：先让 `records->messages` 与 `entries` 共享同一遍折叠（它现在只比 `entries` 多一步
`ensure-complete!`），再落写侧，再补那条判据（同一场对话，带推理帧与不带的日志，`entries` 逐字节相等）。

**文本 delta（2%）另算**：它的口径与推理不同——被掐断的 run 靠它才留得住半句答案，所以那一族
按票面写的「快照」落（50–100ms 一次），不是丢掉。

## 票 03 落地：推理帧不再进记录（2026-09-26）

上一节的两条折叠已经共享一份实现——`records->messages` 现在就是 `ensure-complete!` + `fold-frames`，
而 `fold-frames` 是 `(mapv :message (entries records))`——写侧因此能落。这一节记的是**落了什么**、
**凭什么敢落**、以及**它换不走什么**。

**写侧两处，一处规则。** `harness.edge.http` 的两个帧 sink（agent 路线的 `runner` 与子 agent 路线）
都不再把 `REASONING_*` 整族写进记录；帧照旧广播、照旧进会话内存，只是不落行。要丢就丢**整族**：
只丢 CONTENT 的话，`apply-frames` 光凭 START 就造出一条**空的** reasoning 消息，`:reasoned?` 于是
以为「帧已经带过推理」而跳过模型行那一份——这正是这条票之前卡住的那一格。

**读侧的 id 拼法。** `attach-reasoning` 拼 `<run>-r<n>`（`harness.edge.ag-ui` 拆出的思考消息就是这个
拼法，客户端按键取消息）。`runId` 在**行**上，不在 payload 里——payload 是模型那条消息，它不认识 run；
所以函数收整行，从 `(:runId row)` 取。`<n>` 是「这一 run 的第几条带推理的调用」，`RUN_STARTED` 归零。
（第一次尝试拼的是 `<msg-id>-r`，与帧的拼法**不同名**——同一个思考，两条折叠各叫各的名字，客户端按 id 取
消息时就会当成两条。）

**判据。** `harness.edge.replay-test/a-run-without-its-reasoning-frames-rebuilds-the-same-conversation`：
同一场 run 两种写法（带推理帧 / 不带），`entries` 的**消息**与 provider 形状**逐字节相等**（不比 `:seq`，
两种写法的行数不同，行号自然不同——ADR 0003 决策 9）；`harness.edge.http-test` 那条线上帧 vs 记录的
契约用例把 `REASONING_*` 从线上那一侧滤掉（线上有、记录没有，正是这条票说的那件事）。

**走查（证据在 `evidence/`）。** 一份真记录 `39be8804-…`（2026-09-25）：13,631 行、8,640 行是推理帧
（63% 的行）；按写侧那条谓词把整族删掉，3,536,019 → 1,713,228 字节（**51.5%**），而
`replay/history` 两边答出**同 100 条消息**、每条的角色 / 正文长度 / 推理长度 / 工具调用数**逐个相同**，
`(= before after)` 为 **true**（25 条消息带着推理）。脚本 `evidence/03-reasoning-out-of-the-record.clj`
可重跑，`~/.clj-harness` 只读；一次实跑的转写在同名 `.txt`。原始探针留在 `dev/scratch_real_log.clj`。

**测试**：`harness.edge.replay-test` 31 用例 / 178 断言，`harness.edge.http-test` 108 / 1200，
另跑 mux / sessions / frames-route / stats / record / trajectory / delegation / ag-ui / kernel.event
合计 127 / 608 —— 全绿（`~/.clj-harness` 只读，每进程一个临时根）。

### 换不走的东西（知道就好，不在本票内）

- **一条没跑到 `:run/done` 的 run**（崩了 / 被掐断）在旧记录里推理还在帧上；新记录里那一族的推理就
  没有了——模型行要等 `:run/done` 才写。这份真记录里「有推理帧的 run 都有一条模型行」，所以它**没**
  吃到；脚本每次都会把这个数报出来（`would lose their reasoning`）。
- **文本 delta（2%）不在此列**，它照旧逐条写——被掐断的 run 靠它才留得住半句答案；合并成快照那一半
  （票面写的 50–100ms）**还没做**。

## 票 02 落地：背压是组合的性质，不是新代码（2026-09-26）

票面要的「队列有上限、满了顶回上游」**已经由票 01 消解**：无界队列没了，写一行就是一次同步写，而写
它的那条线程读的是 `harness.kernel.loop/run-chan` 的**无缓冲**通道（生产者 `>!!` 阻塞），所以磁盘一
停，消费 vendor SSE 的那一处就停。**它不是一段新代码，是一条要证明的性质**，所以判据落在真实路径上：
`harness.edge.http-test/a-stalled-record-write-holds-the-run-instead-of-buffering-it` 把 sink 卡在
promise 上，断言「只有 1 行被交出去」（异步队列那版会是几十行）与「那一发 POST 还没被答复」
（那版会让 run 先跑完），松手之后记录仍等于线上去掉推理族——不丢、不重、不乱序。ADR 0007 的「落地」
一节记的是同一段。

## 票 04 落地：fsync 的三个时机 + 降级四级（2026-09-26）

**三个时机都成了动作**：每 `record/fsync-every`（**64 行**）在写锁里 force 一次；**会话被放下**时
`harness.kernel.session` 新开的 `:put-away!` 缝（`sweep!` 与 `drop!` 两个门都敲）接到
`record/fsync!`；**进程退出**时 `shutdown!` 对每个写过的文件 force 一次（一个文件一次——承诺是对
文件许的，不是对线程）。

**降级分四级**（`record/health`，每级带 `:says` 那句原话）：**L0 `:ok`** 什么都没扣住；
**L1 `:behind`** 有行被扣住、还没再敲过门；**L2 `:stuck`** 门敲过了、磁盘又拒了；**L3 `:lost`**
写手被拆掉时还有行扣着——**记录此刻比对话短，而此后没有任何一次读能分辨**。1 与 2 是同一场故障的两个
年龄，分开是为了让人不必对一句话脱敏；L3 不是年龄，所以它只能在退出的路上说出来（`give-up!` 一行
`ERROR :record/lost`）。

**fsync 失败不进级别**：字节**在**记录里（读者看得见），悬的只有「崩溃会不会留住它」——那半挂在
`health` 的 `:fsync` 上，因为两件事的证据不同（一个行数、一个 syscall）。**读侧**：`GET /sofar` /
`POST /rebuild` 的 `:record` 多 `:level` / `:says` 两个字段，`:state` 仍是页面认识的那个词。

**判据**：`harness.edge.record-test` + `harness.edge.sessions-test` 合跑 **45 用例 / 204 断言全绿**，
其中 7 条是新的（三个时机各一条、四级一条、一次被拒的承诺、一次重试成功后回到 L0、`:put-away!` 从表那
一侧看的一条）。**承诺的缝**（`set-forcer!`）与 `set-sink!` 同形，理由也一样：fsync 在文件上留不下
痕迹，缝是唯一能把三个时机分开的地方。**要一起核的读侧调用点**（`stats-get` 的 `:behind`、
`evictable?` 的 `pending?`）照旧踩在 `pending-count` 上，一个字没动。

## 还没做：三票，与它们各自要先答的问题（2026-09-26 交班）

票 02 / 04 / 05 已落（上面两节）。下面三张票**一行都没动**，而每一张都带着一个必须先答的问题；
写在这里是为了下一个会话不必重新摸一遍。

### 票 03 的剩余半：文本 delta 合并成 50–100ms 快照

推理族那半已落（ADR 0009）。**剩下的是 `TEXT_MESSAGE_CONTENT`**：今天它与别的帧一样逐条经 `runner`
的 sink 写一行（`harness.edge.http` 里有两个 sink：agent 路线与子 agent 路线）。

**先要答的问题：快照那一行长什么样。** 两条路各有代价，且都要落 ADR 才动：

- **改 `TEXT_MESSAGE_CONTENT` 的 payload**（`delta` 换成「到此刻为止的全文」）：读侧所有折叠都得改成
  「替换」而不是「拼接」，而 `row-of` 的规矩是 payload **逐字节是厂商给的**——写一份改过的就破了这条。
- **新一族（例如 CUSTOM `text/snapshot`）**：不动厂商的 payload，但**缺口补齐**那条路（`events.mux` 的
  `runSince`）会把 CUSTOM 交给客户端，而客户端只认它知道的帧——要么客户端也认这一族，要么补齐时把它
  转回增量的形状。

**合并里必须保住的两件事**：被掐断的 run 靠它才留得住半句答案——所以合并**不能**是「丢掉还没写出的
尾巴」，只能是「把到此刻为止的全文写成一行」；以及「无洞的有序前缀」——行号仍是行数，快照行与别的
行一样占一行。**判据**：同一场 run 两种写法（逐条 / 快照）折出来的对话逐字节相等，外加「一次被掐断
的 run 的最后一份快照里有半句话」。

### 票 06：记录文件头（依赖票 03）

**先要答的问题：这一行由谁拼。** 记录的行形（`{:type .. :payload ..}` 两种行）是
`harness.edge.http/row-of` 一个人的规矩；`record.clj` 自己再拼一份就是第二份规矩。**走缝**
（`set-header!`，与 `prepare-with!` / `set-sink!` 同形）、由装配处（`http/start!`）装进真实的
行形，是这一族既有的形状。

**还有一件必须先看的事**：文件头**多出来一行**，于是 `http_test` 那条契约用例
（`the-record-keeps-every-wire-frame-except-the-reasoning-family`：记录 == 线上去掉推理族）会红——它
要改成「……**再加上文件自己那一行**」。凡是数行数、数事件的地方都要过一遍。

**格式版本描述的是文件的形状**（行种类、信封字段），不是 payload 的词汇——所以票 03 的快照族**不**让
它升版。这也正是它排在票 03 之后仍然成立的理由：它描述的是文件，而不是那份改了词汇的 payload。
**不在每行加 `seq`**（ADR 0003 决策 9）这一条不动。

### SQLite 内容投影（ADR 0008，最大的一张）

ADR 0008 已经把它拍定（连内容一起投影），落地的东西一件都没有：`harness.infra.db` 今天只有项目、会话
归属、锚点、待办那几张表，没有 `messages` / `tool_calls` / `projection_offsets`，也没有投影消费者。

**先要答的问题有三个**：

1. **消费者住在哪、谁启停**：ADR 0008 决策 4 点名了它与 `harness.infra.db` 连接纪律的关系（那里是
   per-call 打开、不缓存句柄），一个长期持有连接的消费者要单独说清；停法也要有（`start!` 回答 stop
   fn 的那一族）。
2. **测试里怎么换成同步实现**：ADR 0008 的代价一节自己要求「要在测试里能换成同步实现」——缝的形状
   （读哪些文件、进度推进到哪里）得先定。
3. **哪些会话被投影**：库里的行是「本项目知道的会话」，而记录文件散在
   `projects/<workspace>/<thread>.jsonl`；投影跟着库走（它知道哪些会话），还是跟着记录树走（扫一遍）？

**判据**（ADR 0008 决策 6）：删掉投影、从记录重放一遍，结果逐行相同；以及决策 5 的「延迟 = 记录
字节数 − 投影 offset」是一个读得出来的数。

## 顺手修的一处：一条用例的等待没盖住「run 自己那几行」（2026-09-26）

`harness.edge.http-test/the-record-keeps-every-wire-frame-except-the-reasoning-family` 只等
`RUN_FINISHED` 那一行，而 `:run/done` 走到消费侧比终止帧**晚一拍**（写那一行的注释自己说着这件事），
于是它后面两条断言（「记录单独就能折回会话」与「折叠把推理拿回来了」）会读到一份还没有 `message` 行的
记录——而推理正是从那几行回来的（ADR 0009）。单独跑这个命名空间一直是绿的；本分支多了 9 条用例之后，
全量里它开始**稳定地**输那一拍（两次全量都落在同一行）。按同族既有的写法（`wait-for-recorded` 的别的用处
等的是 `message` 行）把它改成等 run 的**最后一行**（`script` 自己那句答案）——全量于是只剩
`pressure_test.clj:344` 那条已知红。**这条等待从来没盖住过这一拍**：它踩的是记录自己的时序，不是票
02/04/05 改的东西。

**全量判据（本分支）**：1298 用例 / 13865 断言，**1 红 0 错**，那一条红是 `pressure_test.clj:344`
（`.scratch/compaction-shape/spec.md`「已知、未修」里记着的那条，baseline 也是它）。前端一个 `ui/src`
文件都没改，所以 `npm test` / `npm run build` 不必为这次重跑（baseline 那一遍是 158 通过）。
