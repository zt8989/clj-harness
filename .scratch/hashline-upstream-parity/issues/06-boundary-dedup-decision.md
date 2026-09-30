# 06 — boundary dedup：跟上游「整体移除」，还是保留（决策票）

**What to build:** 一个决定，不是一段代码。上游 4.3.9 先把 boundary dedup 默认关掉，4.4.0（`561ff2b`）
**整个删掉**；本仓今天还带着它，而且**默认开着**。要么跟上游删掉，要么明确保留并说明理由。

**Blocked by:** 人的决定（本票 `needs-triage`）

**Status:** needs-triage

## 现场

```clojure
;; cap/editing.clj:74 —— 默认开着
{:mode :hashline :anchor-grep true :require-path false :strict-input false
 :boundary-dedup :on :diff-context-lines 1}
;; cap/editing.clj:88 —— :on | :strict | :off
;; cap/hashline/edit.clj:424-473 —— dedup-edges：替换首/末行重复了紧邻的边界行时剥掉
;; cap/hashline/edit.clj:548-549 —— 回答里的 `dedup│N line(s) at the boundary were not added again`
;; cap/hashline/insert.clj —— 文档明说 insert 永不 dedup
```

上游 4.4.1 里 `src/`、`prompts/` **一处 `dedup`/`boundary` 都没有**（只有 batch 的 `dedupeWarnings`，
与这件事无关）；配置里也没有 `boundaryDedup` 键。移除的理由见提交 `561ff2b`（4.4.0）。

## 要拍板的问题

1. **跟上游整体移除？**
   删 `edit.clj` 的 `dedup-edges` 与 `dedup│` 行、`:boundary-dedup` 键（`editing.clj` 的默认值与
   校验、`config.edn.example` 的 `:session :editing` 块、`hashline-edit/spec.md` 的决策）、`insert` 文档里那句交叉引用
   （它说「unlike replace, insert never deduplicates」——replace 不再 dedup 后这句要改）。
   一次 `replace` 若把边界行重抄一遍，就会**真的多出那一行**（今天是被悄悄剥掉 + `dedup│` 说明）。
2. **保留但默认改 `:off`？**
   行为对**没写配置的人**等价于移除，但留了逃生舱；代价是配置面继续存在、`dedup│` 行的语义还活着。
3. **保持现状？**
   默认 `:on` 是上游 v4.2.11 的行为；本仓当初据此实现。保留就要明说「这是一处**有意**的不同答」，
   并接受它对模型粘贴边界的**静默改写**（尽管有 `dedup│` 一行）。

## 为什么值得单独拍

这是一处**会改变编辑结果**的差额：同一个 `replacement_lines`、同一个范围，跟不跟上游得到**不同的文件**。
它不能像 01–05 那样「兼容地跟上」，必须人选。

## 我要告诉你的（我的建议）

三个选项里，**倾向 1（整体移除）**：本仓的 `:on` 默认比上游任何一版都激进，而 `dedup│` 那行说明
只有**读得懂它**的人才看得见「你粘的边界行被吃掉了」——这正是上游最终删掉它的理由。
若要保守，选 2（默认 `:off`）也比现状稳。

## 验收（拍板后按选项落地）

- [ ] 选了 1：`grep -rni "dedup\|boundary" src/ prompts/ config.edn.example` 无输出；
      一条用例证明「边界行被重抄不再被剥」；`hashline-edit/spec.md` 的决策加注被推翻
- [ ] 选了 2：默认值改 `:off`，`:boundary-dedup :on` 仍可用，用例钉住两种默认行为
- [ ] 选了 3：`.scratch/hashline-edit/spec.md` 起草一段「对齐上游差额：boundary dedup 有意保留」，
      并说明为什么与上游不同答
- [ ] 任一选项：全量 `timeout 900 clojure -M:test -m harness.test-runner` 失败用例名与基线一致；
      `cd ui && npm test` 条数一致
