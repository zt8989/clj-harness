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
| 06 | boundary dedup | needs-triage | 跟上游「整体移除」，还是保留本仓默认 `:on` |
| 07 | auto-read-all | needs-triage | 是否引入上游 4.3 的整仓注入 |
| 08 | `patch`/`diffLineNumbers` | needs-triage | 响应是否补 unified patch 与行号数组 |
| 09 | 刻意不跟的上游实现 | wontfix | 记录，不施工 |

## 状态

**立票，未开工**（本次会话）。基线待开工当天实测：

- 后端：`timeout 900 clojure -M:test -m harness.test-runner`
- 前端：`cd ui && npm test`（本特征前五张票都不碰 `ui/`，票 08 若做要看 UI 是否消费 `details`）

**这台机器上的读法**：后端全量在并发下可能撞隔离守卫（`ISOLATION NOTE` 不算红）；
报数只认 `Ran … 0 failures, 0 errors.` 那一行与退出码。细则见 `docs/rules/testing.md`。
