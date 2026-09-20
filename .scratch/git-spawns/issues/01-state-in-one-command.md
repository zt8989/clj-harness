# 01 — `git/state` 一条命令答三个问题（4 → 2 个进程）

**What to build:** `git/state` 今天起 **4 个** git 进程：四个独立的问题各一个新进程
（`rev-parse --is-inside-work-tree`、`rev-parse --abbrev-ref HEAD`、`branch --format=…`、
`status --porcelain`）。`git status --porcelain=v2 --branch` **一条**答三个：是不是仓库（非仓库
exit 128）、当前分支（`# branch.head`）、改动（非 `#` 的行数）。目标 **4 → 2**（`branches` 那条
省不掉，porcelain 不给分支列表），而**答案的形状与今天逐字相同**。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 要落地的判断

1. **映射表写进代码注释**，因为它就是这一票的全部内容：

   | porcelain v2 | 今天的来源 | 答案 |
   |---|---|---|
   | exit 128 | `rev-parse --is-inside-work-tree` ≠ `true` | `{:repo? false}` |
   | `# branch.head main` | `rev-parse --abbrev-ref HEAD` = `main` | `:branch "main"` |
   | `# branch.head (detached)` | 同上 = 字面量 `HEAD`，特判成 nil | `:branch nil` |
   | `# branch.oid (initial)` | 同上 **exit 128** → nil | `:branch nil`（**不是** porcelain 给的名字） |
   | 非 `#` 的行数 | `status --porcelain` 的行数 | `:dirty n` |

2. **`(initial)` 那一格必须落到 nil**，理由就是 docstring 已有的那条、只是换了个地方出现：
   `:branch` 要指一个**在 `:branches` 里的**名字。未出生时 `:branches` 是空的（`branch --format`
   什么都不给），而 porcelain 会热情地答出 `# branch.head master` —— 照抄就是画一个不存在的名字。
   实测（git 2.23）：未出生 exit 0 / `(initial)`；今天 `rev-parse` 在那里 exit 128。（detached 同理，
   但那格今天已经处理过。）
3. **`branches` 同步读**，不懒加载：两个调用方都要它（选择器要画、`switch!` 要当 allow-list），
   懒加载改的是 API 契约。见 spec 决策 3。
4. **`git` 那个私有 helper 一个字不动**：`require-posix!`、`quoted`、`timeout-ms` 都沿用，本票只换
   `state` 问的问题。
5. **失败仍然是一种答案，不是抛。** 今天 `branch --format` 失败 ⇒ `:branches []`、`status` 失败 ⇒
   `:dirty 0`。折叠之后 `status` 自己失败（**除 128 之外**的失败）仍要给一个形状，不能变成异常。
6. **`state` 的 docstring 跟着改**：它今天点名了 `rev-parse --abbrev-ref HEAD` 与 `status --porcelain`
   两条命令（用来说明 detached 与 dirty 的口径），换命令之后那几句就是假的。

## 验收

- [ ] **进程数**：`state` 4 → 2；`switch!` 成功路径 9 → 5（临时给 `shell/run` 装计数器量，跑完还原）
- [ ] `git-test` 既有 7 个用例**一条不改**、全绿
- [ ] 新用例：**detached HEAD** ⇒ `:branch nil`，且那几个 commit 不在 `:branches` 里
- [ ] 新用例：**未出生 HEAD**（`git init` 之后没有 commit）⇒ `:branch nil`，**不是** `master`，
      而且 `:repo? true`（它确实是一个仓库）、`:dirty` 按 porcelain 的口径数
- [ ] 非仓库（目录存在但不是仓库）⇒ `{:repo? false}`；不存在的目录 / nil / `""` ⇒ `{:repo? false}`
      且**起 0 个进程**（既有那几条断言不改）
- [ ] `:dirty` 的口径不变：未跟踪的文件算一条（既有那条断言不改）
- [ ] `harness.edge.http-test` 走 `state` 的那条路径跑一次不回归
