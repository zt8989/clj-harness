# 04 — 思考行不为别人的更新重渲染

**做什么**

`message-parts.tsx` 的 `ReasoningBlock`（`:1087`）订阅的是**整个** `s.thread.messages`：任何一次 store
更新（每个 token 一次，见票 02）都会让**页面上每一行思考**重新跑一遍 `thoughtAt` 与 `previewOf`。
一行一次不贵（两趟走只到本轮边界），一行乘上「页面上有多少行」乘上「一秒多少个 delta」才贵。

**做法（三个方向，做的时候定一个）**

1. 把这一行的正文收进一个 `memo` 组件，相等判断按「这一行关心的那段 parts 有没有变」——注意
   `messages` 每 token 换一次身份，所以默认的浅比较不管用。
2. 把订阅收窄到本消息的 parts；跨消息那一趟（`thoughtAt` 的两趟走）留一个窄口子——它在
   `.scratch/reasoning-order`（2026-09-22）之后只对**已经写下的旧记录**和别的厂商怪次序才需要。
   收窄就必须把那条路覆盖住，不能顺手删。
3. 都不改订阅，只把每行的计算做便宜（缓存上一帧的 parts 引用，没变就不重算）。

**与票 01 的关系**：改的是同一个文件（`message-parts.tsx`）。先做 01 再做 04 更省事（01 会把
`previewOf` 的代价变成常数，04 剩下的才是「谁被重渲染」），但两张票不互相阻塞；同时开工的话，
合的时候在 `ReasoningBlock` 那一段别打架。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] 一次 delta 里，**与本行无关**的行不再重算：用例断言 `thoughtAt` 的调用次数不随页面上别的行数增长
      （0 行 vs 20 行的差是 0）。
- [ ] 行为一条不改：每步一行、答案正文不结束一个想法、停下来回首行——`ui/test/suites/reasoning-row.ts`
      全绿，`.scratch/thinking-row-tail/walkthrough.mjs` 全绿。
- [ ] 若走方向 2：跨消息那条路仍被覆盖（用 `reasoning-row.ts` 里那条三消息字面量的用例，或把一场旧
      记录种进隔离家开一次）。
- [ ] `cd ui && npm test`、`npm run typecheck`、`npm run build` 绿。
