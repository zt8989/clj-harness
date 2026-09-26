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
- **HEAD 上本来就红的一条**：`pressure_test.clj:344`
  （`the-endpoint-answers-the-pressure-section-and-the-run-leaves-it-on-the-record`，
  49087 < 50000），与本分支无关，修之前就在红。
- `case-insensitivity-is-optional`（`harness.cap.hashline.grep-test`）在本机（Windows/rg）红，
  同样与本分支无关。
- **本机的全量套件本来就摇**：`harness.edge.http-test` 单独跑要 184–280s（上限 300s），全量那一轮它
  就是在 300s 上被掐掉、报 exit 2 的；`hooks-test` / `hooks.dispatch-test` / git 端点那几条也随负载
  时红时绿（单独跑都绿）。本分支动过的几个命名空间在单独跑里是干净的 —— 除了上面那条先红的。

## 四张票

- `issues/01-summary-request-is-provider-shaped.md` —— 已落地（`77f65f1`）
- `issues/02-estimator-prices-reasoning-content.md` —— 已落地（`77f65f1`）
- `issues/03-real-log-slice-compaction-walkthrough.md` —— 已走查（证据在 `evidence/`）
- `issues/04-measure-before-every-model-call.md` —— 已落地（`be3adc1`）
