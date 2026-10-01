# spec: 与 pi-hashline-edit-pro 4.4.1 对齐（差额清单）

**本仓的锚点编辑是上游 `pi-hashline-edit-pro` v4.2.11 的一次重实现**（见 `NOTICE`：版本钉在 4.2.11，
锚点表从那一版的 `anchors` 串抄来）。上游随后走到 **4.4.1**，中间有 4.3/4.4 两轮改动。
这次审计逐项比对了本仓 `src/harness/cap/hashline/*` 与上游 4.4.1 源码，**锚点池逐字节相同**
（池大小 1,353,139、表 sha256 `67175ac4…ecb4d`、probe stride 836286 都一致），
因此本 spec 只收两类差额：

1. **上游有、本仓没有**的小而安全的兼容项（可直接实现）；
2. **上游改了、本仓没跟**的行为变化（需要人拍板，不静默跟）。

刻意不跟的上游实现（换算法/换存储/换命名等）单列在票 09（`wontfix`），不进施工范围。

**参考**：上游本机工作树 `pi-hashline-edit-pro @ 68346e7`（`package.json` = 4.4.1，工作树干净）；
上游 `git log 4.2.11..4.4.1` 的关键提交：`e64a717`（auto-read-all 起步）、`7cc0d4c`（`from`/`to` 别名）、
`97264dd`（字符串化行字段归一）、`1cdb246`（批与单独提交的字节护栏）、`561ff2b`（**移除 boundary dedup**）、
`ded316b`（锚点大小写提示）。

## 差额表

| # | 差额 | 上游证据 | 本仓现状 | 处置 |
|---|---|---|---|---|
| 01 | 编辑/写入结果无大小护栏 | `src/commit.ts:50`、`src/batch.ts:617` 调 `assertByteLimit(final)`，`MAX_BYTES = 100*1024*1024`（`src/constants.ts:2`） | `max-file-bytes` 只在 **read** 上生效（`reading.clj:29-33`、`:135`），replace/insert/write 的结果不查 | 票 01 |
| 02 | `from`/`to` 别名 | `src/utils.ts:15-31` `normalizeAnchors` 同时认 `replace_from/replace_to` 与 `from/to` | `edit.clj:237-243` 只认 `replace_from/replace_to`（+ `file_path`） | 票 02 |
| 03 | 字符串化的行字段归一 | `src/utils.ts:347-387` `decodeStringArray` + `normalizeEditLines`：字段本身是 JSON 串、或单元素数组里塞 JSON 串，都解回数组 | `replacement-arg` 只处理「数组里某一元素塞了整段 JSON」，字段本身是字符串时抛 `:not-an-array`（`edit.clj:105-115`） | 票 03 |
| 04 | 锚点大小写提示 | `src/edit-common.ts:99-108` `staleAnchorMessage`：只差大小写的锚点点名 `Anchors are case-sensitive; "Hasu" differs only in case.` | `edit.clj:113-135` 的 `anchor-arg` 只说「四个字符、要照抄」，不提大小写 | 票 04 |
| 05 | read 页脚起始行 | `src/read.ts:46-55` `formatPaginationHint(startLine, endLine, …)` 用真实起点 | `reading.clj:221-227` 写死 `"lines 1-"`，`offset>1` 时印错 | 票 05 |
| 06 | boundary dedup | `561ff2b`（4.4.0）**整体移除**；4.3.9 先默认 off | 仍在，且**默认 `:on`**（`editing.clj:74`、`edit.clj:424-473`） | 票 06（决策） |
| 07 | auto-read-all | `e64a717`+`d059214`+`cb8a748`+`071ab9c`（4.3.0-4.3.3）：会话开头整仓注入锚点，`off/on/git`、ignore 目录、`[E_AUTO_READ_ALL]` 重读拒绝，默认 off | 完全没有；`hashline-edit` 的 spec 还删掉了 `:auto-read` 键 | 票 07（决策） |
| 08 | 响应里的 `patch`/`diffLineNumbers` | `src/replace-diff.ts:566` `genPatch`；`src/replace-response.ts:157,162` | 只有带锚点的 `+/-/上下文` 行，无 unified patch、无行号数组 | 票 08（决策） |
| 09 | 刻意不跟的实现 | 见票面 | — | 票 09（`wontfix` 记录） |

> **2026-10-01 复议（票 10）——03 行的「上游证据」是错的。** 上游 `replace` 拿到**字符串**字段时走的是
> payload 分支（`src/hashline/resolve.ts:198-200` → `parsePayloadText`），契约就是「the exact text to
> write」：**原样写文本、不解 JSON**。包内实测（真实 `replace` 工具，落盘字节）：
> `replacement_lines: "[]"` → 一行 `[]`；`replacement_lines: "[\"a\",\"b\"]"` → 一行 `["a","b"]`。
> `decodeStringArray` 的**字符串分支**在 replace 路径上根本走不到——全仓除 `utils.ts` 自己，只有
> `resolve.ts:203` 一处调用它，而那处拿到的是数组。所以「字段本身是字符串就解回数组」是本仓**自己的**
> 读法，票 03 当初把它记成了 parity。见
> `.scratch/hashline-upstream-parity/issues/10-string-field-decodes-json-not-upstream.md`。

## 交叉：已有票覆盖的部分（**不重复立票**）

- **`read` 行加行号 / 抄回 write 的标记从拒绝改剥离** → 已在
  `.scratch/omp-parity/issues/05-markup-shape-and-strip.md`（`ready-for-agent`）。本次审计发现
  `write.clj:63` 的 echo 正则漏抓 `grep` 的 `    42 │ Hasu│…` 行首数字列——**正是 omp-parity 05
  要修的那一处**（它把判据扩成「可选前导行号列 + 四字符 + `│`」），所以不再立票。
- **`grep` 命中上下文默认、正则阶梯、`anchor_grep`→`grep` 改名** → `.scratch/omp-parity/`，已落地或已立票。

## 非目标（本特征内不动）

- **不动锚点机制**：分配/对齐/已展示/陈旧判定的语义保持——本仓相对上游的几处**有意**偏离（不做内容
  派生 base、不回卷 probe、不掺 pid、不回收已释放锚点、无 spans 时只做前后缀对齐、共用 sqlite 而非
  会话 sidecar）见票 09，保持现状。
- **不动数字**：`heal-rows` 40（vs 上游 `MAX_RANGE_STALE_LINES` 100）、`grep` 行超长按 51200 整行
  （vs 上游 `MAX_GREP_LINE_BYTES` 500 碎片）、read 越界抛错（vs 上游返回文本）——是「同类不同数」或
  本仓更严的选择，见票 09。
- **不动工具名**：`undo_last_replace`（上游 `undo_last_change`）、单独的 `grep`（上游 `anchor_grep` +
  宿主 `grep`）——本仓命名立场见 `CONTEXT.md`，`grep` 改名已由 omp-parity 01 落地。

## 交付顺序

票 01–05 互不阻塞，可并行（各动一处，其中 01 与 05 都碰 `reading.clj` 的邻居，注意 05 只改页脚）。
票 06/07/08 是决策票，**拍板前不开工**；票 09 是记录，不施工。

| # | 票 | 状态 | 交付什么 |
|---|---|---|---|
| 01 | 编辑/写入结果的大小护栏 | ready-for-agent | replace/insert/write 落盘前按 `max-file-bytes`（100 MiB）拒绝，与 read 同一把尺 |
| 02 | `from`/`to` 别名 | ready-for-agent | `remove_from`/`remove_to` 的兼容写法 |
| 03 | 字符串化的行字段归一 | ready-for-agent | 字段本身是 JSON 串（或 `["…"]`）时解回数组，带 `[W_` warning |
| 04 | stale-anchor 大小写提示 | ready-for-agent | 拒绝话里点出只差大小写的锚点 |
| 05 | read 页脚起始行 | ready-for-agent | 页脚印真实起始行号 |
| 06 | boundary dedup | 已落地（整体移除，2026-09-30） | 跟上游 4.4.0：`replace`/`insert` 一律按字面应用 |
| 07 | auto-read-all | wontfix（2026-09-30 拍板） | 明确不做；将来要翻案另开 spec |
| 08 | `patch`/`diffLineNumbers` | wontfix（2026-09-30 拍板） | 不补：本仓无消费者 |
| 09 | 刻意不跟的上游实现 | wontfix | 记录，不施工 |

## 状态

**立票，未开工**（本次会话）。基线待开工当天实测：

- 后端：`timeout 900 clojure -M:test -m harness.test-runner`
- 前端：`cd ui && npm test`（本特征前五张票都不碰 `ui/`，票 08 若做要看 UI 是否消费 `details`）

**这台机器上的读法**：后端全量在并发下可能撞隔离守卫（`ISOLATION NOTE` 不算红）；
报数只认 `Ran … 0 failures, 0 errors.` 那一行与退出码。细则见 `docs/rules/testing.md`。

## 落地结果

**票 01–06 落地**（2026-09-30）；票 07/08 `wontfix`（明确不做，见各自票面的拍板结果）；
票 09 `wontfix` 不动。票 06 单列在下面的「票 06 落地」。

改动（按票）：

- **01 大小护栏**：`files.clj` 新增公开 `max-bytes`（100 MiB）与 `check-size!`；`reading.clj` 改引用它
  （read 的数字与话术逐字不变）；`write-file!` 与 `write/perform!` 落盘前各查一次——replace /
  insert / undo 三条写路径都由 `write-file!` 收口。**行为变化**：结果超过 100 MiB 的编辑/写入现在
  **指名拒绝**、一个字节都不写（以前会写下去，直到下一次 read 才报）。
- **02 别名**：`edit/parse` 认 `remove_from`/`replace_from`/`from`（`_to` 同理）并按此优先级取值；
  `tools.clj` 的 replace `:required` 由 `[:remove_from :replacement_lines]` 收成 `[:replacement_lines]`，
  缺锚点时由 parse 给指名错误；`replace.clj` 的 target-path / plan 两处 `or` 补 `:from`。
  **顺带修好一件从未接线的事**：`replace_from`/`replace_to` 此前只进了 `known` 白名单、值恒从
  `(:remove_from args)` 取——只给别名会得到「got nil」的 `:bad-anchor`。**行为变化**：只给
  `from`/`to`/`replace_*` 现在是一次正常编辑。
- **03 字符串字段**：`replacement-arg`（`replace` 的 `replacement_lines` 与 `insert` 的 `lines` 共用）
  接受「字段本身是字符串」：JSON 数组解回数组、其余按换行切行，带 warning；`:strict-input` 会拒绝它。
  **行为变化**：`replacement_lines "X"` 以前是 `:not-an-array` 拒绝，现在是单行 `"X"`（`42` 等非字符串仍拒绝）。
  > **2026-10-01 复议（票 10）**：上面那句里的「上游」不成立——上游的字符串字段是 payload，原样写文本。
  > 「非空 JSON 字符串数组就解回数组」是本仓的读法。顺带：`[]` **空数组**那一格已由 `hashline-edit`
  > 票 01 改成「写一行字面量 `[]`」——解包成零行等于悄悄删掉这一段。
- **04 大小写提示**：`replace.clj` 的 `unresolvable!` 先查本会话是否持有只差大小写的锚点，命中就点名；
  否则维持原来的 `:not-read` / `:not-an-anchor` 两句。**行为变化**：只改一句拒绝话术，不挡任何编辑。
- **05 页脚起始行**：`reading.clj` 的 `footer` 收真实起始行。**行为变化**：`offset>1` 且被截断时页脚从
  `lines 1-…` 变成 `lines <offset>-…`；`offset=1` 逐字不变；`offset=` 续读算术不变。

**顺带的既有 bug 修复（不在任何票面里）**：`edit.clj` 里 `:require-path` 的拒绝话术还写着
「the project's harness.edn」——config-merge 把 `:editing` 搬到 `config.edn` 的 `:session` 之后这句没跟，
已改成 `config.edn's :session :editing`。

### 票 06 落地（boundary dedup 整体移除，2026-09-30，分支 `hashline-boundary-dedup`）

严格照上游 4.4.0（`561ff2b`）：**编辑一律按字面应用**。删掉了：

- `edit.clj` 的 `dedup-edges`、`;; --- the dedup` 段、`:boundary-strict` 拒绝、`render-diff` 的 `stripped`
  入参与 `dedup│` 行，以及 ns docstring 里那段；
- `replace.clj` 的 `dedup-edges` 调用、plan 里的 `:stripped`、`ok-message` 的 `:stripped` 归并；
- `editing.clj` 的 `:boundary-dedup` 默认值与 `vocab` 项；`config.edn.example` 的那段；
- `tools.clj` 的 replace 描述里「会被 dedup」那句、insert 描述里「unlike replace … never deduplicates」；
- `insert.clj` 里「NO BOUNDARY DEDUP」那段改为「重复的行照写」。

**行为变化**：一个「把范围外的邻行再抄一遍」的 `replacement_lines` 不再被剥掉——那一行会真的多出来。
旧 config.edn 里残留的 `:boundary-dedup` 现在得到「未知编辑键」的具名失败（新用例
`boundary-dedup-is-also-an-unknown-key` 钉住）。

**用例**：删掉 4 条 dedup 用例，换成 1 条「按字面应用」（前导/尾随各一）；`insert_test` 里那条
对照用例改成「两个工具都不去重」；`editing_test` 里 `:boundary-dedup` 的默认/取值/取值域用例退役，补未知键用例。

**判据**：`hashline.*` + `cap.editing-test` + `cap.editing-mode-tools-test` 定向跑
`Ran 222 tests containing 7526 assertions. 0 failures, 0 errors.`；后端全量见下。

**判据**：

- 后端全量 `clojure -M:test -m harness.test-runner` → `Ran 1395 tests containing 14428 assertions.
  12 failures, 1 errors.`
- 这 13 条红**全部**落在四个已知红命名空间（`kernel.tools-test` 8、`cap.mcp-wired-test` 3、
  `kernel.hooks-test` 1、`cap.claims-test` 1）；把它们单独在 `main` 上跑一遍，得到**同样的
  12 失败 + 1 错误、同样的用例名**（`.scratch/config-merge/spec.md` 已记它们与本特征无关）。
- 改动面命名空间（`hashline.*` 十个）定向跑全绿：`Ran 195 tests containing 7347 assertions.
  0 failures, 0 errors.`
- `ui/` 未改（票 01–05 都不碰前端）。
