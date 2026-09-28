# spec: 记录长成三层信封 —— 轮 / 步 / （一次模型调用 · 一次工具调用）

**Status: ready-for-agent**

主人 2026-09-27 拍定：

1. **Turn 的生命周期要包裹这一轮的全部；**
2. **Model 的生命周期要包裹请求；**
3. **Tool 的生命周期要包括工具结果；**
4. **`step` 那一族也要进来**——一步 = 一次模型请求 + 它调的那些工具，它把请求与返回裹进自己的信封。

第 4 句把 `.scratch/step-events/` 那份（在 `step-events` 分支上，**未合进 main**，只有一个 commit：
spec + 六张票，没有代码）并进本目录。**两份之间有两处冲突，按主人更晚的话裁**：

| 冲突 | 裁决（2026-09-27 深夜，按 main 真正落地的结果） |
|---|---|
| `turn/*` 进不进记录 | **不进**——main 的 ADR 0011 明确**保留** 0006 决策 3；轮只在下行包裹这一轮，
  「你没看着的过去」归读侧。本目录原先那句「进」**作废**。 |
| `step/*` 要不要 | **要**，作为第三级。main 已经落地（ADR 0011 + 它的票 02–06），本目录那几张票删掉。 |

起点是一个症状：**一轮正在跑的时候，轨迹上只看得见提问与注入，看不见它在做的工具调用**
（`.scratch/trajectory-live/spec.md` 有复现）。查下去那不是渲染问题，而是**记录的形状**问题——
同一件事在记录里有两份、时机不同：

- **当场写的**：AG-UI 线上帧与内核的审计行（`model/*`、`tools/*`）。
- **押后写的**：**所有 `message` 行**——内核把「这一轮加了哪条消息」攒在 `:added` 里，只在 `:run/done`
  那一个事件里交给边，边一次写完。真实记录的时间戳就是证据：`tools/pre-execute` 到 `RUN_FINISHED`
  之间那 26 秒里，盘上关于这次调用只有审计行，工具与助手的 `message` 行都在终帧**之后**。

而三层边界在记录里都没包住该包住的东西：工具的结果在 `tools/post-execute` 之后；请求在 `model/start`
之前、回答在终帧之后；**步与轮的边界根本不在记录里**——步的边界今天由读者拿 `model/start` 与随后的
`tools/*` 现推，而推法至少两份（`ui/src/lib/turns.ts` 与 `harness.edge.turn`）；轮的边界同样两份
（`harness.edge.stats/user-ids` 与客户端）。**算得出来的不写，算不出来的写**——这是 ADR 0006 自己立的
纪律，它当年对步与轮都判成「算得出来」，这一步把两处都翻过来。

## 决策

1. **记录长成三层信封；一行的位置由「发射它的那一层的边界」决定。**
   `turn/*` 包住这一轮的全部；`step/*` 包住一次请求与它调的那些工具；`model/*` 包住这次请求写下的行
   与它产生的回答；`tools/*` 包住它的结果。**ADR 0006 决策 2（不吃 DSH 的 `step`）与决策 3（轮不进记录）
   都被本版推翻**，理由见上。

2. **（2026-09-27 撤回）** 原先要内核逐条报「加了哪条消息」（新事件）、边收到就在调用者线程上同步写一行。
   **主人否决**：main 的 `step/*` 已经把「这一步调了哪些工具、各自什么结果」当场写进记录与下行，
   再逐条报一遍返回侧的消息是把同一件事说两遍。那个事件、边那套逐条写、以及 `harness.edge.ag-ui/step`
   为它加的分支，**都已退回 main**。

3. **（同上撤回）** `:run/done` 的 `:added` 照旧，一次把返回侧写出来，不是对账表。

4. **Model 信封包住请求。** 这一轮**为这次调用写下**的行——prompt（system）、客户端的提问、这次 run
   派生的注入、以及「请求将发出时有多满」那条压力读数——落在 `[model/start, model/end]` 里。
   做法：边照旧在调用前把它们拼好，但**攒着不写**，等内核报这次调用的 `:model/start` 时一次写下去。
   **代价**：请求不再「发出前就在盘上」，差的是从「请求拼好」到「调用开始」的那几毫秒。
   **只有一轮的第一次调用有这一段**：第二次起，请求的增量（助手消息、工具消息）本来就是别的信封里
   写下的行。

5. **（同上撤回）Tool 信封包住结果。** 结果帧与工具的 `message` 行**不搬**：`tools/*` 那三行本来就把
   这次调用夹在中间（`pre-execute` .. `post-execute`），而那条消息行与别的返回侧一样等 `:run/done`。
   要搬它的理由是「逐条报」那套，那套已撤。

6. **`step/*` 是第三级，两端进记录。** 一步 = 内核 loop 的一次迭代：一次模型请求 + 它调的那些工具。
   - `step/start` 发在 `model-call-watched` 的**外面**（一步的区间比一次调用大）；
   - `step/end` 发在**这一步最后一个工具的结局（`tools/post-execute`）之后**；一次工具也没调的步，
     它紧跟在 `model/end` 之后；
   - `step/end` 的载荷说这一步做了什么：`{:tools [{:id … :name … :outcome …} …]}`，没调的步是**空向量**
     （不是缺键）；它与这一步的 `TOOL_CALL_START` 帧、`tools/*` 行**对得上**（id 与 name 逐字）。
     载荷是「收口那一行带摘要」这条既有纪律的又一处（与 `turn/end` 的 `steps`/`messages` 同理），
     所以**必须有一条用例断言它与区间里的行一致**；
   - **重试算同一步**（`overflow-retries` / 空转重试产生的额外调用也在这一步里）；
   - **被停的步要收口**：记录里不留没有 `step/end` 的 `step/start`；
   - **`run/interrupt` 不是一步的终局**：带着人的答复回来收的是**同一步**，`step/start` 不重发。
   - 身份由记录行号说（`:seq`），**不发明第三个 id**（有 `turnId` 是因为…见 7）。

7. **（2026-09-27 深夜改口）Turn 信封不进记录。** main 的 ADR 0011 明确保留 0006 决策 3：轮只在下行
   包裹这一轮（`turn/*` 带 `steps`/`messages`，读侧用它对齐「你看着的」那一半），**记录里没有**
   `turn/start` / `turn/end` 两行。原先那句「推翻 0006 决策 3」作废；`turnId` 这个名字也就不存在。

8. **（2026-09-27 深夜改口）读侧那份现推不删，它是「你没看着的过去」的唯一主人。** main 已经把这条
   写死：事实**不重放**（一根有界的内存环 + 短缺口补齐），所以「你没看着的那几轮」只有读侧能回答——
   服务端 `turn/end` 的数只拥有它**刚关掉**的那一轮，别的轮归读侧（main 的补/补二实做了这两半，
   并有一条真 run 的共享用例表把两套读法钉在一起）。原先「第二份必须删」作废。

9. **段机的判据改成按 `step/start` / `model/start` 认。** 轨迹今天靠「run 的第一条 `message` 行」开段、
   靠「第一条 `event` 之前 / 之后」切提交侧与返回侧；请求侧的行落进 `model/start` 之后，这两条都失效。
   新判据：**一次调用的请求侧 = 从它自己的 `model/start` 到它产生的第一条帧 / 消息之间的那些 `message` 行**；
   段的边界按 `step/start` / `turn/start` 认。`harness.edge.context` 与 `harness.edge.pressure`
   复用同一台段机，跟着走。

10. **`seq` 的语义一个字不改**（ADR 0003 决策 1/9）：仍然是**那一行自己的行号**。
    三族在同一轮里的**相对偏序**是判据：`turn/start` 在任何 `step/start` 之前；同一步的 `model/*`
    与 `tools/*` 落在这一步的两端之内；`turn/end` 在最后。断言的是**相对顺序**，不是时刻。

11. **事实族的类型表只有一处拼写**（这是并进来的第一张票，也是一切的前置）：
    「哪些帧名是事实」今天至少四处各写了一遍（服务端广播那一处、`test_support`、
    `ui/src/lib/mux.ts` 的 `FACT_TYPES`、`ui/test/e2e.ts` 里又抄一份）。一族两个名字时抄四遍还能忍，
    三族六个名字就是「加了一族、漏改了某一处」的现成剧本——而漏在客户端那一处的后果是
    **这一轮被打死**（事实落进 `run` 那一支，会被 `@ag-ui/client` 按 AG-UI 的 schema 校验）。

12. **旧记录仍然是一等公民。** 没有 `turn/*` / `step/*`、请求在信封外、返回侧押在终帧之后的记录，
    读侧必须两种形状都认（`harness.edge.replay` 对旧 `input` 行就是这么做的）。
    **新写的记录不许再产生旧形状。**

## 记录的形状

**今天**（骨架，`…` 是线上帧）：

```
message system / user / user(注入)
context/pressure
RUN_STARTED … model/start … model/end
tools/pre-execute → tools/execute → tools/post-execute → TOOL_CALL_RESULT
RUN_FINISHED
message assistant / tool / assistant     ← 返回侧整批押在这里
```

**信封之后**：

```
turn/start                                  ← 轮开了（人说了话）
  RUN_STARTED … 帧 …
  step/start                                ← 这一步开了（一次请求 + 它的工具）
    model/start                             ← 这次调用开了
      message system / user / user(注入)     ← 请求：它写下的行都在它自己的信封里
      context/pressure
      …帧…
      message assistant                     ← 调用一返回就落盘
    model/end
    tools/pre-execute
      message tool                          ← 结果一出就落盘
    tools/execute
    tools/post-execute
  step/end   {tools: [{:id "c1" :name "bash" :outcome "pass"}]}
  step/start
    model/start
      message assistant
    model/end
  step/end   {tools: []}
  RUN_FINISHED
turn/end     {turnId, steps, messages, reasoning, seqFrom, seqTo}   ← 这一轮的最后一行
```

## 谁要跟着改

| 层 | 谁 | 改什么 |
|---|---|---|
| 内核 | `harness.kernel.event` / `loop` / `tools` | 新事件「加了一条消息」；`added!` 与 `answer!` 接上发射；`step/*` 两端的发射（在 `model-call-watched` 外面、在最后一个工具的结局之后）；工具的结果帧与它的消息搬进缝（spill 一起搬） |
| 边（写） | `harness.edge.http` / `ag_ui` | 逐条写；请求侧攒到 `model/start`；`turn/*` 与 `step/*` 落行并上会话下行；`:added` 变对账 |
| 边（读） | `harness.edge.stats` / `turn` / `trajectory` / `context` / `pressure` / `replay` | 轮与步改从 `turn/*` / `step/*` 读；段机换判据；两种记录形状都认 |
| 客户端 | `ui/src/lib/mux.ts` / `turns.ts` 等 | 事实族多一族（名字只写一处）；轮与步改从事实来；折叠那一行按步计 |
| 文档 | `docs/adr/0011`（main 已写）、0006（**加注不改写**）、`docs/architecture/{edge,kernel,client}.md`、`CONTEXT.md` | 事实表、记录一节、步与轮的定义 |

## 不做的事

- **不动 AG-UI 的帧词汇**：三族事实**都不是** AG-UI 帧（`harness.edge.ag-ui/step` 对它们仍是空操作，
  它是个穷尽 `case`，漏一个分支就会抛）。
- **不改行的两种类型**（`message` / `event`）与 `:source` 那套。
- **不改 ADR 0007 的同步语义**：仍然不 fsync、当场拿行号。
- **不从落盘门铃发事实**（ADR 0006 决策 6）：事实与记录同一颗钟、同一段顺序。
- **不开第三条 socket**；**不做历史回放**（ADR 0006 决策 7）。
- **不给旧记录补行**：历史是历史。

## 验收的缝

- **最高的缝是记录本身**：真起一场会话、跑一轮带工具调用的脚本，然后**读盘上那份 jsonl 断言行的顺序**
  （三层信封的嵌套与三族的偏序）。先例：`harness.edge.http-test` 的那些真跑 + 真记录用例。
- **第二道缝是轨迹的答案**：`harness.edge.trajectory` 折出来的 `{:turns …}`（跑着的时候看得见调用）。
- **第三道缝是那一条摘要行**：轮末折叠的 `3 步 · 25 条消息`（服务端 `turn/end` 的数与客户端读侧的数
  **同一张用例表**）。
- **断线补齐**：三族在同一条 `seq` 游标上不漏不重。
- **旧记录的形状**：每个读侧各一条「读一份旧记录仍得到从前的答案」的用例——这版最容易漏的一格。

## 并进来的那份（原 `.scratch/step-events/`）

它在 `step-events` 分支上（一个 commit：spec + 六张票，**没有代码**）。内容已并进本目录的 spec 与票；
**它的 `turn/*` 不进记录那条按主人更晚的话作废**。那个分支与 `.scratch/step-events/` 可以丢掉
（没有代码要保）。
## 已落地（2026-09-27）

**票 01 —— 事实族的类型表只有一处拼写：main 已经做过了，我这份丢掉。** main 的 `ed5f7e2` 用
`harness.edge.mux/fact-types` + `ui/src/lib/mux.ts` 的 `familyOf` 落了同一件事，连**运行期对账**都有
（`ui/test/suites/frames.ts` 拿 `frameTypesFromRun` 收服务端真正发过的名字，同一个 `fact-names` 前缀）。
rebase 时那一整支取 main 的：连我加的运行期用例一并去掉（重复），票也删了。

**票 02/03 —— 按主人的话撤回。** 主人 2026-09-27：「`message/added` 不需要吧，有点多余了」。
**对的**：main 的 `step/*` 已经把「这一步调了哪些工具、各自什么结果」当场写进记录与下行，再让内核逐条
报一条 `message/added`、边再逐条写一遍返回侧的行，是把同一件事说两遍。于是全部退回 main：内核那个事件、
`harness.edge.ag-ui/step` 的空操作分支、边的 `log-message!`/对账表、缝里的 `on-result`（工具结果帧的
搬位），以及为它而生的 `:model/end` 关正文那一刀。`loop_test`/`ag_ui_test`/`trajectory-test` 里那几条
断言随之回到 main 的写法。

**留下来的只有一样：读侧补丁**（`.scratch/trajectory-live/`）——那才是症状的修法：返回侧的 `message` 行
落盘时那一刻已经是 :run/done 之后，而 `tools/*` 那几行**是当场写的**，所以轨迹给**仍然开着的那一段**补一条
「还没有结果」的工具条目。它与信封无关，main 之上照旧成立。

**顺带量到、值得留着的一条知识**（它解释为什么返回侧只能等）：`replay/fold-frames` 把**一个 run 的帧当
一组**折（「一条消息的正文活在 START 与它的 CONTENT 帧之间」），所以 `message` 行插在它们中间会把这组
切开、正文出错；而助手那条的 `TEXT_MESSAGE_*` 是**run 的终帧**才闭合的。谁以后要让返回侧的行提前落盘，
先处理这两条。

判据（rebase 后、撤回后再跑）：`trajectory-test` / `loop-test` / `tools-test` / `ag-ui-test` /
`pressure-test` = **161 / 697 / 0**；前端 `typecheck` 绿、vitest **171**。**`http-test` 没在安静机器上
重跑过**：早先它在负载下飘（红过两种互不相同的用例，一次日志里写着 idle guard 把 run 掐了），不许当成绿。


## 现存的半成品

`.scratch/trajectory-live/spec.md`（同一症状的**读侧补丁**：给仍然开着的那一段补一条「还没有结果」的
`tool` 条目）已经做完并绿了，它让症状今天就消失。信封落地之后它**仍然有用**（`pre-execute` 到 `execute`
之间确实还没有结果），但它的前提是段机；**票 04 会重写它的判据**。

## tracker 的现状（2026-09-27，rebase 之后）

剩下的票是 `04…10`。**但其中几张已经被 main 做掉了**（main 的 `.scratch/step-events/` + ADR 0011：step 成为
第三级、`step/*` 两行两帧、折叠按步计、`turn/*` 与 `model/*` 照旧），而 `docs/adr/0012` 里「轮进记录」那半
**与 main 的 ADR 0011 相撞**——0011 明确保留 0006 的「`turn/*` 不进记录」。所以这份 tracker 需要按 main 的
现实重划一遍，只留 main 真没做的（04 请求进 model 信封、08 客户端、09 游标、10 收口），这部分**等主人的裁决**。

## 票 04 落地（2026-09-28）——tracker 到此清空

**请求进 model 信封。** 边把这一轮**为第一次调用**写下的那几行（prompt、动作的条目、派生的注入、压力读数）
攒着，等这次调用的 `:model/start` 到达时一次写下——信封先开，请求才落（`harness.edge.http` 的 `queued` /
`request-log!` / `request-messages!` / `flush-request!`）。`model/start` 在记录里是**事实帧**那一行，
所以判据按 `replay/kind` 认，不按行的 `:type`。

- **没有调用也一行不丢**：run 收尾时（没有 provider、进门就被拒）照样 flush —— 人的提问必须在记录上。
- **判据**：`http-test` 两条断言从「prompt 在 run 第一个帧之前」改口成「prompt 在这次调用的 `model/start` 之后」，
  并说明理由；`trajectory-test` 新增 `an-enveloped-request-folds-to-the-same-conversation-as-a-plain-one`
  （两种形状并排比，同样的轮、同样的条目）。
- **读侧全绿**：`trajectory` / `context` / `pressure` / `stats` / `replay` = **109 / 509 / 0**；
  `http-test` = **115 / 1241 / 0**。
- **段机没有改判据**：票面原以为两条（开段认 run 的第一条 `message` 行、切侧认段内第一条 `event`）都要换，
  实测两条都仍然成立——所以没有动那段代码，只把「两种形状折出同一结果」钉成用例。
  （**这是本票与票面不同的一处，量出来的。**）
- **代价**（请求不再「发出前就在盘上」）写进 `docs/architecture/edge.md`。

**tracker 现在空了**：01 main 先做（取 main 的），02/03 主人撤回，05–10 由 main 做掉或按 main 的裁决作废
（main 的 `.scratch/step-events/` + ADR 0011，票 02–06 全绿），04 是这一刀。`docs/adr/0012` 已删
（「轮进记录」那半与 main 的 ADR 0011 相撞；step 那一级 main 已经写过），本目录只作历史。
