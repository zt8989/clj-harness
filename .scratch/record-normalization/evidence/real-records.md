# 真记录走查：判据对着 40 条真记录跑一遍（2026-09-28，票 01）

命令（**只读** `~/.clj-harness`，一个字不写；`isolate!` 把配置根与 OS home 指到本进程的临时目录）：

```bash
clojure -J-Dstdout.encoding=UTF-8 -M:dev -m scratch-normalized-census
```

## 一、两种读法，读数把「位置读法」排除了

主人那一句「以后所有的消息都要被那个 start/end 这种信封包裹」有两种读法：

- **位置读法**：每条 `message` 行夹在自己的 `START`/`END` **之间**（上一刀 `e71821e` 落的就是这条）；
- **成对读法**：信封成对（每个 `START` 有它的 `END`，反之亦然）+ 工具调用与答复成对。

**40 条真记录里，位置读法在每一条上发声**（5 到 385 行不等）：

```
df75ff9d…jsonl    rows=17     msg=5     位置读法裸露=5    成对读法=true
6f7b8864…jsonl    rows=160    msg=5     位置读法裸露=5    成对读法=true
eab41056…jsonl    rows=997    msg=135   位置读法裸露=135  成对读法=true
86c1c343…jsonl    rows=1393   msg=189   位置读法裸露=189  成对读法=false ["1 次工具调用没有 message 行答复"]
2c380f7f…jsonl    rows=2905   msg=385   位置读法裸露=385  成对读法=false ["3 次工具调用没有 message 行答复"]
58c8d1c3…jsonl    rows=9186   msg=103   位置读法裸露=103  成对读法=true
```

它判裸露的行里，头几条是会话的出生行 —— `system-prompt`、`client`、`opening`：

```
{:src "system-prompt" :role "system" :id nil}
{:src "client"        :role "user"   :id "nLcerTn"}
{:src "opening"       :role "user"   :id "session-opening-0"}
```

**为什么位置读法必然不成立**：写手是「帧先落、它描述的那条 `message` 行紧跟其后」
（`.scratch/record-stream` 票 02：内核写它自己的消息；`http.clj` 的 `write!` 走栅栏）。
一条工具答复是 `TOOL_CALL_START/ARGS/END`（真记录里行 17–19）之后才有的 `message` 行（行 25）——
它的正文**按结构不可能**出现在 `TOOL_CALL_END` 之前：结果得先算出来。而 `TEXT_MESSAGE_START/END`
是线上帧（`ag/outbound` 按模型流式产出的），也从不套在写于 `:model/end` 之后的那条 `message` 行外面。

⇒ 按位置判，**每个会话一打开就是只读**，连刚写下的那一轮也不算数。落地的判据是**成对读法**。

## 二、成对读法在真记录上的读数

**40 条：过 29 条，未重整化 11 条**，理由只有两类：

| 条数 | 形状 | 判什么 |
|---|---|---|
| 5 | `~/.clj-harness/projects/<project>/*.jsonl` 里的**旧格式**行（顶层 `kind`） | 读侧在判据之前就按名字拒绝：`:old-contract`，提示开新会话。判据 (1) 的真判例 |
| 6 | 有 `TOOL_CALL_START` 与它的帧，**没有那一行 `message` 答复** | 判据 (3)：「N 次工具调用没有 message 行答复」 |
| 0 | 信封不成对（`START` 没有 `END`） | 真记录里一条都没有：`callStart=callEnd`、`textStart=textEnd` 处处相等 |

那 6 条里有 5 条是**当前进程正在写**的会话（答复还没落）或**被停/被切**的会话
（`RUN_ERROR` 之后那次调用只剩帧）：

```
2c380f7f-…jsonl  no-row call call_00_rGN406 start=504  end=506
                 no-row call call_00_9p05KE start=1566 end=1568
                 no-row call call_01_1pHYpv start=1569 end=1571   ← 紧随其后 RUN_ERROR 1579
c695967b-…jsonl  no-row call call_00_ET_awB start=974 end=976     ← 紧随其后 RUN_ERROR 980
http-answer.jsonl（43 行，resume 那次答复只剩帧的那条，票 05 的现场）
```

**这是判据 3 该说的话**：一次被停/被切/正在跑的调用，答复只剩一行帧，`message` 行没写下来 ——
按主人的规矩，这份记录要先 fork 重整化才能继续（票 02 的拒绝语、票 03 的补齐，都是冲它去的）。

## 三、判据落在哪

- 判据（成对读法）：`src/harness/edge/normalized.clj` 的 `step` / `finish` / `fold` / `normalized?`；
- 读侧信号：`harness.edge.http/sofar-get`（`:normalized` 与 `:normalizationReasons`，
  与消息同走 `replay/fold-sofar` 的那一趟），只在**答案来自记录**时给判定——正在本进程写的那条不给；
- 判例：`test/harness/edge/normalized_test.clj`（行形状照本节的真记录）、
  `test/harness/edge/replay_test.clj` 的 `the-normalization-verdict-rides-the-same-walk`。
