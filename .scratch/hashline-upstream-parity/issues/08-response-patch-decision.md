# 08 — 响应不补 `patch` / `diffLineNumbers`（`wontfix`）

**What to build:** 一个决定。上游 `replace`/`undo` 的成功响应里，除了模型看到的那份带锚点 diff，
`details` 还带一份 **unified patch** 与一个**行号数组**；本仓没有。要不要补？

**Blocked by:** 人的决定（本票 `needs-triage`）

**Status:** wontfix

## 拍板结果（2026-09-30）

**不补。** 本仓没有任何一方读工具 `details`（UI 把结果当文本渲染），补 `patch`/`diffLineNumbers` 就是
死字段——还要连带引入 1 MB 上限与截断话术。等真有外部消费者（脚本、导出、另一个前端）时再补，那时
也知道它要什么形状；届时应**另立一张票**，不要改这张。

## 现场

```ts
// 上游 src/replace-response.ts:151-162
const patchResult = genPatch(path, originalNormalized, result);   // Diff.createTwoFilesPatch
return { details: { diff, patch: patchResult.patch,
                    ...(patchResult.truncated ? { patchTruncated: true } : {}),
                    diffLineNumbers: diffResult.lineNumbers.map((line) => line ?? null) } };
// src/replace-diff.ts:566 genPatch —— MAX_DIFF_INPUT_BYTES = 1MB，超了给截断标记
// undo 路径同上（src/replace-undo.ts）
```

本仓的成功响应只有**模型可见的**那部分：`ok-message` / `render-diff` 产出的 `+`/`-`/上下文行
（`cap/hashline/edit.clj`、`replace.clj`、`undo.clj`），**没有** `details` 里的 patch 或行号数组。

**消费者核查**：本仓 UI 把工具结果当**文本**渲染（`ui/src/components/trajectory-view.tsx` 的
`<details>` 折叠），**不读**工具 `details.diff/patch`（`rg` 过 `ui/src`，没有 patch/diffLineNumbers
的消费者）。所以补这两样，**今天没有任何一方会读它**。

## 要拍板的问题

1. **不补**（我的建议）：没有消费者，补了就是死字段——而且 patch 有 1 MB 上限、要额外的截断话术，
   是一段只增不减的代码。等真有外部消费者（脚本、导出、另一个前端）时再补，那时也知道它要什么形状。
2. **补**：给 `replace`/`insert`/`undo` 的响应加 `patch`（unified）+ `diffLineNumbers`
   （注意上游为 JSON 兼容把行号里的 `undefined` 映射成 `null`）。要连带决定：
   patch 的 1 MB 上限与 `patchTruncated` 话术、以及谁读它。

## 我的建议

**不补**，除非你已经有一个会读它的外部消费者。若同意，本票转 `wontfix` 并在 spec 的「非目标」记一句。

## 验收（拍板后）

- [ ] 选「不补」：本票转 `wontfix`；spec 记一句「无消费者，不补」
- [ ] 选「补」：`replace`/`insert`/`undo` 三处响应带 `patch`/`diffLineNumbers`；1 MB 上限与截断标记
      有用例；并在票面写清**谁**消费它
