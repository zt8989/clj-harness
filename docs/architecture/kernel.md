# 内核：event / loop / llm / tools

这四个命名空间是内核的全部。`event` 定义词汇，`llm` 说话，`tools` 干活，`loop` 把它们串起来。

## 事件：11 种，就这些

`harness.event` 是内核唯一的输出面。**AG-UI 的帧全部由 `harness.ag_ui` 从这些事件派生**，
内核自己不知道 AG-UI 存在。

| 事件 | 含义 |
|---|---|
| `:run/start` | 一轮 run 开始 |
| `:text/delta` | 助手文本的一个增量 |
| `:reasoning/delta` | 推理内容的一个增量 |
| `:tool/call` | 一次工具调用，参数是**累积完成的**文本（不是碎片） |
| `:tool/result` | 一次工具调用的结果 |
| `:tool/pre-execute` | 一次调用进入执行缝（**不上 wire**，只落审计行） |
| `:tool/execute` | 一次调用离开执行（同上） |
| `:tool/post-execute` | 一次调用的生命周期闭合（同上） |
| `:run/end` | 正常收尾 |
| `:run/interrupt` | **第二种终态**：有调用 park 等人，本次 run 到此为止 |
| `:run/error` | 出错收尾 |

`:run/end` 与 `:run/interrupt` **互斥**，一次 run 恰好发其一——客户端因此永远看得到一个终结。

## 循环：`harness.loop`

```
drive! :
  emit :run/start
  [有 resume 就先 replay! —— 把人的决定重放进缝]
  loop:
    施加 skills/derived-injections（技能正文，按会话自身重算；幂等）
    llm/stream!      流式一轮（事件边流边发）
    tool_calls 非空 → 并发跑，收齐结果，追加 tool 消息，再来一轮
    tool_calls 为空 → 结束
  有 parked → emit :run/interrupt，否则 emit :run/end
  然后 emit :run/done（携带最终 history），通道关闭
```

**技能正文在每次 `llm/stream!` 之前重算一次**，就在这一行：模型调用 `skill` 是为了**现在**照着做，
等下一轮等于白调；而它是**派生**的（从会话自己扫出加载过的技能，见
[skills-and-instructions](skills-and-instructions.md#技能正文是派生的不是累积的)），
所以每轮施加不需要任何簿记。

几个不显然的地方：

- **同一轮的工具调用并发跑**，各自一个 `async/thread`；每个 `:tool/result` 在它自己的工具跑完时立刻发出
  （结果按**完成顺序**流动），但写进历史的 tool 消息按**调用顺序**——每个 `tool_call_id` 恰好被回答一次。
- **每条调用一个缓冲通道，且永不关闭**：`alts!!` 把「已关闭且为空」的通道当成就绪返回 nil，
  真关掉会让一次排空吞下一个幻影 nil，把真实结果晾在那儿。
- **parked 的调用不回答**：不发 `:tool/result`、不写 tool 消息。它还没被回答，
  它的工具消息落在 resume 那一轮。
- **`:run/done` 不是 wire 帧**，它是「本 run 追加了哪些消息」的返回面，边把它落成 `message` 行。
- **没有迭代上限**，这是设计。
- `replay!` 遇到本进程没 park 过的 interrupt **直接抛**——猜一个批准是这里最坏的失败模式。

## provider 层：`harness.llm`

一个 multimethod，按 `:protocol` 分派；生产上只有 `:openai-completions`，测试里有一个 `:fake` 脚本替身。

请求体只带 `{:model :messages :tools :stream true}`（有 reasoning-effort 就加 `reasoning_effort`）。
`:tools` 来自 `tools/specs`（**每个 LLM 请求都现取**——这条以后会变成 MCP 连接必须缓存的理由）。

两件被反复踩出来的事：

- **`reasoning_content` 必须原样留在历史里。** 带 `tools` 的请求，后续每一轮都要回传它，
  否则 DeepSeek 直接 400。所以 `loop` 把 assistant 消息**逐字**追加，从不重建。
  **推理字段两个拼法都认**：`llm/consume-sse` 同时读 `reasoning_content`（DeepSeek）与 `reasoning`
  （OpenRouter 代理），回来时按 OpenAI 形状写成 `reasoning_content`。
- **`line-seq` 是惰性的**，必须在 `with-open` 里强制求值，否则响应体泄漏、调用方死等一个没人读的流。

`:reasoning-effort` 只有某一档真的选了才出现（`reasoning_effort` 仅部分厂商需要，不给就是厂商默认）。

### prompt 的载体：冻结的是**开头**

`prompt.md` 的载体在这里（`harness.llm/prompt` / `reset-prompt!`），因为「冻结」这件事的理由就是
provider 的前缀缓存——它是 provider 的约束，放在 provider 层。

**但它是 system 消息的开头，不是整个 system 消息。** 一条 system 消息的全文由
`harness.system-prompt/assemble` 在每次 run 组装：`prompt.md` 的字节（冻结）之后，接上
`SystemPrompt` 点每条匹配声明追加的文本，顺序由来源档位定（内建三条 → 文件 → 会话），块间一个空行。
追加的文本**原样**进 prompt，引擎不包装——块自己带 `<tools>` 这类标签。

**冻结的边界就是「与任何会话无关的话」这条线**：身份、hook 自助、secrets 纪律、以及「其余自己读」是
**承诺**，写死在文件里；工具集合、绑定的目录、生效的 provider 是**事实**，现算。理由不是洁癖——
事实能在会话中途变（`project/bind!`、`session-configure`、`session-disable!`），冻下来的那句就会
说一件已经不成立的事。代价是事实动了要付一次冷前缀，那是它该有的样子。

两半各有主人：**system 半是 `harness.system-prompt`，user 半是 `harness.preamble`**
（`harness.project` 已经 require 了 `preamble`，而 system 半要 `tools` / `project` / `hooks.dispatch`，
并进去就是 require 环）。两半不可能交错：不同的 message role。

`harness.llm/prompt` 对**没有 hook sink 的调用方**（离线工具、replay、直接驱动内核的测试）返回的就是
那份开头，逐字节——见 [edge](edge.md) 与 [hooks](hooks.md) 的 no-op 一节。

## 工具表与执行缝：`harness.tools`

工具定义：

```clojure
{:description string :parameters JSON-Schema :required [kw..] :run (fn [args] string)}
;; 另有可选标记：:fence-paths（受围栏约束的文件工具）、:requires-approval（调用即 park）
```

**表与读表的缝住在一起**，因为它们是同一件事的两半：缝决定一次调用意味着什么，表说有什么可调。

### 基座：两份工具表，按会话的编辑模式二选一

基座**不是一个固定清单**：文件编辑有两套实现，一次只会有一套装在本会话的工具表里，由
`harness.edn` 的 `:editing {:mode …}` 决定（见 [home-and-storage](home-and-storage.md#配置根一个根三层优先级)）。

| 模式 | 文件工具 | 其余 |
|---|---|---|
| `:hashline`（**默认**） | `read` `replace` `insert` `anchor_grep` `undo_last_replace`（都带 `:fence-paths`） | `bash` `eval` `session-configure` `skill` `write` |
| `:str-replace` | `read` `write` `edit`（都带 `:fence-paths`） | 同上 |

`session-configure` 带 `:requires-approval`，其余不带。`read` 与 `write` 在两种模式下**同名**，
靠 `:describe` 换脸：参数与说明随模式变，名字不变——同一个名字在两种模式下是两件不同的事，
比两个名字各自只在一半时间里存在更好读。

文件工具的相对路径经 `project/resolve-path` 重根到会话的项目目录，**回报的是已解析路径**。
`bash` 的 cwd 是绑定的目录；**命令内容永不判定**（这是明示接受的逃逸面）。
`eval` 在常驻的 `harness.user` 命名空间里执行，`def` 跨调用保留。
`session-configure` 改本会话的 provider/model/reasoning-effort，**先解析后写**——改不动的配置不会被写进会话。
`skill` 只按名字查表（表由目录列举产生，所以名字永远变不成路径），**不标审批**：读一份指令不是副作用，
而正文里让人做的事各自过各自那道缝。它唯一的效果是把那份正文带进对话，施加点在循环里那一步
（见 [skills-and-instructions](skills-and-instructions.md#skill-工具)）。

### 会话 overlay：两条正交轴

```
thread-id → {:added {name tool}   ; presence：本会话贡献的定义
             :disabled #{name}}   ; availability：本会话关掉的，定义不动
```

- **关闭不是隐藏**：被关的工具**仍在工具表里**（模型仍看得见），调用被缝以 `:disabled` 拒掉。
  藏起来会让「没有这个工具」和「这个工具关着」变成同一个观察，而前者是谎话。
- **没有 `:removed`**：任何东西都不许从工具表里消失。
- **编辑模式是这条规则的唯一例外**（2026-09-15 推翻 tool-toggles 的裁定）：`:hashline` 下 `edit`
  **不在表里**，`:str-replace` 下锚点那四个**不在表里**。代价是模型可能把「被策略关掉」读成「这能力
  不存在」，所以豁免附了一条对等义务——**调用一个本会话不服务的名字要指名拒绝并说清替代品**：
  「本会话按 old_string 编辑，用 replace；要切换写 `:editing {:mode :hashline}`」。一句话同时给出
  能力去哪了、怎么拿回来，模型既不会以为能力不存在，也不必自己摸。豁免只归编辑模式解析器所有，
  `session-disable!` 那条轴一个字没动。
- 关掉是**策略开关，不是安全边界**——关掉 `write` 不阻止 `bash` 写文件。
- 全部进程内、按 thread、重启即失。

### 执行缝：一次调用，三相，三个出口

```clojure
(tools/run! call thread-id on-phase)
;; call 是 PROVIDER 形状：{:id .. :type "function" :function {:name .. :arguments json-string}}
;; 永不抛、永不返回 nil —— 工具失败是给模型的信息，不是 run 的失败
```

`on-phase` 收到三相事件（pre / execute / post）。**生命周期总是闭合**：连没通过 pre 的调用
（未知工具、被关、缺参数）也有自己的 `:tool/post-execute`。

判定次序（`cond`，先匹配者胜）：

| 序 | 判定 | 出口 | pre-execute 的 outcome |
|---|---|---|---|
| 1 | 本会话关掉了它 | 阻断（硬拒） | `:disabled` |
| 2 | 本会话的编辑模式不服务它 | 阻断（硬拒） | `:unserved` |
| 3 | 缺必填参数 | 阻断 | `:missing-args` |
| 4 | 审批规则命中 | 悬置**或**按已有决定穿越 | `:needs-approval` / `:approved` / `:vetoed` |
| 5 | `PreToolUse` 门禁 | 放行 / 阻断 | `:pass` / `:hook-blocked` |
| — | 工具不在表里 | 阻断 | `:unknown-tool` |

**为什么门禁最后**：前四条是 harness 自己的判断；一个根本跑不起来的调用拿去问用户的规则，
既浪费一次 spawn，也把模型读到的理由搅浑。

**`:disabled` 与 `:unserved` 不同，两者都答时 `:disabled` 先出**：一个说「你关掉了它」，一个说
「本会话编辑用的是另一套」。两个都成立时两条都要说（先关掉、再说替代品），否则模型会去把自己关掉的
工具打开，然后发现它**仍然**不会跑。

### 悬置：先问规则，再问人

一条必须悬置的调用问两次：

1. **已经有人答了吗？**（`take-decision!` 取 resume 带回来的裁决，取过即标记已消费——
   重放同一个 interrupt 不能把调用执行两次。）
2. 没有就问 **`PermissionRequest`**：让一条**规则**回答本该打断人的事。
   它的效力与人的答案相同（`approve` 就执行，`deny` 就用它的理由回答这次调用）；
   **声明了但没给答案，就照旧 park 等人**。

所以一个没声明任何 hook 的会话，行为与 hook 存在之前逐字节相同。

悬置的**原因**算一次、随 parked 记录走（`:tool-declares` / `:session-asks` / `:out-of-bounds`），
因为人（和读日志的人）需要知道这是工具自己声明的、本会话要求的、还是撞了项目围栏。

审批状态全在进程内存（`parked-registry`：interrupt-id → 记录），重启即失；
拿一个本进程没 park 过的 interruptId 来 resume 会被**明确拒绝**，不猜。

**不做超时，也不做跨进程持久化**：人一直不响应，这个 thread 就一直待决——这是可接受的语义，
不是缺陷（interrupt 也不填 `expiresAt`，延续本仓「不写 sleep、不重试」的纪律）。
两个开启悬置的来源都不删：工具自带 `:requires-approval` 与 `session-require-approval!`，
它们是「给这个工具装一条悬置型判定」的两种来源，与 `hooks.edn` 里写的门禁同一族。
**默认全放行**：没有任何工具被标记时，帧序列与这套能力存在之前逐字节相同。

**wire 上的形状**（客户端那一侧）：

```
内核 :run/interrupt
  → ag_ui 映射为 RUN_FINISHED + outcome{type:"interrupt",
      interrupts:[{id, reason:"tool-approval", message, toolCallId}]}
  → 客户端从 outcome.interrupts 落 pendingInterrupts
  → 下一次 run 的 RunAgentInput.resume:[{interruptId, status, payload?}]
      status "resolved" ⇒ 批准   "cancelled" ⇒ 否决   （同一个 POST 端点，不做第二个）
```

interrupt 的键是**严格校验**的（AG-UI 的 zod 多一个键就失败），所以内核只往外带四件事实；
给**人**看的那句话是 `message`，不是 `reason`。一次 run 里有多条开着的 interrupt 时，
客户端必须一次把它们**全部** resume——「只回答一部分」会被运行时按名拒绝。

`*thread-id*` 在工具体外面被绑定，所以工具内部的代码（`eval` 尤其是）能问到自己属于哪个会话。

### 文件编辑：锚点那一套（`harness.editing` + `harness.hashline.*`）

**按锚点编辑是这个 harness 的默认编辑方式**（2026-09-15 起）：`read` 每行回成 `锚点│内容`，
`replace` / `insert` 用那个锚点定位，而不是让模型重打一遍要改的文本。

**锚点是什么。** 一行的 4 字符名字（`a3f9`），由**分配**得来——从一张随仓携带的 1353139 个条目的表里
取下一个没用过的（表是 vendored 资产，MIT，署名见 `NOTICE`）——**不是从内容算出来的**。这一点是有意的：
内容相同的两行**绝不**共用一个锚点，所以「改第二处 `return null`」这种话不需要加长上下文来消歧。
一行不动，它的锚点一直是它；锚点**只属于铸造它的会话**，另一个会话读同一个文件拿到的是另一套。
每行还带一个校验和（规范化后的 SHA-256 前 16 位，规范化 = 去 `\r`、去行尾空白、超 500 字节截断）。

**三个词决定了它的行为：**

- **已展示**（served）：本会话**真的把这一行连同锚点印出来过**。它与「归谁所有」是两件事——`read`
  分页时给没返回的页也铸了锚点，那些行归你所有但你没见过，**编辑它们会被拒**。防的正是「凭记忆改一行
  自己没看过的代码」。`read` 与 `anchor_grep` 印出来的算，拒绝里回带的那几行也算。
- **漂移**（drift）：读到某一版之后文件在磁盘上变过了（判据是逐行校验和，不是时间戳）。这时**不静默
  重定位**，而是拒绝并把该区间**当前**的锚点一起交出来。
- **拒绝即交付**：编辑被拒时回的不只是一句错，而是它当时在说那几行 + 它们**现在**的锚点，并把这些行
  登记为已展示。于是重试是一次新的编辑调用，**不是一次重读**——这是整套东西的脊柱。

**改完的答复是 diff，不是一句 "edited"**：`+锚点│行` / ` 锚点│行` / `-    │行` 三态，上下文行数由
`:diff-context-lines` 决定，被删掉那行的锚点位留空（免得有人复制一个已经死掉的名字）。模型照着 `+`
与空格行的锚点就能下下一笔，不必重读文件。

**一次消息里对同一个文件的多次编辑是一次提交。** 一个回合的工具调用是**并发**跑的
（`harness.loop`），两笔各自针对同一基态的编辑各自都成立、合起来丢数据——所以它们按目标路径分组、
区间必须两两不相交、全部针对消息开始前的状态校验、最后一次调用给出合并后的 diff、**一次写一次撤销**，
不成立就整批拒绝（其余调用得到的答复是「已并入」）。不这么做的失败模式是**静默数据丢失**。

**`write` 是锚点的边界**：写完之后该文件所有锚点释放、撤销记录清空（内容已经与模型看到的不是一回事），
并且**拒绝把自己印出来的锚点行回写进文件**。`undo_last_replace` 读的撤销记录只保留最近一次，
把文件**和锚点**一起退回去（只还原文本会让库里那套锚点描述一个已经不存在的状态）。

`anchor_grep` 走 `rg --json`，命中行直接带锚点（行号仍然印，但它**不是拿来编辑的**）；`rg` 不在 PATH 上
是点名失败，不静默降级。危险正则在跑之前就被拒（反向引用、量词化的组、量词化的选择分支、大 `{n}`、
嵌套量词），出路是 `literal: true`。

**存储**（表结构与那三次迁移见 [home-and-storage](home-and-storage.md#sqlitehome-的元数据层)）：
落盘持久化是刻意的——会话长命（jsonl 日志 + 重建），重启后日志里的锚点还该能用。
**铸造是会话级的**，所以那一段临界区要的是一把**会话锁**：`read` 两个文件在同一个回合里并发跑过，
两次铸造从同一个探针位置起步，给不同的行铸出**同一批锚点**，第二次 claim 撞上主键。`with-path-lock`
盖不住这件事（两个不同文件本来就该是两把锁），所以 `store/with-session-lock` 永远是最外层。
