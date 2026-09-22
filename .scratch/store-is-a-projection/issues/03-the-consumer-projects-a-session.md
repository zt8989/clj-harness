# 03 — 消费投影：空库 + 一份记录 ⇒ 侧边栏列出会话

**What to build:** 一个投影消费者：按 offset 读记录的新增行，把可投影的状态**幂等** upsert 进库；offset 记在库里一张新表里（名字别踩 `db_test` 的 forbidden 正则——`events?` / `logs?` / `jsonl` / `messages?` 都是禁词）。判据是一个端到端场景：**删掉库、只留 `projects/` 那棵树，启动后侧边栏能列出会话、带上标题与上次发送时间，任务清单也在。** 重复消费同一行不产生重复、不漂移。

**Blocked by:** 02 — 得先知道每一列从哪一行推。

**Status:** ready-for-agent

- [ ] 新表（offset / 游标）进库，并加进 `db_test` 的声明清单；表名与列名都不踩 forbidden 正则
- [ ] 空库 + 一份记录 ⇒ `GET /api/projects` 答出该行，含标题与上次发送时间；`todos` 也在
- [ ] 同一行消费两次结果相同（幂等），有专门用例
- [ ] offset 落后时消费者追赶到最新；消费是纯追加、不回退