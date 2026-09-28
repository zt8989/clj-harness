# Handoff —— 记录重整化 票 03 / 04（lisp-harness）

写给下一位 agent。**先读这段，再动手。**

## 0. 你现在该做什么

`.scratch/record-normalization/` 的四张票，**01 与 02 已落地并合进 main**（提交 `73c2170`）。
剩下两张：**03（fork 变成重整化）→ 04（前端强制 fork）**。顺序别换：04 的门要靠 03 真的能修好一份记录。

- 03：`POST /api/threads/<stem>/fork` 今天「逐字复制截点之前的行 + 给被截断的 run 补终局」；
  要升级成**重整化**：同一趟里把「只有帧、没有 `message` 行」的那些调用**补上那一行**，
  且**幂等**（对已重整化的记录再 fork，产物逐字相同）。原会话一个字节不动。
- 04：`ui/` —— 未重整化 ⇒ 禁用输入（发送/resume/compact）+ 一条说明 + 一个「重整化（fork）」按钮，
  点它走 03 的门，成功后自动开新会话；文案进 i18n（`ui-i18n` 的词表）。

## 1. 位置与状态（写这份文档时）

- 主工作树：`C:\Users\zhouteng\Documents\workspace\lisp-harness`，**main = `73c2170`**，工作树干净。
- 「记录重整化」的账在 `.scratch/record-normalization/spec.md`（两张票的**落地小节**都在文末，
  含判据数字与两个例外）；判据对着真记录的走查读数在
  `.scratch/record-normalization/evidence/real-records.md`。
- 后端门：`clojure -M:test -m harness.test-runner <ns…>`；一轮全绿的数字见 spec。
  **单跑一条用例**（省时间、也躲开机器的抖动）：
  `clojure -M:test -e "(require 'harness.test-runner)(harness.test-runner/isolate!)(require 'harness.edge.http-test)(clojure.test/test-vars [#'harness.edge.http-test/<名字>])"`

## 2. 这一程做了什么（细节在产物里）

- **判据（01）落地的形状**：`src/harness/edge/normalized.clj` 的 `step`/`finish`/`fold`/`normalized?`。
  三条判据：新格式、信封成对、工具调用与答复成对。读侧（`sofar` 的记录路径）用
  `replay/fold-sofar` 喂 `normalized/fold`，**一趟读**同时答消息与判定；本进程持有的会话按会话答 `true`。
- **两个例外**（都是真形状逼出来的，别删）：
  - `parked-calls`：终局帧 `outcome.interrupts` 点了名的调用是**等人裁**，不是缺行；
  - **被切/被停的调用是真的缺行** —— 所以写手那一侧补上了：`loop.clj` 的停止分支、
    `replay/closing-frames` 的 `:messages`（→ `close-off-open-run!` / `fork-session!` 落行），
    两处共用 `harness.kernel.frames/tool-message`（**一处拼法，票 03 也用这个**）。
- **门（02）**：`handle-run` 修缮之后**只读一次**记录（`record-shape` = 信封 + 判定），未重整化 ⇒
  409 一条句子 + `:reason "unnormalized"` + `:normalizationReasons`，点名
  `POST /api/threads/<stem>/fork`；`compact-post` 用已读的那份记录答同一判定；`rebuild` **不在此列**
  （它是修缮并交回那道门）；读的门一个字节没动。
- 走查脚本：`dev/scratch_normalized_census.clj`（只读 `~/.clj-harness`，`-J-Dstdout.encoding=UTF-8`
  跑，中文才不乱码）。

## 3. 03 的落点（我量过、但没动手）

1. **补哪一行**：判据 3 抓到的正是「答复只剩 `TOOL_CALL_RESULT` 帧」。那一行的内容就在帧里
   （`:toolCallId` + `:content`），用 `frames/tool-message` 拼 —— 与写手同一处，别另写一份。
2. **补在哪**：`fork-session!`（`src/harness/edge/http.clj`，约 4100 行）今天把 `keep` 的行逐行
   `stream/push!` 进新文件，再按 `closing-frames` 补终局帧 +（02 之后）那几行答复。缺的行要在
   **同一趟**里补：对 `folded`（或对 keep 的那些行）跑一遍判据的账，拿 `:calls` 减去
   `:answers`/`:parked`，就得到「该补哪些调用」；内容从记录里那条 `TOOL_CALL_RESULT` 帧取。
3. **幂等**：对一份**已重整化**的记录再 fork，产物必须逐字相同 —— 上面那个集合为空时，
   一行都不许多写（用例：fork 两次，第二次新文件与第一次逐字相同）。
4. **旧格式**：`replay/read-row` 对顶层 `kind` 的行**按名字拒绝**（`:old-contract`），所以
   `fork-session!` 今天在旧格式记录上根本读不到东西。「补齐信封（新格式）」要不要真的迁移旧记录，
   是这一票里唯一**还没定**的取舍 —— 我倾向：先按现状**明确拒绝**（一句话说清「开新会话或换旧 build
   读它」），把「迁移旧格式」留成单独一票；动之前问主人一句。
5. **用例**：缺行（真形状：`~/.clj-harness/projects/.unbound/http-answer.jsonl` 那种）、
   信封不成对、缺行 + 信封同时缺，各一条；再一条「原会话字节不变」；再一条幂等。

## 4. 04 的落点

- 客户端拿 `:normalized` 的两条路：**本进程持有**的会话（`sofar`/`rebuild` 的内存分支）答 `true`；
  **从记录读**的那条带 `false` + `:normalizationReasons`（中文句子，直接可画）。
- 「未重整化」今天主要出现在：旧格式记录（读侧直接 400）、被切/被停**且**答复行仍缺的记录、
  外来记录。所以 04 的界面不要假设「经常出现」。
- 合之前按 `AGENTS.md`：`cd ui && npm test && npm run typecheck`，再
  `node scripts/dev.mjs --scripted` 起服务、自己开浏览器走一趟（机器门挡不住「渲染看不到布局」）。

## 5. 硬知识（省得重走）

- **判据只认行自己的身份**：记录一行是 `{:type "event" :payload {:type …}}`，帧类型在 payload 里；
  `message` 行的工具身份是 `payload.tool_call_id`，帧的是 `payload.toolCallId`。
- **别用位置判「包裹」**：真记录里 message 行写在它那组帧**之后**（走查读数就在 evidence 里）。
- **本机跑测试会抖**：并行跑第二个 JVM（或别人也在跑）时，`http` 那套会因脚本 provider 的
  500ms idle 或 300s 上限出**假红**（`RUN_ERROR: the model went quiet…`、`a-stop-does-not-wait…`、
  `the-git-endpoint…`、`asking is read-only…`）。**单跑那一条**复绿即可，别去改代码。
  真绿的一轮：`217/1730/0`（http 177.9s）。
- **文件工具的坑**（我踩过）：`insert`/`replace` 的 `replacement_lines` 里，**一行如果是合法的
  JSON 数组**（比如单独一行 `[]`）会被当成「粘进来的数组」**拆掉** —— 需要 `[]` 参数表时，
  写在同一行（`(defn- f []`），或改用 `(list …)` 别用 `[…]` 起头/收尾的裸行。
- **大文件（`http.clj` / `trajectory.clj` / `loop.clj`）一律锚点编辑**，别用 `sed`；改完**先编译**
  （`clojure -M:test -e "(require 'harness.edge.http …)"`）再跑用例：括号错在用例里报得很远。
- 中文注释/文档是这个仓库的第一语言；提交信息也是中文；**每条提交都要带判据数字**。

## 6. 建议调用的 skill

- **`to-tickets`** —— 03/04 的票就按它写（完成即删票；01/02 的票已经删了）。
- **`tdd`** —— 03 的判例（幂等、字节不变）先写再接线。
- **`code-review`** —— 提交前过一遍（`http.clj` 那种大文件尤其）。
- **`diagnosing-bugs`** —— 若 fork 的产物读不回来（幂等/字节用例红），先量「补了什么、补在哪」。
- **`handoff`** —— 再交给下一位时，按这份的形状写。
