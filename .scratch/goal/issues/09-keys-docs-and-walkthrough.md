# 09 — 键集合不漂、文档落地、端到端走查（含 driver）

**What to build:** 收口前面八票。

**一、键集合不漂（spec 决定 3）。** 目标快照的字段被三处消费，漏一处必须红：

- 服务端：`harness.cap.goal` 写读的键 = `POST`/`GET` 与 `goal` 帧里 `goal` 那份的键；
- 客户端：`ui/src/lib/goal.ts` 的类型 = 线上那份的字段名。

服务端一条测试、客户端一条**读 wire** 的用例（照 `ui/test/suites/frames.ts`：起隔离服务、发一次
`GET …/goal`、把 `goal` 的键集合当断言），两条合起来才是「三处同一份」。加一个字段（例如 `pending-block`）
必须三处都动，漏一处即失败，**测试名里说出它守的是哪三处**。
`armed?` 是唯一的例外：它**不在 `goal` 里**、是并行的一个进程布尔，同一个测试也要钉住
「它没有被混进 goal 对象」。

**二、文档。** 都是既有的表，各加一处：

- `docs/rules/panel-data.md`：那条清单现在是**五处**，目标条是**第六处**——写明：挂载一次 `GET …/goal`、
  由 `goal` 帧推、两个 fact 与 `onDownlinkOpen` 各补一次。**别把「五处没有欠账」那句留着**。
- `docs/architecture/overview.md`「状态存在哪」那张表：`项目 / 会话归属 / 归档 / 认领 / todos` 那一行
  加上 **`goals`**，理由写清是**物化 fold**（记录是真相），不是第二条真相。
- `CONTEXT.md`：票 01 加了**目标**词条；这一票核对它与实现逐字一致（字段名、四个相位、谁立谁改谁停、
  `goal/change` 是记录而 `goals` 是 fold），不一致就改文档，除非实现错了——那就两处一起改并写明。

**三、端到端走查。** `node scripts/dev.mjs --scripted`，打开它报的地址，自己走一趟：

1. `/goal 重构登录模块，补齐测试和迁移说明` → 目标条长出来、文字逐字对；
2. 发一句话 → 对话栏那张注入卡上看得见提醒；
3. scripted provider 回放一个**改文件的工具调用 + 收尾**的回合 → driver 自动开出下一轮
   （`round 2/…` 出现在历史里、`rounds` 涨）；
4. scripted 回放一个**纯只读**的回合 → driver **不开下一轮**，目标变 `blocked`（`no-progress`），
   目标条画出来；
5. `/goal resume` → 相位回 active（人恢复），再回放一个改文件的回合 → 又续上；
6. `/goal pause` → 下一轮不再开；`/goal clear` → 目标条消失。

现场收进 `.scratch/goal/walkthrough.mjs` 与 `.scratch/goal/evidence/`（照既有做法）。

**Blocked by:** 01、02、03、04、05、06、07、08

**Status:** ready-for-agent

- [ ] 服务端键集合测试：删一个字段即红，三处都加即绿。
- [ ] 客户端 wire 用例：类型里删一个字段即红；`armed?` 没混进 `goal`。
- [ ] `docs/rules/panel-data.md` 从五处变六处，且**没有**留下「五处没有欠账」那句。
- [ ] `docs/architecture/overview.md` 状态表那一行含 `goals`，并说明它是物化 fold。
- [ ] `CONTEXT.md` 的**目标**词条与实现逐字对得上。
- [ ] 走查六步全过，evidence 落在 `.scratch/goal/`。
- [ ] 机器门全绿：后端 `clojure -M:test -m harness.test-runner`；前端
      `cd ui && npm test && npm run typecheck && npm run build`；端到端 `node scripts/dev.mjs --scripted`。
- [ ] **README 一个字不动**（四节之外不写）。

**本票的界线**：只收口。走查里发现的洞，要么本票补、要么新开一票，不许悄悄改口径。
