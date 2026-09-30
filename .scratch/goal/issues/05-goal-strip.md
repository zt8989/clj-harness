# 05 — 目标条：输入框之上那条，存量 + 推送 + 按钮

**What to build:** 人看得见、点得动的目标条。

`ui/src/lib/goal.ts`：类型与两份读法——`goalFor(threadId)`（一次 `GET …/goal`）与
`applyGoal(threadId, action, text?)`（一次 `POST /api/goal`）。失败与空态照 `lib/todos.ts` 那条：
拉不到就是 `null`，不抛、不画错。

`ui/src/components/composer-goal.tsx`：挂在 composer 上方（**在任务横条之上**，spec 决定 9）。
**没有目标就什么都不画**（与任务横条「空列表不占地方」同一条）。有目标时：

- 一行 `🎯 状态词 · 文字`（过长截断），展开是文字全文 + `progress`；
- 按状态给按钮：`active` → 暂停 / 编辑 / 清除；`paused` → 恢复 / 编辑 / 清除；
  `completed` → 清除；
- 编辑是**就地输入**（一个 textarea + 保存/取消），走 `applyGoal(threadId, "edit", text)`；
- **它读模型写的 `progress` 原文、不翻译**；状态词进无障碍树（`sr-only`），与任务横条同款。

**什么时候读**（`docs/rules/panel-data.md` 的两半，一处都不许少）：

1. **存量**：挂载一次、换会话再拉一次（旧答案落地前用 `live` 旗子拒掉迟到的那个，
   与 `composer-todos.tsx` 同一个写法）；
2. **推送**：`subscribeGoals(threadId, …)` 收到的 `goal` 帧直接换掉当格状态；
3. **模型那一半的写不发帧**，所以照任务横条在 `model/start` 与 `turn/end` 两个 fact 上**补一次存量**
   （`goal` 工具跑在一次模型调用结束之后、下一次开始之前，下一个 `model/start` 是它第一个可见的边界）；
4. **重连之后补一次存量**：`onDownlinkOpen` 叫它重开——推送会丢，存量不会。

**Blocked by:** 02

**Status:** ready-for-agent

- [ ] `lib/goal.ts` 的类型与 `GET …/goal` 的字段**同名同形**（spec 决定 2 那份清单）；
      它与服务端两处的一致性由票 07 那条键集合测试钉住。
- [ ] 没有目标：`ComposerGoal` 渲染出**零个节点**（`ComposerTodosView` 对 `null`/`[]` 同一条）。
- [ ] 有 `active` 目标：画出文字与三颗按钮；三颗各自调 `applyGoal` 的对应对作，
      成功后**当格状态立刻换成响应体那份**（不等下一次推送）。
- [ ] `paused` 画「恢复」不画「暂停」；`completed` 只画「清除」。
- [ ] `progress` 非 nil 时画出来，nil 时**那一行不在**（不是空串占位）。
- [ ] **会话换了，旧目标不许留在屏上**：`threadId` 变的那一刻清成 `null`，迟到的旧答案被 `live` 拒掉。
- [ ] **不轮询**：这个文件里没有任何定时器；一次 `GET` + 帧 + 两个 fact + `onDownlinkOpen`，就这些。
- [ ] `subscribeGoals` 只把**这个 threadId** 的帧交回来（票 02 的 mux 那一半的第二次断言）。
- [ ] i18n：`ui/src/locales/{zh,en}/composer.json` 多一组 `goal.*`（状态词、三颗按钮、编辑的保存/取消、
      以及一句空态文案给 `sr-only` / title 用）；**字面键**，`npm run typecheck` 能抓到改名的漏。
- [ ] 测试：`ui/test/suites/goal.ts` 用 `react-dom/server` 把 `ComposerGoalView` 渲成字符串，
      钉住三种状态画出的按钮集合与文案；`npm test` / `npm run typecheck` / `npm run build` 全绿。

**本票的界线**：不在输入框里认 `/goal`（那是票 06）。
