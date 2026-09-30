# 07 — 目标条：输入框之上那条，存量 + 推送 + 按钮

**What to build:** 人看得见、点得动的目标条。

`ui/src/lib/goal.ts`：类型与两份读法——`goalFor(threadId)`（一次 `GET …/goal`，答 `{goal, armed?}`）与
`applyGoal(threadId, action, body?)`（一次 `POST /api/goal`）。失败/空态照 `lib/todos.ts`：拉不到就是
`{goal: null, armed?: false}`，不抛、不画错。

`ui/src/components/composer-goal.tsx`：挂在 composer 上方（**在任务横条之上**）。**没有目标就什么都不画**
（与任务横条同一条）。有目标时画：

- 一行 `🎯 <相位词> · <objective 截断>`，展开是 objective 全文 + `round n/max` + `blocked` 的 code/说明；
- 一行**续跑状态**：`armed?` 真 → 「自动续跑中」，假 → 「已停（要人再说一句）」；
- 按相位给按钮：
  - `active` + armed → 暂停 / 编辑 / 清除
  - `active` + 未 armed → **继续**（re-arm，`/goal resume`）/ 编辑 / 清除
  - `paused` / `blocked` → 恢复 / 编辑 / 清除
  - `completed` → 清除
- 编辑是就地输入（textarea + 保存/取消），走 `applyGoal(threadId, "edit", ...)`。

**什么时候读**（`docs/rules/panel-data.md` 的两半，一处不少）：

1. **存量**：挂载一次、换会话再拉一次（`live` 旗子拒迟到答案，与 `composer-todos.tsx` 同款）；
2. **推送**：`subscribeGoals(threadId, …)` 收到 `goal` 帧直接换当格状态（票 03）；
3. **两个 fact**（`model/start`、`turn/end`）各补一次存量，兜住模型在 run 里的写；
4. **重连之后补一次**：`onDownlinkOpen` 叫它重开。

**Blocked by:** 03

**Status:** ready-for-agent

- [ ] `lib/goal.ts` 的类型与 `GET …/goal` 的字段**同名同形**（spec 决定 3 那份）；与线上的
      一致性由票 09 那条键集合测试钉住。`armed?` 单独一个布尔，不进 `goal` 对象。
- [ ] 没有目标：组件渲染出**零个节点**。
- [ ] 四种相位/armed 组合各自画出**正确的那组按钮**（一条用例把四种都渲成字符串断言）。
- [ ] 点任一颗按钮 → 对应 `applyGoal`，成功后**当格状态立刻换成响应体那份**（不等推送）。
- [ ] 展开区画 `round n/max`；`blocked` 时画 code 与人话说明；`armed?` 假时画「已停」。
- [ ] **会话换了旧目标不留屏**：`threadId` 变即清 `null`，迟到旧答案被 `live` 拒掉。
- [ ] **不轮询**：文件里没有定时器；一次 `GET` + 帧 + 两个 fact + `onDownlinkOpen`，就这些。
- [ ] i18n：`ui/src/locales/{zh,en}/composer.json` 多一组 `goal.*`（相位词、续跑状态、按钮、编辑的
      保存/取消），**字面键**、`npm run typecheck` 能抓到改名。
- [ ] 测试：`ui/test/suites/goal.ts` 渲成字符串钉按钮集合与文案；
      `npm test` / `npm run typecheck` / `npm run build` 全绿。

**本票的界线**：不在输入框里认 `/goal`（那是 08）。
