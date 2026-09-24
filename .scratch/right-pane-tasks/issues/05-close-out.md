# 05 — 收口：文档、两套全量、走查与落地记录

**What to build:** 把这一族改过的说法改到逐字一致，把需要**新写**的那几段写出来，跑两套全量与一次
浏览器走查（本特征全在 `ui/src` 与一条新路由上），把**实测**数与落地记录写进
`.scratch/right-pane-tasks/spec.md`，票面按仓库约定删除。

**Blocked by:** 02, 03, 04

**Status:** ready-for-agent

## 要落地的判断

1. **`docs/architecture.md`**：`cap.jobs` 那一行现在多了一件「按会话列出来」的事，`edge` 那一行/路由
   清单要出现 `jobs` 这个动词（`thread-verbs` 是闭合集合，文档里那份清单跟着闭合）。
2. **`docs/architecture/client.md`**：右栏那一处（今天是「点 `agent` 卡开的镜子」）要写上第二条入口
   （开关）、两态、以及任务视图的两段各从哪儿读。
3. **`CONTEXT.md`**：立词（**任务视图** / **右栏**，并给「镜像」一个说法），并改「后台作业（job）」
   词条——「起 / 读 / 停 / 列」之外多一个**发起人**：模型停与人停的差别（一个当场告知、一个下一通
   注入），以及「人这一栏只列作业，不列前台 `c*` 记录」这条范围。逐条核对与实现一致。
4. **跨特征对照里那几条要真的核过一遍**：`.scratch/subagent-view`（复用了哪些、加了哪一颗返回）、
   `.scratch/readback-verbs`（状态话只有一处出处：`job_list` 与这一栏读同一个 `ending-of`）、
   `.scratch/job-receipt-no-path`（这一栏报路径合规）、`.scratch/job-tools`（认领的规矩分家）。
   该划线改注的（若发现某条旧说法不再成立）在**旧文件**里划线 + 日期注。
5. **两套全量 + 一次走查**：后端 `node scripts/test.mjs --backend`、前端 `node scripts/test.mjs --ui`；
   `ui/src` 改过，所以按 AGENTS.md 跑 `node scripts/dev.mjs --scripted` 并**自己开浏览器**走一趟：
   开关开/关、后台任务秒数在长、点 ■ 之后那行变 `[stopped]` 且会话里出现那张注入卡、点子代理一行进
   镜像、镜像那颗返回回得来、窄窗口（767px）两者都不画、**栏关掉之后轮询停了**（网络面板里没有在飞的
   请求）。这些是离线套件说不清的那几格，写进证据。
6. **测试期间 `~/.clj-harness` 只读**（AGENTS.md 的铁律）：后端 runner 自己隔离，不手设
   `CLJ_HARNESS_HOME`；跑完看 `ISOLATION FAILURE` / `ISOLATION NOTE` 两种判据。
7. **落地记录**写进 `.scratch/right-pane-tasks/spec.md`（每票一段 + 撞上的坑），报数用实测（分支 +
   切出提交写清）；五张票面删除。

## 验收

- [ ] `docs/architecture.md` 与 `docs/architecture/client.md` 与实现逐字一致；`CONTEXT.md` 的新词立了、
      旧词条改到位；`grep` 一遍「右栏只能点 `agent` 卡开」这类旧说法一处不剩
- [ ] 后端全量 + 前端全量各跑一次，失败数为 0（或与基线一致），数写进 `spec.md`
- [ ] 浏览器走查那八格逐条过（含关掉之后轮询停了、767px 两者都不画），证据写清
- [ ] 四条跨特征对照逐条核过；需要划线的旧说法已划线 + 日期注
- [ ] `spec.md` 有落地记录（每票一段 + 坑 + 实测数）；五张票面已删
