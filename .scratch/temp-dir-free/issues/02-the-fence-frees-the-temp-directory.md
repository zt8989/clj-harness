# 02 — 围栏放行临时目录

**What to build:** 绑定了项目的会话，在**临时目录**下读、写、编辑、搜索不再被 park：
`out-of-bounds?` 答 false，执行缝因此不产生悬置，`<project>` 块也把这几条自由路径一并列给模型
（它读的就是围栏那份清单，不另写一句）。`:approval {:strict true}` 收不走它们——
strict 只拿掉项目目录那一格，与配置家、技能根同一条理由。

**Blocked by:** 01 — 本机临时目录只有一个可隔离的答案.

**Status:** ready-for-agent

- [ ] `out-of-bounds?` 对 `<java.io.tmpdir>` 与 `/tmp` 下的绝对路径答 false，含边界：
      目录自身、任意子目录；`..` 逃逸出这两个根后照旧答 true；两棵根之外的路径照旧答 true。
- [ ] 端到端过执行缝：一次指向临时目录的 `write` **真的执行**、落盘、不产生 `:run/interrupt`；
      对照组——同形状的一次界外 `write` 照旧 park（不回归）。
- [ ] `<project>` 块列出临时目录（及 `/tmp`，若与前者不是同一个 canonical 目录），
      理由句来自围栏本身，不是块另写的第二份；未绑定与 strict 两种分支的表现照旧。
- [ ] `:approval {:strict true}` 下临时目录仍自由：strict 项目里对临时路径 `out-of-bounds?` 答 false、
      对项目内相对路径答 true。
- [ ] 放行确由「生产的取法」证明：单独一例把 01 的 override 指到一个真实临时树，
      断言临时路径自由；其余用例仍跑隔离值，互不影响。
- [ ] 临时目录与 `/tmp` 指向同一 canonical 目录时（Linux 上常见）只列一条，不重复。
- [ ] 全量套件全绿。
