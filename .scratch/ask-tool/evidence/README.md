# 票 01 的证据：`ask` 在真浏览器里走一遍

`AGENTS.md` 要的那一层（渲染看不到布局，只有真浏览器说得清）。起法与平时一样：

```
node scripts/dev.mjs --scripted .scratch/ask-tool/walkthrough.json --ui-port 5211
```

然后在 `http://localhost:5211/` 上走：加一个项目（`POST /api/projects`，不走那个会开原生对话框的按钮）
→ 项目行上新建会话 → 发一句话。脚本按 **thread** 重放（`dev/harness/e2e_server.clj` 的 pin），
所以**每新建一个会话就从头来一遍**：第一轮模型调 `ask` 问两件事，第二轮是收尾那句。

- `t01-01-ask-card.png` —— run 停在卡上。标题「模型在向你提问」，题面两问用 ` / ` 连成一行，
  两格输入（`port` / `tests`），`发送` / `拒绝`；**输入框是关的**，侧栏那行写着「等你回应」。
- `t01-02-ask-card-filled.png` —— 填完。
- `t01-03-answers-as-tool-result.png` —— 发送后 run 接着跑，`ask` 那一行的「结果」是人话两行。
- `t01-04-ask-card-second-session.png` —— 换一个会话重放，卡片又来一次（不是一次性的）。
- `t01-05-declined-result.png` —— 点「拒绝」：结果是 `The person declined to answer.`，run 照常收尾。

## 它证明了什么

1. **三种标题里的第二种真的画出来了**——不是文案断言，是页面上那行字：
   `document.querySelector('[data-slot="elicitation-card-title"]').textContent === "模型在向你提问"`，
   而 `document.body.innerText` 里**没有**「服务器」三个字。「有服务器在向你提问」缺席，这一票的
   主要病害就没复发。
2. **一次调用一列问题**：一条 interrupt、一张卡、两格输入，题面在卡上是一行
   （`服务应该监听哪个端口？ / 需要我一并改测试吗？`），没有拆成两次问。
3. **答案回到模型手里的是人话**——从 `ask` 那张工具卡里读出来的原文：

   ```
   参数 {"questions":[{"key":"port",…},{"key":"tests",…}]}

   结果：
   - 服务应该监听哪个端口？ -> 5211
   - 需要我一并改测试吗？ -> 要，后端和前端都补上
   ```

   一个问题一行，题面是模型自己写的那句话，对着的答案是这一问的。
4. **拒绝不是失败**：点「拒绝」之后 run 跑到了收尾那句回答，工具行的状态是「完成」，
   卡片的 `dom` 里没留下 `[data-slot="elicitation-card"]`（停 → 答 → 散，干净）。
5. **停着的时候发送是关的**：`t01-01` 里输入框是灰的——这正是那张卡片说明书里那条
   「停着时发出去的消息会无声消失」的护栏，在真浏览器里是有效的。

## 它没有证明什么（如实写在这里）

- **第三种标题（说不清是谁在问）没有活样本。** 今天两个生产者都自报家门：`cap.mcp` 的题面总带
  `:server`，`ask` 总带 `:asked-by :model`。所以那一支只有**渲染断言**
  （`ui/test/suites/elicitation-card.tsx` 把一行渲成字符串再读），这一次的活浏览器里没有东西能碰到它。
- **选项与多选没走**（题面里那一列今天只有自由文本），那是 02/03 的事。
- **这一层不是逻辑证据。** park 的形状、`take-decision!` 的恰好一次、`GET /api/elicitation` 的
  `askedBy` 与「没有 `server`」、不进审批路径，都在后端套件 `test/harness/cap/ask_test.clj` 里断言；
  这里补的是「画出来了、点得动、看得见答案」。
- **控制台有三条 pre-existing 噪声**，与本票无关：两条 `GET /api/threads/<id>/stats` 404
  （会话还没有日志时去取统计）和一条 `favicon.ico` 404。

## 家目录

走查走的是 `--scripted` 的那对**临时家**（脚本启动横幅里报的路径，退出即删），
`~/.clj-harness` 一个字节都没动；这台机器上本来也没有那个目录。

---

后端两条腿的结果不在这里——`--backend` 在这台机器上是红的，而那是**改动之前就红**的同一批，
证据见 `backend-run.md`。
