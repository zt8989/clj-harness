# 02 — 记录只有一处拼、一条寿命：`records/` 与一个前台也走得到的开门

**What to build:** 「一份命令的记录」这件事从**作业**升格为**命令**：记录住的地方还是配置家、寿命还是
这个进程、读者还是模型手里那三个工具，但它不再只服务后台那一档——`bash` 打完一条命令也要能开一份。
这一票只做**让票 03 好做**的那一步（make the change easy）：把 `cap.jobs` 里那台机器抽到前门可以走，
并把目录名改对。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

`cap/jobs.clj` 里那台记录机器已经全了，缺的只是「谁能走到它」：

- **路径**：`record-path`（`:96-104`），docstring 第一句就是
  「**ONE PLACE BUILDS THIS STRING**」——答案是 `(io/file (home/root) "jobs" (home/sanitize thread-id)
  (str job-id ".log"))`。**唯一**拼它的地方，所以改名是改这一处。
- **开 / 追加 / 收尾**：`open-record!`（`:140-155`，截断开、UTF-8，因为 `infra.shell` 就是用 UTF-8
  解的）、`append-line!`（`:155-168`，每行 flush，「一行躺在缓冲区里，就是模型看不见的一行」）、
  `write-last-line!`（`:169`，`getAndSet` **认领**末行，所以只可能写一次——两个线程同时伸手是常态：
  看见进程没了的 pump，与同一刻停掉它的 `stop!`）。
- **收**：`shutdown!`（`:277-303`）先停进程、再放掉 writer、**最后**删文件（Windows 删不掉还开着的
  文件，顺序就是这么来的），删不掉只 `log/warn!`。
- **围栏**：记录住在配置家，所以读它不挂审批（`cap/project.clj:636` 把 `home/root` 列在 `:free`，
  `test/harness/cap/project_test.clj:149-150` 钉着）；`bash` 自己没有围栏，`tail` 也读得到。

**前台与后台的两处不同，是本票要抽清楚的那条缝**：作业有 pump 线程与一个要等的进程，所以末行由
**认领**写（`write-last-line!`）；前台是**同步**的——`shell/run` 返回时命令已经结束，`t-bash` 自己
就是那个「已经拿到全部输出」的人，它要的是「开一份、写整份、写末行、关掉」。

## 要改成什么

1. **目录 `jobs/` → `records/`**：一份记录不等于一条作业，而目录名是本仓要认的词（`record-path`
   的 `str` 那一行改一处；`cap/jobs.clj` 的 ns docstring 里 `<root>/jobs/<thread-id>/<job-id>.log`
   那句跟着改）。
2. **抽一个前台走得通的门**，形状照 `open-record!` / `append-line!` / `write-last-line!` 的既有语义：
   给一个 `thread-id` 与一个**前缀**（作业用的是它自己的 `j1`，前台要一个不会与作业撞的名字——
   具体拼法由实现选，但**路径仍然只由 `record-path` 拼**），答出 `{:id .. :path ..}` 与一个能
   「追加一行 / 认领末行」的把手。前台**不许**自己拼路径、不许自己 `io/writer`。
3. **`shutdown!` 一并收掉**：前后台的记录同一条寿命（进程走，记录走）。`job_kill` 照旧**不删**。
4. **`start!` 的外部行为一个字不改**：`{:id "j1" :path "…"}`、`job` / `job_kill` 的答案、末行的两种
   写法、重定向那条 note，全部照旧——这一票改的是「谁走得到那台机器」，不是它的语义。
5. **词**：`cap.jobs` 的 ns docstring 里 `A JOB'S OUTPUT IS A FILE, AND THE FILE IS THE RECORD` 那一段
   要把「作业的」扩到「命令的」（哪一档由哪一行末行收尾，两档说清）；`CONTEXT.md` 的**作业记录**
   词条在票 04 里改，本票不动词表。

## 验收

- [ ] `grep -rn "\"jobs\"" src/harness/cap/jobs.clj` 之后，配置家里那棵树叫 `records/`；一台真进程跑
      一条作业，`~/.clj-harness` 下**出现的是 `records/<会话>/j1.log`**，旧名不再出现（用例可直取
      `start!` 答出的 `:path` 断言前缀）
- [ ] 作业的既有用例**一条不改而通过**（`harness.cap.jobs-test` 里那批：起/读/`[exit N]`/`[stopped]`/
      指名拒绝/`shutdown!` 连子孙一起收/退出钩子只装一次）
- [ ] 新抽的那个门有用例：开一份、追加两行、认领末行、**再认领一次拿到 nil**（认领是一次），
      文件里就是那两行加末行；末行写完之后再追加**一个字都不落**
- [ ] 路径仍然由 `record-path` 拼：`grep -n "io/file" src/harness/cap/jobs.clj` 里拼记录的只有这一处
      （前台与后台两条调用者都走它）
- [ ] `shutdown!` 之后**前后台**的记录都不在了；`job_kill` 之后**还在**（既有用例）
- [ ] `node scripts/test.mjs --backend` 与基线一致；本票不碰 `ui/`
