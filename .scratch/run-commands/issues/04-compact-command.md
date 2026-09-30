# 04 — `compact` 命令，`POST …/compact` 退役

**What to build:** 手工压缩从「run 之外的一次调用」变成「run 里的一条命令」，与自动触发同一个地方。

- **命令**：`{:type "compact"}`，`no-run` 是 `:start-run`（票 01 的表）——没有 run 就起一场只有命令的 run。
- **执行处**：**模型调用前**那个边界，与自动触发 `compact-if-pressured!` **同一处、同一个锁、同一批行**
  （`.scratch/compaction` 的 `perform!`）。这一票是把今天两条路（自动、手工）**收成一条**。
- **`POST /api/threads/<stem>/compact` 退役**：`/compact` 变成输入框指令（客户端包成 `commands`）。
  今天 `compact-post` 的审计行与响应形状（`:compactionId`）不再有——命令的结果是**行与帧**，
  不是一条 HTTP 应答。
- **一个顺带的好处**：手工压缩如今**跑在 run 里**，所以那张压缩卡（`compacted-frame`，
  `.scratch/compaction-frames`）**发得出来**了——今天那句「手工 `/compact` 不发这张卡，
  那条路由没有 run」随这一票作废，`docs` 与注释里那处要一起改。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `/compact` 在**没有 run** 时起一场只有命令的 run，压缩在**模型调用前**执行，
      记录里出现 `compaction/start` + `context/compacted` + `compaction/end` 三行（顺序不变）。
- [ ] `/compact` 在**有 run 跑着**时入那场 run 的队列，在下一个模型调用前执行（不另起 run）。
- [ ] 自动触发不受影响：一条用例证明自动那条路仍走同一个 `perform!`、同一个锁。
- [ ] 压缩卡发得出来：手工压缩后客户端收到 `compacted-context` 那张 CUSTOM 帧（一条用例）。
- [ ] `POST …/compact` **不在路由表里了**；旧路径不再是能用的路。
- [ ] `docs` 与 `harness.edge.compaction` / `CONTEXT.md` 里「手工压缩没有 run、不发卡」那几处一起改掉
      （**别留着**，它们那时就不成立了）。
- [ ] 一次**没有可压范围**的 `/compact` 与今天一样什么都不写（`perform!` 那条既有规矩）。

**本票的界线**：自动压缩的预算与阈值一个字不改（只改「谁触发」）。
