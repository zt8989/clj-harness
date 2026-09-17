# 02 — 脚本里做不到的事，指名说出来

**What to build:** 两类在本地序列里**做不成**的事，从「静默地半途而废」变成「指名拒绝」：
需要人点头的调用，与 `skill` 的加载。两者都在**执行缝**上拒——所以手搓 `run!` 的那条路径一样拒。

**Blocked by:** 01 — 得先有那条内层调用的路

**Status:** ready-for-agent

## 两类半途而废（都已实测／按代码可证）

**一、需要人的调用。** 内层调用命中悬置型判定时，缝会建一条 parked 记录，把
`{:content "" :error false :parked {..}}` 交给 eval 代码——而**运行不会中断**：`loop` 只看顶层那次调用有没有
`:parked`，所以它继续往下走，人永远不被问，那条记录就此孤儿（谁也 resume 不了它，客户端从没见过它）。
今天这看起来像「工具什么都没干」，实际是「一个本该问到人的决定掉进了地板缝」。

**二、`skill`。** 正文靠 `harness.cap.skills/derived-injections` 发现：它把 assistant message 里 `skill` 的
`tool_call_id` 与一条以 `[skill-loaded]` 开头的 tool message 对起来。内层调用产生不了这一对
（历史里只有 eval 那一次调用），于是正文被取回、印在 eval 的输出里、然后丢掉——**看起来成功，实际没有**。

## 决定

- **拒在缝上。** 判定做在执行缝，不做在 `call!` 上：手搓 `run!` 发的内层调用一样不许静默 park。
  做法是缝知道「这次调用是在某个工具体里面发生的」——在工具体外面包一层动态绑定，与 `*thread-id*`
  被绑在同一个位置、同一个理由（内层代码要寻址自己所属的那次执行）。
- **理由点名。** 拒绝的正文要说清是 `:tool-declares` / `:session-asks` / `:out-of-bounds` 哪一条，
  并给出出路：**把它作为顶层调用发出**，人才能被问到。模型读到的是「怎么做得到」，不是「不行」。
- **`skill` 走标记。** 本仓已有两个标记（`:fence-paths` / `:requires-approval`），
  加第三个同一族的：工具自己声明「只能在顶层调用」，缝照着执行。**这不是往工具的参数 schema 上加东西**
  ——标记与 `:requires-approval` 一样，是 harness 自己的事，不进 `specs`（`approval_test` 里那条
  「the flag is a harness concern, not part of the provider's tool schema」的断言继续成立）。
- **拒绝是信息不是失败**：返回 `{:error true :content "…"}` 给序列，序列可以据此改道继续，run 不炸。

## 验收

- [ ] 内层调用命中三种悬置理由中的任意一种 → **工具不执行、不建 parked 记录**、结果是错误结果；
      正文点名是哪一条理由，并说清顶层调用才能被问到
- [ ] 断言「没留下孤儿」：进程内的 parked registry 里查不到这次调用（`parked-for-call` 为 nil）
- [ ] **手搓 `run!` 的内层调用同样被拒**（不能只在 `call!` 上设防）
- [ ] 顶层调用**一字不变**：同一工具在顶层照旧 park、照旧 `decide-approval!` / resume，既有断言一条不改写
- [ ] 内层加载 `skill` → 指名拒绝（说清顶层调用才行），且**不返回** `[skill-loaded]` 那行
      ——免得派生注入去追一条根本不存在的 tool message
- [ ] 被拒之后序列能继续：后续调用照常执行，run 正常收尾
- [ ] 若拒绝要报相位事件，新增的 outcome 值必须写进 `harness.kernel.event` 的 docstring
      （本仓的规矩：不允许悄悄多一个没人记录的值）
- [ ] 离线全量 `harness.test-runner` 全绿

## 边界

拒的是**需要人点头的调用**，不是「所有危险的调用」——`bash` 没有被任何门槛标记时在内层照跑，
与它在顶层照跑是同一条规矩。审批是流程约定不是安全边界，这一票不改这个立场。
