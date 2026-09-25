# 01 — 记录不再写推理帧（线上照发，一个字不少）

**做什么**

`harness.edge.http` 的两处**帧 sink** 不再把 `REASONING_*` 交给记录：

- `runner`（主 agent 那条，`log!` 与 `mux-broadcast!` 并列的那一处）；
- 子 agent 那条 sink（同一文件里 `convert` + `frame-seq` 那一段）。

**线上一帧不少**：`mux-broadcast!`、子 agent 那段的 `frame-bus/publish!` 与 `sessions/land!` 的回调
一个字不动；`runner` 收进 `state` 的 `:frames` 也一个字不动（`settle!` 折叠**内存里**那场会话用的就是它）
——所以**记忆里的会话、屏幕上的流、客户端收到的东西与今天完全一样**，改的只有磁盘上那一份。

**一处判据，一个地方。** 哪一族帧不进记录是**一个决定**，要有一个有名字的谓词（放在 `row-of` / `terminal`
旁边），不是两处各写一个 `(when-not ...)`。`.scratch/jsonl-two-kinds` 那句「记录持有它能被重建出来的那套
词汇」和 `runner` 那句「记录与线上逐帧相同」**在这一票里就要改掉**——一票改代码、一票改那句话，等于让
下一个人照旧契约读代码。

**代价照实说**：模型那一行 `message` 是 `:run/done` 才写的，帧是流出来就写的。所以**一个被杀掉的 run
丢掉它想过的那半段推理**（今天至少留下半段）。答案不受影响——文本帧照旧写。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] 主 agent 与子 agent 两条 sink 都用同一个谓词；谓词只有一处定义，且有 docstring 说清为什么是它。
- [ ] 一次真 run 的记录里**一条 `REASONING_*` 都没有**；而 mux 上收到的帧集合、顺序、内容与改前**逐帧一致**
      （测试：同一次 run 两边各收一份，比集合与顺序）。
- [ ] `settle!` 之后内存里的会话仍带推理（`sessions/messages` 的用例保持绿）——这一条是「只动了记录」的判据。
- [ ] 断掉的 run 这条代价有一条用例**钉住**（它是被接受的代价，不是意外）：run 在推理中途被杀，记录里没有
      推理、文本帧照旧在。
- [ ] 顺手记下（不一定要在这一票里做）：`harness.cap.frame-bus` 在 `http.clj` 里被 require **两次**
      （118、119 行），而且它自 ADR 0004 票 05 删掉 follow 路由之后**只被 publish、没有订阅者**；
      2020 行附近那段 `:seq` 的注释还在说「a follow channel replays what the record holds」——那句话今天
      没有事实支撑，别照它推理。
