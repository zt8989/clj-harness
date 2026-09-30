# 票 06：文档与全量门

Blocked by: 01, 02, 03, 04, 05。

## 目标

把目标状态写进文档，跑两套全量与一次真浏览器走查，留下报数与落地记录。

## 改哪里

- `CONTEXT.md` 的**注入**词条：外形换成 `<system-reminder>` + 首行标签行；开场从「N+1 条」改成「两条」；
  「多个 AGENTS.md 合成一条」写成决定。**轨迹**词条：一轮 = 用户 + llm，系统提示词在轮外。
- `docs/architecture/skills-and-instructions.md`：注入物的形状与顺序（顺序不变，形状变）。
- `docs/architecture/edge.md`：`returned-source` 的读法（标签行 + 旧标签兜底）、轨迹 payload 的 `:system`。
- `docs/architecture/client.md`：会话栏折轮带注入卡；卡片标题来源。
- 各文档里指向 `<instructions path=…>` / `<job-ended>` 的句子逐条改。
- 落地记录写回本 spec（`## 落地`），票按仓库约定删除。

## 判据

- `clojure -M:test -m harness.test-runner` 全量，报出「N 例 / M 断言 / 几红几错」，红的是不是既存失败要点名。
- `ui && npm run typecheck && npm test && npm run build`。
- `node scripts/dev.mjs --scripted` + 自己开浏览器：
  1. 新会话出生 → 只有一张指令卡，标题 `Instructions from …`；
  2. 发一句 → 轮折上，注入卡跟着折；展开回来；
  3. 起一条后台作业、等它结束 → 通知是 `<system-reminder>`，卡片标题是 `Background job …`；
  4. 切轨迹 → 系统提示词在轮外，一轮只有用户与 llm。
- 收尾：合并后三样一起清（worktree、本地分支、远程分支）。
