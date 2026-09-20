# spec: `bash` 自己会后台跑 —— 动词收成两个，通知收成三样

**一句话**：`job` 与 `bash` 是**同一条命令的两个模式**（同一处解析 cwd、同一份记录、同一套 spawn），
差别只有「这次调用等不等」——所以动词收成一个：`bash` 多一个参数 `run_in_background`（**工具数 18 → 17**）。
同时**通知收成三样**：`<job-ended id="j1" path="…">[exit 0]</job-ended>` —— id、记录路径、结论行；
没有尾部、没有截断话术、没有「去读它」的提示。工具答案也照这条走：**答案是事实，怎么读在描述里**。

2026-09-18 立，当日落地。两张票：`01 → 02`。

## 问题

1. **两个动词的边界是同一件事的两个模式。** `bash` 与 `job` 都跑一条 shell 命令、都在同一处解析工作
   目录、都写同一份记录；差别只有「这次调用等不等」。多一张脸就是多一次「该用哪个」的判断——而这事
   本仓已经付过学费：`.scratch/bash-record/spec.md` 的现场就是模型把 `job` 读成「慢的那个」，
   再拿 `sleep` 拼一个 `join`。
2. **通知里塞了答案。** 现在它带记录的尾部（最多 1200 字节）+ 一行截断话术 —— 一条「提醒」不该有答案
   那么大；模型要读，`job_output` 与那条路径都在。
3. **答案里带用法说明。** 「read it with `job_output`, or with `bash` / `read` / `grep`」是**说明**，
   不是**事实**：它在每一份答案里重复一遍，而工具描述已经写着怎么读。

## 决策

1. **合并成一个动词。** `bash` ← `job`：参数 `run_in_background`（布尔，缺省 false）。为真时立刻返回，
   答案是 **job id + 记录路径**；`timeout` **忽略**（后台没有时限，描述里写明）；`stdin` **指名拒绝**
   （本仓不给后台喂 stdin；静默吞掉一个模型明确要求的东西是撒谎）。`job` 从工具表退场，**18 → 17**。
2. **`job_output` / `job_kill` 留名。** 它们读与停的仍然是**一条作业**——`run_in_background` 是一条
   命令的模式，不是「作业」这个概念的名字。
3. **通知只有三样。** `<job-ended id="j1" path="…">[exit 0]</job-ended>`：id、路径、记录的结论行。
   没有尾部、没有截断话术、没有「用 `job_output` 读」这类提示。**记录再大，通知都这么大。**
4. **工具答案只说事实。** `job_output` / `job_kill` 的答案不再附「read it with …」；`bash` 后台模式的
   答案是 id + 路径（重定向那条注留着——它是**诊断**，不是用法说明）。
5. **计数与清单跟着改**：`cap.tools` 的 ns docstring、`docs/architecture.md`、`docs/architecture/layers.md`、
   `CONTEXT.md` 的闭清单（三处硬编码清单在**票 01 里就改**，不留给收口）。
6. **旧话划线 + 日期注**：`.scratch/job-tools/spec.md` 决策 1（「后台**不**引入 `run_in_background`」）
   与 `.scratch/job-endings/spec.md` 决策 3（「有界、带尾部、带截断话术」）各划删除线 + 一行日期注。

## 非目标

- **不改 `job_output` 的语义**（`offset` / `limit` / `wait` / `timeout` 一个字不动）。
- **不改记录的写法、寿命与三条读法**（`cap.jobs` 的 `spill!` / `tail-within-budget` / `truncation-line`
  照旧服务前台 `bash` 的答案）。
- **不给后台喂 `stdin`**（`infra.shell/start` 的 `:write-line!` 还在那儿，需要时再挂）。
- **不做推送**、不做作业面板、不改 `job_kill` 的幂等、不改 `ui/src`。

## 验收主线

1. `bash {command: "sleep 1; echo hi", run_in_background: true}` → **立刻**返回（墙钟远小于命令），
   答案是 id + 路径；同一会话 `job_output {job: <id>, wait: true}` 拿得到 `[exit 0]` 与那行输出。
2. `bash {command: "echo hi"}` 与今天**逐字相同**（前台是缺省，一个字不改）。
3. `bash {command: "sleep 2; echo late", run_in_background: true, timeout: 1}` → 照旧返回，
   **命令没有被杀**（时限在后台模式里不适用）。
4. `bash {command: "cat", run_in_background: true, stdin: "x"}` → **指名拒绝**，说清后台不喂 stdin。
5. **通知只有一行**：一条 5000 行的记录、一条空记录，通知都是
   `<job-ended id="…" path="…">[exit 0]</job-ended>` 这个大小（`cap.jobs-test` 量字节）。
6. 工具面：`job` 不在表里、总数 **17**（两处清单用例 + `CONTEXT.md` 闭清单是证据）。

## 跨特征对照

- **`.scratch/job-tools/spec.md` 决策 1**：「后台**不**引入 `run_in_background`（本仓后台已经是一个
  独立动词）」被本特征翻过来——同一条命令的两个模式合成一个动词。划线 + 日期注。
- **`.scratch/bash-lifetime/spec.md` 决策 6**：当年写「后台是三个名字」，后来复议成两个名字
  （`job` / `job_kill`）——本特征的合并把「起」并回了 `bash`，那两个名字里的 `job` 退场。
- **`.scratch/job-endings/spec.md` 决策 3**：通知的「尾部 + 截断话术」被收成三样。划线 + 日期注。
  它的其余部分（谁算告知、一次、不推送、前置步骤那半）一个字不动。
- **`.scratch/bash-record/spec.md`**：它留下的那个判据——**「慢」不是两个动词的轴**——本特征从此
  不必再讲：轴的两个模式在同一个动词里，由一个布尔选。

## 交付顺序

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 合并：`bash {run_in_background}` 上场，`job` 退场 | — | `bash` 的后台模式（立刻返回、答 id + 路径、忽略 `timeout`、拒绝 `stdin`）；`job` 的描述与注册删掉、`redirect-note` 挪到 `bash` 的后台分支；三处硬编码清单与 `CONTEXT.md` 闭清单 18 → 17；`cap.tools` 的 ns docstring 跟着改 |
| 02 | 通知与答案收成事实；计数、旧话、报数 | 01 | `cap.jobs` 的 `notice` 只剩 id + 路径 + 结论行（`notice-budget-bytes` 退场）；`job_output` / `job_kill` 的答案不再附用法说明；`docs/architecture.md` / `layers.md` / `kernel.md` / `README.md` / `CONTEXT.md` 跟着改；两张旧 spec 划线；两套全量报数；本特征自己的落地记录 |

## 状态

**2026-09-18 立票，当日落地。** 两张票按 01 → 02 走完，票面按仓库约定删除。

### 01 — 合并：`bash {run_in_background}` 上场，`job` 退场

- `t-bash` 有了两个模式（`if run_in_background` 一个分支，`work-dir` 共用一处解析）：
  后台时 `jobs/start!` 拿 id 与路径、立刻返回；前台一个字节没动。
- **两个参数各有态度**：`timeout` 不适用（描述里写明「A job has NO timeout」，用例钉住「写了 1ms
  也没杀它」）；`stdin` **指名拒绝**（说什么、为什么、怎么办，一条 ex-info）。
- `redirect-note` 挪到 `t-bash` 旁边（它现在服务后台那一半），措辞改成**诊断**：
  「记录会是空的，输出在那个文件里」——不再指路「read that file instead」。
- `job` 的描述、`t-job`、注册都删了；背景段那段「三个名字」的注释重写成「两个名字」+「起不是其中之一」。
- 计数与清单：`cap.tools` 的 ns docstring、`docs/architecture.md`、`docs/architecture/layers.md`、
  `CONTEXT.md` 的闭清单、两处测试清单：**18 → 17**。

### 02 — 通知与答案收成事实

- `cap.jobs/notice` 只剩三样：`<job-ended id="…" path="…">[exit N]</job-ended>`；
  `notice-budget-bytes` 退场（它服务的是「尾部」）。`ending-of` 取记录末行，所以「怎么结束的」仍是
  记录自己说的那**一行**，不是另立一套。
- 答案去掉用法说明：`job_kill` 的答案只剩「哪条作业 + 怎么结束的 + 记录在哪」；`job_output` 的答案本来
  没有；后台模式的答案是「id + 路径」。用例 `the-answers-state-facts-and-not-instructions` 钉住这一条。
- 文档：`docs/architecture.md`（`cap.tools` 的数目与名单、`cap.jobs` 一行）、`kernel.md`
  （模式表 + 通知那一段的三样事实）、`projects.md`（围栏那节里 `job` 的指代）、`README.md`、
  `CONTEXT.md`（闭清单 + 后台作业 / 注入两个词条）。
- 旧话划线 + 日期注：`.scratch/job-tools/spec.md` 决策 1、`.scratch/job-endings/spec.md` 决策 3。

### 落地记录：与票面不一致的一处

- **`stdin` 的拒绝句里带了「怎么办」**（"Run it in the foreground to write to its stdin"）。票面只说
  「指名拒绝」；按本仓拒绝话术的规矩（说出是什么、以及怎么达成）多写了一句。

### 撞上的坑

- **一条落地过的特性的「可重跑证据」会被工具表的变化弄坏。** `.scratch/job-endings/evidence/go.json`
  里那一步写的是 `job`，合并之后它已经是「unknown tool」——脚本重跑会当场打脸。已改成
  `bash {run_in_background: true}`，并在那份 README 里记了一行（这属于「证据要能重跑」的一部分，
  不是改历史：那天**看见了什么**的话一个字没动）。
- **`notice` 仍然要读一次记录**（拿末行），但它不再读整份用于拼正文：5000 行的记录与空记录的通知
  一样大（用例量过：两条都在 400 字节以内，彼此差不过 200 字节）。

### 基线

- **立票当天（2026-09-18，`.scratch/job-endings` 落地之后的树）：后端 953 / 11943，0 failures /
  0 errors，退出码 0**。
- **落地当天：后端 955 / 11956，0 failures / 0 errors，退出码 0**。
- 前端：`node scripts/test.mjs --ui` → 47 passed；`npm run build` 过（`ui/` 一个字节没动）。
- 真会话走查：`.scratch/job-endings/evidence/` 那份脚本（已改成合并后的调用）重跑一遍，看见的记在
  那份 README 里：工具卡只剩 `bash`（两次调用，一次带 `run_in_background`）、结果是 id + 路径、
  注入那一格是 `<job-ended id="j1" path="…">[exit 0]</job-ended>` 一行。
