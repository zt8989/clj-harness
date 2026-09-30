# spec: 窗口的号 —— 一轮 run 的条目，由谁、在什么时候编号

**一句话**：`seq`（条目在记录里的偏移）是「那条行落盘时填的」，可**一轮 run 的条目要到这一轮结束才进会话表**
（`settle!`：半截答案不算一轮）——于是写行那一刻 entry 还不存在，号落早了等于**没落**：每个 entry 都留着
`:seq nil`，而 `since` 把「没有号」读成「在游标之后」，读者问「我错过了什么」，服务端把**整个会话**又给一遍，
读者把**比它更老的**那些条目接到自己窗口的**末尾**，最新那一轮就此被推到中间。

2026-09-30 牛总报的症状：侧栏说「等你回应」，点进去底下是一轮**老的**（换一个「还没有 host」的页面点开，
卡片画得出来、也答得了）。同一个 bug 下还有一层：`show()` 对已经存在的 host 是空操作，所以**再点多少次都是
那份被打乱的窗口**，刷新也只是把同一场打乱重来一遍。

## 读数（2026-09-30，活进程 `8080` + 真记录 `7e8adcc0-…`）

| 事实 | 读数 |
|---|---|
| 内存里那场会话的 entry | 563 条，**只有 7 条带 `:seq`** |
| 同一份文件折出来（`replay/entries`） | 563 条 **全部**带号（首 10、末 3699） |
| 那 7 条是谁 | `land-at!` 落过的：出生上下文 + 人自己打的那几条（**行上有 `:id`**） |
| 读者手里那份窗口（从 React fiber 读出） | 557 条、`state: parked`、**末尾是 `dc64f63d-…-t176`**；`cherry pick to dev` 在第 47 位、这次 park 的 `940aed3f-…-m7` 在第 48 位 |
| `window-page` 收到 `since=3699` 时给的 | 556 条、**从对话第一句开始**，`:baseSeq` 却写着 3699（那一支把 `baseSeq` 硬写成 `since`） |
| 门铃 | `settle!` 先把 entry 放进表、**再** `ring!`；而号是写行时得到的——**早于这两件事** |
| 一轮 run 的条目在**文件**里跨几个号 | 那一轮 8 条 entry 占了 **5 个号**（3667 / 3673 / 3683 / 3689 / 3699）——它自己的 `message` 行把帧组切开了 |

**两条补号的路当时都不通**：run 的 message 行 payload 里**没有 `:id`**（实测行文：`{"role":"assistant",
"content":"",…}`），所以按名匹配没得匹配；按位置匹配要 entry 已经在表里，而它要到 `settle!` 才进表。

## 决定

1. **号在 entry 进表那一刻落，落点是 `settle!`，而且落在门铃之前。** `settle!` 多一个可选参数
   （这一轮**终帧那一行**的偏移），由边**捎**过来：号只有写行的人有（`log!` 同步答号，ADR 0007），
   而 entry 只有 `settle!` 才有——两个事实分开在两头，所以是**传一个数**，不是让谁去猜。
   落在门铃之前是硬要求：门铃一响就是「来看」，看之前必须已经有号。
2. **两处帧汇都改**（agent 路由的 `runner` 与 subagent 路由），并且**崩掉、没走到终帧的 run 也要有号**：
   用它「写过的最后一行」。
3. **逐条对齐：不写第二份分组规则，去问读者那份 fold。** 一轮 run 的条目在文件里可能跨好几个号
   （`message` 行会把它前面的帧组切开），所以活着的那一份**不能**自己造一条规则，也不能只给整轮一个号。
   做法：写行的人把自己这一轮写下的行**留一份**（`*written-rows*`：`[line row]`，`log!` 顺手收），
   收尾时交给 `harness.edge.replay/entries-of-rows`——**同一个 fold、同一份代码**——折出 `{entry-id line}`，
   `settle!` 按名逐条落（`number-entries!`），没配上的才退回「整轮一个号」。

   **原设计（票 01 写的那张「待落表」）作废了**，理由是读出来的：run 自己的 `model` / `tool` 行**根本不成
   entry**（`replay/our-entry?` 把它们排除，那条 entry 是**帧**折出来的，`-m7` 这种 id 也从帧来），
   而 `apply-frames` 是 N 帧 → M 条消息，谁也没有「自己那一行」。所以「一行配一条 entry」这个前提**不存在**；
   能对齐的不是「行 ↔ entry」，而是「**行决定帧组在哪里被切开**」——这正是 fold 里那一条规则。

## 非目标

- **不在表这边再写一份 fold 的分组规则**（那会漂移）；`entries-of-rows` 是公开的一扇门，
  与 `entries` 共用 `entries-step` / `flush-group` / `entries-answer`。
- 不改 `since` / `before` / `window-page` 的算法本身；不给 message 行加 `:id`（那是动记录格式）。
- 前端一个字不改。

## 验收

- [x] 机制（`sessions-test`）：一轮的帧进表时就有号，**且门铃响那一刻号已经在**。
- [x] 前提（`replay-test`）：**只折这一轮自己写的行**，与**折整份记录**，给出同一串 entry 与同一个号
      （同一条断言里还钉住「不是整轮一个号」）。
- [x] 端到端（`http-test`，真跑一次 run）：窗口答出的**每条** entry 的号，**等于文件 fold 给同一条 entry 的号**。
- [x] 真浏览器（`node scripts/dev.mjs --scripted` 自写脚本，真调 `ask`）：卡片画在消息列表**末尾**；
      切到另一个会话再切回来**仍在**；那一刻服务端那份 window 是 `[6 21 21]`。
- [x] 真机：热加载后在活进程里合成一次 settle（`[5]` → `[5 42]`）；把**已经卡住的**那场会话放掉一次让它
      从记录重生（记录没动、`GET /api/elicitation` 仍 200、park 仍在），`/page` 50 条 **0 条没号**、末尾就是
      那张 ask；在报障的那个 tab 里点开，顺序正常、卡片在最底下。
- [x] 机器门：定向（`sessions` / `http` / `replay` / `mux` / `ag-ui` / `normalized` / `stats` / `ask` / `loop` /
      `host`）全绿；全量 1381 例 / 14372 断言，5 红 0 错——就是那 5 条已知的（`mcp_wired`×3、`claims`×1、
      `hooks`×1）。
- [x] 文档：`CONTEXT.md` 窗口那一条、`docs/architecture/edge.md` 窗口那一节各补一段；`harness.edge.replay`
      `entries` 的 docstring 把「两边给的号一样」写成**怎么做到的一样**。

## 落地（2026-09-30）

- `harness.kernel.session/settle!` 多两个可选参数：`landed`（整轮一个号，兜底）与 `numbers`
  （`{entry-id line}`，来自读者的 fold）；新门 `number-entries!` 按名逐条落，**在 `ring!` 之前**。
  崩掉、没走到终帧的 run 用「写过的最后一行」兜底。
- `harness.edge.replay/entries-of-rows`：`[line row]` 对 → 那条记录折出来的 entry（与 `entries` 同一份 fold）。
- `harness.edge.http`：`*written-rows*`（每轮一个 atom，`log!` 顺手收 `[offset row]`）；两处帧汇收尾时
  把它交给 `entries-of-rows`（`entry-lines`）并传给 `settle!`。
- 用例：`sessions-test/a-run-this-process-answered-is-numbered-by-the-line-that-ended-it`、
  `replay-test/one-runs-own-rows-answer-the-numbers-a-window-hands-out`、以及 `http-test` 真跑一次 run 那条
  用例里「与文件 fold 逐条相同」的断言。
- 未覆盖：**旧进程里已经跑出来的会话**不会自己补号——它们要等被放掉一次（从记录重生）或进程重启。
  真机上那场卡住的会话就是这么治的：放掉一次，记录一个字不动。
- 未覆盖（另一处，与号无关）：客户端 `show()` 对已存在的 host 是空操作，**点了不重读存量**。
  修好号之后窗口不会因为这条路再变旧；要不要给它补一次重读，另开一票。
