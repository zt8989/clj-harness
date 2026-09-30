# 05 — `read` 页脚的起始行（`offset>1` 时印错）

**What to build:** 一页被字节预算截断时，页脚那句 `[Showing lines A-B of N — …]` 的 `A` 是**这一页真实的
起始行**，而不是写死的 `1`。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

```clojure
;; cap/hashline/reading.clj:221-227
(defn- footer [^long shown-to ^long total ^String why]
  (str "[Showing lines 1-" shown-to " of " total " — " why
       ". Use offset=" (inc shown-to) " to continue.]"))
;; reading.clj:299 —— 调用处手里有 start（0-based）
{:text (if why (str body "\n" (footer shown-to total why)) body) …}
```

`preview` 在 `:248-252` 已经算出了 `start`（`(dec (long (or offset 1)))`），却只把它用于切片，
没有传给 `footer`。于是 `offset=50` 读到预算截断时，页脚会说 `lines 1-…`——
模型据此以为这一页从第 1 行开始，`offset` 的语义被这句话污染。

上游把真实起点传进格式化器：

```ts
// src/read.ts:46-55
export function formatPaginationHint(startLine, endLine, totalLines, nextOffset, byteLimit?) {
  const sizeSuffix = byteLimit !== undefined ? ` (${formatSize(byteLimit)} limit)` : "";
  return `[Showing lines ${startLine}-${endLine} of ${totalLines}${sizeSuffix}. Use offset=${nextOffset} to continue.]`;
}
```

## 要改成什么

1. `footer` 增加一个起始行参数（`(footer from shown-to total why)`），调用处传 `(inc start)`。
2. **`offset=` 的算术一个字不改**（仍是 `(inc shown-to)`）——本票只改**行号标签**，
   不碰 `.scratch/omp-parity/issues/05` 里「页脚 offset 算术不变」那条承诺。
3. **`-` 前后仍是「这一页覆盖到的行」**：`shown-to` 的定义（从顶部起覆盖了几行）今天就是
   `start` 之下的绝对行号，核对一遍：`offset=50`、预算在第 99 行截断 → 页脚应为 `lines 50-99 of N`。
4. 字节预算那句 `why`（`"stopped at the N-byte budget"`）与 `limit` 那句不动。
5. 空文件、越界 `offset`、单行超预算三条特殊路径不涉及 `footer`，不动。

## 验收

- [ ] `offset=50` 且被预算截断 → 页脚是 `lines 50-<shown-to> of <total>`，`offset=` 指向下一行
- [ ] `offset=1`（默认）→ 逐字与今天相同（既有截断用例不改而通过）
- [ ] `limit` 截断（非字节预算）那条也印真实起点
- [ ] 越界 `offset` 与空文件路径逐字不变
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.read-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.read-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致（本票不碰 `ui/`）
- [ ] 落地那天：`.scratch/hashline-edit/spec.md` 里页脚那句加注（起始行曾是写死的 `1`）
