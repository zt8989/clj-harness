# 走查证据

## 怎么跑

```bash
node scripts/dev.mjs --scripted .scratch/trajectory-live/walkthrough.json
```

它把 `ui/dist` 建好、由一个地址发出来（**它不驱动浏览器**）。脚本
（`walkthrough.json`）里模型要一次 `bash {"command": "sleep 25; echo done"}`——
**刻意让它停 25 秒**，所以有窗口去看「一轮还在跑」的样子。打开它报的那个地址、发一句话、
切到「轨迹」。

## 看到了什么

**`t01-call-in-flight.png`（跑着的时候）**

- 页头是 **「最后一次运行还没结束」**。
- 第 1 轮 **3 个条目 · 1 次模型调用**：系统、用户「跑一下」、**工具 bash**。
- 那一行只有 `工具 bash`：**没有参数、没有 `→ 结果`**（参数和结果都还没落盘）。
- 点开它，右侧面板是 `已执行: 尚未`、`执行结果: pass`、`id: c1`——**没有「参数」块，也没有「结果」块**。

**`t02-call-answered.png`（跑完之后，同一条）**

- 「最后一次运行还没结束」消失；第 1 轮变成 **5 个条目 · 2 次模型调用**。
- 同一行长成 `工具 bash {"command":"sleep 25; echo done"} → done`——名字、参数、结果都来了。
- 面板里点开的条目变成了**助手**那条：一轮结束时返回侧被**插在**工具条目之前，面板记的是
  `(轮, 下标)`，于是下标落到了前一条上。**这是既有的解析规则，不是本票引入的**
  （见 `spec.md` 的「已知的相关毛病」）。再点工具那一行就是 `已执行: 是`、`id: c1`、`参数`、`结果: done`
  ——这一版（12 秒那次的走查）实测过。

## 探针（不驱动浏览器的那一半）

`dev/scratch_trajectory_live.clj` 是同一件事的机器版本：真起一个会话，用 `bash sleep` 把 run 卡在工具缝上，
一边采样 `trajectory/view-value`，一边读记录里的 `tools/*` 行。修复前后各跑一次的输出见 `spec.md`。

```bash
clojure -M:dev -m scratch-trajectory-live
```
