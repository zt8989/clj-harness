# 01 — `write` 的答案不带内容：`auto-read` 退场

**What to build:** 锚点模式下 `write` 成功之后只答「写了多少、写到哪里」加一句「锚点已释放，去 `read`」，
不再把文件开头 20 行的带锚点行交回来。`:editing` 的 `:auto-read` 键、`auto-read-lines`、`auto-read-note`
连同 `perform!` 那个只为它存在的 `config` 参数一并删除，工具描述与四处文档例子跟着换键。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

**答案那一半在 `cap.hashline.write`：**

```clojure
;; hashline/write.clj:27-32（ns docstring）
  `:auto-read` IS THE POINT OF THE WHOLE TICKET. Having just written a file, the
  model's next move is to adjust something in it -- and it has no anchors, because
  the write released them. ... So a successful write hands back anchored rows, and
  those rows are registered as shown, exactly as a read's are.

;; hashline/write.clj:37-40
(def auto-read-lines 20)          ; 「头 20 行」

;; hashline/write.clj:82-95
(defn- auto-read-note [thread-id path]
  (try (let [{:keys [text]} (serve/read! thread-id path {:limit auto-read-lines})] …)
       (catch Throwable t (str "… could not be produced, so no anchors are shown: " …))))

;; hashline/write.clj:120-125（perform! 的答案）
(str "wrote " (count content) " chars to " path
     (if (:auto-read config)
       (auto-read-note thread-id path)
       (str "\n\nThe file's anchors have been released: read it to get the"
            " anchors for what is there now.")))
```

`:120-125` 的第二支就是本票要的答案——**它已经写好了**，只是默认走不到。

**键与脸在另外两处：**

- `cap/editing.clj:78` `:auto-read true`（`defaults` 里 7 个键之一）、`:93` `{:ok boolean? :legal "true or false"}`（`vocab`）；
  `:38-43` 那段 docstring 拿它当**逐键合成**的论据（「a project that wants to turn `:auto-read` off …」）。
- `cap/tools.clj:157-171` `t-write` 把 `(editing/editing-mode …)` 当 `config` 传下去，**只为这一个开关**；
  `:414-419` `write-anchor-description` 承诺「The answer shows the top of the file it just wrote with the
  anchors that name those lines now, so you can edit what you wrote without reading it back.」

**用例（`test/harness/cap/hashline/write_test.clj`）：** `:109` `the-auto-read-replaces-the-anchors-the-write-took-away`、
`:200` `a-write-hands-back-rows-that-are-immediately-usable`（注释自称 "THE POINT OF THE TICKET"）、
`:214` `the-auto-read-does-not-flood-the-answer`、`:222` `auto-read-off-says-how-to-get-anchors`、
`:233` `a-write-that-cannot-be-read-back-still-succeeded`；另有 `:92` 的 `(use-mode! :hashline {:auto-read false})`
与 `:162-165` 那段注释（「a successful write releases them and the auto-read mints new ones」）。
`test/harness/cap/editing_test.clj` 拿它示范逐键合成与布尔校验：`:87-99`、`:194`、`:215`。

**文档五处：** `harness.edn.example:39-42`（键 + 三条注释）、`README.md:256-257`（例子 + 「全部七个键」）、
`CONTEXT.md:21`、`docs/architecture/skills-and-instructions.md:224`、`docs/architecture/kernel.md:283`
（「**`write` 是锚点的边界**」那一句）。

**为什么撤**（`.scratch/write-no-content/spec.md` 有全文与 omp 的原文引用）：omp 的 `write` 只多吐一行
`[path#TAG]`，并把刚写的文件记成 **seen 行区间为空**——「Authoring content is not knowing its line numbers」，
紧接着的锚点编辑照样被拒。本仓没有那个文件级 tag 可交（地址是逐行锚点），能照的是它的立场：
**写不是读**。今天的代价是三处：一个有界授予被读成「整份都能编辑」、每次 write 都付一段 token
（包括写完根本不打算改的调用）、以及 `write` 的锁区里因此多出第二把锁（`immutable-data/03` 那半张票的现场）。

## 要改成什么

1. **`hashline/write.clj`**：答案只剩「wrote N chars to PATH + 锚点已释放，去 read」；
   删 `auto-read-lines`、`auto-read-note`、`perform!` 的 `config` 参数；docstring 删 `:27-32` 那一段，
   把 `:103-108` 次序说明里提 auto-read 的半句改掉（次序本身照旧：**先释放、再有机会读**——
   只是不再自动读），补一句本票的规矩与**理由**（写不是读，照 omp 那句）。
   `check-no-echo!` 与 echo 拒绝一个字不动：它管的是**输入**，与答案无关。
2. **`hashline/write.clj` 的锁**：`auto-read` 一走，`with-path-lock` 区里没有第二把锁了
   （`forget-file!` / `clear-undo!` 走 DB 事务）。在函数 docstring 的次序段里点一句，别让下一个人
   以为这里还需要 session 锁。
3. **`cap/tools.clj`**：`t-write` 不再传 `config`；`write-anchor-description` 里那句承诺换成
   「a successful write RELEASES the file's anchors：要锚点就 `read`」（RELEASES 那半句留着，
   用例 `the-write-description-follows-the-mode` 查的就是它）。
4. **`cap/editing.clj`**：`defaults` 与 `vocab` 删 `:auto-read`；`:38-43` 的论据例证换成
   `{:grep false}`（同为布尔、同样是项目会想覆盖的键）——**机制不动，例子换键**。
5. **`harness.edn.example`**：删 `:39-42` 那三行注释与 `:auto-read true`。
6. **用例**：`:109`、`:200`、`:214`、`:233` 四条退场（它们问的行为没有了）；`:222` 升格为**唯一形状**
   （去掉 `{:auto-read false}` 参数，改名成「write 的答案说怎么拿锚点」）；`:92` 的
   `{:auto-read false}` 去掉；`:162-165` 的注释改掉（不再有新锚点被铸）；`editing_test` 三处换
   `:grep`，并**新增一条**：`harness.edn` 写 `:auto-read` 得到 `:unknown-editing-key`
   的指名失败（键名 + 文件路径都在话里）。
7. **文档**：`kernel.md:283` 那句中补上「答案里不带内容、不带锚点行」；`README.md:256-257`、
   `CONTEXT.md:21`、`skills-and-instructions.md:224` 三处例子换成真键；README 那句「全部七个键」
   **去掉数字**（本票正好让它动一次，而语义说法不必再改——先例是 `cap.tools` 那三处「十五个」）。
8. **不碰**：`read` / `replace` / `insert` / `undo_last_replace` / `grep`、`hashline/store.clj`、
   `:editing` 的逐键合成机制、`ui/`（答案文本走通用渲染）、`.scratch/immutable-data/`（该文件此刻在别处被改，
   见 spec 的非目标）。

## 验收

- [ ] `write` 的答案里**没有 `│`、没有内容行**：`harness.cap.hashline.write-test` 的用例
      （直调 `tools/run!` 断言答案里不含 `│`，且文件内容确实已写对）
- [ ] 紧接着用**旧锚点** `replace` → 得到指名「先 read」（沿用 `:99-107` 那条既有用例，去掉 `{:auto-read false}`）
- [ ] `harness.edn` 写 `:auto-read` → `:unknown-editing-key` 的指名失败；合法键清单里没有它
      （`harness.cap.editing-test`）
- [ ] `grep -n auto-read harness.edn.example` **无输出**；`grep -rn "auto-read" src/ test/ docs/ CONTEXT.md README.md`
      只剩新加的那条「它不再是合法键」的用例与说明
- [ ] `read` / `replace` / `insert` / `undo_last_replace` 的既有用例**一字不改**地通过（本票只动 write）
- [ ] 定向跑（立票当天验过：`Ran 49 tests containing 323 assertions. 0 failures, 0 errors.`，约 12 秒）：
      `timeout 240 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) (require 'harness.cap.hashline.write-test 'harness.cap.editing-test 'harness.kernel.tools-test) (let [r (clojure.test/run-tests 'harness.cap.hashline.write-test 'harness.cap.editing-test 'harness.kernel.tools-test)] (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"`
- [ ] 全量 `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
      （基线见 `spec.md` 的状态一节）
- [ ] `cd ui && npm test` 条数与基线一致（`EXPECTED_CASES`，本票不碰 `ui/`）
- [ ] 落地那天：往 `.scratch/hashline-edit/spec.md` 加**加注的复议**（旧话不动、被推翻的行划删除线），
      至少 `:34`、`:449-455`、`:478-480` 三处；`immutable-data/03` 的现场表若仍未落地，
      把「write 半边消失」这一条记进本特征的 spec 跨特征对照（**不要**去改一个正在被别人改的文件）
