# 07 — 键集合不漂、文档落地、端到端走查

**What to build:** 把前面六票收口：**一份字段只有一份答案**这条纪律钉成会失败的测试，
把目标写进既有的文档，走一遍端到端。

**一、键集合不漂（spec 决定 2）。** 目标的字段清单被三处消费，任何一处漏一个字段都必须是**红的**：

- 服务端：`harness.cap.goal` 写读的键 = `GET …/goal` 与 `goal` 推送帧报出的键；
- 客户端：`ui/src/lib/goal.ts` 的类型 = 线上那份的字段名。

服务端一条测试、客户端一条**读 wire** 的用例（照 `ui/test/suites/frames.ts` 的样子：起隔离服务、发一次
`GET …/goal`、把返回的键集合当一个断言），两条合起来才是「三处同一份」。加一个字段（例如日后的
`due-at`）必须三处都动，漏一处即失败，**测试名里说出它守的是哪三处**。

**二、文档。** 都是既有的表，各加一行/一条：

- `docs/rules/panel-data.md`：那条「今天谁这么做了」的清单现在是**五处**，目标条是**第六处**——
  写明它：挂载一次 `GET …/goal`、由 `goal` 帧推、两个 fact 与 `onDownlinkOpen` 各补一次存量、
  不轮询。**别把「五处没有欠账」那句留着**，它那时就变成假话。
- `docs/architecture/overview.md`「状态存在哪」那张表：`项目 / 会话归属 / 归档 / 认领 / todos` 那一行
  加上 **`goals`**，理由与 todos 同一句（会被改写，所以进库）。
- `CONTEXT.md`：票 01 已经加了**目标**词条；这一票核对它与实现一致（措辞、字段名、状态集合），
  不一致就改文档，不是改实现——除非实现真的错了，那就两处一起改并在这里写明。

**三、端到端走查。** `node scripts/dev.mjs --scripted`，打开它报的地址，自己走一趟：

1. `/goal 重构登录模块，补齐测试和迁移说明` → 目标条长出来、文字逐字对；
2. 发一句话 → 模型那一侧读到提醒（在对话栏里那张注入卡上看得见）；
3. 让脚本回放一次带 `goal` 工具调用的回合 → 进展进目标条；
4. `/goal pause` → 目标条画「恢复」，**再发一轮，注入卡不再出现**；
5. `/goal resume` → 注入卡回来；`/goal clear` → 目标条消失。

现场收进 `.scratch/goal/walkthrough.mjs` 与 `.scratch/goal/evidence/`（照
`.scratch/composer-todo-strip` 与 `.scratch/task-pane-push` 的既有做法）。

**Blocked by:** 01、02、03、04、05、06

**Status:** ready-for-agent

- [ ] 服务端那条键集合测试在**删掉一个字段**时红，在**三处都加**时绿。
- [ ] 客户端那条 wire 用例在**删掉类型里一个字段**时红（也就是它能咬住「线上多/少一个键」）。
- [ ] `docs/rules/panel-data.md` 的清单从五处变六处，且**没有**留下「五处没有欠账」这种说明文。
- [ ] `docs/architecture/overview.md` 的状态表那一行含 `goals`。
- [ ] `CONTEXT.md` 的**目标**词条与实现逐字对得上（字段名、三个状态、谁立谁改）。
- [ ] 走查六步全过，evidence 落在 `.scratch/goal/`。
- [ ] 机器门全绿：后端 `clojure -M:test -m harness.test-runner`；前端
      `cd ui && npm test && npm run typecheck && npm run build`；端到端
      `node scripts/dev.mjs --scripted`。
- [ ] **README 一个字不动**（四节之外不写，这条 feature 没有进 README 的资格）。

**本票的界线**：只收口，不新造能力。走查里发现的洞，要么在本票里补、要么新开一票，
不许在本票里悄悄改口径。
