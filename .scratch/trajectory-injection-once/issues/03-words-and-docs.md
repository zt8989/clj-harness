# 03 — 收口：词、文档、spec、整跑一遍

**What to build:** 这一票不新增行为。它是本特征离开「一直在改」状态前必须落下的几件事：把两条规矩写进它们该在的
地方，再拿一条真会话把轨迹页从头看到尾。

**Blocked by:** 01, 02

**Status:** ready-for-agent

## 要落的四件事

1. **`.scratch/trajectory/spec.md` 的记录清单改口。** 上下文条那一行今天只说来源（「`message` 的 `role: "user"`
   里不属于该 run `input.messages` 的那些」），本特征之后还要说清**画哪些**：同一段文本整场只画一次——开场块
   因此只在第一轮，技能正文只在用它的那一轮。
2. **`docs/architecture/client.md`（轨迹页那一段）跟上**：开场块只画一次；`/<skill>` 的注入落在用它的那一轮，
   并且上下文列里不会有自造的东西。
3. **`docs/architecture/edge.md`**：`message` 行的契约按本特征说清——**submitted 侧 = 第一次模型调用真正收到的
   那一份（含会话的注入）**，returned 侧 = 之后追加的。今天那种「注入落在切片之外、客户端消息被顶出来」的写法
   在文档里不许留。
4. **`.scratch/trajectory-injection-once/spec.md`**：决策、非目标、已知局限三节。

## 非目标（写进 spec）

- **不改 AG-UI 帧、不加 jsonl 行种类**：记录里已有的行够用。本特征是把 submitted 侧**记对**，不是加一行。
- **不动模型的提示词内容**：正文照旧每轮派生——客户端手里从来没有它，不派生就真没了。本特征改的是记录与轨迹。
- **不动 `system` 条那条既有规矩**（只在第一轮、变了再画一次）。
- 不做轨迹的实时流式、不做「每次调用等宽」的画法（`.scratch/trajectory/spec.md` 的既有非目标）。

## 已知局限（写进 spec）

- 「是不是同一段」的判据是**整段文本的字节**：两段逐字相同的注入物只画一次。带标签的那些（`<instructions path=…>`、
  `<skill name=…>`）基本不会被误伤，但本 run 的 context 没有标签——两份逐字相同的尾随上下文只画一次。
- 轨迹仍然只画**已落盘**的部分：正在跑的最后一轮里，注入要等 `:run/done`（`message` 尾落在终帧之后）才看得见。

## 验收

- [ ] 上面四处都改了口，且没有留下「上下文条每轮都画」的旧说法。
- [ ] `.scratch/trajectory-injection-once/spec.md` 存在，含决策 / 非目标 / 已知局限三节。
- [ ] 真机走查：一条 3 轮以上、含一次 `/<skill>` 的会话，打开轨迹页，截图进
      `.scratch/trajectory-injection-once/evidence/`：`t03-01-opening-once.png`（第一轮有开场块、第 2 轮没有）、
      `t03-02-injection-turn.png`（用它的那一轮有正文，且上下文列里没有客户端自己的话）。
- [ ] 离线全量 `clojure -M:test -m harness.test-runner` 全绿；`cd ui && npm test` 全绿，
      `npm run typecheck` / `npm run build` 0 error。
- [ ] `git diff --stat` 里本票只动文档与 `.scratch/`：`src/` 与 `ui/src/` 零改动。
