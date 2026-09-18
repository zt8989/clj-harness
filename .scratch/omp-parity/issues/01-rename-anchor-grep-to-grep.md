# 01 — `anchor_grep` 改名 `grep`（配置键一并改成 `:grep`）

**What to build:** 那个检索工具就叫 **`grep`**——名字里不再带 `anchor`（本仓没有第二个 grep 与它争，
那个词只是把一件显然的事说第二遍），与 omp 的名字一致；`harness.edn` 里那个开关跟着从 `:anchor-grep`
改成 `:grep`（一个键的名字要说出它管的东西，而那个东西现在叫 `grep`）。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

名字散在**四处性质不同的地方**，漏掉任何一处都会留下一个指向不存在的东西的字符串：

1. **工具表与模式家族**：`cap/tools.clj:623` 的 `(register! "anchor_grep" …)`；
   `cap/editing.clj:219` 的 `families` 集合 `#{"replace" "insert" "anchor_grep" "undo_last_replace"}`；
   `cap/editing.clj:236-241` 的 `search-tool` 映射 `{"anchor_grep" :anchor-grep}`（**工具名 → 配置键**，
   两个都要改）；`cap/editing.clj:273` 那句「this session edits by anchor, and anchor_grep is the
   anchor-based editor」的注释。
2. **别人的描述里指了指这个工具**（模型读到的交叉引用，漏了就是让模型去叫一个不存在的名字）：
   `cap/tools.clj:665`（`glob` 的描述「use `anchor_grep` when you are looking for content」）、
   `infra/rg.clj:4` 与 `:81`（`require-posix!` 的**用户可见错误**「`anchor_grep` / `glob` search with `rg`」）、
   `cap/hashline/reading.clj:210`、`cap/web.clj:47`、`cap/web/search.clj:45`、`cap/glob.clj:17,29,65`、
   `cap/hashline/grep.clj:2,240` 的 docstring。
3. **配置与文档**：`harness.edn.example:47` 的键与注释；`CONTEXT.md:102` 那张**闭名字清单**（`CONTEXT.md:69`
   与 `:79` 也各一处）；`docs/architecture.md:67`（工具清单）、`kernel.md:126`（模式表）、`:268`、`:287`、`:294`、
   `client.md:188`（`TOOL_ICONS` 那张表）；`README.md:247`。
4. **UI 两处**（用户看得见）：`ui/src/components/message-parts.tsx:259` 的 `TOOL_ICONS`
   （`anchor_grep: SearchIcon`）与 `:370` 的 `subjectOf` `case "anchor_grep"`。漏掉这两处的后果是可预判的：
   图标退回 `WrenchIcon`、那一行的摘要退回兜底。

**不欠债的两件事，写清免得白找**：注入给模型的 `<tools>` 清单由工具集生成，跟着走；
`~/.clj-harness/` 下的 jsonl 是**记录**（里面那个名字是历史，不改也不用改）。
`docs/architecture/layers.md:45` 那句提到这个名字是**历史例子**（「核心层里残留过 `"anchor_grep"` 这样的
工具名」）——同样不改。

## 要改成什么

1. 工具名 `anchor_grep` → **`grep`**：`register!`、`families`、`search-tool` 的键、
   以及所有把它当**名字**用的地方（描述里的交叉引用、错误话术、docstring）。
2. 配置键 `:anchor-grep` → **`:grep`**（`defaults`、`vocab`、`search-tool` 的值、`harness.edn.example`）。
   理由与工具名同源；**这台机器上没有 `~/.clj-harness/harness.edn`**（立票当天实测），所以没有任何真实配置
   会被这条改名打到；真要被写到，本仓的立场是**指名失败**（`check-known-keys!`），话说清键名与文件。
3. **不给别名**：`CONTEXT.md:102` 那句「就是这些名字，不给它们起别名」照旧——旧的 `anchor_grep`
   不认，调用它得到的是「这个会话不服务这个工具」那条既有话术。
4. **描述文本一个字不改**（除了自指与交叉引用里的名字）：`anchor│` 那套、`literal`、
   「No read afterwards is needed」都照旧——这次只动名字。
5. **`.scratch/` 分两类处理**（立票当天 `grep -rln anchor_grep .scratch/` 的实测清单）：
   - **还没落地的计划**里的名字跟着改（它们不是历史，是待执行的指令）：`.scratch/write-no-content/`
     的 spec 与 `issues/01`（其中「例子换成 `{:anchor-grep false}`」→ `{:grep false}`）、
     `.scratch/edit-merge/issues/05`、`06` 里顺带提到的名字、`.scratch/immutable-data/issues/07`
     提到的名字、以及 `.scratch/omp-parity/` 自己的票面。
   - **已落地特征的 spec 一个字不动**（那是历史）：`layer-layout`、`flat-step-rows`、`tool-parity`、
     `trajectory`、`hashline-edit`、`composer-status/evidence/`——它们只是**提到**这个名字，
     没有一条**主张**被推翻，所以连加注都不需要。唯一被推翻的主张在 `edit-merge` 的决策 7（见验收末条）。

## 验收

- [ ] `grep -rn "anchor_grep" src/ test/ ui/src/ docs/ CONTEXT.md README.md harness.edn.example` **无输出**
      （`docs/architecture/layers.md` 与 `~/.clj-harness/**` 的历史提及不算，验收时用路径排除）
- [ ] `grep -rn "anchor-grep" src/ harness.edn.example` **无输出**；`:grep` 在 `defaults` 与 `vocab` 里
- [ ] 被服务工具集里那个名字是 `grep`：一条断言（`harness.cap.editing-test` 或
      `harness.kernel.tools-test` 任一，直取 `tools/specs` 的名单）
- [ ] **`glob` 的描述指向 `grep`**（用例断言那条交叉引用；今天它写的是 `anchor_grep`）
- [ ] 关掉它的开关仍然管用、话说对：`:grep false` → 不服务，拒绝话术点的是 `:grep`
      （今天的用例改键名，断言里那句 `:anchor-grep false` 跟着改）
- [ ] UI：`TOOL_ICONS` 与 `subjectOf` 都认 `grep`（`cd ui && npm test` 过；两处的用例各加/改一条，
      `EXPECTED_CASES` 若因新增用例而变，照它自己的提示改那个常量）
- [ ] `CONTEXT.md` 那张闭清单、`docs/architecture.md:67` 与 `kernel.md:126` 的模式表里是新名字，
      工具数仍是**十八**（改的是名字，不是数量）
- [ ] 定向跑：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.editing-test 'harness.cap.hashline.grep-test 'harness.cap.editing-mode-tools-test 'harness.kernel.tools-test) (let [r (clojure.test/run-tests 'harness.cap.editing-test 'harness.cap.hashline.grep-test 'harness.cap.editing-mode-tools-test 'harness.kernel.tools-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
- [ ] 落地那天：`.scratch/edit-merge/spec.md` 的**决策 7**（「`anchor_grep` 保名…也**不改名成 `grep`**」）
      加**加注的复议**（旧话不动、被推翻的那一行划删除线），理由写本票的：本仓没有第二个 grep 与它争，
      `anchor` 是在说一件显然的事。它的 `04-one-edit-in-both-modes.md` 里那处引用同样加注。
      `docs/architecture.md:143` 那句「`anchor_grep` 保名」跟着改。
