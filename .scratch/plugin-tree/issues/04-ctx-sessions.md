# 04 — `ctx.sessions`：会话服务与它的五道缝

**What to build:** `harness.kernel.session` 今天靠 `install!` 收五样东西（`:build` / `:model-messages` /
`:read` / `:fold` / `:claim`）。这一票把会话升格成核心服务 `ctx.sessions`，五样**各立一份接缝声明**
——定义在脊柱，实现与消费在别处，三个角色各有名字。行为一字不改：**记录逐字节相同**是这张票的判据。

**Blocked by:** 03 — `ctx.scope`；`.scratch/cordis-clj/` 02–04

**Status:** ready-for-agent

## 验收

- [ ] `ctx.sessions` 是一个服务；今天那五道缝各有**声明**（谁定义、谁实现、谁消费），不再是匿名函数槽
- [ ] 同一场会话经新旧两条路读出来**逐条相同**（拿一份现成的 jsonl 逐行对照）
- [ ] **铁律 3**（run 进行中内核不读自己的记录）在新形状里成立，且有一条断言钉着
- [ ] 「一场会话只出生一次走查」成立，同样有断言
- [ ] `cap.claims` 作为 `:claim` 缝的实现接进来，**脊柱不再 require 它**（ADR 0005 那条边照旧是 0 条）
- [ ] 窗口切分（尾页 50、批边界、补页锚定）的语义与数字不变
- [ ] 守卫表里这一行按新角色记
