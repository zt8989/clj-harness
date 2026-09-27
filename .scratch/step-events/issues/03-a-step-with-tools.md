# 03 — 一步带工具：在最后一个工具的结局之后收口

**What to build:** 一步的区间真的括住「一次请求 + 它调的那些工具」——`step/end` 落在这一步**最后一个工具
有了结局之后**，并且说出这一步调了哪些工具、各自什么结局。

从用户视角：一段「模型要了三个工具、跑完、又调了一次模型」的过程，在记录与线上被切成两步，
每步的两端恰好把它自己那次调用与它自己的工具括在里面；一步有几次调用就有几个 `model/*` 对，
但 `step/*` 仍然只有一对。

**Blocked by:** 02

**Status:** ready-for-agent

## 验收

- [ ] **收口的时机**：工具的结果在 `harness.kernel.loop` 那条 drain 循环里等齐（`await-call` 那一处），
      `:step/end` 发在**这一步的最后一个工具的结局（`tools/post-execute` 那一行）之后**；
      一次工具也没调的步，`step/end` 紧跟在 `model/end` 之后（02 的形状）。
- [ ] **载荷说这一步做了什么**：`step/end` 的记录行带 `{:tools [{:id … :name … :outcome …} …]}`，
      与这一步的 `TOOL_CALL_START` 帧、`tools/*` 行**对得上**（id 与 name 逐字）。
      没调的步带空向量（`[]`），不是缺键。
- [ ] **`step/start` 与 `model/*` 的关系**：一步里的**每一次**模型调用都在自己那一步的两端之内；
      重试（`overflow-retries` / 空转重试）产生的额外调用也在同一步里——它们是同一步的尝试，
      不是新的一步。
- [ ] **停在半路**：一次被停的 run，被停的那一步**要收口**——记录里不留下没有 `step/end` 的 `step/start`。
      一条用例：跑到一半按停，断言 `step/start` 的条数 === `step/end` 的条数。
- [ ] **park 不是一步的终局**：`run/interrupt` 之后带着人的答复回来，收的是**同一步**（`step/start` 不重发，
      一次 `step/end`）——同 ADR 0006 决策 3 对轮的判法。一条用例钉住「park → resume 只发一次 `step/end`」。
- [ ] **区间是读得出来的**：读侧（`harness.edge.trajectory` 或一步的折叠）能把任一步的区间读成
      「`step/start`.seq … `step/end`.seq」，并落出这一步的 `model/*` 与 `tools/*`——
      一条用例：一步两次工具 + 一次额外调用，断言区间里的行**不多不少**。
- [ ] `clojure -M:test -m harness.test-runner` 与 `cd ui && npm test` 全绿（报数带上分支与提交）。

**为什么这一步值得进记录（复核用）：** 一步的边界今天只能由读者拿 `model/start` 与随后的 `tools/*`
**现推**，而推法至少两份（`ui/src/lib/turns.ts` 与 `harness.edge.turn`）；把边界写进记录，
与「轮的边界算得出来所以不必再写一行」是同一条纪律的两个方向（见 `spec.md`）。
