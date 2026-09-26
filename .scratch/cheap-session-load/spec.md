# spec: 打开一场会话，成本与最后几轮成正比

主人（2026-09-25）：**加载会话很慢。我要的其实很简单——只要用户发送的和大模型返回的摘要，加上工具调用
的次数；这个之前就做了（`ui/src/lib/turns.ts` 的折叠，`thread.aui.tsx` 画的那条
`72 次工具调用 · 25 条消息`）。最后一次 turn 要全量、不折叠。**

主人同时拍了三件事：三条路（侧栏点开 / 刷新回来 / 往前翻）都慢；折起来的旧轮点开时**允许再拉一次**；
**服务端「解析整份记录」那一遍也要一起治**。

## 症状与现场

一场真会话（`~/.clj-harness/projects/C__Users_zhouteng_Documents_workspace_lisp-harness/fa35f356-….jsonl`）：
**约 65 MB、237,012 行**，而它只有 **9 条 user 行、497 次工具调用**。按字节与行拆开：

| 记录里的东西 | 字节 | 占比 | 行 |
|---|---|---|---|
| `REASONING_MESSAGE_CONTENT`（逐 token 的推理 delta） | 42.7 MB | 65.6% | 222,996 |
| `model/start`（**旧日志**：改动前每次模型调用把 45,410 字节的工具表再记一遍 × 392） | 16.6 MB | 25.5% | 392 |
| `message` 行（9 user / 321 assistant / 408 tool / 7 system） | 1.9 MB | 3.0% | 745 |
| `TOOL_CALL_*`（参数与结果） | 1.6 MB | 2.4% | ~1,500 |
| `TEXT_MESSAGE_CONTENT`（逐 token 的答复） | 1.3 MB | 2.0% | 6,836 |

**要看的东西加起来约 5 MB，占 8%。** 其余 92% 是「折起来就不该传」的东西。

**`model/start` 那一行是这张表里唯一已经被改掉的**（2026-09-24，主人：工具表不再跟着每次模型调用记一遍）。
今天它只带一个 **signature**（`:tools-names-hash` / `:tools-bytes` / `:tools-count`，`harness.kernel.event/model-start`
的 docstring 把那次事故写下来了：`bbcd4ae4-…` 672 行、129.7 MB 的日志里 50.2 MB 是同一张表），**整张表挪到了
system 那一行 `message` 的 envelope 的 `:tools`**，一次 run 写一次，`harness.edge.trajectory` 从那里读它
（`system-item`；trajectory 里读 `model/start` 上 `:tools` 的那几行留着，是给旧日志的后路）。

所以这张表要分两栏读：**旧日志**（`fa35f356` 这份）里 25.5% 是 `model/start` 抄的表；**今天写下来的日志**里
它已经近乎为零。实测最近的一场（`ed334c9c-…`，39.9 MB）：`model/start` **594 行共 0.22 MB**，
**0 行**带表、593 行带 signature。同一场里推理帧 **32.6 MB = 81.7%** —— 那才是今天日志的大头。

三扇门都在打开的那一刻读**整份**记录：

1. **侧栏点开** → `POST /api/threads/<stem>/rebuild`（`ui/src/app.tsx` 的 `HistoryRead = "rebuild"`）。
   `rebuild-post` 自己的说法是 *"the AG-UI message list (seed + every recorded frame, **reasoning and
   tool calls included**)"*——整场对话连同全部推理一起回传。
2. **刷新回来** → `GET …/page`（尾页）加 `GET …/sofar`（`readSofar`；`sofar-get` 对一个 settled 的会话答的
   就是 `rebuild` 那份消息表）。
3. **往前翻** → `GET …/page?beforeSeq=N`（`page-get`）。

还有一遍**每次都发生**的全量读：`GET …/stats` 在 `assistantCount` 变化时重问
（`ui/src/components/composer-numbers.tsx` 的 effect 依赖里有它），走 `harness.edge.stats/log-stats`——
**一个 run 里问很多次，每次从头解析 65 MB**。`context` 就在同一个载荷里，`trajectory` 那扇门同样。

而**折叠今天是渲染层的决定**（`thread.aui.tsx`：`fold === "step" && "hidden"`）：字节、解析、网络、
客户端建 745 条消息，全都付过了，最后用一个 CSS 类把它藏起来。

## 根因（两条，都不是「帧存在哪里」）

1. **读的粒度是「整份记录 → 整场对话」。** `harness.edge.replay` 的每一扇门（`entries` /
   `messages-so-far` / `records->messages`）都从**文件头**开始 `line-seq`，答案的单位是「整场」。
   **没有「只要最近几轮」这种问法**，所以想少传也没有地方少传。
2. **没有从文件尾开始的读。** 记录是只追加的 JSONL，`seq` 就是**行号**（`replay/entries` 的 docstring），
   可是没有任何一条路能 seek 到最后一行附近——要拿尾页，都得先数完整份文件。

（这就是为什么上一轮问的「帧写进 SQLite」是错的那一半：帧**已经在记录里**，而且**每一帧都落盘**
（`harness.edge.http/runner` 对每一帧 `log!` 再 `mux-broadcast!`）。慢的不是存的形状，是**读的形状**。）

## 决策

1. **折叠从渲染层挪到读层。** `harness.edge.replay` 新增一层投影：一轮折成**一张摘要卡**（`calls` /
   `messages` / 它的 `seq` 区间）贴在答案消息上，步骤（推理、工具调用、中间的 assistant 消息）**根本不发**。
   折法照 `ui/src/lib/turns.ts` 已经写死的规则：相邻 assistant 消息是一轮（`turnBounds`）、结论是最后一条
   非空文本（`turnConclusion`）、计数是它的 tool-call 部件与消息数（`turnCounts`）、那行字是
   `turnSummaryLabel`。
2. **最后一轮永远不折。** 它还在长，折它也没有意义。这个判据在服务端（投影）和客户端（窗口）都要能说
   清楚，而且是同一个：`turnIsSettled`。
3. **展开是拉，不是推。** 折好的轮带着它的 `seq` 区间，点开时按区间拉那一轮的原始 entries。这是
   ADR 0003 决策 4 的原话（更早的历史是拉，不是推）——本特征只是让**默认形状**也变成折好的。
4. **读从文件尾开始。** 尾页与往前一页都**反向读**（从 EOF 往前收行，凑够一页就停，切在一轮的边界上）；
   页的答案除 `entries`/`baseSeq` 外再带一个**字节游标**，客户端「更早」按 `?beforeByte=` 问。
   字节游标是文件自己的事实、可重放，而且**不需要新索引文件、不需要动库**——「库不是日志索引」那条裁定
   （`harness.infra.db` 的 docstring、`home-and-storage.md`）因此一个字都不用碰。
5. **那张工具表只服务轨迹那扇门，而且它今天已经不住在 `model/start` 上了。** `model/start` 只带
   signature（`:tools-names-hash` / `:tools-bytes` / `:tools-count`，见上）；整张表在 **system 那一行 `message`
   的 envelope 的 `:tools`** 里，一次 run 一份，只有 `harness.edge.trajectory` 读（`system-item`）。面向屏幕的读
   既不该把它读进 payload，也不该为它每次去解码 envelope——而 `replay/payload` 本来就把 envelope 挡在消息之外，
   所以这一条是**守住**，不是新建。旧日志里 `model/start` 上的 `:tools` 仍要容忍（那是后路），但不必为它建任何东西。
6. **记录格式一个字不动**（ADR 0003 决策 9）。所以那 65 MB **还在盘上**——本特征只保证它不再被**整份解析**、
   不再被**整份传输**。逐 token 的推理 delta 照旧一行一帧（今天日志的 82% 是它——那个决定在
   `.scratch/reasoning-out-of-the-record/`，与本特征各管一头）。
7. **`stats` / `context` 那一遍不再是「第 N 遍」**：会话活着时从内存答（`sofar`/`rebuild` 已有这条先例），
   冷会话的那一遍并进「打开」那一次读里，而客户端不再随着 `assistantCount` 每变一次就全读一次。
   （`stats` 要的是散在整份记录里的 `model/*` 行，所以它**天生**是 O(文件)——本决策治的是「读几遍」和
   「什么时候读」，不是「一遍都不读」。）
8. **一轮的规则有两份实现，这是先例不是例外。** 服务端的投影与客户端的窗口各写一遍同一套规则，和窗口
   那套（`harness.edge.sessions` 的 `tail`/`since`/`before` ↔ `ui/src/lib/window.ts`）是**同一个先例**；
   两份由**一份共享用例表**钉住（现在 `ui/test/suites/turns.ts` 只有 TS 那一半）。

## 票

| # | 票 | 依赖 | 交付 |
|---|---|---|---|
| 01 | 读侧的「一轮折成摘要」 | 无 | `harness.edge.replay` 的投影：折好的轮 + 最后一轮原样。纯函数，规则与 `turns.ts` 由共享用例表钉住。 |
| 02 | 从文件尾开始读 | 无 | 尾页与往前一页反向读，切在一轮的边界上；页带字节游标；`page-get` 不再从文件头 `line-seq`。 |
| 03 | 两扇门改用投影 | 01 | `rebuild` / `sofar` / `page` 的载荷 = 折好的轮 + 最后一轮原样；客户端不再自己折，改画服务端给的那张卡。 |
| 04 | 展开是拉 | 02, 03 | 按 `seq` 区间拉一轮的原始 entries，窗口**原地**替换成步骤；复用 `beforeByte` 那套寻址。 |
| 05 | `stats` / `context` 不再是一遍又一遍的全量读 | 02 | 活着时从内存答，冷会话并进打开那一次读，客户端不再随 `assistantCount` 每次重问。 |

票面见 `issues/01-…md` … `issues/05-…md`。

## 非目标

- **不改记录格式、不动写侧。** 65 MB 的 66% 是逐 token 的推理 delta，本特征治的是**读**，不是**存**。
- **不新增库表、不新增索引文件。** 字节游标 + 反向读就是索引（见决策 4）。
- **不改 `seq` 的语义。** 它仍是行号（ADR 0003 决策 1/9）；字节游标是**另加**的一个前进量，不是替换。
- **不做「整场对话的骨架一次全给」的另一个接口。** 打开给最后几轮，更早的按需拉——ADR 0003 决策 4 的
  窗口代数不动。
- **不改写侧的 delta 粒度**（把逐 token 合成一行要改 ADR；不在这里做）。

## 代价与风险

- **窗口要长出一种新东西**（折好的一轮 + 字节游标）。ADR 0004 的边界里写着「不改 `WindowFrame` 的形状」，
  那句话被本特征取代——**要写进 ADR 0004 的「修正」段或新开一条 ADR**，不能只在代码里改。
- **「一轮的边界与计数」从 TS 搬到 Clojure 之后，两份实现会悄悄分家。** 共享用例表是这一条的解药，也是
  这一票的验收项之一。
- **展开多一次往返。** 主人已拍定接受；但「展开过的轮再折回去」不能重新拉（窗口要记住它已经有原始步骤）。
- **反向读要跟末尾半行同一套判据。** 文件末尾可能有一个没写完的行（`rows-tolerating-a-torn-last-line`
  的既有语义），从尾读必须容忍它，而且**不能把半行当一整轮**。
- **`rebuild` 那扇门还兼着「把一个断掉的 run 收口」的写**（`close-off-open-run!`）。投影不能把这条写弄丢。

## 验证

- **实测数字**（同一场 65 MB 会话，前后各一次，写进本目录的 `evidence/`）：`page` 的答案字节数、
  `rebuild` 的载荷字节数、服务端答一次各要多久、打开时读了多少字节；浏览器里从点击到画出来多久。
  目标是**打开一场会话的载荷 ≈ 最后一轮 + 每轮一张卡**。
- **投影**：`test/harness/edge/replay_test.clj` 钉「折好的轮是什么形状」「最后一轮不折」「展开按区间取回
  原样，与直读整份逐字节一致」。
- **反向读**：`test/harness/edge/http_test.clj` 钉「尾页只读文件尾」「往前一页按字节游标 seek」「末尾半行
  照旧容忍」。判据是**读了多少字节**，不是答对了——答对是必须的，但不足以证明它没读整份。
- **客户端**：`ui/test/suites/` 里窗口新形状的合并语义（折好的轮、原地展开、展开过的不再拉）。
- **共享用例表**：`ui/test/suites/turns.ts` 的那些字面消息列表与期望，Clojure 侧读同一份。
- 真浏览器走查：`node scripts/dev.mjs --scripted` 起服务，打开那一场 65 MB 的会话，滚动、展开某一轮、刷新。
- `cd ui && npm test` / `npm run typecheck` / `npm run build`；`clojure -M:test -m harness.test-runner`。
