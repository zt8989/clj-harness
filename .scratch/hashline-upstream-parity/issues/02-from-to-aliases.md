# 02 — `from`/`to` 别名（并修好今天半接线的 `replace_from`/`replace_to`）

**What to build:** `replace` 收 `remove_from`/`remove_to` 的兼容写法：`replace_from`/`replace_to`
**今天根本没接线**（见下），先接上；再加上上游的 `from`/`to`。四个键按固定优先级取值，
只给别名时也照样是一条正常编辑。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

**上游**（`7cc0d4c`，4.3.4）在进入校验**之前**归一，四条别名都认：

```ts
// src/utils.ts:15-31 normalizeAnchors —— 顺序即优先级：remove_* 在，别名不动
if (typeof record.remove_from !== "string" && typeof record.replace_from === "string") { record.remove_from = record.replace_from; … }
if (typeof record.remove_to   !== "string" && typeof record.replace_to   === "string") { … }
if (typeof record.remove_from !== "string" && typeof record.from === "string") { … }
if (typeof record.remove_to   !== "string" && typeof record.to   === "string") { … }
```

**本仓现状：别名只进了白名单，值从来没被读过。**

```clojure
;; cap/hashline/edit.clj:236-243
known    #{"remove_from" "remove_to" "replacement_lines" "path"
           "replace_from" "replace_to" "file_path"}
from-key (if (contains? args :replace_from) :replace_from :remove_from)
to-key   (if (contains? args :replace_to)   :replace_to   :remove_to)]
;; edit.clj:260 / :265 —— 注意值恒取 remove_*，from-key 只用来做报错时的字段名
(let [from (anchor-arg from-key (:remove_from args) warnings)
      to   (if (contains? args to-key) (anchor-arg to-key (:remove_to args) warnings) from)]
```

于是 `{"replace_from": "Hasu", "replacement_lines": ["x"]}` 会拿 `nil` 去 `anchor-arg`，
得到一条**指着 `replace_from` 说「got nil」的 `:bad-anchor`**。更早一层还有第二道门：

```clojure
;; cap/tools.clj:674 —— replace 的 :required
[:remove_from :replacement_lines]
;; kernel/tools.clj —— missing-args 在 :run 之前跑，只有 :replacement_lines 能到 parse
```

`target-path` 那一侧倒是容错的（`replace.clj:100`、`:682` 都 `(or (:remove_from args)
(:replace_from args) …)`），所以今天这批别名唯一被真正读到的场合是「定位文件」，
**不是**「取锚点的值」——半接线。

## 要改成什么

1. **`edit/parse` 按优先级取值**：`remove_from` > `replace_from` > `from`；
   `remove_to` > `replace_to` > `to`（与上游一致：规范名在，别名不参与）。四个都在 `known` 里。
2. **`tools.clj` 的 replace 注册把 `:required` 收成 `[:replacement_lines]`**：锚点键的「缺了」改由
   `edit/parse` 给**指名错误**（照 `insert` 那句 `` `anchor` is required: … `` 的写法：
   「`remove_from`（或 `replace_from`/`from`）必给：read 行里 `│` 左边那四个字符」）。
   不这样改，kernel 的 `missing-args` 会在别名到达 parse 之前先报 `missing [:remove_from]`。
   `insert` 的 `[:anchor :direction :lines]` 不动。
3. **别名不报 warning**：它是等价写法，不是 slip（`file_path` 处理同此）。
4. `target-path` 的 `(or …)` 顺序改成与第 1 条同一份优先级（今天它先看 `remove_from`，顺序已对，
   只需确认 `from`/`to` 也进 `or`）。
5. **`remove_to` 省略语义不变**：`to` 缺省 = `from`（单行）；只给 `replace_to`/`to` 而不给
   `remove_to` 时，`to-key` 选中别名后**必须取别名的值**，不能像今天那样取 `(:remove_to args)`。

## 验收

- [ ] `{"replace_from": "Hasu", "replace_to": "Arvm", "replacement_lines": ["x"]}` → 一次正常编辑
      （今天得到 `:bad-anchor`，这就是本票要修的）
- [ ] `{"from": "Hasu", "to": "Arvm", "replacement_lines": ["x"]}` 与用规范名的同一笔编辑**逐字同结果**
- [ ] 优先级：同时给 `remove_from` 与 `from` → `remove_from` 胜，别名不进 `extras`
- [ ] 只给 `from` 不给任何 `to` → 单行区间
- [ ] 一个锚点键都不给 → **指名错误**（说清四个名字里至少一个），不是 kernel 的 `missing [:remove_from]`
- [ ] 未知字段仍拒绝：`{"start": …}` 照旧 `replace does not take …`
- [ ] `target-path` 对四种写法都能定位同一个文件（各一条最小用例）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.replace-test 'harness.cap.hashline.batch-test 'harness.kernel.tools-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.replace-test 'harness.cap.hashline.batch-test 'harness.kernel.tools-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] `cd ui && npm test` 条数与基线一致（本票不碰 `ui/`）
- [ ] 落地那天：`.scratch/hashline-edit/spec.md` 里「别名只有 `replace_from`/`replace_to`」那处加注，
      并记下「那两个别名当时是半接线的」这一事实
