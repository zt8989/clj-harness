# Handoff —— 记录重整化 票 03 / 04（lisp-harness）

写给下一位 agent。**先读这段，再动手。**

## 0. 你现在该做什么

`.scratch/record-normalization/` 的四张票，**01、02、03 已落地并合进 main**（提交 `73c2170`、之后那一笔）。
只剩**一张：04（前端强制 fork）**。

- **03 已落地**（`replay/missing-tool-rows` + `fork-session!` 按 run 分组补那一行；幂等靠「没缺的行就一行不写」；
  旧格式按名字拒绝、不迁移）。落地小节在 `spec.md` 的「落地：票 03」；数字 **286/2047/0**。
- 04：`ui/` —— 未重整化 ⇒ 禁用输入（发送/resume/compact）+ 一条说明 + 一个「重整化（fork）」按钮，
  点它走 03 的门，成功后自动开新会话；文案进 i18n（`ui-i18n` 的词表）。
  后端**已经就绪**：记录路径答 `:normalized false` + `:normalizationReasons`（中文句子），
  写门（run/resume/compact）答 409 + `:reason "unnormalized"` + 一条点名 fork 地址的英文句子。

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

## 3. 03 落地了（做法与它留下的一个取舍）

1. **补哪一行**：`replay/missing-tool-rows`（新增）——只有 `TOOL_CALL_RESULT` 帧、没有那一行 `message`
   的调用，`{:run-id .. :message <行>}`，内容取自帧本身，行用 `frames/tool-message` 拼（与写手同一处）。
2. **补在哪**：`fork-session!` 在同一趟里 `group-by :run-id` 之后 `log-messages!`（**按 run 分组**——
   我第一版用 `partition-by` 解构 `[[run-id entries] ...]`，那是**行不通**的：`partition-by` 给的是
   一串 seq，不是键值对；`group-by` 才是。调试现场：新记录 9 行、少的就是那一行）。
3. **幂等**：没有缺的行时一行不写，所以对已重整化的记录再 fork，产物除了它自己的 header 与
   `session/forked` 之外逐字相同（用例逐行比过 `rows-of`）。
4. **靠切点丢掉的不用补**：半截的信封在最后一步的结束之后，切点根本没复制它。
5. **还没定的一件事**：旧格式记录（顶层 `kind`）读侧按名字拒绝（`:old-contract`），fork 照直答 400。
   要不要真的**迁移**旧记录，是这一票留给你和主人的取舍——现在**先明确拒绝**，别静默改写；
   要动就单开一票。
6. **坑**（我踩了）：一条**单独成行**的旧格式行会被 `rows-tolerating-a-torn-last-line` 当成
   「写手正在写最后一行」吞掉，于是用例会以 `no-fork-point` 而不是 `old-contract` 红——旧格式的
   判例至少要**两行**。

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
