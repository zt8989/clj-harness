# 05 — 记录停帧，format 升 3

**What to build:** 帧与事件在记录里并存（票 01）且事件 fold 对账通过（票 02）之后，把
**帧一族**从记录里停掉：`runner` / subagent 两条 sink 里，AG-UI 帧继续广播（wire 不动）、
继续进 `:frames`（`settle!` 不动），但不再 `log!` 成行。`text-lines` 的新行族（票 04）与
pre-injection 行（票 03）照写。记录 format 升 **3**：首行 header（`record/header`）标新方言，
`:events true` 之类的自述在这里一并定。停掉的帧清单要**点名**（TEXT_MESSAGE_*、
TOOL_CALL_*、RUN_*、REASONING_* 本就不存、CUSTOM 的卡片/快照/timeout），而不是按前缀——
这是 `reasoning-frames` / `wire-only-frames` 两条注释里的既有纪律。

**为什么值得做：** 这是本线的兑现点。停帧的直接收益就是当初动机那笔账（2026-09-24，
thread `bbcd4ae4-…`：50.2 MB 的表被 `model/start` 签名收敛救掉之后，帧信封仍是记录的
大头）；事件基线让「对话是事件流的一个投影」从读侧习惯变成记录格式本身。

**Blocked by:** 02, 03, 04

**Status:** needs-triage

- [ ] 两条 sink 的停帧名单点名成集合（带「为什么每一族不在了」的一行话），新帧类型漏网要红；
- [ ] format 3 的 header 落地；format 2 的旧记录由旧 fold 照读（本票不动读侧，只留门）；
- [ ] `wire-custom-names` / `card-custom-names` / `wire-only-frames` 三个名单按新方言重述
      （哪些名字还会出现在记录里，各自一句话）；
- [ ] 回归：一条 scripted run 的 format-3 记录里，`replay/kind` 的分布只有 `message` 与
      事实行；`ensure-complete!` / `closing-frames` 的语义在新方言下复验（repair 现在写
      `:run/error` 事件行 + tool message 行，不写帧）；
- [ ] 体积对账：同一 scripted 场景 format 2 vs 3 的字节数，落进 evidence（这是这条线的成绩单）。

## Comments

2026-10-02 — 从对账拆出。双写期不停、对账不过不开这张票。
