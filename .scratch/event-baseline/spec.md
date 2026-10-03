# spec: 记录的事件基线 —— JSONL 不存 AG-UI 帧，全部从内核事件与 message 行派生

**一句话**：记录（jsonl）不再落 AG-UI 帧；会话的读侧改从**内核事件行 + message 行**折叠出来。
wire 一个字不动（客户端照旧吃帧），折进会话内存的那份帧也照旧——本刀只裁「记录」这一份。

## 背景（2026-10-02 的对账结论）

记录的 `event` 行今天有两族（`.scratch/jsonl-two-kinds`）：载荷**是帧**的（TEXT_MESSAGE_*、
TOOL_CALL_*、RUN_*、CUSTOM 卡片/快照），与载荷**是 CUSTOM 事实**的（model/start、tools/*、
approval/decided、context/compacted……）。后者不是帧，不在裁撤范围。把帧一族逐个对到内核
二十种事件（`harness.kernel.event`）上加 message 行，结论是：

**能直接派生的**——

| 现在落盘的帧 | 从哪派生 |
|---|---|
| RUN_STARTED / RUN_FINISHED / RUN_ERROR | `:run/start`、`:run/end`、`:run/interrupt`（outcome.interrupts 就是 `:interrupts`）、`:run/error`/`:run/stopped`（`code: "stopped"` 由后者给） |
| TOOL_CALL_START/ARGS/END | `:tool/call`（args 全量）；assistant 的 message 行本身就带 `tool_calls`，帧是第二份 |
| TOOL_CALL_RESULT | `:tool/result`；`:run/cut-off-result` 的答案已在内核自己写的 cut-off tool message 行上 |
| 文本三件套 | message 行（assistant 逐字）+ `text/snapshot` 行（半截答案，见缺口二） |
| REASONING_* | 记录今天就不存（ADR 0009）；完成态的 reasoning 在 assistant 行的 `reasoning_content` 上 |
| `-ctx<n>` 注入卡 | `:context/injected` |
| compacted-context 卡 | `context/compacted` 事实行（compactionId/summary/tokens/shadowed 全在） |
| MESSAGES_SNAPSHOT | 出生那几条本来逐条落盘的 message 行，纯冗余 |
| 整套 id（`<run>-m/r/t/ctx<n>`） | outbound 是纯状态机；把事件按序重放一遍即复现同一套 id（`:model/start` 重置 parent 的事件行已在） |

**缺的只有两件**——

1. **边端 pre-LLM 注入（`-pre<i>`）没有事实来源**：今天只以帧的形式搭 `:run/start` 的车上记录
   （`harness.edge.http`：「the cards for those messages can only come from this side」）。
2. **被停/死掉的 run 的半截答案**：内核只在答案完成时写 assistant 行；半句话唯一的落盘处是
   `text/snapshot`（75ms 一行）。「run 停在半路要留住它说过的话」这条判据全靠它。

**净赚的**：`:model/timeout` 今天是 wire-only（`wire-only-frames`），记录里根本没有；新基线下
它作为事件行自然落盘，补上「这次调用为何中断」。

## 拍板点（主人没拍的，票里写默认）

1. **半截答案走哪条路**——默认 **A：边端继续合并**（`text-lines` 的机器原样保留，行载荷从帧
   换成事实行；节奏 75ms 已经在那里，内核不加钟）。备选 B：内核新增累计快照事件（内核纯度
   最高，代价是 loop 里长出计时逻辑）。两条路产出同一个行族，fold 不在乎。
2. **`turn/*` 要不要顺带并掉**（轮边界 = client 来源的 user 行 + run 终局，能推出来）——
   **本刀不做**，能推出来不等于该在这一刀里动它；要并另开一票。
3. **`:run/cut-off-result` 上不上记录**——默认**上**（一行，让 fold 的账诚实），它写的
   message 行照旧。

## 事件基线（本刀之后记录里「事件」的含义）

`harness.kernel.event` 二十种里，**十六种上记录**：lifecycle 六种（`tools/*`、`model/start`、
`model/end`、`step/*`，今天已是事实行）+ 本刀新增十种（`run/start`、`run/end`、`run/error`、
`run/stopped`、`run/interrupt`、`tool/call`、`tool/result`、`run/cut-off-result`、
`context/injected`、`model/timeout`）+ 边端自己的两种（pre-injection 行、text 快照行）。
**四种不上**：`:text/delta`（逐 token 一行正是快照发明时要省的字节；文本的真值在快照行与
message 行上）、`:reasoning/delta`（ADR 0009 不翻案）、`:drained`（控制事件，不是事实）。

## 边界（不做的事）

- **wire 不动**：客户端照旧收 AG-UI 帧；`mux/fact-types` 的事实族照旧；不做协议升级。
- **settle! 不动**：会话内存的折帧照旧——帧还在内存里攒，只是不再写进记录。
- **旧记录照读**：format 2（帧）的记录继续由今天的 fold 服务；新旧双方言并存，header 说了算。
- **不并 `turn/*`**（拍板点 2）。

## 票

| # | 票 | 依赖 | 交付 |
|---|---|---|---|
| 01 | 内核事件行全量上记录（双写，帧照旧） | 无 | 记录里帧与事件并存，wire 不变 |
| 02 | 第二个 fold：从事件行 + message 行折叠会话，双跑对账 | 01 | 与 `fold-frames` 逐 entry 相等的判据 |
| 03 | 边端 pre-injection 有了自己的行 | 01 | `-pre<i>` 卡的事实来源 |
| 04 | 半截答案上记录 | 01 | 「停在中路留住话」在新基线下仍成立 |
| 05 | 记录停帧，format 升 3 | 02, 03, 04 | 新记录只有事件行 + message 行 |
| 06 | 读侧切流：双方言 | 05 | rebuild/trajectory/compaction/repair 全走新 fold，旧记录照读 |
| 07 | 收口：ADR、文档、走查 | 06 | 新 ADR + 词表改口 + 真浏览器走查 |

## 词表

**事件基线**（event baseline）——记录承认的「事实」的清单：十六种内核事件 + 边端两种，
见上表。**事件 fold**——从事件行 + message 行折叠出 AG-UI 消息表的读侧状态机（`outbound`
的重放对）。**双方言**——format 2 的记录吃帧 fold、format 3 的记录吃事件 fold，header 定方言。
