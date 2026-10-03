# 03 — 边端 pre-injection 有了自己的行（`-pre<i>` 的事实来源）

**What to build:** 边端 pre-LLM 步折进 history 的注入（skill body、job 结束等，今天以
`<run-id>-pre<i>` 的 CUSTOM 帧搭 `:run/start` 的车上记录）在**事件基线**里要有自己的行。
默认做法（spec 拍板点 1 的姊妹款）：边端在 pre-injection 发生处写一行事实
（CUSTOM 信封，名字如 `context/pre-injected`，载荷 `{:id "<run>-pre<i>" :role .. :text ..}`），
id 沿用 `ag/pre-injection-suffix` 的同一拼写，一个字不改——记录里卡片的事实来源从
「帧的存在」换成这一行。票 02 的 fold 吃它产出与今天相同的卡消息。

**为什么值得做：** 这是「缺事件」清单的第 1 件。内核的 `with-skills` 对边端已折入的 body
幂等、什么都不加，所以 `:context/injected` 永远不会替它发声；今天它只以帧的形式存在，
帧一停，卡片在重建的会话里就没了，而且「这个 run 开场时模型读了什么」在记录里断了线索。

**Blocked by:** 01

**Status:** needs-triage

- [ ] 边端在派生 pre-injection 的同一处写行（`injected` subvec 的每个元素一行），
      id 与今天的帧 id 逐字相同（`counter-spelling` / `wire-numbered?` 的对账不变）；
- [ ] 出生那一场（opening blocks + context entry）照旧走 message 行，**不**重复成行
      （`conversation-snapshot` 已死在票 05 的裁撤清单里，这里不复活它）；
- [ ] fold（票 02）吃这行产出的卡与今天 `fold-frames` 产出的卡逐字相等
      （id、`data` part 的 name 与 value）；
- [ ] 对账用例：一条带 skill 的 run，双写期两个 fold 的卡片一致；带 `-pre` 与 `-ctx`
      同场的记录各一条（两套计数不串）。

## Comments

2026-10-02 — 从对账拆出（缺事件第 1 件）。id 拼写是唯一必须逐字保真的东西：
它是记录折叠卡片 under 的名字，也是「wire 与 fold 计数一致」的对账键。
