# 01 — ADR：事实的真相在记录，库是它的投影

**What to build:** 把「谁是真相」这个反转写下来，并让文档只在一个地方说它。产出一份 `docs/adr/0004-*`（日期 2026-09-22，状态「已采纳」），写明：记录的字节是事实的真相；内存是运行时的**工作副本**；库装的是**能从记录推导出来的状态**、因此可重建。逐条点名它修订了 ADR 0002 的哪几条（1、10 的措辞）与哪几条**不动**（5 的放掉钉子、6 的落盘节奏与降级）；再逐条点名从原 spec 砍掉的「重活」（fsync、攒批调参、L0–L3 背压、`stream_snapshot`、JSONL header/version、应急文件 dump、把对话搬进库）。然后改写 `CONTEXT.md` 的「状态住在哪」与 `docs/architecture/home-and-storage.md`「库与文件的边界」：从「库不镜像日志」改成「库不装**对话内容**；库的状态是记录推导出来的**投影**」。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] `docs/adr/0004-*.md` 落盘：日期、状态「已采纳」、修订/不动/不做三张清单齐全，并说明为什么选「投影」而不是原 spec 的重活
- [ ] 被修订的决策在 ADR 里被**引用**（`docs/adr/0002-...`），不是悄悄绕过
- [ ] `CONTEXT.md`「状态住在哪」与 `home-and-storage.md` 的边界段各只剩一处说法，且与 ADR 一致
- [ ] 「不做」清单与 `spec.md` 的那一节逐条对齐，没有只在一处写的重活