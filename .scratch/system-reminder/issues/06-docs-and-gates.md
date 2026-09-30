# 票 06：文档与全量门

Blocked by: 01, 02, 03, 04, 05。

## 目标

把目标状态写进文档，跑两套全量与一次真浏览器走查，留下报数与落地记录。

## 改哪里

- `CONTEXT.md`：
  - **注入**词条：外形换成 `<system-reminder>` + 首行标签行；开场从「N+1 条」改成「两条」；
    「多个 AGENTS.md 合成一条」写成决定。
  - **轨迹**词条：改成 dsh 的**平铺账本**——`system_prompt` 在轮外，`turn-start`/`turn-end` 夹轮，
    轮内 `message → context → assistant/tool`，没有轮的格是 `Between turns`；折轮时 `context` 一起收。
- `docs/architecture/skills-and-instructions.md`：注入物的形状（顺序不变，形状变）。
- `docs/architecture/edge.md`：
  - `returned-source` 的读法（首行标签行 + 旧标签兜底）；
  - 轨迹 payload 换成 `:cells`；
  - 第 473 行那句「`turn/start` / `turn/end` 只上会话那条下行，**不进记录**」旁边补一句：
    轨迹里的这两条是**折出来的**，与事实帧不是一回事。
- `docs/architecture/client.md`：会话栏卡片标题来源；轨迹视图的轮分割线与 `Collapse turns`。
- 各文档里指向 `<instructions path=…>` / `<job-ended>` / 「轨迹是 `{turns, items}`」的句子逐条改。
- 落地记录写回本 spec（`## 落地`），票按仓库约定删除。

## 判据

- `clojure -M:test -m harness.test-runner` 全量，报出「N 例 / M 断言 / 几红几错」，红的是不是既存失败要点名。
- `ui && npm run typecheck && npm test && npm run build`。
- `node scripts/dev.mjs --scripted` + 自己开浏览器：
  1. 新会话出生 → 只有一张指令卡，标题 `Instructions from …`（不是每个 AGENTS.md 一张）；
  2. 发一句 → 会话栏的轮折上后注入卡跟着折，展开又有；
  3. 起一条后台作业、等它结束 → 通知是 `<system-reminder>`，卡片标题是 `Background job …`；
  4. 切轨迹 → 最上是 `Initial System Prompt`，下面 `Turn 1`；折上这一轮，注入的 `context` 一起消失。
- 收尾：合并后三样一起清（worktree、本地分支、远程分支）。
