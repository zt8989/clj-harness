# 03 — 文档与全量验收

**What to build:** 文档说的是落地后的样子，并把这套行为全量量一遍。下一个读架构文档的人
看得见「临时目录也是一条自由路径，且 strict 收不走」；全量后端套件跑绿，证明改动没有连带动到别处。

**Blocked by:** 02 — 围栏放行临时目录.

**Status:** done

- [ ] `docs/architecture/projects.md` 的「围栏 · 允许集」补上临时目录一条，并写明
      `:approval {:strict true}` 不收走它（与配置家、技能根同一条理由）。
- [ ] `docs/system-prompt.md` 的 `<project>` 样例补上临时目录那行。
- [ ] `CONTEXT.md` 的「围栏」词条更新自由路径的枚举（仍注明只有一个来源）。
- [ ] 全仓搜一遍与本次改动冲突的现状描述（`docs/`、`README.md`、工具描述都算），
      逐处判断「历史记录 vs 现状描述」：历史不动、现状跟上；判断结果逐个写进本票末尾的落地记录。
- [ ] `clojure -M:test -m harness.test-runner` 全绿，报数带上分支与提交。
- [ ] `git diff --stat` 确认本特征的**闭表**：动的只有围栏那个命名空间、机器事实那一层、
      测试基建与相关用例、以及上面列出的文档；其它为 0。

## 落地（2026-09-23，提交见本票那笔）

- `docs/architecture/projects.md`：允许集新增第 3 条「本机的临时目录」，原 3/4 顺延为 4/5。
- `docs/system-prompt.md`：`<project>` 样例补上临时目录两行；下面的说明补一句「配置家、技能根与本机临时目录不退场」。
- `CONTEXT.md`：围栏词条的自由路径枚举加入「本机临时目录（`java.io.tmpdir` 与 POSIX `/tmp`）」。
- **冲突现状描述清查**（`rg` 扫 `src/ docs/ README.md CONTEXT.md`）：唯一命中是 `cap.tools` 四个工具描述的
  「outside the project directory and the configuration home parks」——**本就是简化**（它连技能根与 `:approval {:allow ..}`
  都没列，权威一直是 `<project>` 块），不是本次引入的失真，判断为**保持不动**。无任何文档声称临时目录会 park。
- 判据：全量 `harness.test-runner` 1140 tests / 13198 assertions，0 failures 0 errors。
