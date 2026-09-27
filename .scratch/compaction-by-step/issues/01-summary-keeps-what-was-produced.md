# 01: 摘要记住「已经产出了什么」

**What to build:** 压缩写下的摘要里有一栏「已产出物」——这次压缩之前**已经存在**的分支、worktree、
改过或新建的文件；并且 harness 自己从被折的范围里抽一份**确定性清单**附在摘要之后（不靠模型发挥）。
从人视角：压缩后模型接着干活时，知道自己已经建了哪个 worktree、改过哪些文件，不再把自己做过的事
当成别人的。

**Blocked by:** None（可以立即开始）

**Status:** ready-for-agent

- [ ] `summary-instruction`（`harness.edge.compaction`）明确要求摘要写出「已经产出了什么」：
      建/切过的分支与 worktree、新建/修改的文件路径、尚未提交的改动；仍然只总结被折的范围。
- [ ] harness 从**被折的范围**里抽一份纯投影的清单，附在摘要之后（或随摘要一起进那一条 fact）：
      被 shadow 段里出现过的建 worktree / 切分支之类命令的目标、被 write/replace/insert 过的文件
      路径，去重、按出现顺序。清单可断言，不依赖 summarizer 的措辞。
- [ ] 一条用例：一段含「建 worktree + 写到某路径 + `git status`」的记录被压缩后，摘要里 worktree
      路径与那个文件都能找到；一条只有对话、没有工具的记录被压缩后，清单为空且摘要正常。
- [ ] 摘要不引入被折范围之外的消息（边界仍由 `plan` 决定）。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（报数带分支与提交）。
