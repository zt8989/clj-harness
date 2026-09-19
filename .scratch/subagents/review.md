# code review：subagents（两轴，固定点 `a3847ea`）

`git diff a3847ea` 16 个文件 1084 增 72 删，另有 7 个未跟踪的新文件（`subagents.clj`、
两个测试、三个前端文件）。两轴各一个子代理，互不见彼此的上下文，报告在下面原样保留，
末尾是**逐条的处置**。

---

## Standards

### 硬违规（有书面标准可引）

无。子代理逐条对过 `AGENTS.md` 的几条硬规矩，都成立：

- `live`（`subagents.clj:365`）是按 `thread-id` 分家的进程级容器，**读的那一侧也带 key**
  （`served?`、`live-subagent`、`unserved-message`、`system-prompt-block` 四个读者都带）；
- `begin!` / `end!` 各自是一次 `swap!`（`subagents.clj:380,383`），没在两个原子操作之间夹副作用；
- 写路径的路由不写审计行（`http.clj:1530,1590`），校验失败不留痕——与 `edge.md` 一致；
- 长 docstring 讲 WHY 是本仓自己的风格，不算味道。

### 判断项（味道基线，都是"可能的"，不是违规）

1. **重复的措辞**：范围的两种说法，服务端 `range-phrase`（`subagents.clj:440`，给模型看的拒绝语）
   与客户端 `rangeText`/`BASELINE_LABELS`（`subagent-list.tsx:53-70`，给人看、随语言变）。
2. **同一问题有第二条派生路径**：`live` 的 docstring 说冻结的表是"一个答案，不是每个读者的副本"，
   但 `system-prompt-block`（`subagents.clj:512`）又用 `effective-tools` + `served?` 自己算了一遍可见工具。
3. **测试隔离**：`subagents_test.clj` 往 `(home/root)` 写 `harness.edn`（`:44-48,63`），
   与 `AGENTS.md`「不要往 `isolate!` 那对里写」的字面不符（与既有 `project_test.clj` 同一套写法）。

---

## Spec

**06 是砍掉的**（spec 标了可砍），符合预期：子agent 的 park 不走冒泡，由 `unattended-message`
（`subagents.clj:489`）用一句话回答，`subagents.clj:80-85` 明说冒泡是另一张票。

**(a) 缺失或半成品**

- 票 02 的「SubagentStart / SubagentStop 各触发一次，且在记录里有对应的审计行」——
  子代理认为两个点只在父会话里 emit、审计行落错了地方。
- 票 05 说四种拒绝都从"排除字段"走；实际空名字与重名是**名字字段**的拒绝。

**(b) 没要求却做了**

- `face`（`subagents.clj:562`）给 `name` 参数加了严格的 `:enum`。

**(c) 看着实现了、其实不对**

- 票 01 / spec：「来源不能证明只读的（`:source :mcp` 的、本会话 `session-register!` 进来的）一律不进」。
  `provable-read-only?`（`subagents.clj:336`）只看 `(= :builtin (:source tool))`，
  没有问过 `overlays`——本会话自己注册进来的名字可以带着 `:source :builtin` 混进只读范围。

---

## 处置

### 改了（三条）

1. **`provable-read-only?` 补上"谁放进去的"这一问。** 这条成立的证据不在报告里，在既有用例里：
   `a-session-tool-that-cannot-prove-itself-is-out-of-an-exploring-range` 那个 `opaque` 用例**通过的理由是错的**——
   它没有 `:source`，所以是被"来源不是 `:builtin`"挡掉的，不是因为它是本会话注册的。
   也就是说 spec 点名的第二类来源**没有被真正实现**，只是碰巧被另一个条件盖住。
   改动：`kernel/tools.clj` 加 `session-added?`（一次 `overlays` 查询，紧挨它的两个写者）；
   `provable-read-only?` 收 `[thread-id tool-name tool]`，第一个条件就是"这个名字不是本会话放进去的"。
   用例里补一条**穿着内置那身行头**（`:source :builtin` + `:read-only true`）的本会话注册工具，
   断言它进不了 `:read-only` 范围。
   *（注：今天这条路上没有活入口——`session-register!` 的唯一调用者是 `run-one`，而子agent 不可能再委派。
   所以这是堵一个**将来会踩的坑**，不是修今天的功能。零行为变化，全部用例照旧绿。）*

2. **`system-prompt-block` 改读冻结的那张表**（`(:table rec)`），不再自己派生一遍。
   一个说"你有什么"、另一个说"你能跑什么"，两个答案就可能对不上；对不上的那一向最坏——
   块里承诺了、接缝却拒绝，模型就会绕。

3. **`subagents_test.clj` 的 fixture 改成 `with-temp-env`**：每个用例一对自己的 root + OS home，
   建好就空、跑完全删。原来那套"往全 JVM 共用的 root 里写、前后各擦一次"的做法，
   把擦除的正确性押在了"用例不抛异常"上。

### 驳回（两条）

1. **SubagentStart / SubagentStop 的审计行没有落错地方。** 报告说这两个点只在父会话 emit、
   "审计行跑到了别人的记录里"，实测反了。走查里父会话那条记录**没有** `hook/SubagentStart`，
   原因不是没绑 sink，而是**那个点上没有任何 hook 声明**——`fire` 的规矩是"没有声明匹配就什么都不写"
   （`dispatch.clj:238`）。而且 `clojure.core.async/thread` 会传递动态绑定，所以工具线程看得见父会话的 sink
   （`http.clj:720` 绑的那一个），父会话记录里因此会有 `hook/tools/pre-execute` 一类行。
   本仓已有用例正对着这条：`delegation_test.clj:259`
   `the-two-subagent-hook-points-fire-on-the-delegating-record`——两个点各触发一次、顺序、
   `thread_id` 是委派方、审计行在委派方的记录里，且 start 落在它所属的那次调用之内。全绿。
   走查里没看到那两行，是因为那个临时家里没有任何声明；同一个道理，那块记录里也一条 `tools/*` 都没有。

2. **"四种拒绝都从排除字段走"是票面自己的措辞不准**，不是实现走偏。空名字、重名本来就该由名字字段回答，
   票 05 那句话把"四种拒绝都在表单里看得见"写成了"都从排除字段走"。表单行为与票的意图一致，
   改的是票面的说法，不是代码。

### 留着不动（两条，都是判断项）

1. **两种措辞不合并**：服务端那句是**给模型**的拒绝语（英文、说的是范围事实），
   客户端那句是**给人**的（随语言切换、说的是同一条范围）。合成一处没有共同语言可合。
   值得做的是把"为什么有两句"写清楚，别让下一个读者以为漏了共享。
2. **`:enum` 留着**：它每轮按本会话的定义重算，比只写在描述里更硬地把"只能选这几个"说给模型；
   票 02 要求"名字从哪来"要说清，这就是说清的一种方式。要砍掉是产品决定，不是实现缺陷。
