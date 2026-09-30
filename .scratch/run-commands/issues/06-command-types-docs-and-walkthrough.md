# 06 — 命令类型一处、文档落地、端到端走查

**What to build:** 收口这套通道。

**一、命令类型名一处。** 命令的 `type` 集合（`interrupt` / `compact` / `goal` / `steer` / `queue`）
被四处消费：`handle-run` 的校验、优先级表（票 01）、前端包命令的那一处、测试。
服务端一条测试、客户端一条**读 wire** 的用例（照 `ui/test/suites/frames.ts`：起隔离服务，
发一个未知 `type`，把拒绝里点出的合法集合当断言），两条合起来才算「一处」。
加一个类型必须两处都动，漏一处即失败，**测试名里说出它守的是哪两处**。

**二、文档。** 都是既有的表，各加一处：

- `docs/architecture/edge.md`（一次请求的路径那一篇）：那条 `POST /api/agent` 的说明加上 `commands`，
  并把 `POST …/cancel`、`POST …/compact` 从图上拿掉——**别留着**，它们那时就不存在了。
- `CONTEXT.md`：新增词条**命令**（一条对会话下的动作，挂在 run 请求上、进有序队列，
  优先级打断最高；`*别叫成*` 消息（命令里改变对话的那种才是消息）、帧（那是下行））与
  **命令队列**（一场会话一条、进程内存、按会话）。
- `docs/rules/testing.md` 若提到「停一场 run 要 POST cancel」，一并改。

**三、端到端走查。** `node scripts/dev.mjs --scripted`，自己走一趟：

1. 起一场会跑的 run（脚本 provider 回放一个慢回合）→ 按停止 → run 当场结束、终止帧照旧；
2. 发一个 `/compact`（没有 run 在跑）→ 起一场只有命令的 run、压缩行落盘、压缩卡看得见；
3. 一条只有 `idle` 的会话说 `/goal …` → 起一场只有命令的 run、目标条长出来（见 `.scratch/goal`）；
4. 未知命令 → 拒绝里点出合法集合。

现场收进 `.scratch/run-commands/evidence/`（照既有做法）。

**Blocked by:** 01、02、03、04、05

**Status:** ready-for-agent

- [ ] 服务端类型集合测试：删一个类型即红，两处都加即绿。
- [ ] 客户端 wire 用例：未知 `type` 的拒绝里点出的集合 = 前端的命令名集合。
- [ ] `docs/architecture/edge.md` 的路径图含 `commands`，且**没有** `…/cancel`、`…/compact` 两条路由。
- [ ] `CONTEXT.md` 的**命令**与**命令队列**两条词条与实现逐字对得上。
- [ ] 走查四步全过，evidence 落在 `.scratch/run-commands/`。
- [ ] 机器门全绿：后端 `clojure -M:test -m harness.test-runner`；前端
      `cd ui && npm test && npm run typecheck && npm run build`；端到端 `node scripts/dev.mjs --scripted`。
- [ ] **README 一个字不动**（四节之外不写）。

**本票的界线**：只收口。走查里发现的洞要么本票补、要么新开一票，不许悄悄改口径。
