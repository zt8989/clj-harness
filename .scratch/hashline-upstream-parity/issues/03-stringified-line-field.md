# 03 — 字符串化的行字段归一（`replacement_lines` / `lines` 本身是字符串）

**What to build:** `replacement_lines`（`replace`）与 `lines`（`insert`）的**字段值本身**是字符串时
——`"[\"a\",\"b\"]"` 这种 JSON 串，或 `"a\nb"` 这种多行串——解回「一行一个字符串」的数组，
带一条 `[W_…]` warning 说明这是被解释过的，而不是抛 `:not-an-array`。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

本仓只会处理「**数组里**某个元素塞了整段 JSON / 内嵌换行」，字段本身不是数组就拒绝：

```clojure
;; cap/hashline/edit.clj:105-115
(not (sequential? v))
(throw (ex-info (str "`replacement_lines` must be an array of strings, one per line"
                     " ([] deletes the range); got " (pr-str v) ".")
                {:argument :replacement_lines :value v :reason :not-an-array}))
```

上游在**校验之前**归一字段值，字符串（或 `["<json>"]` 这类单元素数组）都尽量解回数组：

```ts
// src/utils.ts:347-362  decodeStringArray —— 值本身是串，或单元素数组里是串，都试解
const candidate = typeof value === "string" ? value
  : Array.isArray(value) && value.length === 1 && typeof value[0] === "string" ? value[0] : undefined;
const decoded = decodeArrayText(candidate);
if (decoded !== undefined) return decoded;
if (looksLikeStringArray(candidate)) warnings?.push(`[W_BAD_SHAPE] ${label} looked like a JSON array but could not be parsed; kept as one literal line: …`);
// src/utils.ts:368-387  normalizeLineFieldValue —— 解不出就按换行切成多行
const decoded = decodeStringArray(candidate);
if (decoded !== undefined) return decoded;
if (/^\[\s*\]$/.test(…)) return [];
return splitEditLines(candidate);
// src/utils.ts:381-387  normalizeEditLines —— 对 replacement_lines 与 lines 都跑
```

注意本仓**已经**覆盖了「单元素数组里是 JSON 串」这一半（`edit.clj:120-131` 对以 `[` 开头、
`]` 结尾的元素做 `json/read-str`）——缺的是**字段本身是字符串**那一半。
kernel 只查 `:required`、不按 JSON-Schema 的 `:type` 校验（`kernel/tools.clj` 的 `missing-args`），
所以字符串值确实会到达 `replacement-arg`，得到上面那条 `:not-an-array`。

## 要改成什么

1. **`replacement-arg`（`edit.clj`）接受字符串 `v`**：
   - 先试 `json/read-str`，结果是「元素全是字符串的数组」→ 用它，warning `Unwrapped a JSON array pasted into `replacement_lines`…`（沿用既有那句的形状）；
   - 空数组 `[]`/`[ ]` → `[]`（删除）；
   - 否则按 `\r\n`/`\n`/`\r` 切成多行，warning 说「把一整段字符串按行拆开了」；
   - **不像数组、也不含换行的裸串**（如 `"x"`）→ 一行。
2. **`insert` 的 `lines` 走同一条**：它已经调 `edit/replacement-field`，一处改完两边生效。
3. **warning 用 `[W_` 前缀**：这样 `:strict-input` 会正确拒绝它（`edit.clj` 的 strict 分支按
   `warnings` 非空判，本仓现有片段不加前缀，实现者按本仓 `[W_` 的既有用法统一）。
4. **类型非法仍然拒绝**：数字、`true`、对象、数组里混了非字符串——今天的 `:not-a-string` /
   `:not-an-array` 话术不动。
5. **描述不必改**：规范写法仍是数组；这只是一次容错（与 `[path]` 单元素数组那类的处理同级）。

## 验收

- [ ] `{"remove_from": …, "remove_to": …, "replacement_lines": "[\"a\",\"b\"]"}` → 落盘两行、有 warning
- [ ] `{"…": "a\nb"}` → 落盘两行、有 warning
- [ ] `{"…": "[]"}` → 删除区间；`{"…": "[\"\"]"}` → 一行空行
- [ ] `insert` 的 `lines` 同样三种（JSON 串 / 多行串 / 裸串）
- [ ] `:strict-input true` 时上面每一种都被**拒绝**（warning 带 `[W_`），话里列出它拒绝的修改
- [ ] 数字 / 对象 / 数组里混非字符串 → 既有 `:not-an-array` / `:not-a-string` 话术不改而通过
- [ ] 既有「单元素数组里塞 JSON」用例不改而通过
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.replace-test 'harness.cap.hashline.insert-test 'harness.cap.hashline.batch-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.replace-test 'harness.cap.hashline.insert-test 'harness.cap.hashline.batch-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致（本票不碰 `ui/`）
- [ ] 落地那天：`.scratch/hashline-edit/spec.md` 的「四类 slips」那段加注——字符串字段是第五类
