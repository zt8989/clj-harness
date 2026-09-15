# 03 — 模式决定服务哪张工具表，以及被拒的名字怎么解释自己

**What to build:** 一个会话只会看见**一套**编辑工具。默认（str-replace）下，这份工具表与今天逐字节
相同：`bash` / `edit` / `eval` / `read` / `session-configure` / `write`。切到 hashline 后，表里
**没有 `edit`**。两套工具**都注册在册**、都可被自省看到，是编辑模式解析器在组装本会话工具表时把不属于
本模式的那几个挡在外面。

**被拒的名字要说得出替代品。** 模型调用 `edit` 而本会话按锚点编辑时，它收到的不是 `unknown tool`：
能力去哪了、怎么拿回来，一句话说清（「本会话按锚点编辑，用 `replace`；要改回按内容替换，在
`harness.edn` 里写 `:editing {:mode :str-replace}`」）。反方向同理。**这条豁免只归编辑模式解析器
所有**——它同时决定「服务哪张表」与「拒绝了怎么说」，两件事是一件事。`session-disable!` 那条通用轴
一个字不动，它仍然是「可见但被拒」，且 **disable 优先于模式**：被显式关掉的工具，无论模式如何都不执行。

**为什么推翻 tool-toggles 的「工具永不消失」。** 那条决策的立论是「模型看不见某工具就会以为这能力
不存在，然后去找 bash 绕路」。这里换一种方式满足它：指名拒绝把替代品与恢复路径一起说出来。而让两个
编辑工具同时在场的代价是实打实的——模型看到 `edit` 就会用 `edit`，而 `edit` 一旦用在锚点模式下，
两者对同一文件的锚点归属就会互相打架。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] 默认模式下 `tools/specs` 的输出与今天**逐条相同**（这使本票落地时既有断言一条都不用改，
      包括那条写死六个名字的 `specs-expose-every-base-tool`）
- [ ] hashline 模式下 `edit` **不在** specs 里；`replace` / `insert` / `anchor_grep` /
      `undo_last_replace` 在（这四个工具本体在 04–07 落地，本票用占位定义把「表由模式决定」
      这件事先钉住；若那一票尚未落地，本票的断言以「`edit` 不在」为主）
- [ ] str-replace 模式下 `replace` / `insert` / `anchor_grep` / `undo_last_replace` 都不在
- [ ] 自省面（`harness.tools/effective-tools`）在两套模式下都能看到**全部**已注册的工具——
      「表里没有」与「注册表里没有」是两件事，前者是策略，后者是事实
- [ ] 被模式挡住的调用返回**指名拒绝**，信息里含：该名字、本会话的模式、替代工具名、改配置的写法。
      `:error` 为真（与 `disabled` 同等对待：这是信息，不是运行失败）
- [ ] 被模式挡住发生在**执行缝的最前面**，早于缺参检查与审批 park——一个注定不执行的调用没有理由
      去等人
- [ ] `session-disable!` 优先于模式：hashline 模式下显式关掉 `replace`，调用得到的是 `disabled`
      而不是模式拒绝
- [ ] 两套模式各自的工具表**按会话隔离**：A 会话 hashline、B 会话 str-replace，同时存在互不干扰
      （编辑模式是按 thread-id 解析的）
- [ ] 自省得到模式：`(harness.editing/editing-mode harness.tools/*thread-id*)` 在两套模式下各回其值
- [ ] 离线全量 `harness.test-runner` 全绿
