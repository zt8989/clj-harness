# spec: 压力表的两个量要量同一种形状，压缩的摘要请求也要

**症状**：一个真实会话（`f59c09dd-…`，2026-09-25，459,765 行）跑到 22:52 被厂商的硬上限打死 ——
`This model's maximum context length is 1048576 tokens. However, you requested 1048581 tokens` ——
而这一路上**自动压缩一次也没发生**，手动/自动的压缩**15 次全失败**，记录里 `context/compacted`
**0 行**。

诊断来自日志与记录本身（`.scratch/compaction-shape/issues/03`），两条根因互相独立。

## 根因一：meter 的两个量，量的是两种形状

`harness.edge.pressure/state->pressure` 算的是 `锚点(厂商实测) + 当前 - 锚点估价`。而：

- `current` 量的是 edge 装配好、**真要发出去**的那个数组 —— 思考已被 `harness.edge.ag_ui/absorbed`
  折进 assistant 的 `reasoning_content`（**provider 形状**）；
- `anchor-est` 量的是从记录折回来的快照 —— 思考是**独立的一条 `role "reasoning"` 消息**（**AG-UI 形状**）；
- `estimate-message` **只读 `:content`**，`reasoning_content` 一个字都不算。

实测（同一段 4000 字思考）：AG-UI 形状 1008 估 token，provider 形状 9。整条记录上：

| run | 日志里的 meter | 记录自己折出来 | 实际首个请求 `prompt_tokens` |
|---|---|---|---|
| `5edb3cb3` 19:42 | 515,496 (49%) | 808,321 (77%) | 800,819 |
| `6746c3b0` 21:09 | 589,972 (56%) | 912,272 (87%) | 890,868 |
| `ae7945f6` 22:37 | 651,970 (62%) | 1,021,300 (97%) | 1,013,961 |
| `0dc5857f` 22:40 | **651,432 (62%)** | **1,023,349 (98%)** | **1,020,335** |

阈值是 0.7 × 1,048,576 = 734,003，meter 报 62% ⇒ `compact-if-pressured!` 每次直接返回。

## 根因二：压缩的摘要请求是 AG-UI 形状

`run-compaction!` 的摘要调用把 `replay/model-nodes` 的计划**原样**交给 `llm/stream!`，没走运行路径
那道 `ag/provider-messages`。厂商拒收那个角色：

```
HTTP 422 ... messages[4].role: unknown variant `reasoning`,
expected one of `system`, `user`, `assistant`, `tool`, `latest_reminder`
```

计划实算：head 2641 条里 **588 条 `role "reasoning"`**，索引 4 正好是其中一条。

## 修法（branch `compaction-shape`，commit `77f65f1`）

- **票 01**：摘要调用送出去的数组先过一遍 `ag/provider-messages`（**同一个折叠，不写第二份**）。
  思考不丢：仍在那条 assistant 的 `reasoning_content` 上。
- **票 02**：`estimate-message` 计价 `:reasoning_content`（`text-blocks` 一个读取器，content 与
  reasoning 同一套规则）。两种形状于是只差一条消息的框架开销。
- `test/harness/fake.clj` 补上**厂商那条 422 的复刻**：请求里出现 `role "reasoning"` 就拒收 ——
  下一个手搓数组的地方会被同一句话拦住。
- 回归：`pressure_test/the-shape-a-thought-arrives-in-does-not-move-its-price`、
  `compaction_run_test/a-summary-call-goes-out-in-a-shape-the-vendor-reads`（造一条真含
  `role "reasoning"` 的记录，走 `compact-post` 全路）。**两条在修之前都红**（已验证：
  摘掉源码修复后 7 处断言失败，含这两条）。

## 复算（票 02）：真记录上，meter 从 62% 抬到 97%

`evidence/02-real-meter.txt`、`evidence/02-real-meter.clj`（可重跑）。同一前缀（事故那行之前）：

| | 修之前 | 修之后 |
|---|---|---|
| provider 形状的估价 | 330,648 | **686,877** |
| AG-UI 形状的估价 | 689,229 | 689,229（锚点那一侧没动） |
| LIVE 读数 | **651,432 (62%)** | **1,020,997 (97%)** |
| 厂商对同一发的实测 | 1,020,335 | 1,020,335 |

阈值 734,003：事故里够不着，现在够得着，而且和厂商自己的数差 0.06%。

## 走查（票 03）：真实记录切片 + 真 kongming

`evidence/03-real-slice-compaction.txt`、`evidence/03-slice-compaction.clj`（可重跑）。
真实记录前 20,000 行（107 条 entry，计划 head 49 条，其中 12 条是思考）：

1. **RAW**（旧代码那一发）：厂商用**原句**拒收 —— 连 `at line 1 column 18135` 都和事故日志对上。
2. **代码自己那条路**（`run-compaction!` 激进）：摘要 6,988 字符回来，`context/compacted` 落盘
   （`{:tokens 29887 :range {:start 4 :end 7617}}`），`compaction/end` 无 error，model view 估价
   55,849 → 27,727。送出去的 role 直方图：`{user 4, assistant 12, tool 21}`。
## 中途再量一次（票 04，commit `be3adc1`）

票 01/02 让 meter 说得出真话，但它只在 **run 起点**开口。事故的时间线就是这一格的证词：

```
22:40:38  run 起点，meter 说 62%
22:40:51  第一发实际 97%（1,020,335 tokens）
22:52:01  最后一发 100%
22:52:04  厂商拒：requested 1048581
```

现在 **每一发请求送出去之前**都问一次（`.scratch/compaction-shape` 票 04）：

- kernel 的 `drive!` 在每次模型调用前问 `:on-pressure` —— 与 `:on-overflow` 对称的 seam（厂商
  拒**之后**的那次恢复，现在有了拒**之前**的这一问）。答更短的就换掉 history，然后把这一个循环
  迭代的 pre-LLM 步重新应用上去（技能体自己补回来；不重复记账、不重复发 `context/injected`）。
- kernel 两种回答一律不换：没变短（更短与否由 edge 量，它才有估价器）；换完会留下未答的工具
  调用（重建自会话的视图可能慢半拍 —— 自己造一个缺结果的请求比发大请求更糟）。
- edge 的 `relieve-pressure!` 量的**就是这一发要发出去的数组**（`log-pressure`），到/过阈值才读
  记录、才拿锁、才压一次（预算那条计划）。没到阈值时一个文件都不碰，也不写 `context/pressure` 行
  —— 记录里那一行仍然只有 run 起点那一条。

测试：`harness.kernel.loop-test` 三条 + `harness.edge.relieve-pressure-test` 两条，摘掉源码改动
先红后绿（edge 侧编译即失败，loop 侧 3 处断言失败）。

## 已知、未修（本 spec 之外）
- ~~**HEAD 上本来就红的一条**：`pressure_test.clj:344`~~ —— **已修**（`bash-quoted-args` 那条线收尾
  时查清的）：不是估算器的错，是**量错了面**。端点与自动压缩那两处递进去的是
  `sessions/messages`（**对话**），而锚点那一侧从记录折回来时带**系统消息** —— `state->pressure`
  拿两边相减，差的 913 就是它。现在 `harness.edge.pressure/live-surface` 把那一层前置补上
  （与记录侧的 `messages-of` 同一条拼法），三处改用它：49087 -> 50000，正是那条断言的写法。
- `case-insensitivity-is-optional`（`harness.cap.hashline.grep-test`）在本机（Windows/rg）红，
  同样与本分支无关。
- **本机的全量套件本来就摇**：`harness.edge.http-test` 单独跑要 184–280s（上限 300s），全量那一轮它
  就是在 300s 上被掐掉、报 exit 2 的；`hooks-test` / `hooks.dispatch-test` / git 端点那几条也随负载
  时红时绿（单独跑都绿）。本分支动过的几个命名空间在单独跑里是干净的 —— 除了上面那条先红的。
- **压缩成功了，那一发请求却没变**（2026-09-28，会话 `86c1c343-…`）：第一次压缩折掉 308,071 tokens
  （`context/compacted` 的 `:range` 18–5126），紧接的那一发（`model/start` 落在 `compaction/end` 之后
  2 秒）报 `prompt_tokens` 696,304、`prompt_cache_hit_tokens` 696,064 —— **前缀命中 99.97%**，也就是
  发出去的那个数组与压缩前逐字相同（真折过的话开头已经是另一句话）。压力因此仍越线，同一个 run 里又
  压了 5 次。**根因未查**：是 `relieve-pressure!` 判定「没变短、不换 history」，还是模型视图没吃下那
  条事实，这两个方向都还没量过。
- **那 5 次被厂商 400 挡回**（同一场会话）：`An assistant message with 'tool_calls' must be followed
  by tool messages responding to each 'tool_call_id'` —— 摘要请求把一次工具调用与它的答复切在两边。
  最后一次成功那条 `context/compacted` 记的区间是**反的**：`{:start 10113 :end 5156}`。范围规划
  （`harness.edge.compaction` 挑的那段）与它记的编号口径要单独查。
- **2026-09-30 复查那两条**：现在有一支探针可以重放这类记录 —— `compaction/plan` 在**记录重折**
  与**活会话**上各量一次（`sessions/model-nodes`），两个答案差多少就是「折了却没变」的那一刀。
  `86c1c343-…` 那次的两个数字没有重放，上面两条**仍按未查记**。

## 票 05 已落地：触发与选范围量同一把尺子（2026-09-30，`e5b4f88` / merge `976b5b5`）

**症状**（主人报「所有的 Context 开头的一直在压缩」）：会话 `62f30024-…` 在 2 分 22 秒里连压 4 次，
每次只折掉上一张摘要（`:shadowed` 1 个、`head-tokens` ≈ 4.6k），换回一张差不多大的新摘要。

**两层根因，两层都修了**：

1. **活会话的号与记录重折不一致**（`compaction-numbering`，`2a14a68`）：一条长 run 的 359 个思考在
   活会话里全摊到同一个号（那一轮终帧的行号），`settle!` 按名字对齐时一个都没命中；而压缩按记录
   行号寻址 —— 同一批 facts 从记录侧删得掉 1249 条、从活会话只删得掉 1028 条。根因是 `replay`
   重算 id 时把**不花号**的三种卡（`-pre<i>`、压缩卡、`-cut-<i>`）也数进了 run 的那一个计数器。
2. **触发与选范围量的是两个表面**（票 05，本条）：触发量活会话（`pressure/live-surface`），`plan`
   折记录重折（每一轮派生的注入只剩最后一轮）。修 #1 之后这种差**仍然能量得出**：真会话上，
   记录重折 735 节点 → head **4,787** token（就是上一张摘要），活会话 956 节点 → head **88,898** token。

**改法**：`harness.edge.sessions/model-nodes`（活会话的模型表面，id 就是条目到达的那一行；本进程
不持有会话时答 nil）交给 `plan`；`plan` 另收 `{:min-head-tokens n}` —— 触发说必须减掉多少，减不到
就**不折**（不写行、不调摘要、不发卡）；两个触发各自算自己的 relief，`run-compaction!` 把两样递下去。

**测试**：`compaction-test` 三条（surface / 不能命名的 head / guard 下不写行也不调用摘要）、
`relieve-pressure-test` 一条端到端（记录 16 万 token + 只有活会话才有的 55 万 token 注入：修前一次
都压不动，修后一次压回阈值以下）。全量：1406 tests / 12 failures + 1 error —— 与干净 main 上同一批
（`tools-test` 8、`mcp-wired-test` 3、`claims-test` 1、`hooks-test` 1），本票没添新红。

## 五张票

- `issues/01-summary-request-is-provider-shaped.md` —— 已落地（`77f65f1`）
- `issues/02-estimator-prices-reasoning-content.md` —— 已落地（`77f65f1`）
- `issues/03-real-log-slice-compaction-walkthrough.md` —— 已走查（证据在 `evidence/`）
- `issues/04-measure-before-every-model-call.md` —— 已落地（`be3adc1`）
- `issues/05-two-surfaces-one-threshold.md` —— 已落地（`e5b4f88`，记录见上）
