# 07 — 收口：ADR、文档、走查

**What to build:** 这条线改了「记录是什么」——要有它自己的 ADR（建议名：**记录是事件流的
投影**；它与 ADR 0002「会话活在服务端」、ADR 0009「reasoning 不进记录」、ADR 0011 的
关系各一段），并改口三处文档：`edge.md`（两条 sink 的写行规则）、`kernel.md`（事件基线
清单——十六种上、三种不上）、`CONTEXT.md` 的词表（**事件基线**、**事件 fold**、**双方言**、
`text/snapshot` → 新名）。真浏览器走查照规矩来：`node scripts/dev.mjs --scripted`，
开浏览器发一句话，确认卡片（注入、compaction）、停在中路的半句、parked 的卡都在。

**为什么值得做：** 记录的读者不止 `replay`：eval reader、脚本、还有未来的一切读侧工具。
ADR 是它们的合同；走查是「机器门全绿挡不住的那一格」（AGENTS.md 原话）。

**Blocked by:** 06

**Status:** needs-triage

- [ ] 新 ADR 落 `docs/adr/`（编号顺延），引用 `bbcd4ae4` 的体积账与票 05 的对账数字；
- [ ] `edge.md` / `kernel.md` / `CONTEXT.md` 改口；`harness.kernel.event` 的 ns 注释里
      「eight kinds carry no wire frame at all」那一段重写（停帧之后这句话不再是它今天的样子）；
- [ ] 三个名单（`wire-custom-names` / `card-custom-names` / `wire-only-frames`）的注释
      与新方言对齐；
- [ ] 走查截图进 `.scratch/event-baseline/evidence/`，控制台除既有 404 外无新声；
- [ ] README 不动（四节之外的都不进 README——这条线的解释都在 docs/）。

## Comments

2026-10-02 — 从对账拆出。收口票照 `step-events` 的 06 的形状。
