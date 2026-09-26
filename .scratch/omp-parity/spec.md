# spec: 与 omp 的六处不一致 —— 照它改齐（五处「同工不同答」加一个名字）

**这是对已落地的锚点编辑那套（`hashline-edit`）与检索/写入三个工具的一次复议。** 锚点机制本身
一个字不动（逐行锚点、四张表、已展示、陈旧判定、批计划器）；动的是**同一件事被答成两种样子**的地方，
除了寻址方案之外的那五处，加上一个工具名。

复议（2026-09-17，牛总）：**不一致的地方都和 omp 一致**。范围分两次定：先是五处「同工不同答」，
再加一条**名字**——`anchor_grep` 改叫 `grep`（牛总：「不需要加 `anchor`」）。
寻址方案、预算数字、撤销的存在、`read` 的能力面都不在内，见「非目标」。

**参考**：omp（`can1357/oh-my-pi`）2026-09-17 读的原文：
`docs/tools/edit.md`（`:132` 的「inclusive, must be ordered」、`:144` 的 Common failures）、
`docs/tools/grep.md`（`:9-11` 的引擎阶梯、`:13` 的 `[PATH#TAG]` + `*5:content`、`:145` 的
`contextBefore=1` / `contextAfter=3`）、`docs/tools/read.md`（`:125` 的 `41:def alpha():`）、
`docs/tools/write.md` 的 Outputs 一节、`packages/coding-agent/src/tools/write.ts`（`stripWriteContent`）、
`packages/natives/native/index.d.ts:125-143`（快照/已展示那套 API）。

**一句话**：这几处没有一处是「omp 有本事我们没有」——都是同一件事的两种判法，而它选的那一边更好：
**拒绝要有，但只在真正歧义的地方**；**显示要一致**；**修补要可见**；**名字不要带一个说了等于没说的词**。

## 问题（六处，各自的证据）

1. **反向区间：我们替你修，omp 拒绝。** `hashline/edit.clj:346-357` 把 `remove_from` 落在 `remove_to`
   之后的调用**交换过来**并只记一句警告（`"remove_from and remove_to were reversed; swapped them."`），
   注释给的理由是「模型说了是哪两行，写回去没有歧义」。omp 把 `Reversed or overlapping ranges` 列在
   **Common failures** 里（`edit.md:144`），它的 parser 要求区间 `inclusive, must be ordered`（`:132`）。
   而我们这边这条行为**没有任何用例盯着**（立票当天 `grep -rn "reversed\|swapped" test/` 无输出）——
   既不承诺也不防（`edit-merge` 决策 10 已经打算把「逆序的区间」写进拒绝词汇表，本特征先把它做掉）。
2. **危险正则：我们一律拒绝，omp 换引擎再不行按字面搜。** `cap/hashline/grep.clj` 的 docstring 写着
   `REGEXES THAT CAN HANG ARE REFUSED`，`catastrophic?`（`:58-76`）按形状拒（反向引用、量词化的组、
   `{NNN,}`、量词化的选择分支），出路是 `literal: true`（`:127` → `--fixed-strings`）。
   omp 从不因语法拒绝：Rust regex 先试 → PCRE2 → **还不行就按字面搜**（`grep.md:9-11`、Notes），
   保护是 30 秒超时（`SEARCH_GREP_TIMEOUT_MS = 30_000`），而且它**没有** `literal` 参数。
3. **命中的上下文：我们默认光秃秃一行，omp 默认自带前 1 后 3。** 我们的 `context` 是**对称**的一个数
   （`tools.clj` 的 schema 里默认 0），omp 是**不对称**的两个数落在设置里（`contextBefore = 1`、
   `contextAfter = 3`，`grep.md:145`）。
4. **`read` 的行不带行号，omp 两个检索工具都带。** 我们的 `read` 行是 `a3f9│content`
   （`reading.clj:189-193` 的 `row`；`│` 的选型理由在 `:180`），`anchor_grep` 的行是
   `%6d │ a3f9│content`（`grep.clj:194-198`），而且它的描述专门解释行号的用途
   （`tools.clj:606-608`：「The line number is for you to talk about a hit, never to edit by」）。
   omp 是统一的：`read` 印 `41:def alpha():`，`grep` 印 `*5:content`（`read.md:125`、`grep.md:13`）。
   一个模型从 `read` 出的行里说不出「第 42 行」，从 `grep` 出的行里说得出。
5. **抄回来的标记：我们指名拒绝，omp 剥掉加一句 note。** `cap/hashline/write.clj` 的
   `echo-line` / `check-no-echo!` 在行首认 `^([A-Za-z0-9]{4})│`，且那四个字符必须是**本会话为该文件
   服务过的锚点**，命中就拒绝并说清第几行、哪个锚点。omp 在 hashline 模式下把抄进来的显示标记
   **剥掉**、写干净的正文，并在答案里补一句 `Note: auto-stripped hashline display prefixes from content
   before writing.`（`write.md` 的 Outputs、`write.ts` 的 `stripWriteContent`）。

6. **工具名多带一个词。** 我们的检索工具叫 `anchor_grep`，而那个 `anchor` 是在说一件显然的事：本仓
   **没有**第二个 grep 与它争——`edit-merge` 决策 7 自己就这么说，然后拿它当**保名**的理由。
   omp 叫它 `grep`（它的检索与编辑也是分开的：`read` / `grep` / `ast-grep` 各一个名字）。
   复议（2026-09-17，牛总）：**不需要加 `anchor`**，改成 `grep`；配置键 `:anchor-grep` 跟着改成 `:grep`
   ——一个键的名字要说出它管的东西，而那个东西现在叫 `grep`。（唯一会被这条改名打到的地方是
   别人的 `harness.edn`；**这台机器上没有那个文件**，见决策 10。）

## 决策

1. **反向区间改成指名拒绝**（票 02）：新理由 `:reversed-range`，话说清两个锚点与正确顺序，
   **一个字节都不写**；`replace` 的描述加一句（一个逆序的区间会被拒绝，不会替你交换），
   因为用过旧行为的人需要一个能读到的说明。
2. **危险正则改走引擎阶梯**（票 03）：原样跑 → 失败且是「正则编译不了」→ 加 `--pcre2` 再跑 →
   再失败 → `-F` 按字面跑（等价于今天的 `literal`），并在答案里说明这次是按字面搜的。
   删掉 `catastrophic?` 与 `:unsafe-regex` 拒绝。
   **阶梯落在 `cap.hashline.grep`**（只有这里构造 `--regexp`），**不进 `infra/rg.clj`**：
   `glob` 走的是 `--files` + `--glob`，没有正则，不该凭空继承一条它用不上的性质。
3. **超时跟着阶梯从 10 秒抬到 30 秒**（票 03）：`rg/timeout-ms` 今天的值是 10 秒（「upstream 的数字」），
   而阶梯把**回溯引擎（PCRE2）**引进来之后，超时就成了唯一的保护——omp 的 30 秒正是为这个存在的。
   这是一个**跟着决策 2 一起动的数字**，不是单独抄来的。
4. **`literal` 参数保留**（票 03）：它是相对 omp 的一处**小超集**——拒绝走了，但「我指的是文本」
   这句话还得有地方说（模型要搜 `a.b` 的字面量时不该被迫去转义）。明写在这里，免得下次有人当成漏抄。
5. **命中的上下文照 omp 的数字与形状**（票 04）：两个参数 `context_before`（默认 **1**）、
   `context_after`（默认 **3**），两个默认值各是一个 `def`（房内先例：`grep/default-limit`、
   `bash-default-timeout-ms` 都是「一个来源 + 描述串插值」），今天的对称 `context` 参数退休。
   **一处明示的分歧**：omp 把这两个数放在**设置**里（`grep.contextBefore/After`），我们放在参数默认里——
   本仓没有「按工具设置」那一层，为一个检索默认值新开两个配置键不划算。
6. **`read` 的行与 `grep` 的行同形**（票 05）：`read` 的行加上行号列（`%6d │ anchor│content`），
   行号是**拿来说话的**，编辑仍然只认锚点（那句说明补进 `read` 的描述）。页脚 `offset=` 的算术不变。
7. **抄回来的标记改成剥掉，判据是「本会话为这个文件服务过的锚点」**（票 05，牛总 2026-09-17 选定）：
   **不**按形状剥。omp 的标记自证身份（`[path#TAG]` 是整行 header、`123:` 是行首数字），
   所以它按形状剥是安全的；我们的标记是**四个字母数字 + `│`**，`2024│Q1` 这种正文会撞上同一个形状，
   按形状剥就是静默改写正文——那比接受一次回显坏得多。剥离之后答案里带一句 note（「你给的东西被改过」
   必须可见；omp 也有那句 note，这一条与它一致）。
8. **`read` 行号那一处与 `write` 回显那一处合并成一张票**（即票 05；牛总 2026-09-17 选定）：
   行号的加入与回显的处理碰的是**同一个函数**
   （识别「抄回来的标记」那一条），omp 也是一处（`stripWriteContent` + 两个格式化器）。合并之后那条规则
   一次写完：`(可选前导行号列) + 四个锚点字符 + │` 才是一行标记。
9. **`edit-merge` 决策 10 的「逆序」半条由票 02 兑现**：那张票（`05-guards-and-refusals`）落地时不必再
   写逆序区间的拒绝，但**它的票面不动**——`.scratch/` 是历史，改口用加注。它的**决策 7**（「`anchor_grep`
   保名、也不改名成 `grep`」）由票 01 推翻，同样加注。
10. **改名 `anchor_grep` → `grep`，配置键 `:anchor-grep` → `:grep`**（票 01）：**不给别名**——
    `CONTEXT.md` 的「就是这些名字，不给它们起别名」照旧，旧名调用得到那条既有的「这个会话不服务它」
    话术。描述文本一个字不改，只改名字与指向它的交叉引用（`glob` 的描述、`rg` 的报错、
    UI 的 `TOOL_ICONS` 与 `subjectOf` 各一处）。**这台机器上没有 `~/.clj-harness/harness.edn`**
    （立票当天实测），所以这条改名今天打不到任何真实配置；真要被写到，本仓的立场是**指名失败**
    （`check-known-keys!`），话说清键名与文件。

## 非目标

- **不动寻址方案**：逐行锚点 vs omp 的 `[PATH#TAG]` + 行号是 `edit-merge` 决策 2 明写要保留的**唯一实质
  分歧**（陈旧与「看得见的行」的粒度停在行上）。本特征一处不动。
- **不动预算数字**：`read` 的 2000 行 / 51200 字节、`grep` 的 `limit` 100 / `max-bytes` 51200 与 omp 的
  300 行 / 每页 20 文件是「同类不同数」，不是两种答案；分页信息写在正文里（我们的）还是
  `details.meta` 里（omp 的）也没有模型可见的差别——本仓的工具有没有 `details` 通道这一说。
- **不删 `undo_last_replace`**：omp **没有文件级撤销**（它的 `rewind`/`checkpoint` 是**对话上下文**的
  修剪，默认关闭），我们是加法不是分歧；`edit-merge` 决策 6 保留它并改名 `undo_last_edit`。
- **不动 `read` 的能力面**：omp 的 `read` 还能吃 `path:50-100` 行区间、后缀唯一匹配、`:raw`、
  归档 / sqlite / URL / PDF 内嵌图、结构化摘要——那是**检索工具的范围**，与锚点无关，另议。
- **不加新配置键**：这几处都用参数默认或代码里的 `def` 表达（决策 3、5）；配置键只发生**改名**
  （`:anchor-grep` → `:grep`，决策 10），数量不变。
- **不给旧名留别名**（决策 10）：`CONTEXT.md` 的「就是这些名字，不给它们起别名」照旧。
- **不动 `write` 的答案**：那是 `.scratch/write-no-content/`（本特征的票 05 阻塞于它）。

## 验收主线

一条真会话（或直调 `run!` 的测试），六件事看得见：

1. 反向区间的一次 `replace` → **指名拒绝**（两个锚点都点到，说清正确顺序），文件一字未动，
   而正向与单行区间的行为逐字不变。
2. 一个 rg 默认引擎编译不了的正则（如带 lookahead）→ **有命中**（走 PCRE2）；
   两个引擎都拒的形状 → **按字面搜**有命中，答案里有一句说明；今天的四类「会挂」形状没有一条再被拒绝。
3. `grep` 不给上下文参数 → 命中自带**前 1 后 3**，那几行可编辑（已登记为已展示）；
   给 `0/0` → 退回今天的光行。
4. `read` 的一行与 `grep` 的一行**同形**（行号 + 锚点 + 正文）；把 `read` 的整行抄进 `write.content`
   → 落盘的是**剥干净的正文**，答案里有一句 note，没有错误；本会话没服务过的 `abcd│text` 一个字不动。
5. 工具表里那个名字是 `grep`，`glob` 的描述与 `rg` 的报错都指向它，UI 那一行还是放大镜图标与
   那个 pattern；`:grep false` 仍然能把它关掉（`harness.edn.example` 里写的是新键）。
6. 全量：`timeout 900 clojure -M:test -m harness.test-runner` 的失败用例名与基线一致；
   `cd ui && npm test` 条数一致。

## 跨特征对照

- **`.scratch/hashline-edit/`（已落地）**：本特征是它的复议。落地那天要往**它的** spec 加一段**加注的
  复议**（旧话不动、被推翻的行划删除线），至少这几处：`read` 行的形状（`row` 与那条「按锚点编辑」的
  说明）、`grep` 的 `REGEXES THAT CAN HANG ARE REFUSED` 段与 `context` 参数、`write` 的
  `THE REFUSAL OF AN ECHO IS WRITE'S OWN SHAPE CONSTRAINT` 段（`write.clj` 的 ns docstring）。
- **`.scratch/write-no-content/`（已立票未落地）**：票 05 **阻塞于**它的 `01`——两者重写 `write.clj`
  的 docstring 与 `test/hashline/write_test.clj`（本仓对「动同一处」的规矩是排顺序）。它落地之后，
  那张 spec 的「`check-no-echo!` 与 echo 拒绝一个字不动」这半句要加注复议（拒绝改成剥离是**本**特征的
  决定），而它的非目标里那句「`write` 的定位」仍然成立。另：它选的那个文档例子（四处都拿
  `{:anchor-grep false}` 当「逐键合成」的示范）被**本**特征票 01 改成 `{:grep false}`——
  两处都还没落地，直接改名即可（不是历史，不用加注）。
- **`.scratch/edit-merge/`（立票未开工）**：决策 10 的拒绝词汇表里「逆序或重叠的区间」由本特征票 02
  先兑现（重叠仍归批计划器，`replace.clj` 的 `:batch-overlap` 不动）；决策 11 的「能修的只有一种」
  在票 05 之后仍然成立（剥标记是修，且**可见**）。**决策 7 的「`anchor_grep` 保名…也**不改名成
  `grep`**」被票 01 推翻**——落地那天加注复议（旧话不动、被推翻的那一行划删除线），
  理由照票面：本仓没有第二个 grep 与它争，`anchor` 是在说一件显然的事。
- **`.scratch/immutable-data/`（已立票未落地）**：这几处都不碰锁与共享状态；`store.clj` 一行不改。
- **`docs/architecture/kernel.md`**（锚点那一节对 `write` 与检索的描述）与 `client.md` 的两张表：
  收口那天要跟着改的是四件事——`read` 的行形状、`grep` 的名字（三页文档的清单与模式表）、
  它的正则立场与上下文默认、`write` 剥标记；没有新工具、没有新表，工具数仍是**十八**。

## 交付顺序

`01 → 02` 可以并行（不同文件）；`03` 阻塞于 `01`，`04` 阻塞于 `03`，`05` 阻塞于
`.scratch/write-no-content/01`。**同一张脸的三张票按 01 → 03 → 04 走**：先定名字，再改那张脸上的正立场，
最后加两个参数——本仓先例是 `bash-lifetime` 的 01/02（「两者动同一处与同一套词」，两张票最后落在一个
提交里）。

**五处分歧落在四张票上**（第六件事——名字——落在票 01）：④（`read` 的行加行号）与 ⑤（回显改成剥掉）
合并成票 05——它们碰同一个函数（识别「抄回来的标记」那一条），omp 也是一处（`stripWriteContent`
+ 两个格式化器）。

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | `anchor_grep` 改名 `grep`（配置键跟着改成 `:grep`） | — | 工具名与配置键；`glob` 描述里的交叉引用、`infra/rg.clj` 的报错话术、UI 两处（`TOOL_ICONS` 与 `subjectOf` 的 case）、`CONTEXT.md` 的闭清单与三页文档的清单/模式表；描述文本一个字不改 |
| 02 | `replace`：反向区间指名拒绝 | — | 删掉交换与那句警告，新增 `:reversed-range` 拒绝（点名两个锚点与正确顺序）；描述加一句；用例补上今天缺的那条 |
| 03 | `grep`：危险正则不再拒绝，改走引擎阶梯 | 01 | 删 `catastrophic?` 与 `:unsafe-regex`；原样 → `--pcre2` → `-F` 三段阶梯（落在 `cap.hashline.grep`）；按字面搜时说一句；`rg/timeout-ms` 10 秒 → 30 秒；`literal` 参数保留 |
| 04 | `grep`：命中默认带前 1 后 3 | 03 | `context` 参数退休，`context_before`（1）/`context_after`（3）登场，两个默认值各一个 `def` 并插值进描述；已展示集合跟着包含上下文行 |
| 05 | 显示标记：`read` 的行带行号；抄回来的标记剥掉不拒绝 | `.scratch/write-no-content/01` | `read` 的行与 `grep` 同形；`check-no-echo!` 改成「识别 + 剥离 + note」，判据是本会话为该文件服务过的锚点；描述与 `kernel.md` 那三句跟着改 |

## 状态

**立票，未开工**（2026-09-17）。基线（`main` @ `8cd325f`，立票当天实测）：

- 后端：`timeout 900 clojure -M:test -m harness.test-runner`
  → `Ran 855 tests containing 11252 assertions. 0 failures, 0 errors.`（`EXIT=0`）。
- 前端：`cd ui && npm test` → `Test Files 1 passed (1)` / `Tests 31 passed (31)`
  （`EXPECTED_CASES` 钉在 31，`ui/test/ui.test.ts:62`；本特征不碰 `ui/`）。

**这台机器上的读法**：同一条后端命令在并发下会撞到隔离守卫（`ISOLATION FAILURE: …/.clj-harness/harness.db
changed during this run`），那不是树里的问题——`layer-layout/spec.md:322-332` 已经用硬证据说明这台机器上
有**活的 app** 在用**真** `~/.clj-harness`。报数只认 `Ran … 0 failures` 那两行；撞到守卫先看它打印的
before/after，别急着当成自己写红的。

## 落地结果

**票 01（`anchor_grep` → `grep`，`:anchor-grep` → `:grep`）**：2026-09-22 落地，票文件按本仓规矩删掉。

- 工具名与配置键改成 `grep` / `:grep`：`register!`、`cap.editing` 的 `defaults` / `vocab` /
  `families` / `search-tool`（工具名与键两处）、`harness.edn.example` 的开关与注释。
- 交叉引用与话术跟着改：`glob` 描述里的「use `grep` when you are looking for content」、
  `infra/rg.clj` 的 `require-posix!` 报错、`cap.hashline.grep` / `reading` / `web` / `web.search` /
  `glob` 的 docstring。
- **描述文本一个字没改**（除自指与交叉引用）：`anchor│` 那套、`literal`、
  「No read afterwards is needed」都在原地。
- **不给别名**：`CONTEXT.md` 那张闭清单仍是唯一的名字来源，旧的 `anchor_grep` 认不出来。
- 文档与提示词：`docs/architecture.md`（工具清单与 edit-merge 那段）、`kernel.md`（模式表与三处）、
  `client.md` 的 `TOOL_ICONS` 表、`CONTEXT.md`、`README.md`、`prompt.md`；`docs/architecture/layers.md:45`
  那句历史例子一个字不动。
- **`.scratch/`**：未落地的计划跟改（`write-no-content` 的 spec 与 01，例子 `{:anchor-grep false}` →
  `{:grep false}`；`edit-merge` 的 05 / 06）；`edit-merge` 决策 7、非目标那一行与 `issues/04` 各加**加注的
  复议**（旧话划删除线 + 理由），已落地特征的 spec 不动。
- **用例**：两条硬编码的工具名向量把 `grep` 挪到 `glob` 之后（排序是断言的一部分）；
  「关掉它的开关」那条从「消息里含 grep」收紧成「消息里含 `:grep false`」；`glob_test.clj` 新增
  `the-description-sends-the-reader-to-the-content-search`，把交叉引用钉住（且不许再出现旧名）。
- **UI**：`TOOL_ICONS` 与 `subjectOf` 两处改名，并新增 `ui/test/suites/tool-row.ts` 两条用例各钉一处。
  票面要的是「渲染出来读回」，而这个 run 到不了那一行——`message-parts.tsx` 经
  `composer-chrome.tsx` → `lib/attachments.ts` 摸到 `lib/i18n.ts` 的 `document`（模块作用域），
  而本仓的 vitest 刻意没有 jsdom/window，所以那两种情况改读**源码**（`?raw`），
  与本目录里 `sidebar.tsx` / `i18n.ts` 两条同样的做法。**画出来的那半是走查的**：
  `node scripts/dev.mjs --scripted`。`EXPECTED_CASES` 92 → 94。
- 实测：后端全量 `Ran 1086 tests containing 12815 assertions. 0 failures, 0 errors.`（`EXIT=0`，
  隔离正常——那条 `ISOLATION NOTE` 说的正是这台机器上那个活着的 app）；
  `cd ui && npm run typecheck` 干净；`cd ui && npm test` → 93 passed / 1 failed，失败的是
  `skills > asking-for-the-list-changes-nothing`，**与本次改名无关**：把本票的改动全部 stash 掉，
  同一条用例照样红（后端与 UI 都不碰它；另一轮 92 条全绿过，所以它是这条机器上的不稳定项）。
- **画出来的那一行，在真浏览器里看过**：用 `node scripts/dev.mjs --scripted` 配一份带 `grep` 工具调用的
  脚本起一套隔离实例，页面里发一条消息，那一行渲染成：名字 `grep`、图标 `lucide lucide-search`
  （不是兜底的 `lucide-wrench`）、主题 ` · clj-harness`（就是 pattern）。证据：
  `evidence/grep-row-after-rename.png`。这条也解释了上面那条用例为什么读源码也不算糊弄——
  它钉的是两张表的名字，**画**的那半在这里补上了。
