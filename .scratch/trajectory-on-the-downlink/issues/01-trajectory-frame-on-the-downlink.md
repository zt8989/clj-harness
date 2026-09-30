# 01 — 服务端：`trajectory` 成为下行 socket 的第五族

**What to build:** 一条下行连接声明 `trajectory: true` 之后，服务端把**模型每一轮看到了什么**
（今天那条 NDJSON 路由折出来的同一份东西）当成一族的帧推给它：订阅时一帧**开场快照**，
之后每次会话变化推一帧**增量**（自上次以来最终化的轮 + 当前那一轮），每帧都带上头
（`:incomplete` / `:behind`）。这一票**不动**那条旧路由（票 03 退休它），所以落完之后
新旧两条路并存、仓是绿的。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 形状

```json
{"type": "trajectory", "threadId": "…",
 "snapshot": true,          // 只在开场帧（订阅的第一次、以及每一次重新声明）
 "incomplete": false,       // 每一帧
 "behind": 0,               // 每一帧；没有欠账时不出现
 "turns": [ … ]}            // 增量：最终化的轮 + 当前那一轮
```

- 一条轮的形状**就是** `trajectory/trajectory-answer` 的 `:turns` 里那一条，一个字不变。
- 读不出折子时发一帧 `{:type "trajectory" :threadId .. :error "<定位器自己那句话>"}`——读者当场
  知道为什么没有东西可画（今天那条路由答 404，客户端读成「没有记录」那一态）。这场会话本进程不
  持有、或**别的活进程持有它**（`mux-add!` 那一支只答 `end`）时都不推轨迹帧。
- `:type` 与既有四族不相撞：AG-UI 的词汇是大写，窗口是 `window`/`append`/`page`/`tail`/`end`，
  事实是 `turn/*` `model/*` `step/*`，作业是 `task`。

## 在哪改

- `harness.edge.http/mux-sessions`：`sessions` 那条 JSON 里多读一个 `:trajectory` 布尔（缺省 false）。
- `harness.edge.http/mux-add!`：把旗子传给 `mux-watch!`。
- `harness.edge.http/mux-watch!`：现在那个 push 是**窗口那一半**；它多一半，按旗子做：
  - 持有 `{:cursor since :state nil :sent false}` 的那只 atom 旁边再记「已发出多少轮最终化的轮」；
  - 折子取 `(some-> (trajectory/view-value thread-id) trajectory/trajectory-answer)`
    （`view-value` 是**装**的那扇门：第一次调用做一次记录流式 walk，之后读持有态）；
  - 第一帧带 `:snapshot`（整份），之后带增量；**`turns` 没变、头也没变的那一拍不发帧**
    （今天那条路由每次门铃都重发当前那一轮，socket 上不该这么吵）。
- `harness.edge.mux` **不用动**：门铃本来就是「一条连接一条订阅」的那一个
  （`mux/subscribe!`），旗子由 `mux-watch!` 的闭包捕获；重新声明就是重新注册，游标因此从头来、
  开场快照因此重发。
- **重新声明是这个设计的修补**：`POST /api/events.mux/subscribe` 的那份 body 与握手 URL 是同一个形状，
  所以客户端把 `trajectory` 一起声明（票 02）；服务端这边只是多读一个键。

## 验收

- [ ] 声明了 `trajectory: true` 的连接收到开场帧：`:snapshot` 为 true、`:turns` 是整份折、
      头带 `:incomplete`
- [ ] 一轮 run 里新的一轮最终化：这一条连接收到一帧，`:turns` 里是新的那一轮（+ 当前那一轮），
      **不是**整份重发
- [ ] 同一个会话、没有声明轨迹的连接：一条 `trajectory` 帧都收不到
- [ ] `:incomplete` 跟着 run 落定翻面（一帧即可，不重开任何流）
- [ ] 没有日志的会话：一帧 `:error`，不是沉默
- [ ] 别的活进程持有的会话：一帧 `end`，没有 `trajectory` 帧
- [ ] 单测：`test/harness/edge/trajectory_test.clj` 里那条「后来的轮被推」的用例改成
      **假 channel + `mux/add!`** 的形状（`mux_test.clj` 的 `fake-channel` 就是现成的），
      新加一条「没声明就收不到」；`test/harness/edge/mux_test.clj` 的 `watching?` 三条留给票 03
