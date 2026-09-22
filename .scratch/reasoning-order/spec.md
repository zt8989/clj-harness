# 按 LLM 自己的顺序发帧：答案开始**不**结束思考

**由谁提的**：2026-09-22，主人看过三次修正之后定了方向——*「我想做的是按照 llm 顺序渲染，如果真实情况是
思考，答案，思考，就不要提前关闭思考，直到返回思考结束才停止渲染思考」*。

**推翻的是哪一条**：`harness.edge.ag_ui` 里「**答案的第一个 token 一到达就关掉 reasoning 组**」这条规则
（`:text/delta` 分支里的 `close-reasoning`）。它换来的是折行相邻，代价在下面第 3 条。

## 真实次序是什么（不是推测，是记录里的帧）

那场真会话（`~/.clj-harness/projects/…/24b44253….jsonl`）的帧，按顺序：

```text
REASONING_MESSAGE_CONTENT (r0) "…Answer briefly"      一段想法
TEXT_MESSAGE_START (m1) / TEXT_MESSAGE_CONTENT "我是"   答案开始了
REASONING_MESSAGE_START (r2) / CONTENT " in Chinese."  同一段想法的最后一个 token，晚到
TEXT_MESSAGE_CONTENT (m1) "跑在你这台机器上…"            答案接着写
```

`r2` 之所以是**第二条** reasoning 消息，不是厂商说的——是**我们把 `r0` 提前关了**（`:text/delta` 一进来就关），
厂商只是又发了一段 `reasoning_content`。

## 三条判据与落点

| 判据 | 落在哪 |
| --- | --- |
| 答案的第一个 token 不再关闭思考 | `ag_ui/outbound` 的 `:text/delta` 分支：`(open-text s)`，不再是 `(-> s close-reasoning open-text)` |
| 思考**真的**结束时才收（这一次模型调用结束） | `:model/end` 分支 → `close-reasoning`（它仍然顺手 `open-text`，那条「reasoning 后面必须有 assistant message 可折」的不变式一字未改） |
| 晚到的 delta 落进**同一条** reasoning 消息 | 不需要新代码：`open-reasoning` 只在没有开着的时候开，所以同一条一直开着 |

## 实测

* **线材**（`test/harness/edge/ag_ui_test.clj`，两条）：
  * `the-thinking-is-closed-by-the-end-of-the-model-call`——**常见形状一个字没变**：思考组、然后 assistant
    消息，只是「谁来关、谁来开 text」从答案的第一个 token 换成了 `:model/end`；
  * `the-frames-follow-the-model-s-own-order`——厂商那个形状：`REASONING_MESSAGE_START` ×1、
    `REASONING_MESSAGE_CONTENT` 三截（`想` `一下` ` in Chinese.`）、`REASONING_END` 在答案内容**之后**、
    `TEXT_MESSAGE_END` 之前；
  * `a-vendor-that-thinks-again-after-answering-keeps-one-thinking-message`——**走真 loop + 脚本厂商**
    端到端（`harness.fake` 新增 `:reasoning-after`，就是那个厂商形状）。
* **记录里确认**（`--scripted` 跑完读那份 jsonl）：

  ```text
  REASONING_START r0 / REASONING_MESSAGE_START r0
  REASONING_MESSAGE_CONTENT r0 '先读 de…'
  TEXT_MESSAGE_START m1 / TEXT_MESSAGE_CONTENT m1 '答案开始写…'
  REASONING_MESSAGE_CONTENT r0 ' in C…'        ← 同一个 id
  REASONING_MESSAGE_END r0 / REASONING_END r0
  TEXT_MESSAGE_END m1
  ```

* **真客户端**（`ui/test/suites/client.ts` 第四条 `reasoning-that-comes-back-after-the-answer-stays-one-message`，
  `EXPECTED_CASES` 92 → 93）：客户端**只有一条** reasoning 消息、文本是两截接起来的、并且**排在答案之前**；
  hook 的顺序也证明晚到的那截在 text 之后到达。
* **页面**（`.scratch/reasoning-order/walkthrough.mjs`，5 条 GREEN）：一行 `思考`、它自己就是那块实时窗、
  停下之后说首行、底下没有第二行。
* **既有的那条线没坏**：`.scratch/thinking-row-tail/walkthrough.mjs`（拖动/像素那 23 条）在新后端下重跑 GREEN。
* 后端全套：**1087 tests / 12823 assertions，0 failures**（改动前基线 1085 / 12812）。
* UI 全套：**93 passed**。

## 代价与没做的事

1. **答案开始 ≠ 思考结束**：思考组跨着答案开着，所以那一行在整个回答期间仍然是「正在想」（微光 + 实时窗），
   停下才落回首行——这是这条规则的**直接后果**，也是主人这句话的字面意思（*直到返回思考结束才停止渲染思考*）。
   如果哪天想让它「答案一开始就落定」而**仍然只发一条** reasoning 消息，那需要一个「多久没有新推理就算结束」
   的启发式（时钟），本仓没做。
2. **旧记录不受影响**：`.scratch/thinking-row-tail` 那条线的 `thoughtAt`（页面上「一个想法一行」）仍然在，
   它现在管的是**这次改动之前写下的记录**（那种记录里是两条 reasoning 消息），以及任何别的厂商怪次序。
3. **走查量不到的那一条**（写在 `walkthrough.mjs` 头注释里，也留了 `scratch-timeline.mjs` 作证据）：
   *「答案正在写的时候那一行还活着」* 在这个夹具里**不是可采样的区间**——脚本厂商把一回合的帧一次性交给
   server，server 一次刷出，节流只摊开字节，于是页面把答案和思考的结束**在同一帧**里画出来（实测：行在
   3273ms 落定、答案在 3305ms 出现）。能钉住它的是**线材**与**客户端消息表**那两条，不是浏览器。
4. **`harness.fake` 多了一个 `:reasoning-after`**（测试夹具的厂商形状）：不写它就与从前完全一样。