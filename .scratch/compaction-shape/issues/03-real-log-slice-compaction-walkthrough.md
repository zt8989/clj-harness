# 03 — 拿真实日志切一段，跑一次真的压缩（走查）

**What to build:** 一次**用真记录、真厂商**的压缩，证明 01 与 02 都真的修好了——不是单测绿了就算。

取 **2026-09-25 那场真实会话** `f59c09dd-bedd-4fe6-b852-e643d25fbc5a`（`~/.clj-harness/projects/
C__Users_zhouteng_Documents_workspace_lisp-harness/f59c09dd-….jsonl`，459,765 行 / 100 MB）：
**只读**复制一段到隔离家的 projects 目录（**一个字都不许写进 `~/.clj-harness`**，
隔离按 `docs/rules/testing.md` 走 `harness.test-runner/isolate!`），然后走 `recover-overflow!`
那条**真实路径**——先 prune、再 `compaction/overflow-plan`——用**真的 kongming** 打一次请求。

这一段要满足两件事：够小（几万行量级，跑得快），且**含 `role "reasoning"` 的消息**——
否则验不出 01 那条 422。

**Blocked by:** 01、02

**Status:** ready-for-agent

- [ ] **先复现**：用未折过的数组（`replay/model-nodes` 的计划）打一次，**仍应拿到那条 422**，
      把厂商的原文原样记进 evidence——「修好了」要有「之前确实坏」做对照
- [ ] **再验**：走修好后的路径打同一次，**拿到摘要**，`context/compacted` 落到记录里，
      `compaction/end` 不再带 `error`
- [ ] 附上送出去那个数组的 `role` 直方图（应为 `system`/`user`/`assistant`/`tool`，无 `reasoning`），
      以及被折进 `reasoning_content` 的字符总量
- [ ] 压缩前后各算一次 `pressure/records->pressure` 与 `state->pressure`（同一个数组、两种口径），
      附上数字：压缩后 model view 变短、压力下降，且**两个口径的差回到形状级**（见 02）
- [ ] 证据落到 `.scratch/compaction-shape/evidence/`（前后请求、厂商回文、前后压力、摘要长度），
      spec/README 只留指针
- [ ] `~/.clj-harness` 的 `[bytes mtime]` 前后一致（或只出现 `ISOLATION NOTE` 那一行）

## Comments

2026-09-26 — 主人点名：这次走查要打**真 kongming** 的请求，因为唯一能证明「422 真没了」的
就是厂商自己收下了这一发。

2026-09-26 — **已走查**（分支 `compaction-shape`，commit `77f65f1`）：真实记录前 20,000 行，
plant 进隔离家，真 kongming 打了两发。RAW 那一发被厂商用**原句**拒收（连 `at line 1 column
18135` 都和事故日志对上）；折过之后摘要 6,988 字符回来，`context/compacted` 落盘
（`{:tokens 29887 :range {:start 4 :end 7617}}`），`compaction/end` 无 error，model view 估价
55,849 → 27,727，送出去的 role 直方图 `{user 4, assistant 12, tool 21}`。
证据：`evidence/03-real-slice-compaction.txt`、`evidence/03-slice-compaction.clj`（可重跑）。
