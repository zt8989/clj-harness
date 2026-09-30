# spec: 画出来的那场会话 —— 实测读数，与四刀

**一句话**：成本全在「画」不在「收」。一场 4,285 帧的流，主线程 JS 只花 19–132 ms/秒、样式与布局
≈0–5 ms/秒；而把一场已有会话**冷画一次**（50 条窗口里含一条 18k 字回答）是 **176 ms**，其中
**138 ms 是 JS**。所以优化要落在 markdown 解析 + React 重建上，不要去做 CSS/合成层；「收帧」本身
封顶几十 KB，不是问题。

2026-09-30 在真浏览器里实测（owner 的 Chrome 153，`kimi-webbridge` 借一个 tab），流由
`node scripts/dev.mjs --scripted <script.json>` 的脚本 provider 推。方法是差值读数，不是推算。

## 怎么量的（可复现）

1. 脚本（照 `scripts/example.json` 的形状）：`{"pace-ms": 8, "turns": [{"reasoning": …, "content": …}]}`
   ——5 字一块、8ms 一块，18,000 字回答 + 1,200 字思考 ⇒ **4,285 帧 / 45 秒（≈95 帧/秒）**。
   对照一场真实 run（`62f30024` 上那场）：23,461 帧 / ~450 秒（≈52 帧/秒）——这条流更密、只是短。
2. `node scripts/dev.mjs --scripted <script>` 起隔离服务（临时 home、OS 分端口），把打印的地址
   用真浏览器打开（**本仓库没有 Playwright**，页面的事就是浏览器的事）。
3. 仪器是 CDP（借来的 tab 上）：
   - `Performance.getMetrics` 的**差值**：`TaskDuration` / `ScriptDuration` /
     `RecalcStyleDuration` / `LayoutDuration` / `Nodes` / `JSEventListeners`；
   - `Runtime.getHeapUsage`：`usedSize` / `embedderHeapUsedSize` / `backingStorageSize`；
   - 页面里裹一层 `WebSocket` 数收了多少帧、都是什么类型。
4. 三档场景：① 边流边画；② 画好之后空闲；③ 把会话从 DOM 里拿掉再冷画一次（对话 ↔ 轨迹 切两次）。

## 读数

| 场景 | 主线程 task | 其中 JS | 样式 / 布局 | DOM 节点 | JS 堆 |
|---|---|---|---|---|---|
| 流式 45 秒 / 4,285 帧 | 80–350 ms/秒 | **19–132 ms/秒** | ≈0–5 ms/秒 | 432 → 468 | 32 → 58 MB 锯齿 |
| 冷画一场会话（50 条窗口，含一条 18k 字回答） | **176 ms 一次** | **138.5 ms** | 13.5 / 11.8 ms | 510 → 657 | +1.6 MB |
| 画好之后空闲 | 0.6 ms/秒 | 0 | 0 | 不变 | 不变 |

同一场流的内存那一半（上一轮另测，`mux-run-replay-dupes` 的同一套仪器）：**4,275 帧只换来
+~100 个 DOM 节点**（一条回答一个段落）；跑完强制 GC 后留下的是**那场对话本身**（几 MB，18 KB 的
回答加它的 message / markdown / React 对象）；隐藏（不画）时合批上限 `HOLD_LIMIT = 200` 帧
≈ 几十 KB。整机 Chrome RSS 在这期间反而 886 → 764 MB（噪声远大于一个 tab 的几 MB，没有分辨率）。

## 结论

- **收帧不是成本**：帧交付后就是垃圾，合批窗口有界，隐藏 tab 也只在满 200 帧时画一次。
- **样式与布局不是成本**：流式期间 0–5 ms/秒。别去动 CSS / compositing。
- **成本是「把一条消息的 markdown + React 树建出来，并且流式期间反复建」**：冷画那一刀 138 ms 的
  JS 是它的静态价格；流式那 19–132 ms/秒是它的动态价格。
- **常驻是「窗口里的那场对话」**：窗口由服务端给（`harness.kernel.session/page-size = 50`），
  每点一次「更早」再 prepend 50 条——所以常驻会随阅读历史长，而今天页面同时存着两份表示
  （窗口的 entries 与 runtime 的 messages）。

## 四件事，三张票（`issues/`）

- **A（票 01）正在流的那条消息，别每个 flush 重建整条子树**——动态价格，预期 19–132 ms/秒 → 十几。
- **B（票 01 的第一步）先证实 `defer` 在生效**——`elements/markdown-text.tsx` 已经 `defer` +
  `unstable_memoizeMarkdownComponents`；若流式期间其实每帧重解析，那 138 ms 的来源就是它。
  量法：content 从 18k 字改成 36k 字，看 `ScriptDuration` 是否线性翻倍。
- **C（票 02）冷画那 176 ms**：渲染级虚拟化，或者更省事——老消息默认折成一行（工具卡今天就是这么
  做的，`message-parts.tsx` 的头写着为什么：内容根本不进 DOM）。
- **D（票 03）常驻的窗口**：给「更早」的 prepend 一个界，或虚拟化之后只留游标、条目按需取。
  这张票的第一步是先把「几百条的真会话」量出来——今天手上只有一条长消息，看到的几 MB 是噪声。

## 不用碰的

- 流式期间的样式/布局（0–5 ms/秒）、空闲会话（0.6 ms/秒）、收帧（几十 KB 封顶）。
- 「隐藏 tab 丢帧、回前台重读」：数据说这条路的收益也在「画」上（合批已经把它压到每 200 帧一次），
  而回前台要重新 hydrate 整场对话（那才是几 MB 的那部分）。要做也该先做 A/C。
