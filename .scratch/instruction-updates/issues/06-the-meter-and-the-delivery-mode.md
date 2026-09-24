# 06 — 压力表与送达方式：锚点该不该因为指令签名变了就作废

**What to build:** `harness.edge.pressure/records->pressure` 的锚点判据 `anchored?` 今天把
「锚那次 run 的 system 文本」与「现在这条 system 文本」比一遍，不等就退回 `:baseline "estimated"`。
两件事一起改：

1. **比签名，不比文本**：用票 01 那两个 name hash（hooks 名字集合 + tools 名字集合）加 route，不去
   读/算 system 的内容（`prompt.md` 不参与）。
2. **签名叫不叫「前缀断」要看送达方式**：
   - **tools 名字集合**变了 ⇒ 请求最前那张 `:tools` 数组变了 ⇒ 前缀断 ⇒ **两档都作废**（本特征决定 1
     明说工具表的冷前缀不省）；
   - **hooks 名字集合**变了 ⇒ system 文本变了：`:replace` 换 `message[0]` ⇒ 前缀断 ⇒ 作废；
     `:in-place` 把变化作为尾部 `developer` 消息送出 ⇒ 共享前缀没断 ⇒ **不作废**，新指令全文算进 delta。

**一句话：锚点作废看「前缀断没断」，不看「system 文本变没变」；而「变没变」由 hooks/tools 的
name hash 回答。**

**Blocked by:** 02

**Status:** ready-for-agent

## 要落地的判断

1. **`anchored?` 的输入**：route（`:model` / `:base-url`）+ `:tools-names-hash` + `:hooks-names-hash`
   （`model/start` 上的，见 `.scratch/model-surface-and-meter` 票 04）。system 文本那一格**删掉**：
   只改一个工具描述、只改 `prompt.md`，都不该作废锚点。
2. **hooks 那一半按档走**：`:replace` 下 hooks 名字变了要作废；`:in-place` 下不作废。能力位读不到按
   缺省 `:replace`。
3. **跨档切换要有答案**：一个会话从 `:replace` 模型切到 `:in-place`（或反过来），锚点该不该留？
   决定并写下理由。倾向：拿**锚那次 run 实际用的档**判——锚是关于那一次前缀的断言。若这条定了，`model/start`
   是否要记下这一次的档就跟着定。
4. **与票 01 那份记忆分家**：票 01 记的是「我上一轮说过什么」（判这次要不要重建/送更新），压力表判的是
   「锚那次前缀还在不在」。两者都不要合并成一份——一个进程内存，一个纯函数的输入。
5. **离线也要答得出**：`records->pressure` 仍是纯函数。记录的 `model/start` 有了两个 name hash 之后，
   离线的锚点判据就不必再保守地按 `:replace` 把 `:in-place` 的锚白作废。

## 验收

- [ ] 只改一个工具的描述 → 两份签名都不变 → 锚点仍采用（`:replace` / `:in-place` 都一样）
- [ ] 加/删一个工具 → `:in-place` 与 `:replace` **都**作废锚点（`:tools` 数组在最前）
- [ ] 加/删一条 hook + `:in-place` → 锚点**仍采用**，`:baseline "usage"`，`:pressureTokens` 把新的指令
      全文算进去（用例：与「没变」的差正好是那段文本的估算）
- [ ] 加/删一条 hook + `:replace` → 锚点作废，`:baseline "estimated"`
- [ ] 跨档切换：按判断 3 定的判据，有用例钉住
- [ ] 离线纯函数 `records->pressure` 对同一记录给同一答案
- [ ] `clojure -M:test -m harness.test-runner` 全绿

## Comments

2026-09-24 — 补的一条缝：本特征原本只核了「记录 / `run-segments` / `context`」三处读侧（票 03），
压力表（`harness.edge.pressure`）不在里面，但它的 `anchored?` 恰好会踩这一格。
老板口径（同日）：判据用 **hooks/tools 的 name hash**，不是数量、不是内容；这一条票 01 与压力表**两处都用**。
配套：`.scratch/model-surface-and-meter` 票 03（压力表从「每轮读整份记录」改成「读缓存模型面 + O(1) 条带」）
落地时，`anchored?` 就按本票这组输入写。
