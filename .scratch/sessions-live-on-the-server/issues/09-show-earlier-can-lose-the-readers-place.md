Status: needs-triage

# 「看更早的」偶尔把读者甩掉：屏幕上的副本换成另一段，正在读的那条不见了

2026-09-21 把 `parallel-call-parent` 合进 `main` 时跑走查撞见，**与那次合并无关**：合并前的
`3c8d43a` 与合并后的 `792ba60` 都偶发，失败签名逐字相同（见下面的实测表）。

## 症状与发生率

`06-walkthrough.mjs`（窗口那一份走查）在「27 轮把窗口撑出更多在前面 → 点一次『看更早的』」这一步
偶发变红：

    消息数：{"before":24,"after":32}          ← 正常是 27（一页 3~4 条）
    正在读的那条：{"before":98,"after":null}  ← 这条的文字从 DOM 里消失
    视口 scrollTop：{"before":0,"after":5948} ← 正常是 ~180

也就是：点完之后屏幕上的副本**换成了另一段**（多 8 条；合并前那次红反而是少 2 条 `after:22`），
正在读的那条不在里面，读者被甩到别处。

实测（同一台机器、同一份脚本、连续跑）：

| 树 | 跑了几遍 | 红几遍 | 红的那遍长什么样 |
| --- | --- | --- | --- |
| 合并前 `3c8d43a`（没有 parallel-call-parent） | 5 | 1 | `after:22`，scrollTop 5817 |
| 合并后 `792ba60` | 3 | 1 | `after:32`，scrollTop 5948 |

## 怎么复现

不是每次都能踩到，所以按「跑 N 遍看红几遍」算，别拿一次的结果下结论：

```bash
for i in 1 2 3 4 5; do
  port=$((5310 + i))
  log=/tmp/w06-$i.log
  node scripts/dev.mjs --scripted .scratch/sessions-live-on-the-server/evidence/06-go.json \
       --ui-port "$port" >"$log" 2>&1 &
  dev=$!
  # 等它把临时配置根打出来，再跑走查（那一行里有 <HOME>）
  for _ in $(seq 1 90); do grep -q "temp config root" "$log" && break; sleep 1; done
  home=$(grep -o "temp config root [^ ]*" "$log" | head -1 | awk '{print $4}')
  node .scratch/sessions-live-on-the-server/evidence/06-walkthrough.mjs "http://localhost:$port/" "$home" \
    | grep -E "messages on screen|the anchor message|scrollTop|^RED|^GREEN"
  kill "$dev" 2>/dev/null; wait "$dev" 2>/dev/null
  pkill -f "vite --port $port" 2>/dev/null
done
```

## 为什么不是那次合并的

- 那次合并只改 `ag_ui/outbound` 的**父消息**（一轮只开一条 assistant 消息）。`06-go.json` 是纯文本
  轮、没有工具调用，这条路径上出的帧**逐字不变**，wire 上没有能造成这个现象的差别。
- 合并前 5 遍里已经红 1 遍，签名相同。
- `ui` 侧有一条单元用例正对着这条规矩（`window` 套件「the-reader-stays-where-they-were-when-a-page-is
  -added-above」），它**每次都过**：这个洞只在真浏览器、真滚动容器上出现（有锚点、有真实布局与时序），
  所以它是一条走查级偶发，不是单元级。单元那条别删，它管的是另一层。

## 怀疑的方向（没验，留给接手的人）

点「看更早的」会发一次 `GET /api/threads/<id>/page`（红的那遍走查里一共 7 次 page、6 次 feed），而
`baseSeq` 是「这段副本从哪里开始」的唯一凭据。屏幕上出现的两种错法（多一页、或反而换掉一段）都像是
**一份过期或重复的 page 答复被应用到了已经往前走的副本上**：正常那几次 `after` 是 27，错的是 32
（两页）与 22（换掉一段）。接手先看 `ui/src/app.tsx` 收 page 答复那条路与 `lib/window-*.ts` 里
`baseSeq` 的记账谁先谁后。

## 判据

上面那段循环连跑 5 遍**全绿**（现在是 3~5 遍里红 1 遍），并且能说清原来为什么会红。
