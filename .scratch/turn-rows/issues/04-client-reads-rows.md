# 04 —— 客户端读行：`lib/turns.ts` 不再分组、不再数

Status: open
Blocked by: 03

## 要做的

- `ui/src/lib/turns.ts`：`turnCounts` 删掉（步数从行来）；`turnBounds` 不再是「相邻 assistant
  消息」的推导，而是「同一条 `turn/start` 里的条目」——归属按窗口带来的 `turns` 的区间
  与条目的 `seq` / 消息的 `id` 对齐。
- 归属要有**一处拼写**（一个 store，像 `lib/turn-numbers.ts`）：`app.tsx` 在 `commit` 时写入，
  `turn-steps.tsx` 与 `thread.aui.tsx` 从这里读。
- `turn-numbers.ts` 与 `turn/end` fact 的关系说清：fact 现在只是「这一行刚写下来」的通知，
  数字与窗口那份是**同一份**（服务端从行折出来的），不再有「两个读数谁对」的问题。

## 判据

- 一个开了很久的会话：每条摘要行上的步数与记录里的 `turn/end` 相等。
- 一份没有 `turn/*` 行的记录：客户端不画摘要行（没有轮），也不抛。
- `turnStepsFrom` 的「两个读数」选择逻辑随 `turnCounts` 一起消失。
