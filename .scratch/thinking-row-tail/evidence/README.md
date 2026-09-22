# 走查记录（2026-09-22）

判据、决策与代价写在 `../spec.md`。这里是**这次运行**的现场：命令、环境、原样输出、四张图。

## 怎么起

```bash
# 一个隔离家、脚本厂商、OS 挑后端端口、前端 5392（跑完收摊）
node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5393
# 另开一个终端
node .scratch/thinking-row-tail/walkthrough.mjs http://localhost:5393/
```

* 环境：macOS 15.7.3 / aarch64，Chromium（全局 `playwright@1.62.1`，`npm root -g` 解析），
  窗口 1200×900。**限速**：页面加载之后、发消息之前，用 CDP 把这条 page 的带宽压到
  `24 KB/s`（`Network.emulateNetworkConditions`）——`harness.fake` 一次吐完 5 字符一块、中间不歇，
  不压速的话整段流在采样之前就结束了（第一次跑只采到 1 个样子，已经落定）。压速用的是**同一段字节**，
  夹具一行没改；跑完立刻恢复（后面那次刷新要重新下载 dev bundle）。
* 期望值**从 `script.json` 现算**：首行 = 那段思考第一个非空行，尾巴 = 去掉空白后最后 120 字。
  改夹具不会让判据变得空洞。

## 原样输出（GREEN，20 条）

```text
ok   the row shows a live window while the model is thinking
ok   the panel never opens itself while the tokens arrive -- state "closed", content "closed"/0px
ok   the row is still ONE line while it runs -- 28px tall
ok   the first thing the row says is the beginning of the thought -- subject: " · 先读 deps.edn，确认依赖有没有变。 第 1 段：这一段只是..."
ok   the row's words keep changing while it runs -- 82 live samples, 81 distinct
ok   the live window slides left, so the newest characters stay in view -- inner left: 507 -> -250 (82 samples)
ok   the window cuts at its LEFT edge: the beginning of the thought is behind it -- inner -277 vs box 507; the inner is 1417px wide in a 632px box
ok   every sample is a run of the model's own words -- 82 samples, 80 of them a full window
ok   a full window moves forward through the thought, one sample at a time -- window from 30 to 7605 of 7871 characters
ok   and the window is never the whole thought -- longest sample 120 chars
ok   the thought ends, and the row stops being live
ok   the panel is still folded when the thought has stopped -- state "closed"
ok   the row is still there when the thought has stopped
ok   it says the FIRST line again -- subject: " · 先读 deps.edn，确认依赖有没有变。"
ok   and it is still folded
ok   a click opens the thought -- content "open"/666px
ok   and the opened panel holds the whole thought -- 7871 chars
ok   a second click closes it again
ok   the conversation comes back after a reload
ok   a restored thought arrives folded, saying its first line -- subject: " · 先读 deps.edn，确认依赖有没有变。", live: false, content: "closed"

GREEN -- screenshots in .../.scratch/thinking-row-tail/evidence
```

## 四张图

| 文件 | 拍的是什么 |
| --- | --- |
| `01-while-thinking.png` | 流式期间：折着的一行，行上是在滚的那一段（右边缘是最新到达的字） |
| `02-after-thinking.png` | 停下来：同一行，回到首行 |
| `03-opened-by-hand.png` | 点开：整段（这一次是 7871 字全文） |
| `04-restored.png` | 刷新之后：恢复出来的会话仍是折着的一行首行 |

## 这次走查顺手改正的两处**走查自己**的错

留在这里，因为下一个人会踩同一脚：

1. **「抽屉关着」不能用元素个数判**。Radix 关着时保留一个空的、0 高度的内容元素，
   `document.querySelectorAll('[data-slot="reasoning-content"]').length` 开着关着都是 1
   （第一次跑因此红了 4 条）。判据换成 `data-state === "closed"` + 高度 0——顺带量到一件真事：
   关着时**子节点根本没渲染**，所以折着的那段思考对页面是零成本。
2. **「最新的窗」不等于「全文的后缀」**。窗是**已到达内容**的最后 120 字，所以刚开头那几个样子是全文的
   **前缀**（还没什么可裁的），只有跨过 120 字之后才是后缀。判据因此分成三条：每个样子都是全文里连续的一段、
   整窗的位置逐次前移（`120 → 7700`）、第一个样子是全文的开头。