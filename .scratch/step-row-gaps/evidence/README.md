# 证据：卡与行不许有两种间距

## 怎么复现

```
node scripts/dev.mjs --scripted .scratch/step-row-gaps/go.json --ui-port 5311
node .scratch/step-row-gaps/walkthrough.mjs http://localhost:5311/
```

脚本自己在 `fixture/` 上开一场项目会话（三份指令文件 ⇒ 开场三张卡），把这一轮的步骤全展开，
逐对量 `next.top - previous.bottom`。**判据是数字，不是看图**：下面两张图只是给人核对的。

## 文件

| 文件 | 是什么 |
|---|---|
| `before.txt` / `after.txt` | 脚本的**原样输出**（修前 / 修后）。数字、每一对、每一条判据都在里面。 |
| `gaps.png` | 修后，**刷新前**（先画的那条路：运行途中注入的卡画在它自己的消息里）。 |
| `gaps-rebuilt.png` | 修后，**刷新后**（重建那条路：一条 `CUSTOM` 折成一条自己的消息）。 |
| `gaps-before.png` / `gaps-rebuilt-before.png` | 同样两张，但是**修前**的状态。 |

四张图两两不同（`gaps.png` 与 `gaps-rebuilt.png` 曾经同哈希——两张都拍在最后，见 `spec.md`）。

## 数字

盒子间距（0 就是「只有行自己的 `py-1.5`」，读到 12px）：

| 相邻的一对 | 修前 | 修后 |
|---|---|---|
| 卡 → 卡（开场块，一块一条消息） | 24px | 0px |
| 思考 → 工具（同一消息） | 0px | 0px |
| 工具 → 工具（两条消息） | 8px | 0px |
| 工具 → 卡（同一消息） | 0px | 0px |
| 卡 → 思考（同一消息，运行途中注入的那张） | 0px | 0px |
| 刷新重建后的每一对 | 24 / 8 / 8 / 8 | 0px |

跨过**人的消息**的那一对（轮次边界）不在上表里：修前 198px、修后 174px，都是消息组自己的 24px
加上一段轮次的高度——它是唯一**应该**与步骤不同的距离，脚本单独断言它 `> 12`。
