# 01 — 每条 entry 拿**它自己那一行**的号（文件与表对齐）

**What to build:** 让活着的会话给每条 entry 的 `:seq` 与 `replay/entries`（文件那一侧）**逐条相同**：
run 的消息行各自带自己的行号，而不是整轮共用一个终帧行号。

**Blocked by:** 无（`entry-numbering` 这一票已经落地：号不再丢，只是一轮共用一个）

**Status:** ready-for-agent

## 今天差在哪

- **表里**：`harness.kernel.session/land!` 是**按组**填号——一轮 run 的条目同一天落进表，于是共享
  **终帧那一行**的号（`.scratch/entry-numbering/`）。这是老设计（一条 run 一条 `input` 行）留下的语义。
- **文件里**：`.scratch/jsonl-two-kinds` 票 02 之后，**每条 entry 有自己的 `message` 行**，
  `replay/entries` 于是**逐行**给号；`flush-group` 只在帧派生、没有自己那一行的条目上兜底。

于是同一场会话「活着」与「被放掉后重生」会给两套号（差几行到几十行）。今天不致命——两边都是真实偏移，
读者按 id 去重——但它让两件事不再严格成立：

1. `beforeSeq` 的切法：表里按「一组一个号」，文件里按「一行一个号」，**同一页的两侧可能切在不同位置**
   （`sessions/before-of` 的注释把「页切在 arrival 边界上」当正确性前提）。
2. `since` 的重放量：一轮共用一个号时，读者重连会把整轮重新收一遍（按 id 去重，白传）。

## 要做成什么样

**一张有界的「待落表」**（名字由实现定）：写行那一刻 `log!` 把号答回来，而 entry 还没进表——
于是把 `[组, 这条 entry 的载荷/序号, 号]` 先记下；`append!` 把 entry 放进去时，把与它配对的那一行号填上。

- **配对的键**：run 的 message 行 payload **没有 `:id`**（实测），所以只能按**位置**——写行的顺序与 entry
  进表的顺序必须能被证明一致（`log-message!` 的注释今天就说「a writer logs a group's rows in the order the
  entries went in」，这一票要把它变成断言而不是信念）。位置配不上时**宁可留 nil 也不猜**（nil 有明确语义：
  「还在写的人手里」，`since` 会把它算成新的，重传一次比错号强）。
- **放掉**：表要有界，且**随 run / 随会话的放掉一起走**（`.scratch/memory-hygiene/` 的教训：
  没人记得的会话，行不能还留在表里）。一轮结束、或 `settle!` 之后仍没配上的，直接丢。
- **`log-message!` 要拿到 `lands`**：这条路要改的是写行的入口（今 `log-message!` 传 `nil`），
  与 `land-at!` 按名匹配的那条（动作自己的行）合流。

## 验收

- [ ] 活着的会话（真跑一次 run）与同一场会话重生后，`replay/entries` 与 `sessions/display` 的
      `:seq` **逐条相同**（一条断言，两边都读）
- [ ] `beforeSeq` 在**两种来源**上切在同一位置：同一场会话，从表切一页、从记录切一页，条目集合相同
- [ ] 位置配不上时**留 nil**，且下一次 `since` 会把那条重传一次（不是错号、不是丢失）
- [ ] 待落表**有界**：一轮的最大行数是上界；`settle!` 之后未配上的条目不再留
- [ ] 会话被放掉 / 扫掉时表里那一条跟着走（与 `stream/forget-kept!` 同一类性质）
- [ ] 离线全量 `harness.test-runner` 全绿（对照已知 5 红）
- [ ] 真机：两处帧汇各跑一轮，核对 `GET …/page` 与 `replay/entries` 的号逐条相同

## 不做

- 不改 `since` / `before` 的算法本身（这一票只让**号**对齐）。
- 不给 message 行**加上 `:id`** 来配对——那是动记录格式（`.scratch/jsonl-two-kinds` 说了格式是冻结的）；
  真要往那个方向走，先立票说清它值不值。
- 不为了对齐去改前端。
