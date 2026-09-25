# 04 — 工具行还在跑，就该是转圈：服务端说了 run 在跑，别让适配器的猜测盖过去

**做什么**

一场还在被服务端回答的会话，刷新回来之后，**正在跑的那个 `bash` / 作业行要画成转圈**（运行中），
不是感叹号（待审批）。

主人报的（2026-09-25）：刷新之后 `bash` 明明还在 `sleep`，「等一下」的那些行却变成感叹号。

**根因**

`fromAgUiMessages` 给它转出来的**每一条** assistant 消息都自己安一个 status，而「有工具调用、结果还
没回来」的那条它安的是 **`requires-action`**——那是 **parked** 的 run 需要的形状（审批卡就认它），
对一个**还在写这次调用**的 run 却是错的形状。

页面本来是想纠正它的（`toThreadMessages` 在窗口说 `running` 时把**最后一条**消息的 status 定成
`running`），但纠正**静默失效**了：`fromThreadMessageLike` 是

```ts
status: status ?? fallbackStatus
```

**消息自己的 status 优先**。而 `toThreadMessages` 之前只把状态当 **fallback** 递进去（`message` 原样
传），于是适配器那个 `requires-action` 赢——工具行读到的就是它。

**怎么修**

那个 status 必须**写在消息上**，而不只是当 fallback：`fromThreadMessageLike({ ...message, status }, ...)`
（只对 assistant 消息这么写——别的 role 带 status 会被它拒）。

规则搬进叶子模块 `ui/src/lib/thread-messages.ts`（`toThreadMessages` / `repositoryFrom` / `readsOf`），
`app.tsx` 只调它；这样套件能直接钉这条规则（`ui/test/suites/thread-messages.ts`）。

**不改的地方**：parked 那条读数**原样留给适配器**——`requires-action` 是审批卡的凭据，改掉就把停住
那一轮唯一的门关上了。settled、以及没有窗口的那几扇门（`rebuild`、刚铸出来的会话）也一样。

**Blocked by:** None（与 01–03 同属一族）。

**Status:** 已落地（2026-09-25）。

- [x] `thread-messages` 套件：适配器自己的读数是 `requires-action`（钉住，上游改了要先读这条）；
      窗口说 `running` 时那条消息的 `status` 是 `running`（**而且断言在消息上**，这正是当初漏掉的一半）；
      parked / settled / 没有窗口时**仍是** `requires-action`；`readsOf` 只认那四个词、其余读成 null。
- [x] 真浏览器走查（`walk-tool.json`：`bash sleep 30`）：刷新之后工具行是**运行中**，settle 之后是**完成**；
      同一轮里圆点始终只有一个、动作条只在该回来时回来。截图 `evidence/04-after-reload-tool-still-running.png`。
- [x] `npm test`（146 全绿）、`npm run typecheck`、`npm run build`。
- [x] `docs/architecture/client.md` 记下这条：**重建回来的消息，状态是服务端说的，不是适配器猜的**。
