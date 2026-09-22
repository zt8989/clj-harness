# 走查记录（2026-09-22）

判据、决策与代价写在 `../spec.md`。这里是**这次运行**的现场：命令、环境、原样输出、四张图。

## 怎么起

```bash
# 一个隔离家、脚本厂商、OS 挑后端端口、前端 5393（跑完收摊）
node scripts/dev.mjs --scripted .scratch/thinking-row-tail/script.json --ui-port 5393
# 另开一个终端
node .scratch/thinking-row-tail/walkthrough.mjs http://localhost:5393/
```

* 环境：macOS 15.7.3 / aarch64，Chromium（全局 `playwright@1.62.1`，`npm root -g` 解析），
  窗口 1200×900。**限速**：页面加载之后、发消息之前，用 CDP 把这条 page 的带宽压到 `6 KB/s`
  （`Network.emulateNetworkConditions`）——`harness.fake` 一次吐完 5 字符一块、中间不歇，
  不压速的话整段流在采样之前就结束了。1,058 字 ≈ 25 KB 的 SSE，压到 6 KB/s 就是四秒左右、约 250 字/秒，
  也就是一个快厂商的样子（`TAIL_SPEED` 的封顶是 ~300 字/秒，所以这个速率下拖动能跟上）。
  压速用的是**同一段字节**，夹具一行没改；跑完立刻恢复（后面那次刷新要重新下载 dev bundle）。
* 期望值**从 `script.json` 现算**：首行 = 那段思考第一个非空行，流式那一半 = 同一段字压成一行。
  改夹具不会让判据变得空洞。

## 原样输出（GREEN，21 条）

```text
ok   the row shows a live window while the model is thinking
ok   the panel never opens itself while the tokens arrive -- state "closed", content "closed"/0px
ok   the row is still ONE line while it runs -- 28px tall
ok   the line is the arrived text, whole -- not a window cut out of it -- line: 45 characters of 1058, ends "一段只是把窗口往前推一点。第 2"
ok   the line keeps growing while it runs -- 99 live samples, 18 distinct lines
ok   the words do NOT change between every pair of samples -- so the position must -- at least one hold
ok   while the words hold still the line is still TRAVELLING (interpolated, not a jump) -- 67 of 81 holds moved
ok   the line slides left, so characters leave at the left edge -- track left: 507 -> -10487 (99 samples)
ok   the window cuts at its LEFT edge: the beginning of the thought is behind it -- line starts -63px left of the window
ok   the right edge always has text under it -- the newest characters arrive there -- lag: max 1275px, last 0px, 99 samples
ok   the drag catches up: the newest characters come back into view -- lag: max 1275px, median 190px, last 0px, 32 of 99 samples caught up
ok   every sample is the thought so far, and shorter than the thought -- line 45 -> 1004 of 1058 characters
ok   the thought ends, and the row stops being live
ok   the panel is still folded when the thought has stopped -- state "closed"
ok   the row is still there when the thought has stopped
ok   it says the FIRST line again -- subject: " · 先读 deps.edn，确认依赖有没有变。"
ok   and it is still folded
ok   a click opens the thought -- content "open"/111px
ok   and the opened panel holds the whole thought -- 1058 chars
ok   a second click closes it again
ok   the conversation comes back after a reload
ok   a restored thought arrives folded, saying its first line -- subject: " · 先读 deps.edn，确认依赖有没有变。", live: false, content: "closed"

GREEN -- screenshots in .../.scratch/thinking-row-tail/evidence
```

## 四张图

| 文件 | 拍的是什么 |
| --- | --- |
| `01-while-thinking.png` | 流式期间：折着的一行，行上是那一段字（右边缘是最新到达的字） |
| `02-after-thinking.png` | 停下来：同一行，回到首行 |
| `03-opened-by-hand.png` | 点开：整段 |
| `04-restored.png` | 刷新之后：恢复出来的会话仍是折着的一行首行 |

## 这次走查量到的、并在下一轮用上的三件事

留在这里，因为下一个人会踩同一脚：

1. **「抽屉关着」不能用元素个数判**。Radix 关着时保留一个空的、0 高度的内容元素，
   `document.querySelectorAll('[data-slot="reasoning-content"]').length` 开着关着都是 1。
   判据换成 `data-state === "closed"` + 高度 0——顺带量到一件真事：关着时**子节点根本没渲染**，
   所以折着的那段思考对页面是零成本。
2. **「位置在动」不等于「在滑」**。第一版的判据全是几何的（左边出去了、窗在往前挪），四条 `GREEN`，
   而屏幕上是一跳一跳的——因为**布局位移没有中间态**。这一版加进来的判据是「相邻两个采样**字完全相同**
   而**位置变了**」：99 个采样里 81 对字是相同的，其中 67 对位置仍在动。
3. **字是成批到的，不是一 token 一帧**。AG-UI 客户端与 React 把一串 SSE 帧并成一次渲染：四秒里行文只换了
   **18 次**（每批 ~60 字 ≈ 780px）。所以「每个 token 挪一点点」是错觉，真实的一步是 780px —— 这正是
   `TAIL_SETTLE_MAX` 与「按速度算时长」存在的原因，也是第一版看着像跳的原因。