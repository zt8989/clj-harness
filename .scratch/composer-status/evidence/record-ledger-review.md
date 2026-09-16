# 记录清单逐行核（票 04 的验收，也是评审时的检查表）

spec 的「记录清单」是**闭的**：条子上每一格都要指得到记录里的某一行，而且**同一件事只有一处实现**。
这一页就是逐行核过的结果——左边是清单那一行，右边是**代码里唯一那一处**（用搜的办法核的，不是看个大概）。

| 条子上的格子 | 记录里读哪一行、哪个字段 | 唯一实现（代码位置） | 有没有第二处 |
|---|---|---|---|
| `2 turns` | `input` 的 `:messages` 里 `role: "user"` 的 **id 差集** | `harness.edge.stats/user-ids` + `turns` | **没有**。全仓搜 `role … "user"` 只搜到：`edge/replay.clj`（折对话，看的是消息本身）、`edge/ag_ui.clj`（入站翻译）、`cap/preamble.clj` 与 `cap/skills.clj`（拼 user 侧开场块）——都在**用**这个角色，没有第二处在**数轮**。客户端 `thread.aui.tsx` 那套 `isTurnEnd` / `isTurnContinuation` 判的是**相邻性**（一次 ReAct 轮在线上是好几条相邻助手消息，组件要知道哪条是这一轮的头尾），它**不数数**，条子的轮数不从它来 |
| `2 steps` | `model/start` 的行数，**按次序**与 `model/end` 配对 | `harness.edge.stats/calls` | **没有**。`start`/`end` 的配对只在这里；`loop/model-call!` 是**写**那两行的人，不数 |
| `1371 tok/s` | Σ `completion_tokens` ÷ Σ（`model/end.ts − model/start.ts`） | `output-tokens-per-second` | **没有**。分母只由**成对的行**差出来，不是别的两行之差——`grep completion_tokens` 在 `src/` 里只命中这里与 `cap/providers.clj` 的一处**目录字段**（`max_completion_tokens` 是模型能产多少，与用量无关） |
| `2k tok` | Σ `total_tokens`（该键缺了的那次按 prompt + completion 补，**两半都在才算**） | `total-of` + `usage-of` | **没有**。`total_tokens` 在整个 `src/` 里只被这两个函数读；`ui/src` 一次都没提（客户端拿到的是折好的数） |
| `91% cached` | Σ cached ÷ Σ prompt，**只取同时报了两半的调用** | `cache-hit-rate` | **没有**。`cached_tokens` 只在这里读（另一处提到它的是 `llm.clj` 的一句注释，说的是**厂商怎么拼这个词**，不是算它） |
| 缺的那几格不画 | `records->stats` 的 `cond->`：没报的键**不出现** | `harness.edge.stats/records->stats` | **没有**。客户端拿到 `undefined` 就留空（`ui/src/lib/format.ts/statsCells` 只是决定「写不写」，**不算**），没有任何一处把缺席填成 0 |
| 条子怎么画 | —— | `ui/src/components/composer-stats.tsx`（位置、字号、图标、`tabular-nums`） | 画法与数是分开的：数在服务端折、字符串在 `format.ts` 拼、位置在组件里定。三处各管一件事，没有一处兼任两件 |

**另外两条也核过**：

- **一行都不加，一行都不改**：这两条是**审计行**（`model/start` / `model/end`），不是帧——
  `harness.edge.ag_ui/convert` 明说这两种没有帧，`harness.wire` 的帧校验全过（全量里那条用例）。
- **不进库**：`harness.edge.stats` 一个 sqlite 调用都没有；`docs/architecture/overview.md` 的「状态存在哪」
  里，它是**不存**那一档（每次从记录折）。

复核用的命令（都能重跑）：

```bash
grep -rn "cached_tokens\|total_tokens\|completion_tokens" src/ ui/src/
grep -rln ':role "user"' src/
clojure -M:test -m harness.test-runner -e "(require 'harness.edge.stats-test)"   # 手搓记录那 8 条
```
