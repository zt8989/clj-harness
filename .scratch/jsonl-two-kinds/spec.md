# spec: jsonl 只有两种行 —— `event` 与 `message`

**拍定（2026-09-21，主人）**：记录里**只有两种行**。`message` 是「人说了什么」和「LLM 返回了什么」
这两种消息；**其余一切事实都是 `event`**——初始化一场会话是 event、provider 变了是 event、
工具开始/结束/批准是 event。**判据只有一条：AG-UI 的帧流能从 jsonl 重建出来。**

这一页同时回答主人先问的那句「`input` 行是必要的吗，`message` 不就自带了」：**自带不了，但把它改成
自带得了的**——`message` 行加信封上的 `id`（不进 payload），注入带 `source: "injection"` 标记，
于是 `input` 行可以整个删掉。

## 只有两种行

```
{"type":"message","id":"u1","payload":{"role":"user","content":"看看这个项目"}}
{"type":"message","id":"msg-7","payload":{"role":"assistant","content":"…","tool_calls":[…]}}
{"type":"event","payload":{"type":"CUSTOM","name":"injected-context","messageId":"session-opening-0","value":{…}},"runId":"…","ts":…}
```

- **信封**（`.scratch/context-frames` 那次问答的结论，这里沿用）：顶层 `type` + `payload`，`ts` / `runId`
  留在顶层；payload 里是**原样的对象**（`message` 的 payload 就是交给厂商/厂商返回的那个 map，
  `event` 的 payload 就是那一帧）。可选的顶层 `source`，注入用 `"injection"`。
- **严格读者**：缺 `type`、缺 `payload`、或 `type` 不是这两个之一 —— **一律抛异常**。破坏兼容是
  拍定的一部分：旧行不读、不迁移、不"顺带兼容"。
- `type` 之外的一切键都是信封的事，**不进 payload**，所以 `id` / `source` / `ts` / `runId` 一个字节
  都不会漏给厂商——这是「payload 逐字」与「记录自带身份」能同时成立的原因。

## 今天有十几种行，每一种的新家

| 今天的 kind | 谁写的 | 新形状 |
|---|---|---|
| `event` | `http.clj` 的帧发射器 | **`event`，不变**（payload 已经是帧） |
| `message`（submitted = 整个入站向量，returned = 内核说的那几条） | `log-messages!` | **`message`，只留人的一条与 LLM 返回的那些**；信封带 `id`；注入不再混在里面（见下） |
| `input`（RunAgentInput + `:added`） | `run-agent!` | **删**。它答的三件事各有新家：**身份** = `message` 行的 `id`；**边界** = 行序 + `RUN_STARTED`/`RUN_FINISHED`（已经是帧）；**出生 context / 绑定** = `event` |
| `tools/pre-execute` `tools/execute` `tools/post-execute` | `http.clj` 1227 | `event`（工具往返本来就是 `TOOL_CALL_*` 帧；执行器的三个时点做成帧/CUSTOM） |
| `hook/<Point>` | `http.clj` 872 | `event` |
| `model/start` `model/end` | `http.clj` 661 | `event` |
| `provider/init` `provider/changed` `provider/session-changed` | 1132 / 1152 / 3231 | `event` |
| `project/bound` | 1930 | `event` |
| `approval/decided` | 1139 | `event`（批准本来就以 `CUSTOM`/interrupt 帧走过一遍） |
| `session/closed-off` `session/rebuilt` | 2378 / 2454 | `event` |
| `mcp/server` `git/branch` | 3074 / 3515 | `event` |
| 只有 warn 的那些（`run/*` `jobs/*` `claim/*`） | 各处 | 仍是日志告警，不是记录的"行"；要不要也落 `event` 见票 04 |

**注入（开场块、技能正文、作业结尾）**：它们**不是**"人说的"也不是"LLM 返回的"，所以按拍定是 `event`。
今天它们在 `message` 行的 submitted 侧露过脸（"模型真收到的那一份"），改完之后**模型看到的那一份是
重建出来的**：system（派生）+ 按序的 `message` 行 + 按序的注入 `event`。这不是损失，是这条决定的核心：
**记录存事实，向量是推导**。

## 「帧能从 jsonl 重建」是什么意思

写成可验证的一句：**同一段记录，今天线上发过什么帧，重建就该得到什么帧**（逐帧相同，id 也在里面）。
它今天已经近似成立（`kernel.frames/apply-frames` + `replay/entries` 折 `event` 行），这一票把它变成
**唯一的形态**：不再有第二条"只有 `input` 行知道"的信息。

由此，重建要的三件事在新格式里都从行本身得到：

- **身份**：`message` 行的 `id`（信封）与帧的 `messageId` 是同一个命名空间——开场条目 `session-opening-<i>`、
  出生 context `session-context`、客户端的 `u1`、助手的 `msg-*`。按 id 去重仍是唯一规则
  （`replay/append-new`、`sessions/append!`）。
- **边界**：行序就是对话序；一次动作从它的 `RUN_STARTED` 到自己那条 `message` 行，中间的 `message` 行
  就是这次加进去的（客户端只送 `:append`，所以"这次带了什么"= 那些 `id` 还没出现过的行）。
- **序号/游标**：仍然是**记录行号**（ADR 0003 决定 1），窗口协议的 `beforeSeq`/`since` 一个字不改，
  改的只是"一条记录行贡献哪些条目"。

## 四个补充拍定（2026-09-21，主人选了"都按你的倾向走"）

1. **system 那一份**：**每场会话一条 `event` 带冻结全文**，此后每轮只带 hash。理由：AG-UI 没有
   system 帧，但那句话决定"重建出当时那份 provider 向量"能不能成立，而重建是这条决定的目的。
2. **event 的命名词汇**：**帧就是帧**。线上发过的帧（`RUN_*`、`TEXT_MESSAGE_*`、`TOOL_CALL_*`、
   `CUSTOM injected-context`）原样作 `event` 的 payload；harness 自己的事实（`model/start`、
   `provider/changed`、`tools/*`、`hook/*`、`project/bound`、`session/*`、`mcp/server`、`git/branch`、
   `approval/decided`、`input`）包成一个 **CUSTOM 帧**，`name` 就是那种事实的名字。客户端不认识
   的 CUSTOM 它本来就不画，所以重建出来的一串可以原样喂给客户端。
3. **老记录**：**400 + 一句话提示开新会话**（"这份记录是旧契约……"），不留考古工具。
4. **`source` 细分**：`injection` 之外再分 `opening` / `skill` / `job`，让屏幕上的卡直接说出自己是
   哪一族，不必去猜 `<job-ended …>` 的标签。

## 今天已经落地的第一步（票 01）

- 写侧：`harness.edge.http/row-of` 把每一行写成 `{type, payload, ts, runId}` 两型之一（事实包成
  CUSTOM 帧，名字 = 原来的 kind）；`carry-audit!` 也走同一条路。
- 读侧：`replay/row->record` **严格**——旧契约行（顶层 `kind`）按名字拒绝、缺 `payload`、
  非法 `type`、非对象、半行 JSON 各有 `:reason`，并带上 `:line`。
- ~~**`{:kind ..}` 仍然是内存里的 record 形状**，由 `row->record` 从信封**推导**~~ **（票 02 已拆掉这一层：
  内存里的 record 就是行本身，`row->record` 这个名字不存在了，问"这行是什么"改用 `replay/kind` / `payload`。
  下面这一段保留，是因为它是那道接缝的原始记录）**（`message` → `"message"`；
  自定义帧 → 它的 `name`；其余帧 → `"event"`）。这样格式换了而读者没换，132 处测试断言与全部
  reader 原地不动——`replay.clj` 的注释里写着这是临时的接缝，票 02/04/05 会把它拆掉。
- 判据里"每一行只有两型 / 坏行按行号报错 / 老记录按名字拒绝"三条已经有用例钉住：
  `replay_test/the-record-has-two-kinds-of-row-and-anything-else-is-refused-by-name` 与
  `http_test/the-record-holds-exactly-two-kinds-of-row`（后者读的是**原始字节**，不是推导出的 shape）。

## 补充拍定 1 落地（2026-09-21）：system 消息**仍然是 `message` 行**（本节左侧那份"成了 event"的写法已被主人后来的更正推翻，见下节"票 02 落地"）

> **下面这一整节的结论错了，保留它是因为"错在哪"本身有用。** 主人随后把 `message` 的判据说清了：
> **「所谓 message 就是送给大模型的那些 message 数组的超集」**——一个 `message` 行**就是**那数组里的
> 一个元素，system 消息**就在那个数组里**，所以它是 `message` 行（信封带 `source: "system-prompt"`
> 与 `hash`），不是一条叫 `system-prompt` 的 `event`。本节里"每次 run 一条 event 带 hash"的**机制**
> 照旧成立（每场会话第一条、hook 改动后第一条带全文），变的只是它落在哪种行上。

**（被推翻的旧写法，保留作对照）它不再是 `message` 行。** system 消息是「这一轮交给模型的那句话」，不是谁说的——按拍定它是 `event`，
`name` 是 `system-prompt`：

- **写侧**（`harness.edge.http` 的 submitted 那段）：每次 run 一条
  `{hash: <这一轮 system 消息的 SHA-256>, text: <全文，只有该带的时候才带>}`；
  `log-messages!` 那一侧把 `role: "system"` 滤掉，所以 `message` 行只留人说的与 LLM 返回的。
- **谁欠那份全文**由 `harness.edge.sessions/remember-prompt!` 答：**会话第一次**（birth）、
  **hook 把这句话改动了之后第一次**、以及**本进程重建起来的会话**各欠一次；其余每次 run 只有 hash。
  它住在会话表里（与会话消息同一份内存状态），因为铁律不让 run 去读自己的日志。
- **读侧**（`harness.edge.trajectory/run-segments`）：每一轮的 submitted 列表**前面补回**那条 system
  消息——本轮自己的 `:text`，只有 hash 的那些轮沿用它所属的那一份。于是
  `harness.edge.context` 的三分之一（system 桶）与轮视图的「system 变了才再出现一次」两条一个字没改。
- **hash 是 SHA-256，小写十六进制**（`harness.cap.system-prompt/digest`）。它回答的是「这轮和上轮读的
  是不是同一句」——prefill / prompt cache 靠的就是那条前缀稳定。

用例：`sessions_test/the-prompt-a-conversation-froze-is-remembered-once`（三次回答）、
`trajectory_test/a-run-that-did-not-move-the-prompt-still-finds-its-bytes`（只有 hash 的那一轮仍拿到字节）、
`http_test/the-record-holds-exactly-two-kinds-of-row`（**没有** `role: "system"` 的 message 行，
而有一条带 hash 与全文的 `system-prompt` 事件）。

**它替票 02 清掉了 submitted 侧的一件东西**：那一侧现在少一份 system 消息，剩下的（客户端的消息、
注入）仍归票 02。

## 票 02 落地（2026-09-21）：`message` 行自带身份，`input` 行删掉

**接缝拆了。** 内存里的 record **就是行本身**（`replay/read-row` 只校验、不重写），
`{:kind ..}` 那个由 `row->record` 推导的第二形状没有了；问"这行是什么"的词汇只剩一处：
`replay` 的 `kind` / `payload` / `message?` / `frame?` / `fact?` / `system-prompt?`
（外加 `wire-custom-names` 与私有的 `fact-frame?`）——`grep -c '"kind"' src/` 为 0。

### 写侧

- `run-agent!` 不再写 `input` 行。它原来答的三件事各有新家：**身份** = `message` 行**信封上的 `id`**
  （payload 里一个字节都不进）；**边界** = 行序 + `RUN_STARTED`/`RUN_FINISHED`；**出生 context /
  绑定** = `event`。
- 每个条目**一条 `message` 行**，顺序就是它们进数组的顺序；`land-at!` 拿到的是**那一行自己的行号**，
  所以窗口的 `beforeSeq`/`since` 一个字不改。
- **payload 是"厂商读到的那条消息"，不是客户端的原文。** AG-UI 的 `image` 在记录里就是 `image_url`
  （`ag/provider-messages` 翻的：卡去掉、部件翻译、reasoning 折进它后面那条 assistant）。一条
  **翻出来是空的**条目（单独一条 `reasoning`）**不写行**——为它写行等于说模型收到过它。
- **system 那一份仍是 `message` 行**：信封带 `source: "system-prompt"` 与 `hash`。每场会话第一条、
  hook 改动后第一条带全文（只有 hash 的那些轮沿用它所属的那一份），机制照旧，只是落在 `message` 上。
- **注入不再是 submitted 侧的散件**：开场块、技能正文、作业结尾各自成行，信封的 `source` 细分
  `injection` / `opening` / `skill` / `job`（返回侧同理：`model` / `tool` / `skill` / `job`），
  屏幕上的卡因此能直接说出自己是哪一族。

### 一个被主动放弃的事实

`input` 行的 payload 里还带着**当时的请求体**（`:provider` / `:model` / `:tools` / `:context`——
"客户端要的是什么"）。行删掉，这份事实就**没有任何新家**：记录从此只答"这次跑的是哪一档"
（`provider/init` / `provider/changed` 的 `:resolved`），不答"客户端要的是哪一档"。这是**有意的收窄**：
被拒的那条请求（档位不存在）本来就不该在记录里留下一条看起来像事实的请求体。两条用例
（`a-provider-in-the-run-body-is-not-consulted`、`records-the-run-as-jsonl`）改成从**留下来的行**读这句话。

### 读侧

- `replay/entries` 的折法：一个 run **由它自己的第一条 `message` 行开启**（一条 `event` 从不开 run）；
  一条 `message` 行**是对话的条目**当且仅当 `source ∈ {client, injection, opening}` 且
  （`source = client` 或有 `:id`）——匿名的注入/开场行是"每轮重算"的那一份，不是对话；
  数组里 prompt 是第一个元素，记录里它在动作自己的行**之后**（动作的行先落，run 才是它的应答）。
- `trajectory/run-segments`：一段在三种情形下开新段——(a) 不同 `:runId` 的 `message` 行；
  (b) `:streaming` 为真时来了一个**条目**行（新一轮的条目总在上一轮帧之后到）；(c) 已经有 prompt 的段
  又见一条 `system-prompt` 行（park/resume）。
- `trajectory/align` 变成**两侧都会让**：对不上时要么跳过一段 submitted 派生块，要么跳过一条本轮
  没有重述的历史条目；`raw` 按 id 去重（重发过的客户端消息不会出现两次）。
- **两种方言，出口只有一种。** 条目行是**厂商形状**，而帧折出来的消息是 **AG-UI 拼法**，
  `replay/history` 要一份厂商向量——所以 `ag/provider-messages` 是**幂等**的：`provider-shaped?`
  认出"已经是厂商消息"（AG-UI 独有的角色 / camelCase 工具字段 / `image`·`data` 部件 / `ag-ui-only`
  那些信封键）就原样放过，否则翻译。折出来的消息还带着 `entries` 盖的身份 `:id`，
  `ag/strip-identity` 在 `history` 里把它们去掉——厂商数组没有这个字段。

### 用例

- `http_test/the-record-holds-exactly-two-kinds-of-row`（读**原始字节**：两型、prompt 是 `message` 行
  且有 hash、`input` 这个名字**不再出现**）
- `http_test/records-the-run-as-jsonl`（每一条目一行、信封带 id、payload 是厂商形状、prompt 在该 run
  的第一帧之前）
- `http_test/an-image-part-reaches-the-model-translated-and-the-log-says-so`（记录里是 `image_url`，
  没有 AG-UI 的 `source` 包装；`replay/history` 重建出一模一样的一条）
- `http_test/the-session-s-opening-context-enters-the-conversation-once`（"这次带了哪几条" = 那些
  **带 id 的 `message` 行**，读行本身而不是读某个字段）
- `replay_test`（22 条）、`trajectory_test`（21 条）、`stats_test`（8 条）里那些原本读 `input` 行的
  用例全部改读 `message` 行

## 票 03 落地（2026-09-21）：帧能从记录重建，用例是判据本身

`http_test/the-frames-a-run-sends-are-the-frames-its-record-rebuilds`——真 socket 跑一轮（含
reasoning 与工具往返），拿 SSE 上收到的帧序列与记录里 `frame?` 那些行的 payload **逐个相等**；
再把**只从行**折出来的对话与当时会话手里的对话比一次。这道用例就是"没有第二份真相"的见证：
`input` 行在的时候它能过，`input` 行删掉之后它仍然能过，靠的只有 `message` + `event`。

**两个已知坑，落地时的答案**：

1. **`message` 行不是帧**：判据以 `event` 行的帧为准，`message` 行不进这个比较——它的用处是
   （a）给出对话的条目与身份，（b）让 `replay/history` 重建出当时那份**厂商向量**。
2. **system 那一份**：见上，它是 `message` 行（`source: "system-prompt"`），"向量可重建"因此成立；
   AG-UI 没有 system 帧，所以帧的判据本身不管它。

## 票 04 落地（2026-09-21）：十几种 kind 全量迁完，命名词汇定死

- **写侧只剩一个入口**：`http.clj/row-of` 把每一行写成 `{type, payload, ts, runId}` 两型之一——
  `message` 的 payload 就是那条消息，`event` 的 payload **就是那一帧**；harness 自己的事实一律包成
  `{"type":"CUSTOM","name":<那种事实的名字>,"value":<它当时知道的东西>}`，`carry-audit!` 走同一条路。
  `grep -c '"kind"' src/` 为 **0**。
- **`payload` 的三种读法只有一处**：`replay/kind` 回答"这行是什么"（`message` / 帧 / 事实的名字），
  `replay/payload` 回答"它带了什么"（消息 / 帧 / 事实的 `:value`）。谁都不再自己解那层 CUSTOM 信封。
- **帧就是帧**：线上发过的帧（`RUN_*`、`TEXT_MESSAGE_*`、`TOOL_CALL_*`、`CUSTOM injected-context`）
  的 payload 原样就是那一帧——`wire-custom-names` 是"哪些 CUSTOM 名字是 wire 的"的唯一名单，
  名单外的 CUSTOM 一律读成"harness 在说自己的事"（`fact?`）。加一条 wire 用的 CUSTOM 名字必须
  在同一个提交里进那个集合，否则事实与帧会被读成同一种。
- **只有 warn 的那些**（`run/*`、`jobs/*`、`claim/*`）**不进记录**：它们是**进程的一行日志**，
  不是发生过的一次事实——一份记录活过写完它的进程，而"这次 run 开始过"在进程没了之后没有读者。
- **`source` 是信封上的细分**：进来的一侧 `client` / `injection` / `opening` / `skill` / `job`，
  返回的一侧 `model` / `tool` / `skill` / `job` / `system-prompt`（`entry-source` / `returned-source`
  一处判定）。屏幕上的卡因此不必去猜 `<job-ended …>` 这类标签。

## 票 05 落地（2026-09-21）：读者与界面跟着搬，老记录按名字拒绝

- **后端读者**（`sessions` 的表与 `settle!`、`replay` 的 `entries` / `messages-so-far` / `sofar` /
  `rebuild`、窗口 `page` / `feed`、`trajectory`、`stats`、`context`）读的都只剩这两种行；
  `ensure-complete!` / `record-state` 的截断判定跟着改成按"一个 run 由它自己的第一条 `message` 行开启、
  由终结帧结束"来数（`replay/runs` / `open-runs`），不再依赖 `input` 行存在与否。
- **`trajectory` 的 submitted 侧改成重建**：system 那一份（`source: "system-prompt"` 的 `message` 行）
  + 按序的 `message` 行 + 按序的注入行；`stats/user-ids` 仍是两处共用的**唯一**轮判据。
- **客户端**：`keepInjectionCards` 按 `id` 配对不变（id 现在是信封上的，来源更干净）、
  `toAgUiMessages` 的"不回发注入"不变；刷新/窗口读到的卡片位置与在线时一致
  （`ui/test` 的 `a-rebuilt-conversation-gets-its-cards-back`、`concurrent` 那条读**行本身**的用例）。
- **老记录按名字拒绝**：`replay/read-row` 见到顶层 `kind` 抛 `:reason :old-contract`，句子里写明
  "这份记录是旧契约，请开一场新的会话"；`replay_test` 钉住这句话与理由，路由把它变成 400。
  现场的 `e538601f-…` 与其他旧会话都属于这一类——**不迁移、不顺带兼容**（拍定 3）。
  用例：`http_test/an-old-record-is-refused-by-name-over-http`——一条手写的旧契约记录，`page` 与 `rebuild`
  两个门都答 **400**、句子里有 "old contract" 与 "start a new conversation"，而且**文件一个字节没动**
  （既不迁移也不修复）。
- **文档**：`docs/architecture/{edge,client,overview,home-and-storage}.md` 里"`input` 行"、
  "`message` 行 = 模型收到的那一份"、"system 是 event"这些说法全部改写成上面这套；
  `CONTEXT.md` 不必改（它说的 `message` 行与 `model/*` 行仍然成立）。

## 判据（做完这一页，应该能说这几句）

- 记录的每一行，`type` 不是 `message` 就是 `event`；拿一条别的（或旧行）喂严格读者，**按行号报错**。
- 一段记录重建出的帧流，与当时 SSE 上发过的**逐帧相同**（含 `messageId`）。
- `input` 行从代码与文档里消失，`input` 这个词在记录里不再出现。
- 一场会话的对话（含开场块、技能正文、工具往返）能只从 `message` + `event` 折叠出来，且**同 id 同序**。
- 老会话打开时**按名字报错**，句子说清"这份记录是旧契约，请开一场新的"。

## 收尾扫尾（2026-09-21）：`input` 这个词在代码里只剩"旧记录"这一种意思

票 02–05 落地之后全仓扫了一遍残留，改动如下（都不是行为改动，除了新增的那条用例）：

- **`src/`**：`http.clj` 的头注释与五处正文注释（`log!` 的 lands、`running?`、`run-agent!`、`row-of`、
  路由表）里"input 行"的说法改成"开启 run 的 `message` 行"；`context.clj` 的头注释从
  "读 `input` / `message` / `model/*`"改成"读 `message` / `model/*`"。
- **`test/`**：三处把 `"input"` 当作**任意审计行**的夹具（`log!` 路由、carry-back、overlap 三组用例）
  换成显式的 `test/probe`——那个名字是"一行只是一行"的占位符，不会让人以为它还是一种真事实；
  两个分区用例（`old-logs`、split-log）改成写一条真的**客户端 `message` 行**；
  五处注释里"`row->record` 推导 `:kind`"的说法改掉（那个函数不存在了）。
- **`ui/`**：`trajectory-timeline.tsx` 里"用户消息的 `at` 来自 input 行的 `:ts`"改成"来自它自己
  `message` 行的 `:ts`"（这条泳道本身是轨迹的三条泳道之一，与记录的行类型无关）。
- **`dev/`**：`scratch_multicall_frames.clj` 读的是一份旧契约的日志，现在会被**按名字拒绝**——
  脚本头部写清了这一点（保留它是作为一次诊断的记录）。
- **新增用例**：`http_test/an-old-record-is-refused-by-name-over-http`——一条手写的旧契约记录经
  `page`（GET）与 `rebuild`（POST）两个门都答 **400**，句子里有 "old contract" 与
  "start a new conversation"，且**文件一个字节没动**。在那之前，按名字拒绝只有读者层的用例
  （`replay_test`），没有一条说"客户端遇到的是答案而不是 500 或一次悄悄的重写"。

扫完之后：`grep -rn 'row->record'` 为空；`"input"` 在 src 里只出现在模态（input/output modalities）
与"旧契约"这个词组里；`"input"` 作为**行种类**只出现在那条拒绝用例手写的坏记录里。

## 相关

- `.scratch/context-frames/spec.md`（信封、注入物的位置与卡）
- `.scratch/sessions-live-on-the-server/spec.md`（`:added` 的由来——这一页把它删掉）
- `.scratch/session-opening/spec.md`（开场条目与它的卡）
- `docs/architecture/edge.md`（`message` 行那条契约要改写：从"模型真收到的那一份"到"说过/返回的每条消息"）
