# 03 — `job_output` 的答案：状态行挪到最后一行

**What to build:** 读一条作业的答案按**记录自己的顺序**读：先是它说过的正文，再是（装不下时的）范围/路径
行，**最后一行才是状态**——`[exit N]` / `[stopped]` / `[running]`。与 `bash` 答案末尾那一行同一个位置，
与那份记录的末行同一个事实。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## 形状

- `正文 → （装不下时的范围/路径行） → 状态行`；装得下的时候就是 `正文 → 状态行`。
- 状态行的**字一个不改**（还是记录的末行，或 `[running]`）；改的只是它排在哪。
- `jobs/output` 的**返回形状不动**（`:status` 仍是它自己的一个字段）；动的是「脸」怎么摆
  （`t-job-output` 怎么拼那几行）。

## 决策

- **与 `bash` 一致。** `bash` 的答案末尾那一行就是它怎么结束的（非零才印），理由写在 `t-bash` 里
  （「结束是一件事实，写一次」）；`job_output` 此前把同一件事实摆在**头一行**，读起来与它所读的记录
  正好相反。记录的末行是状态，答案的末行也就是状态。
- **范围/路径行在状态之上。** 它是**正文那句话的一部分**（「这段是记录的第几行到第几行」），不是结局；
  状态是答案的最后一个字。
- **跟着改的谎话**（这一票落地当天就不成立的，逐条改，不留）：
  `cap.tools` 的 `job-output-description`（「The first line of the answer is how it stands」）、
  `cap.jobs` 的模块注释那两句（在「TWO FACTS, AND THE RECORD ANSWERS BOTH」那段里，
  「`job_output`'s answer prints that line as its first line」）、
  `CONTEXT.md` 的「作业的读法」词条（「头一行是状态（记录的末行，或 `[running]`），……结尾一行说这段是
  记录的第几行到第几行」）、`README.md` 那句「头一行是状态」。
- **一个字不改的**：`job_kill` 的答句（它本来就说记录的末行）、通知（`<job-ended id="…">[exit N]</job-ended>`
  三样事实）、「告知」的规矩、`offset` / `limit` / `wait` / `timeout` 的语义、记录末行的约定。

## 验收

- [ ] 用例：跑完的作业 → 答案**末行** `[exit 0]`（或它真实的码）；挂着的作业 → 末行 `[running]`；
      `wait: true` 挂到结束 → 末行是那一条结局；`wait` 超时 → 末行 `[running]`
- [ ] 用例：被停的作业 → 末行 `[stopped]`
- [ ] 用例：输出超过 `answer-budget-bytes` 的作业 → 正文、省略/范围行、状态行**逐行对得上**这个顺序
- [ ] 用例：`offset` / `limit` 只影响正文窗口，**不影响状态行的位置**
- [ ] 用例：`(no output)` 那种空正文档也照样以状态行收尾
- [ ] 原先断言首行是状态的用例**跟着改到末行**，不许删断言删到没人看着这件事
- [ ] 用例钉着**没动**的两处：通知注入的字节、`job_kill` 的答句（逐字）
- [ ] 「首行是状态」那四处旧说法在 `src/`、`CONTEXT.md`、`README.md` 一处不剩（`grep` 核）
- [ ] `harness.cap.jobs-test` / `harness.kernel.tools-test` 全绿
