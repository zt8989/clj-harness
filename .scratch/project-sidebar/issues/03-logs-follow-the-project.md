# 03 — 日志搬进项目目录

**What to build:** 一次 run 的 jsonl 落在 `~/.clj-harness/projects/<workspace>/<threadId>.jsonl`。
`<workspace>` 是项目绝对路径的 `sanitize` 结果；没有项目归属的会话落在 `projects/_unbound/`（侧边栏
不列这一组，见 spec 决策 1）。读侧——重建、列表、审计行——全部跟过来落点。旧
`~/.clj-harness/logs/` 退役：**不再被读，不迁移，不导入库**。

**不导入说的是三件事，三件都要有用例**：旧目录下的文件不进侧边栏列表；库的 `sessions` 表里没有它们的
行；进程启动时不扫描那个目录、不做任何"发现即入库"的动作。文件本身一个字节不动，重建它们的能力也
还在（`harness.replay` 只吃目录与 thread id，不认识项目）——把文件手动挪进 `projects/<workspace>/`
就能被这份界面看见，工具不替人做这个决定。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] 日志路径按会话的项目归属解析：有项目落在 `projects/<workspace>/`，没项目落在 `projects/_unbound/`
- [ ] `sanitize` 的规则对整个目录名生效，且**同一个项目下的会话落在同一个目录里**（不是每个会话一个目录）
- [ ] `logs-dir` 从 home 里消失，全仓（`src/`、`dev/`、`test/`、`ui/test/`）搜不到对它的引用
- [ ] 一次真实 run（e2e 的脚本化 provider）之后，文件确实在新位置，内容与今天**逐行同形**（`input` /
      `event` / `message` / `tools/*` 的行种类与字段一个不少）
- [ ] `/api/threads` 与 `/api/threads/<id>/rebuild` 仍工作；列表返回的 thread id 仍是文件 stem
- [ ] `~/.clj-harness/logs/` 下的旧文件**不出现在任何列表里**：有一条用例，把文件放进旧目录，断言列表
      里没有它
- [ ] 旧目录下的文件**在库里也没有行**：同一条用例顺手查一遍 `sessions` 表，断言查不到那个 id
- [ ] 启动**不扫描**旧目录：库里的会话行只在人在界面上新建/添加项目时产生，没有从磁盘反推归属的路径。
      这条要说得出口，也要有断言（起进程、旧目录里有文件、断言库里依然没有它）
- [ ] 路径解析的依赖方向保持单向：谁需要日志路径，谁先解析出项目的目录。**home 只认 root 与 sanitize，
      "这个会话属于哪个项目"不是它的知识**——否则 home 会去 require 存储层，整条依赖链倒过来
- [ ] e2e 的 `seedHome`（它在测试 home 里预建 `logs/`）与所有断言日志路径的用例跟上
- [ ] `dev/harness/evals.clj` 的默认目录跟上，或把目录改成必需参数并说清
- [ ] `clojure -M:test` 与 `cd ui && npm test` 全绿

**不做的：** 不迁移旧目录、不为它留兼容读取路径、不保留一个"两个位置都找"的回退——那种回退会让
"日志到底在哪"在很长时间里都是一个要靠实验回答的问题。也**不做搬迁工具**：自动迁移要先猜"这个会话
属于哪个项目"，而日志里没有可靠答案，猜错会把会话悄悄挂到别的项目下。
