# 合并那一轮的证据：`ask` 重新站上 2026-09-21 的 main

这一轮不改 `ask` 的行为，改的是**它脚下的地**：`main` 从分叉点 `c5e8940` 往前走 64 个提交
（290 个文件、+32768/−3787），会话改成服务端持有、记录分两种行、测试入口换了、侧边栏整块重画。
所以这里要证的只有一件事：**三张票当时在真浏览器里看到的东西，今天还在。**

起法与 01/02/03 同一条命令，脚本换一下：

```
node scripts/dev.mjs --scripted .scratch/ask-tool/walkthrough.json      --ui-port 5211
node scripts/dev.mjs --scripted .scratch/ask-tool/walkthrough-0203.json --ui-port 5211
```

走查走的是 `--scripted` 各自那对**临时家**（`C:\Users\zhouteng\AppData\Local\Temp\clj-harness-dev-*\`，
退出即删），`~/.clj-harness` 一个字节都没动——这一轮唯一那条 `ISOLATION NOTE`
（`merge-4ns.log`）说的是开发者自己活着的会话在写它，与本次无关，判据见 `docs/rules/testing.md`。

## 六张截图

| 截图 | 看到的 |
| --- | --- |
| `merge-t01-ask-card.png` | run 停在卡上：标题**「模型在向你提问」**，题面两问用 ` / ` 连成一行（`服务应该监听哪个端口？ / 需要我一并改测试吗？`），两格输入（`port` / `tests`），`发送` / `拒绝`；composer 的发送键是灰的 |
| `merge-t01-ask-card-filled.png` | 两格填完（`5211` / `要，后端和前端都补上`） |
| `merge-t01-answers-as-tool-result.png` | 发送后 run 接着跑完，轨迹视图里 `ask` 那一行的结果 |
| `merge-t02-01-card-options.png` | 四种形状同屏：下拉 ×1、勾选框 ×5、输入框 ×1、「或自己填」×2 |
| `merge-t02-02-card-filled.png` | 按"故意拧着来"的顺序填完：`db` 先选 `sqlite` 再在「或自己填」里打字，`targets` 先点 docs 再点 api，`extras` 一个没勾，`note` 空着 |
| `merge-t02-03-answers-in-detail.png` | 轨迹里 `ask` 那一行的参数与结果 |

数得到的那几个数（`data-slot` 计数，与 02/03 那一轮逐字相同）：

```
elicit-01: title = 模型在向你提问
elicit-02: elicitation-select × 1   elicitation-checkbox × 5
           elicitation-input  × 1   elicitation-other    × 2
填完之后:  下拉自己清回 ""（`sqlite` 被手填的 `mysql 8` 顶掉），勾上的是 api / docs
```

## 它证明了什么

从这次 run 的记录里读出来的 `ask` 工具结果原文（`role: "tool"` 那条 `message`，与 wire 上那条
`TOOL_CALL_RESULT` 事件是同一份字节——`jsonl-two-kinds` 之后两种行都在）：

```
- 用哪个数据库？ -> mysql 8
- 这几个方案里你要哪些？ -> api, docs
- 还要带上哪几样？ -> (nothing chosen)
- 还有什么要交代的？ -> (no answer)
```

1. **标题那一支还是对的。** 活浏览器里那行字是「模型在向你提问」，`document.body.innerText` 里
   没有「服务器」——`askedBy` 这条支路在今天的 `GET /api/elicitation`（缺的键不出现）之上仍然通。
2. **一次调用一列问题、一张卡、一次停。** 四问共用一条 interrupt、一张卡；题面在卡上是一行。
3. **候选是点得动的**，而且**「自己填」只在题面写了 `allow_other` 的那两问上出现**
   （`db` 与 `extras` 有，`targets` 没有——两者候选形状一样，差的就是那个键）。
4. **点选与手填是同一个答案的两个来源**：下拉先选了 `sqlite`，往那格打字之后下拉自己清回空。
5. **顺序按候选走，不按手速**：先点 docs 后点 api，答案是 `api, docs`。
6. **「一个都没勾」与「这一问没答」是两句不同的话**：`(nothing chosen)` 对 `(no answer)`。
7. **停着的时候发送是关的**（截图 01 里那颗键是灰的），**答完卡片就散**——
   发送后 `[data-slot="elicitation-card"]` 计数为 0，run 跑到了收尾那句。

## 它没有证明什么（如实写在这里）

- **这一层不是逻辑证据。** park 的形状、`take-decision!` 的恰好一次、不进审批路径，在后端套件
  `test/harness/cap/ask_test.clj`（11 例 86 断言）与 `harness.cap.mcp-wired-test` 里断言；
  这里补的是「画出来了、点得动、填得进、答案看得见」。
- **第三种标题（说不清是谁在问）仍然没有活样本**：两个生产者今天都自报家门，那一支只有渲染断言
  （`ui/test/suites/elicitation-card.tsx`）。
- **「拒绝」那条路这一轮没在浏览器里点**（01 那一轮点过，截图 `t01-05-declined-result.png`）；
  这一轮点它只重复一次同样的 UI 路径，逻辑在后端 `a-declined-form-is-an-answer-and-not-a-failure`。
- **工具行旁边那个词是「待审批」。** `ask` 不进审批这条缝（后端有断言），但**客户端把任何悬置
  的 interrupt 都画成 `needs-approval` 那一格**（`message-parts.tsx` 的 `state`），
  所以一行提问旁边写着「待审批」。这是 `c5e8940` 起就有的同一个形状（那一格与 ask 那三张票
  都没碰过它），**这一轮没有改**——记在这里，是因为它读起来像错的，而它今天确实是这个样子。
- 控制台仍有 pre-existing 噪声（`favicon.ico` 404 等），与本特征无关。

## 记录里的两行

一份 jsonl 里同一次工具结果出现两次是**对的**，不是重复写：`jsonl-two-kinds` 之后
`message` 是"模型被喂到的那条消息"、`event` 是"wire 上那一帧"，两行各有各的读者。
