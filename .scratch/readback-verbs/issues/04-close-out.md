# 04 — 收口：计数、闭清单与落地记录

**What to build:** 三张票各自把该改的谎话跟着改了，这一票负责**核到逐名相等**、把需要**新写**的那几段
写出来、把两套全量与一次浏览器走查的**实测**数报出来，并把本特征的落地记录写进
`.scratch/readback-verbs/spec.md`。票面按仓库约定删除。

**Blocked by:** 01, 02, 03

**Status:** ready-for-agent

## 要落地的判断

1. **计数到二十，而且逐名对得上**——不是只改数字：`docs/architecture.md` 的模块地图（`cap.tools` 那行、
   工具名清单、它那句「干活的不在这里」的分工句）、`CONTEXT.md` 的工具名清单、
   `docs/system-prompt.md` 的名单、README 那一节。核法：把三处硬编码名单（`tools_test` 两份、
   `editing_mode_tools_test` 三份）与四份文档摆在一起**逐名**对——今天 `CONTEXT.md` 的闭清单比真实工具表
   少一个 `skill`，01 顺手补过，这里核一遍。
2. **需要新写一段话的地方**（不是改旧说法）：`docs/architecture.md` 的 `cap.todos` 与 `cap.jobs` 两行
   （读侧各自多了一只手）；`docs/architecture/overview.md` 的状态表（作业那一行说清「列出来」的入口、
   清单那一行说清读侧）；README 那一节（两只读法 + 状态行归位之后的读法：**末尾一行说它怎么结束**）。
3. **`CONTEXT.md`**：核对「回执 / 作业的读法 / 告知 / 任务清单 / 后台作业（job）」五处与实现逐字一致；
   立词表里 `job_list` 与 `todo_read` 该出现的地方出现。
4. **两处被翻掉的旧决定**：`.scratch/tool-parity/issues/03-todo-write.md`（不设读工具）与
   `.scratch/bash-record-persistence/spec.md`（不引入列出/找旧记录的动词）——01/02 各划一处并加日期注，
   这一票核一遍日期、指路与措辞。
5. **两套全量 + 一次走查**：后端 `node scripts/test.mjs --backend`、前端 `node scripts/test.mjs --ui`。
   `ui/` 改过（图标与一行断言），所以按 AGENTS.md 跑一次 `node scripts/dev.mjs --scripted`，**自己开浏览器
   走一趟**，确认 `todo_read` / `job_list` / `job_output` 三行卡都看得见、`job_output` 的答案末行是状态。
6. **落地记录**写进 `.scratch/readback-verbs/spec.md`（每张票一段 + 撞上的坑），报数用**实测**（分支 +
   切出提交写清）；四张票面删除。
7. **测试期间 `~/.clj-harness` 只读**（AGENTS.md 的铁律）：后端 runner 自己隔离，不手设
   `CLJ_HARNESS_HOME`；跑完看它报的 `ISOLATION FAILURE` / `ISOLATION NOTE` 两种判据。

## 验收

- [ ] 工具表**二十**个：三处硬编码名单 + `CONTEXT.md` 闭清单 + `docs/architecture.md` 逐个名字对得上
- [ ] 「`job_output` 首行是状态」那四处旧说法在 `src/`、`CONTEXT.md`、`README.md`、`docs/` 一处不剩
- [ ] 两处旧非目标已划线 + 日期注，且指路指向本特征
- [ ] 后端全量 + 前端全量各跑一次，失败数为 0（或与基线一致），数写进 `spec.md`
- [ ] `node scripts/dev.mjs --scripted` 下浏览器走查过，三行卡与状态行位置写进记录
- [ ] `spec.md` 有落地记录（每票一段 + 坑）；四张票面已删
