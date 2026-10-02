# spec: 前端长出一个不存在的工具「tool」

**一句话**：侧栏点开一个会话，窗口最顶上一张工具卡写着「tool」——一个没注册过的工具，参数 `{}`，
结果是一段**真的**工具结果。修法：窗口首部那些**配不上调用**的 tool 结果不交给转换器。

**2026-10-02 查，当场修**。现场会话：`4f1f48d5`（「实现把那个打断， queue steer 全部放进 run 的那个需求」）。

## 现场（Playwright）

`http://localhost:8081` 点开 `4f1f48d5`，`显示更早` 下面第一张卡就是「tool · 完成」；展开：参数 `{}`，
结果 `insert` 那次 `2816 is not an anchor` 的拒绝。

## 问题

1. **页首可以是一条 tool 结果。** `GET /api/threads/<stem>/page` 的 tail 页按**到达边界**切，
   于是页首正好落在一次调用的**结果**上，它的调用（`assistant` 那条）在**前一页**，还没加载。
2. **线上那条消息没有名字。** `harness.edge.ag_ui` 折出来的 tool 消息是
   `{id, role:"tool", toolCallId, content}` —— `TOOL_CALL_RESULT` 帧本身就只有
   `{messageId, toolCallId, content, role}`，没有 name。
3. **上游替它编了一条调用。** `fromAgUiMessages` 拿一条 `role:"tool"` 配不上任何已转换的 tool-call 时，
   会**自己造**一条 assistant tool-call（`conversions.js` 里 `if (updated) continue;` 之后那段），
   名字取 `getString(rawMessage,"name") ?? getString(rawMessage,"toolName") ?? "tool"` ——
   线上两样都没有，于是落到字面量 `"tool"`；参数 `{}`（同文件 `toToolCallPart` 起的一段）。
4. **页面照画。** `components/message-parts.tsx` 的 `TOOL_LABELS[toolName] ?? toolName` 把 `tool` 原样画出来，
   图标走兜底扳手 —— 一张「不存在的工具」的卡。

## 范围（当时的实测）

- 39 个能打开的会话里，**17 个**的 tail 页首个 entry 就是这种「孤儿 tool 结果」。
- 拿真转换器跑真实 page：`790b8646`、`878e7788`、`3c85b20e`（都 **settled**）各造出 1 个
  `toolName:"tool"` 的调用，`4f1f48d5`（running）同样 1 个 ⇒ 与 live 半成品无关。

## 决策

1. **首部的孤儿结果不交给转换器**，落点 `lib/thread-messages.ts` 的 `dropOrphanResults`，接在
   `fromAgUiMessages` 之前。不是「换一个更诚实的名字再画」——没有调用可画，那种卡上的参数谁也没有。
2. **只砍首部那一段，且只砍这一段。** 记录以人的 `user` 消息开篇（实测：本机 76 个有消息行的日志，
   首行都不是 `system` 块，全部 user），结果永远跟在它的调用后面 ⇒ 窗口首部的 `tool` 消息一定配不上。
   窗口本身**不动**（只动导入），所以 `显示更早` 仍从同一个 `baseSeq` 往前接。
3. **不丢东西**：前一页里调用和结果是在一起的，`显示更早` 就是把它取回来的那扇门。

## 落地

- `ui/src/lib/thread-messages.ts`：`dropOrphanResults`，`toThreadMessages` 里接在 `fromAgUiMessages` 前。
- `ui/test/suites/thread-messages.ts`：新用例
  `a-window-that-opens-on-a-tool-result-drops-it-instead-of-inventing-a-tool`（票数 225 → 226）。
  用例两侧都钉：上游**真的**会编出 `toolName:"tool"`，而本模块交出去的是 `[r1, m2]`；
  再钉住两种**不许动**的形状（能配上的结果、开在 user 上的普通窗口）。
- **机器门**：见本目录 `README`-style 记录在下节。

## 机器门与走查

- `npm run typecheck` 干净；`npm run build` 过。
- `npm test`：226 例全过（`EXPECTED_CASES` 225 → 226）。
- **用例的红先验过**：把 `dropOrphanResults` 那一行换成 `agUiMessages`，用例失败在
  `expected [ 't1:assistant', 'r1', 'm2' ] to deeply equal [ 'r1', 'm2' ]` —— 那就是被造出来的那条调用。
- **浏览器走查（`node scripts/dev.mjs --scripted <自造脚本>`）**：脚本 40 个 `read` + 末尾一个
  `bash sleep`，让**一个 user turn** 长到超过一页且 run 还在跑（turn 不折，行就看得见）。
  整页刷新后从侧栏点进去，`GET …/page` 实测 `first: "tool"`、`leadingTools: 1`——这就是现场。
  A/B 同一次会话：
  - 不带修：首行是 `tool · 完成`，25+1 张卡里正好 1 张叫 `tool`。
  - 带修：首行是 `read · deps.edn`，0 张叫 `tool`。
