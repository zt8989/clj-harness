# 03 — 什么时候重问它一次（不轮询）+ 真走一遍

Status: ready-for-agent
Blocked by: 02

## 做什么

票 02 画出来的是**一次**答案。这一票定的是**下一次问是什么时候**，并且真的开一次服务器走一遍。

**四个时机，各问一次**：

1. **挂载**——第一次问。
2. **`threadId` 变了**——换会话就是换一份列表。上一条会话的答案**不许落在**这一条上
   （`lib/composer-numbers.tsx` 那个 `live` 旗子就是干这个的，照抄那一段的形状）。
3. **`model/start`**——这是「刚写完」的第一个可靠边界。`todo_write` 是在一次模型调用**结束之后**才执行的
   （`model/end` 写在那次调用上，工具在它之后才跑），所以问在 `model/end` 上会**晚一轮**；
   而每个工具调用之后模型都要再被叫一次，所以**下一次 `model/start` 一定在写完之后**。
4. **`turn/end`**——一次 run 结束时的最后一次。一条 run 恰好以 `todo_write` 收尾、
   或者中途被停掉的边角，都由它兜住。

走的是**既有的**那条机制，`ui/src/lib/mux.ts` 的 `subscribeFacts(threadId, onFact)`——
就是 composer 下面那条统计条接 `model/end` 用的同一个（`lib/composer-numbers.tsx`）。
**不加协议、不加帧、不改服务端。**

**不轮询，写进注释里**：这条横条要回答的是「还剩什么没做」，而这件事**只在工具跑完的那一刻变**。
一秒一问换来的是一秒钟里 999 次「和刚才一样」的请求，以及一个「它是不是卡住了」的假象——
与 `composer-numbers.tsx` 里那句「THERE IS NO POLLING」同一条纪律。一次 run 从开始到结束问的次数
是**它可以数的**：每个模型调用一次 + 收尾一次。

**一处要写下来的理由**：`turn/end` 之后再补一拍的**不做**。统计条那边有一个 400ms 的补问，
因为日志的写入者比它最后一帧慢半拍；而任务列表是**工具执行时同步写进库的**（`todos/write!` 一个事务），
排在 `turn/end` 后面才写不下东西——所以这里没有那一拍，写一句注释说明为什么没有。

## 走查（这一票的另一半）

`node scripts/dev.mjs --scripted` 那套：照 `scripts/example.json` 的形状写一份会在第一轮就调
`todo_write` 的脚本（第二条 turn 不再调工具，让 run 正常结束），然后**自己开浏览器**走：

1. 发一句话 ⇒ provider 回放那份脚本 ⇒ **看横条是不是当场长出来**（不用刷新）、计数对不对。
2. 点开 ⇒ 明细与模型写的那份逐条对得上；收回。
3. 刷新页面 ⇒ 横条**还在**（读的是服务端那行，不是对话），状态是**折着**的。
4. 再发一句话，让模型把其中一条改成 `completed`（改脚本或换个脚本）⇒ 横条上的数字**动一次**，
   而且只动一次（抓一次网络面板，确认没有一秒一问的请求）。
5. 让模型写 `todo_write []` ⇒ 横条**消失**。

证据（截图 + 那一串请求）落到 `.scratch/composer-todo-strip/evidence/`，命令与脚本一并记下——
照 `.scratch/composer-status/evidence/` 与 `.scratch/compaction-shape/` 里已有的做法。

## 验收

- [ ] 只在这四个时机各发一次 `GET /api/threads/<stem>/todos`：挂载、`threadId` 变、`model/start`、`turn/end`。
- [ ] 一次 run 从头到尾（含 N 轮模型调用）的请求数是 **N + 1**；页面上没有任何定时器在问它。
- [ ] 换会话时，前一条会话的答案**不会**画到这一条上（哪怕它回来得更晚）。
- [ ] 刷新页面 ⇒ 横条立刻在有列表的会话里出现（答案是服务端的），且是折着的。
- [ ] `node scripts/dev.mjs --scripted <本特征那份脚本>` 上，上面「走查」五步逐条亲眼过一遍，
      证据文件落进 `.scratch/composer-todo-strip/evidence/`。
- [ ] `cd ui && npm test`、`npm run typecheck`、`npm run build` 全绿；
      `clojure -M:test -m harness.test-runner` 全绿。
