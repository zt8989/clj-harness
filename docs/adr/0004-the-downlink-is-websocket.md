# 0004 —— 下行是一条 WebSocket，订阅是 HTTP 事实

- **日期**：2026-09-23
- **状态**：**已采纳**
- **修正**：`docs/adr/0003-the-feed-is-a-window.md` 的**决策 7**（「服务端不记谁在订阅」）。0003 的
  决策 1–6、8、9 与窗口/游标那套代数**不变**——本 ADR 换的是**推的载体**，不是窗口的形状。
- **参照**：DSH 的架构笔记（浏览器 Web GUI 从两条 SSE 换成每条下行流各一条 WebSocket，
  `events.mux` / `events.host`；上行仍走 HTTP，socket 只下行），以及 Discussion #1316 的根因分析
  （`events.mux` 的 handler 当时把所有会话的全量广播给每个连接，未按订阅集合过滤）。

## 背景

**SSE 长期占用连接槽位。** `GET /api/threads/<stem>/feed` 一条流服务一场会话，而且它不会自己结束：
host 永不被回收，隐藏的 host 也照旧攥着自己的 feed，服务端那边「一条连着的窗口就是一个 pin」
（`harness.edge.sessions/evictable?`）。于是一页开过几场会话就占几个连接，浏览器的同源连接池
（HTTP/1.1 下约六条）被吃掉，下一次请求——一次 run、一次读、一个页面资源——排在后面。

**订阅需要一个能承载它的东西，而 SSE 的响应不能再说话。** 0003 的 feed 把游标放在**每一次读**的
`since=N` 上；连接一多，这个「每次重连重新声明」的性质反而是对的（决策 7：服务端不记谁在订阅），
只是载体从「每条流各声明一次」变成「一页声明一次」。

## 决策

1. **下行是一条 WebSocket，按下行**类别**分。**浏览器为每个类别各开一条 socket：
   - **`events.mux`** —— 每场会话的帧：run 的 AG-UI 帧、子 agent 的帧、窗口条目。帧按 `threadId` 标。
   - **`events.host`** —— 家级事实：会话出现/改名/发送时间/跑起来/停下、项目增删、run 注册表变化。
2. **socket 只下行，上行仍是 HTTP。** 一次 run 仍是一次 `POST /api/*`；socket 不接受客户端应用消息。
3. **订阅是 HTTP 事实。** 两个半边：握手 URL（`GET /api/events.mux?subscriber=<token>&sessions=<json>`）
   声明这条连接持有哪几场、各自从哪个游标开始；此后集合变化由
   `POST /api/events.mux/subscribe` 更新。token 是**这条连接**的名字，随 socket 生、随 socket 死。
4. **服务端按连接级订阅集合过滤。** 只有被这条连接订阅的会话才有 watch；别的会话的 ring 到不了它。
   进程不持有连接之外的任何订阅状态——这是 0003 决策 7 想保的那件事，本 ADR 把「服务端不记谁在订阅」
   的**准确说法**定为「**服务端不记连接之外的订阅**」。
5. **窗口与游标的语义一个字不改。** 帧的形状、`seq`/`generation`/`since` 的规则、拉取（尾页、补页、
   `sofar`/`rebuild`）都照旧走 HTTP。换的只有推。
6. **不许全量广播。** 一个把每场会话的帧推给每个连接的 mux，正是 Discussion #1316 记下的卡顿源；
   过滤是**验收项**，不是优化。

## 代价

- **每页一条 socket 的接线**：一个页面级单例（`ui/src/lib/mux.ts`），由每个跟随窗口的 host 注册；
  重连时在握手 URL 重新声明整份集合。
- **`events.mux` 之后是 `events.host`**：两条类别、两条 socket，各有自己的连接纪律。
- **契约期（已落，票 05）**：三条 SSE 路由（`POST /api/agent` 的响应体、`GET …/follow`、
  `GET …/feed`）在没有调用者之后删掉了——`POST /api/agent` 现在只回 ack。`runner` 也不再拼
  SSE 字节，只记录、广播、在终帧处收口；异常结束（崩溃、事件通道无终帧）由它广播一条合成的
  `RUN_ERROR`，替原来那条 SSE close 收尾。

## 边界（不做的事）

- **不改记录的格式**，也不改 `WindowFrame` 的形状。
- **不改上行**：`POST /api/*` 仍是 HTTP。
- **不把 run 的响应改成 socket 的上行**：socket 只下行。

## 落地

`.scratch/events-mux-and-host/spec.md` + 五张票（01 → 05）。票 01 落的是 `events.mux` 承载**窗口帧**
（`harness.edge.mux` 这张连接级注册表、`GET /api/events.mux`、
`POST /api/events.mux/subscribe`，页面侧 `lib/mux.ts`），SSE `feed` 并行留着作为回滚；run 帧、子 agent 帧、
`events.host` 与契约收口分别是票 02–05。
