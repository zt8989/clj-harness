# 04 — stale-anchor 拒绝补一句「只差大小写」

**What to build:** 一个锚点在本会话里**没被拥有**而它**只差大小写**地撞上某个已拥有的锚点时，
拒绝话里点名那个真正在手的锚点（`Anchors are case-sensitive; "Xxxx" differs only in case.`），
免得模型对着一个「看起来对」的四个字母反复重试。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

```clojure
;; cap/hashline/replace.clj:56-77 —— 本会话拿不出文件的两个形状
(defn- unresolvable! [anchor]
  (if (anchors/anchor? anchor)
    (ex-info (str "no anchors here name a file in this session. Call read on the file"
                  " you mean first …") {:anchor anchor :reason :not-read})
    (ex-info (str "`" anchor "` is not an anchor. The names this scheme hands out are"
                  " four letters from a fixed table, and this one is not among them"
                  " (… the letters have to be spelled exactly). Copy the anchor from"
                  " the left of the read row …") {:anchor anchor :reason :not-an-anchor})))
```

两句话都在说「照抄」，但**没有一句**告诉模型它抄错的是**字母的大小写**——
而锚点字母表同时有 `A–Z` 与 `a–z`，`Hasu` 与 `hasu` 是两个合法但不同的锚点。

上游 4.4.1（`ded316b`）在「本会话没拥有它」那句话后按需追加一句：

```ts
// src/edit-common.ts:99-110
const folded = ownersDifferingOnlyByCase(hash, knownPaths);   // 扫本会话 owned，按 toLowerCase 比
const hint = folded.length > 0
  ? ` Anchors are case-sensitive; ${folded.map((m) => `"${m.anchor}"`).join(", ")} differs only in case.`
  : "";
return `[E_STALE_ANCHOR] "${ref}" is not owned in this session.${hint} Call read() on the target file first.`;
```

```ts
// src/anchor-registry.ts:576-587 —— 为空/未给 paths 时扫全部 owned；给了 paths 才按文件过滤
export function ownersDifferingOnlyByCase(anchor, paths?) {
  const lower = anchor.toLowerCase();
  for (const [owned, entry] of state.owned) {
    if (owned === anchor || owned.toLowerCase() !== lower) continue;
    if (paths && paths.size > 0 && !paths.has(entry.path)) continue;
    matches.push({ anchor: owned, path: entry.path });
  }
  return matches;
}
```

本仓有等价数据：`store/ownership`（`store.clj:70-77`）一次取回本会话的 `[anchor path]`。

## 要改成什么

1. **`unresolvable!` 收 `thread-id`**（两个调用点 `replace.clj:139`、`:307` 手里都有），
   在 `:not-read` 那一支追加这句：
   `If it was copied from a row, check the case: "Xxxx" (anchors are case-sensitive).`
   —— 形状照本仓话术（一句话说清「谁」与「怎么办」），比上游那句更贴本仓语气。
2. **匹配规则**：从 `store/ownership` 里找 `(.equalsIgnoreCase anchor owned)` 且 `≠ anchor` 的锚点；
   多于一个就都列出（上游也是复数）。
3. **只对 `:not-read` 追加**：`:not-an-anchor` 那一支（不在表里的名字）不动——那种情形下没有
   「本会话在手的相似锚点」可言。
4. **别名与 `:not-shown` 不涉及**：本票只碰「本会话没拥有」这一条。
5. `insert` 与 `replace` 共用 `unresolvable!`（`target-path` 那条路径），一处改完两边生效。

## 验收

- [ ] 读一个文件得到 `Hasu`；用 `hasu` 调 `replace` → 拒绝话里**两个都出现**（`hasu` 与 `Hasu`），
      并说明锚点大小写敏感
- [ ] 用一个表里根本没有的名字（如 `0000`、`xy`）→ 仍是 `:not-an-anchor`，**没有**大小写那句
- [ ] 一个合法但本会话从未服务过的锚点（如 `Qwer`）→ `:not-read`，**没有**大小写那句
  （除非碰巧撞上某个在手锚点的大小写变体——加一条反例钉住「巧合才提示」）
- [ ] `insert` 的同一条路径也带这句（一条最小用例）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.replace-test 'harness.cap.hashline.insert-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.replace-test 'harness.cap.hashline.insert-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致（本票不碰 `ui/`）
- [ ] 落地那天：`.scratch/hashline-edit/spec.md` 的「拒绝词汇表」那处加注——`:not-read` 多了一句大小写提示
