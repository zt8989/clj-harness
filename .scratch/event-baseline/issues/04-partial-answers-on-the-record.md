# 04 — 半截答案上记录（run 停在中路，话要留住）

**What to build:** 被停/死掉的 run 的半截答案，在事件基线里要能从记录折回来。今天唯一落盘处
是 `text/snapshot`（CUSTOM 帧，75ms 一行，`text-lines` 的机器：首词立刻落、75ms 合并、
END 与 terminal 强制冲刷）。默认走 spec 拍板点 1 的 **A：边端继续合并**——`text-lines`
的整台机器原样保留，**行载荷从帧换成事实行**（CUSTOM 信封，名字如 `text/progress`，
载荷 `{:messageId .. :content ..}`，`text/snapshot` 的同构换名）；fold（票 02）把
「替换而非追加」的语义照吃（`apply-frames` 对 snapshot 的那一支换皮）。message 行照旧在
答案完成时写，完整答案的真值不变。

**为什么值得做：** 这是「缺事件」清单的第 2 件。内核只在答案完成时写 assistant 行
（`:message/added` 那一刻 call 已结束），半句话没有任何事件替它发声；不补这条，
「run 停在半路要留住它说过的话」（`a-run-that-stops-mid-thought-keeps-its-words-and-not-its-thinking`）
这条判据在新基线下直接失效。

**Blocked by:** 01

**Status:** needs-triage

- [ ] 行族落地：名字、载荷、节奏（`text-snapshot-ms` 75 不动）、首词立刻落、END/terminal
      强制冲刷、「与已落的那份相同就不写」——全部照旧，只换信封；
- [ ] fold 吃它：半截 + 完整两条记录上，事件 fold 的文本与今天 `fold-frames` 的逐字相等；
- [ ] 停止用例重跑：stop 中路的 run，重建的会话里那半句在，thinking 不在（旧判据新方言）；
- [ ] `wire-numbered?` 的计数不受影响（快照行不是消息 id 的消费者，说死在用例里）。

## Comments

2026-10-02 — 从对账拆出（缺事件第 2 件）。备选 B（内核新增累计快照事件，loop 里长计时逻辑）
已在 spec 拍板点 1 记下：fold 不在乎行是谁写的，哪天要挪回内核，本票的行族不动。
