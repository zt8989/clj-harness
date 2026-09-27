# 02: 加载闸门——message 脱出信封就拒绝继续，只允许 fork

**What to build:** 一场会话在能被接受新提问之前，先校验它的记录：每条 `message` 都在某个信封
（一对 start/end 事件）之内。任何一条脱出 → 这一轮**直接拒绝**（点名是哪条 message、脱在哪个信封
外），并说明该会话只允许 fork 重整；fork 出来的新会话照常可以提问。

**这是临时闸门，以后要拆（主人，2026-09-27）**：它只为兼容旧的 JSONL 记录而设；等 JSONL 的格式稳定、旧记录退役，**整段删除**。它不是长期契约——收在一处（一个纯函数 + 一个调用点），旁边写明退场条件。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 一个纯函数（建议在 `harness.edge.replay`）回答「这份记录里有哪些 message 脱出信封」，
      按层查：run 层（有头无终局）、model 层（`model/start` 无 `model/end`）、
      工具层（有 `TOOL_CALL_START` 无 result）、step 层（step-events 落地后：`step/start` 无 `step/end`）。
      与 `open-runs` / `unpaired-model-row?` / `llm/unanswered-tool-calls` 共用同一套读法，不另写一份。
- [ ] 开 run 之前问一次；不过关就以 RUN_ERROR 拒，错误里点出脱出的那条 message 与它该在的信封。
- [ ] 拒绝之后该会话**不能**继续提问（也不许靠重建绕过）；fork 出来的新会话不受影响。
- [ ] **跑着的 run 不算脱出**：一个开口 run 与本进程正在跑的 run 在文件里长得一样（`open-runs`
      的注释自己写着这一点），所以闸门先问进程（live-runs 注册表 / `harness.edge.http/running?`）：
      **正在跑就放行**，只有「没人跑、又没有终局」的开口 run 才算坏。一条用例钉住这条。
- [ ] 一条用例：人为把一条 `message` 行搬到 `model/end` 之后，加载/提问被拒；搬回去就通过。
- [ ] 一条用例：一份自洽记录（含开场块、且压缩过）加载**不被误拒**。
- [ ] 一条用例：一个 mid-run 的记录被拒之后，fork 出来的新会话能继续提问。
- [ ] **闸门是临时的**：代码里写明退场条件（旧 JSONL 退役 / 格式稳定后删除），并把它收在一处，便于整段拔掉；不许把它焊进长期契约。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（报数带分支与提交）。
