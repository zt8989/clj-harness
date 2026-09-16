# 07 — 收口：现状文档与全量验收

**What to build:** 四样能力落地之后，**说现状的那几处要跟上**——`CONTEXT.md`、`docs/architecture/`、
`README.md` 今天都有一句话会因为本特征变成假的（工具清单、库里的表、模块地图、跑分那条）。
本票把它们改对，并把两套全量跑一遍，把端到端证据留在票面。

从用户视角：一个刚克隆这个仓库的人，只看文档就能知道这个工具集有哪些手、任务清单落在哪、
以及今天跑出来是几条。

**Blocked by:** 02, 03, 04, 05, 06

**Status:** ready-for-agent

## 验收

- [ ] `docs/architecture/kernel.md` 的"基座：两份工具表"那张表加上四个新工具——
      **它们两栏都有**（不属于任何编辑家族，所以两种模式都服务它们），并说明为什么
      （列路径 / 不碰文件系统 / 出网，与"按锚点编辑"无关）。
      那张表下面关于 `:requires-approval` 的句子要仍然成立（今天只有 `session-configure` 带它）。
- [ ] `docs/architecture/kernel.md`（或 `home-and-storage.md`）说清 `glob` 与 `anchor_grep`
      共用 `harness.rg`，以及为什么 `--json` 的解析留在 `grep` 自己那里。
- [ ] `docs/architecture/home-and-storage.md` 的库章节加一节「任务清单的表」：
      `todos` 一行一个会话、清单整存整取，并**写下它为什么是状态**——
      与旁边 `anchors` / `line_checksums` 那段（"一个值，整写整读，从不按元素查"）同一段话的口气。
      迁移那句"链是 append-only、`user_version` 只是遗迹"要仍然成立。
- [ ] `docs/architecture.md` 的模块地图加上 `harness.rg` / `harness.glob` / `harness.todos` /
      `harness.web` / `harness.web.search`；文首的**快照提交号**改成本特征落地后的提交。
- [ ] `docs/architecture/client.md` 写进 `TOOL_ICONS` / `subjectOf` 那两张按工具名开的表
      （六票的注释指着这里）。
- [ ] `README.md`：跑分那一行（`607 tests / 9774 assertions`）改成实测的新数并**带上分支与提交**；
      前端那一行（`11 tests`）若 `EXPECTED_CASES` 变了就跟着改。
      **若那两条 JDK 25 的环境失败还在，就如实写进去**（见 `spec.md` 的"状态"）；
      顺手治好的话更好（例如让那条断言只认它自己的标记行、或给 fork 出来的子 JVM 加选项），
      **但那不是本票的门槛**。
- [ ] `README.md` 的特性叙述里补上这四样（站在"为什么这样设计"的角度，不是列名词）——
      尤其：任务清单为什么进库、出网为什么不 park（决策 8）。
- [ ] `spec.md` 的"状态"改成落地记录：每张票的提交、量到的数、**与票面的差异**逐条写明
      （这个仓库的 `.scratch` 是历史，落完就不再改，所以差异必须在落地当次记进去）。
- [ ] **两套全量**：`clojure -M:test -m harness.test-runner` 与 `cd ui && npm test`、
      `cd ui && npm run build`，三者都通过，报数带上分支与提交。
- [ ] **端到端证据**（`spec.md` 的"验收主线"1、2 条）：一条会话里 `glob` / `web_fetch` /
      `todo_write` 都跑过，真前端截图；并且另起一个 JVM 打开同一个 home 读回那份清单，
      两条输出都存票面。
- [ ] 检查一遍没有顺手改坏既有断言：本特征**应当**只碰
      `tools_test/specs-expose-every-base-tool` 的两份名单、`db_test` 的两条元断言、
      以及 `test_runner/test-namespaces` 的列表——除此之外任何既有断言的改动都要在票面说明理由。
