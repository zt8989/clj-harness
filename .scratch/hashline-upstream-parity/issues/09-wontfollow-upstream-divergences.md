# 09 — 刻意不跟的上游实现（`wontfix` 记录）

**What to build:** 没有代码。这张票是**记录**：审计里那些「上游这么做、本仓那么做」的差额中，
**本仓有意不跟**的部分，连同理由，留在这里，免得下次有人当成漏抄再审计一遍。

**Blocked by:** None

**Status:** wontfix

## 不跟的清单

每一行都是「上游 4.4.1 ↔ 本仓」，本仓保持现状。

### 会改变模型可见行为的（本仓更严或更宽，都是有意）

| 项 | 上游 | 本仓 | 为什么不跟 |
|---|---|---|---|
| `write` 后自动读回（`autoRead`，默认 true） | 写完把文件内容追加回工具结果 | 删掉了 | `receipts-not-echoes` 的决定：写的内容已在调用里，回读是重复计费；答案只说「写了多少、锚点已释放」 |
| `remove_to` 必填 | `assertReq` 两个锚点都要 | 可省略（= `remove_from`，单行编辑，`edit.clj:262-266`） | 单行编辑是最常见的，逼模型把同一个锚点拼两遍是多此一举 |
| `require-path` 关闭时带 `path` | **拒绝**（unknown field，`edit-common.ts:resolveEditTargetWithRequirement`） | **接受**并校验与锚点归属一致 | 模型常顺手带上刚读过的路径；本仓选择「接受 + 交叉校验」，比「因为多写了一个字段而拒绝」合理 |
| `MAX_RANGE_STALE_LINES` 100 | 回填区间前 100 行、**无上下文** | `heal-rows` 40 + `:diff-context-lines` 上下文 | 40 行足够一次可重试的编辑，带上下文更有用；这是「同类不同数」 |
| grep 超长行按 `MAX_GREP_LINE_BYTES` 500 碎片 | 命中点周围切 `…` 碎片 | 按 51200 整行给 | 本仓一次给全行，模型拿到的是可编辑的完整内容 |
| read 越界 `offset` | 返回一句文本 | **抛错** | 越界是调用错误，报错比「假装成功再让模型去读下一句」更名副其实 |
| 图片 read | 转交宿主内建 read 附图 | **拒绝**并指路 `bash` | 本仓没有宿主贴图能力；假装支持比拒绝更坏（`reading.clj` 的 ns docstring） |
| `insert` 的锚点行保号 | after 保、**before 不保**（`insert.ts:80-89` + `alignOwnershipWithSpans` 的逐位映射） | **两个方向都保**（零宽 span，`insert.clj` 的 ns docstring） | 本仓的设计目标之一：编辑后的 diff 里全是仍然可用的锚点 |

### 实现/存储细节（模型不可见，本仓有意另做）

| 项 | 上游 | 本仓 | 为什么不跟 |
|---|---|---|---|
| 行校验和 | xxhash-wasm `h64ToString`（+ 纯行内 base 用 `xxh32`） | SHA-256 截断 8 字节（`anchors.clj:374-386`） | 只在进程内比对陈旧，模型看不到；不值得为换算法引一个 wasm 依赖 |
| 无 path 时的内容派生 base | `_lineHashesPure`：`(xxh32(src)>>>14) % HASH_SPACE` | 无（read 恒有 path） | 本仓的 read 一定带路径，这条路径到不了 |
| probe 种子 | `${key}:${process.pid}` | 只由 session-key 折叠 | 要**跨进程可复现**（重启后同一会话的锚点不变） |
| 会话内不回收已释放锚点 | `everMinted` 集合 | 只跳 `owned`，释放的名字可再用 | 删掉的行把名字**还给池子**是本仓想要的；唯一不能重复的是仍被活行占用的锚点 |
| 池耗尽 | 8192 次后全池线性回退、丢弃 served | 8192 次后**具名失败** | 回退会在模型背后回收名字；本仓选择说清楚「池满了，用 write」 |
| 无 spans 时的对齐 | `Diff.diffArrays` 真 diff，中段也能保锚 | 只保留最长公共前缀/后缀，中段重铸 | 本仓刻意不做通用 diff（`anchors.clj` 的 docstring：等真需要再说），认了「多铸几个锚点」 |
| 锚点持久化 | 每会话 JSONL sidecar + `hash-store.sqlite` | 共用 `~/.clj-harness/harness.db` 的 4 张表 | 与界面元数据同库是本仓 `project-sidebar` 的既定架构 |
| `patch`/`diffLineNumbers` | 响应 `details` 里带 | 无 | 见票 08：本仓无消费者 |

### 命名（本仓的闭清单不跟上游）

| 项 | 上游 | 本仓 | 为什么不跟 |
|---|---|---|---|
| 撤销工具名 | `undo_last_change` | `undo_last_replace` | 名字把「单位是一次 `replace`/`insert`」说清楚；`CONTEXT.md` 的闭清单 |
| 检索工具名 | `anchor_grep`（扩展）+ `grep`（宿主内建） | 只有 `grep`（语义 = 上游 `anchor_grep`） | omp-parity 01 已定：这里没有第二个 grep 与它争，`anchor` 说了等于没说 |

## 上游 4.3.x 的其余小改动（本仓不逐条跟）

这些是批话术/警告策略/提示词措辞，本仓有自己的写法定，除非某条被明确点出要跟：

- `29f6757` 批中止消息里报出失败调用与错误码 —— 本仓的 `wrapped`（`replace.clj:245-257`）已说「第几笔、共几笔、指哪个文件」，措辞不同但信息在；
- `a3445a4` 错误消息里高亮批引用 —— 纯 UI 措辞；
- `939f166` 静默 reversed-range / JSON-wrapper / embedded-newline 三类自动修的警告 —— 本仓对这三类**保留 warning**（可见优于静默）；而 reversed-range 本身已由 `.scratch/omp-parity/02` 改成**拒绝**；
- `561ff2b` 移除 boundary dedup —— **例外**，见票 06（会改编辑结果，单独拍板）。

## 验收

- [ ] 无需代码改动
- [ ] 复核一次：`git log` 无本票内容；spec 的「非目标」与本票一致
- [ ] 若将来有人想翻案（如要 xxhash、要 auto-read、要 patch），**另立票**并在此票的 `## Comments` 里留一句指向
