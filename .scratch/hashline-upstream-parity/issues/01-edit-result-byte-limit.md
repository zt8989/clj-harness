# 01 — 编辑/写入结果的大小护栏（100 MiB，与 `read` 同一把尺）

**What to build:** `replace`/`insert` 与 `write` 在落盘前，用 `read` 已经在用的那个上限（100 MiB）
量一次**将要写入的字节**；超了就**指名拒绝**、一个字节都不写，而不是把超大正文写下去、
再让下一次 `read` 报「文件太大」。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

今天只有 `read` 那一侧有这把尺，写入侧完全没有：

```clojure
;; cap/hashline/reading.clj:29-33 —— private，且语义绑在「读」上
(def max-file-bytes
  "A file bigger than this is refused rather than read. 100 MiB, upstream's number…"
  (* 100 1024 1024))
;; reading.clj:135 —— 唯一一处使用
(> (.length f) max-file-bytes)
```

- `replace.clj` 的 `perform!` 走 `files/write-file!`，**结果正文多长都照写**；
- `write.clj` 的 `perform!` 直接 `(spit f content :encoding "UTF-8")`，**更不查**；
- 于是「一次 `replace` 把文件撑到 300 MiB」会成功，直到下一次 `read` 才说文件太大——
  这时磁盘已经多了一个自己都读不回来的文件。

上游在**提交那一刻**查，读与写共用同一个常量：

```ts
// src/constants.ts:2
export const MAX_BYTES = 100 * 1024 * 1024;
// src/utils.ts:196-200
export function assertByteLimit(content, displayPath, limit = MAX_BYTES) {
  if (Buffer.byteLength(content, "utf-8") > limit)
    throw new Error(`[E_FILE_TOO_LARGE] File is too large: ${displayPath} (exceeds the ${limit/(1024*1024)}MB size limit). For very large files, use write.`);
}
// src/commit.ts:50 与 src/batch.ts:617 —— 落盘前调用（finalFileBytes 含 BOM 与行尾）
assertByteLimit(finalFileBytes, path);
```

## 要改成什么

1. **上限与检查提到 `cap/hashline/files.clj`**（文件边界的 ns）：公开 `max-bytes` 与
   `check-size!`（入参：path、正文；内部按 `UTF-8` 字节数，含 BOM 与恢复后的行尾——与
   `write-file!` 里 `body` 的构造同源，别量两遍不同的东西）。`reading.clj` 改成引用它，
   **数字与话术一个字不改**（`read` 的行为逐字不变）。
2. **`files/write-file!` 里查一次**：它是 replace / insert / undo 三条写路径的**唯一收口**，
   放在这里一处顶三处。
3. **`write.clj` 的 `perform!` 单独查一次**（它不走 `write-file!`）：在 `spit` 之前。
4. **拒绝话术**点名文件与上限，仿上游 `[E_FILE_TOO_LARGE] … use write` 的形状；本仓惯用
   `ex-info` + `:reason :file-too-large`，落点由实现者按本仓 `named failure` 的既有写法定。
5. **不动 `read` 的 2000 行 / 51200 字节分页预算**——那是渲染预算，与这条文件上限不是一回事。

## 验收

- [ ] 一次 `replace` 把结果撑过 100 MiB → **指名拒绝**、`:error true`、话里有文件与上限，
      文件、归属表、撤销记录**都一字未动**（照 `a-refused-write-changes-nothing-at-all` 的形状写）
- [ ] `insert` 与 `undo` 走同一处检查（至少 `insert` 一条用例；`undo` 由 `write-file!` 收口，
      加一条「恢复到超限会拒绝」即可）
- [ ] `write` 一份超限 `content` → 拒绝，且**文件未创建**（`perform!` 的顺序：查 → 写）
- [ ] `read` 的行为零变化：既有 100 MiB 拒绝用例与话术不改而通过；`max-file-bytes` 若有别处引用
      一次改净（`grep -rn "max-file-bytes" src/` 只剩 `files.clj` 一处定义 + `reading.clj` 的引用）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.replace-test 'harness.cap.hashline.insert-test 'harness.cap.hashline.undo-test 'harness.cap.hashline.write-test 'harness.cap.hashline.read-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.replace-test 'harness.cap.hashline.insert-test 'harness.cap.hashline.undo-test 'harness.cap.hashline.write-test 'harness.cap.hashline.read-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致（本票不碰 `ui/`）
- [ ] 落地那天：`.scratch/hashline-edit/spec.md` 加**加注的复议**一笔——「`max-file-bytes` 只在 read 上」
      那句被推翻（旧话不动、划删除线）
