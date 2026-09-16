# 02 — `glob`：按名字找文件

**What to build:** 模型想看"这个仓库里都有哪些 clj 文件"（或任何按名字能找到的文件），调一次 `glob`
就拿到它们。`anchor_grep` 回答的是"哪一行里有这段字"，本票回答的是"哪些文件叫这个名字"——
两者互补，而且都不必先 `bash ls`。

从用户视角：一次调用，答案是一列**能直接拿去 `read` 的绝对路径**，按路径排序，条数有上限并且
说清楚"共几条、这是前几条"。找不到东西时说的是一句"没有匹配"，不是一个空字符串。

**Blocked by:** 01

**Status:** ready-for-agent

## 形状

- 工具名 `glob`，参数 `pattern`（必填，如 `**/*.clj`、`src/**/*.ts`）与 `path`（可选，搜索根）。
  `path` 缺省 = 本会话的项目目录，未绑定会话 = 进程工作目录（与 `anchor_grep` 同一条规矩）。
- `:fence-paths true`——它读树，所以绑定了项目的会话里，指到项目外与配置家外的根要 park，与 `read` 一样。
- **两种编辑模式都服务它**：它不属于任何编辑家族（`harness.editing/families` 一个字不改），
  因为它列的是路径，路径没有锚点可言。**没有 `:describe`**，没有第二张脸。

## 决策

- **rg 管道搬一次家。** 今天"二进制叫什么 / 超时多少 / 不在 PATH 上时怎么说"埋在
  `harness.hashline.grep` 里；第二个用户来了，所以抽到 `harness.rg`，`grep` 改用它。
  **`--json` 事件解析留在 `grep` 自己手里**——那是"命中行要带锚点"的知识，`glob` 用不着。
- **走 `rg --files`，不是自己遍历目录。** 理由与 `anchor_grep` 逐字相同：`rg` 认 `.gitignore`、
  跳二进制、快；手写的遍历会静默地与会话里其它工具看到的树不一致。
  `--hidden` 加 `--glob !.git`（与 `grep` 一致：本仓的 `.scratch/` 就是隐藏目录，
  看不见它等于看不见半边仓库）。
- **排序按路径，不按修改时间。** 两次一样的调用必须给一样的答案；按 mtime 排会让答案取决于
  谁刚碰过哪个文件。
- **上限是 `anchor_grep` 的 `default-limit`（100）**，并报出"共 M 条"。
  数字指回它的出处，别处改了这个数字这里就跟着动——不新发明一个数字。
- **没有匹配是一句答案**，不是空输出（`rg --files` 找不到匹配时退出码仍是 0，别把它当成错误）。
- **绝对路径**：与 `grep` 的 `=== /abs/path ===`、与文件工具"回报已解析路径"同一条纪律——
  报告的是**实际发生了什么**。

## 验收

- [ ] `harness.rg` 存在，装着二进制名、超时、以及"不在 PATH 上"那句指名拒绝；
      `harness.hashline.grep` 改用它，**自己的 JSON 解析没搬走**。
- [ ] 既有的 `anchor_grep` 行为（`literal` / `glob` / `context` / `ignore-case` / 上限 / 各类拒绝）**逐字不变**：
      `clojure -M:test -m harness.test-runner` 里 `harness.hashline-grep-test` 与
      `harness.editing-mode-tools-test` 全绿，**一条断言都不用改**。
- [ ] 新命名空间 `harness.glob-test` 写进 `harness.test-runner/test-namespaces`，
      且覆盖：按 `**/*.clj` 找到多个文件、`path` 收窄、项目重根（相对 `path` 落在绑定目录）、
      `.gitignore` 里被忽略的文件**不在**结果里、`.git` 下的东西不在结果里、隐藏文件**在**结果里、
      无匹配时说"没有匹配"、超上限时报"共 M 条，这是前 N 条"、`rg` 不在 PATH 上时是**指名**拒绝。
- [ ] `pattern` 缺省/空白是**指名**拒绝（缺参数的那条消息由既有的 `missing-args` 给，行为与别的工具一致）。
- [ ] 顺序断言：同一棵树、同一次调用连跑两次，两条答案**逐字节相等**；
      并且**不受 mtime 影响**（用一条把某个文件的 mtime 改到最新、但它不排第一的用例钉住）。
- [ ] `tools_test/specs-expose-every-base-tool` 的两份名单各加一个 `glob`（排在 `eval` 与 `insert` 之间，
      两份都要加——它不属于任何家族）。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（除基线的两条环境失败）。
