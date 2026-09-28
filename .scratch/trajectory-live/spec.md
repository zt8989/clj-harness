# 轨迹：一轮还在跑的时候，看得见它在做的工具调用

**症状（owner，2026-09-27）**：一轮**正在跑**的时候，轨迹上只有「用户发的」和「注入的」，看不到它正在做的
工具调用。会话那边一有新调用就看得见——轨迹理论上也应该一样，`新增一个调用，轨迹上就能看到最新`。

## 根因

轨迹的条目**全部**来自 `message` 行（`harness.edge.trajectory`：system / context / user / assistant / tool）。
而一轮的**返回侧** `message` 行——助手那条（带 tool_calls）和回答工具的那条——是 **`:run/done` 之后**才写的
（`harness.edge.http/log-messages!`，一次全交出去）。所以只折 `message` 的读者在一轮跑着的时候，
手上只有提交侧那几行（提问、注入、system），**没有可画的工具调用**。

工具调用不是没有留下痕迹：`tools/pre-execute` / `tools/execute` / `tools/post-execute` 是**当场**一行一行写的
（`harness.edge.http/lifecycle-record`）。轨迹此前完全不看这三行。

这是**数据**的事，不是渲染的事：路由每 100ms 就把仍然开着的那一轮重推一次
（`trajectory-get` 的 `push!`），推上去的东西里就是没有那次调用。

**复现（可重跑的探针）**：`dev/scratch_trajectory_live.clj`。它起一个真会话，脚本 provider 让模型要一次
`bash {"command": "sleep 6"}`，于是 run **卡在工具缝上**，在这 6 秒里采样 `trajectory/view-value`：

```
修复前： {:incomplete true, :turns [{:index 1, :kinds ["system" "user"], :tools []}], :tool-lines ["tools/pre-execute"]}
修复后： {:incomplete true, :turns [{:index 1, :kinds ["system" "user" "tool"],
                                    :tools [{:name "bash", :executed false, :hasResult false}]}], ...}
```

## 修法

**只给仍然开着的那一段多折一次**（`harness.edge.trajectory/pending-tool-items`，由 `trajectory-answer` 用
`:live?` 标出来）：

- `segments-step` 把**到达的**调用（`tools/pre-execute`，且 runId 与本段一致）记在段自己的 `:tool-ids` 上；
  `life-step` 顺手记下审计行自带的 `:toolName`。
- 折开着的那一段时，**已经到达、而这一段的返回侧还没有回答它**（没有对应 `tool` 角色的 `message` 行）的调用，
  画成一条 `tool` 条目。名字取调用索引（助手消息落了盘就用它）或审计行的 `:toolName`；
- **参数与结果缺席就是缺席**（`result` 键不在，不是空串；`argsText` 同理）——记录里现在没有这两样，按这个视图的
  规矩就不许填。

返回侧落盘之后，同一条被换成 `message` 行折出来的那条（名字、参数、结果俱全）。**一次调用始终只有一行**：
判据是这一段返回侧有没有回答它（`answered-ids`）。

## 为什么只给「还开着的那一段」

**已经关掉的那一段，记录是完整的**：它没回答的调用，是**同一轮的后一段**回答的——悬置与恢复是两段、一条
工具消息。要是两段都折，一次调用会被画两遍；更糟的是，`life-of` 是按 toolCallId **合并**的（`arrivedAt` 取第一
次、`outcome` 取最后一次），悬置那半会读到**恢复之后**的裁决（`vetoed`），等于把未来塞进读者问的那个过去。

`a-closed-segment-does-not-draw-its-unanswered-call` 钉住这条。

## 前端

- `TrajectoryItem` 的 `result` 变成可选（`ui/src/lib/trajectory.ts`）：跑着的那次调用还没有答案。
- 行里**不画 `→ 结果` 那一半**，右侧面板的「已执行」多一个答案：**尚未**（`values.pending`）——「否」留给真正
  没跑的、被人拦下的那种调用（`ui/src/components/trajectory-view.tsx`）。

## 已知的相关毛病（本票没动，另开）

右侧面板按 **(轮, 下标)** 记住「打开的是哪一条」。一轮结束时，返回侧被**插在**跑着的那条 `tool` 前面，于是
下标整体后移：原本点开的工具条目，重取之后会落到助手条目上（面板不关，只是换了内容）。这是既有的解析规则
（`trajectory-view.tsx` 的 `open`）在下标不再成立时的表现；本票让它在「开着一条等待中的调用」这条常见路径上
更容易撞到。要修就得给条目一个不随插入漂移的身份（至少让 kind 变了就**关掉**面板），不在本票范围。

## 验收

- 后端：`clojure -M:test -m harness.test-runner harness.edge.trajectory-test`（28 / 123 / 0），
  新增三条：跑着的调用被画出来、返回侧落盘后**换掉**而不是并排、关掉的段不画它未回答的调用。
- 全量后端（`clojure -M:test -m harness.test-runner`）：60 个 namespace / 1136 用例 / 12228 断言 / 0 失败；
  它撞上 `harness.edge.http-test` 的 300s 命名空间上限（当时并行跑着走查服务和浏览器），于是余下 8 个
  namespace 没轮到。分开补跑：http-test 单跑 **115 / 1240 / 0**（第一次它红了一条 overflow 的用例——那条按
  provider 调用次序吃脚本，机器忙的时候会飘；单独重跑绿），其余 8 个 **78 / 570 / 0**。
- 前端：`npm run typecheck`、`npm test`（164 / 0）、`npm run build`。
- 走查：`node scripts/dev.mjs --scripted .scratch/trajectory-live/walkthrough.json`，脚本里那次 `bash` 刻意
  `sleep 25`，在它跑着的时候切到轨迹——见 `evidence/`。
