# spec: 删掉 `session-configure` 这个工具

## 问题

`session-configure` 是模型唯一能改写本会话档位（provider / model / reasoning-effort）的手。它带
`:requires-approval`——调用先 park，人批了才写——所以它同时是**审批缝**在树里唯一的用户。

两件事让它该走：

1. **同一个动作已经有第二条路，而且是人在按的那条。** `POST /api/model` 是它的 HTTP 孪生：同样三个
   旋钮、同样的指名拒绝、同样先解析后写，composer 的选档器按的就是它。模型自己改自己的档位不是这个
   产品的动作面。
2. **它带来的机器比它做的事多。** 工具线程写不了日志，所以它唯一的生产物是 provider outbox 里的一条
   待写事实（`record-provider-change!`），再由 edge 在下一轮 run 里排空成 `provider/changed` 行。
   删掉工具，这条 outbox 就没有生产者了。

## 设计

- 删工具本身：`cap.tools` 里的 `register!`、body（`t-configure`），以及它专用的
  `harness.cap.providers` require；UI 那一份脸（`TOOL_ICONS` 的表项与 `subjectOf` 的分支，以及因此
  不再被用的 `SlidersHorizontalIcon` 导入）。
- 名册与文档跟着改：`docs/architecture.md`（十九 → 十八）、`kernel.md`（按模式的表、审批那段、工具
  一句话）、`client.md`（图标表）、`providers.md`（档位表第 2 行、指名失败、先解析后写）、
  `system-prompt.md`、`adr/0002`（退役字段那张表的指针）、`config.edn.example`。
- 引用它「还在时」的说法，改成指**活着的那条路**（`POST /api/model`）——工具已经不在，指针不能悬空。
- **outbox 留下**，并写明它今天没有生产者：`provider/changed` 是**词汇**，`harness.edge.context` 会读它
  （回放时重建会话当时服务的是谁），edge 每轮排空它。删掉读者侧要动的是日志词汇，那是另一个决定，和
  「删一个工具」不是一件事（同 `GET /api/subagents` 那次的处置：留着，但把「没有生产者了」写在原地）。
- **`:requires-approval` 这道缝留下**：它现在树里没有内建用户，但它是 seam 的能力
  （`session-require-approval!` 或一条 hook 都能 park 一次调用），`test/harness/approval_test.clj` 用
  一枚探针工具一直盖着它。

## 不再被覆盖的东西，逐条交代

- **审批**：工具的 park / approve / veto 用例删掉。`approval_test.clj` 的探针工具仍盖着
  `:tool-declares` 这条来源，hook 那条路盖着 `session-require-approval!`。
- **档位写入**：`POST /api/model` 的既有用例盖着「三个旋钮 + `clear` + 指名拒绝 + 只动本会话」；
  `swap-override!` 的既有用例盖着原子性（两个调用同时在飞）、`before/after` 链、拒绝时一字不写。
- **日志**：outbox 的契约（记一次、排一次、按会话分家、`:override` 是整档、`:resolved` 是当场解析）
  与 edge 的排空，现在**直接驱动函数**来测，不再绕一个不存在的工具。
- `POST /api/model` 写自己那行 `provider/session-changed`：这条日志行**本来就没有用例**，本次也没有新增
  ——不是这次删掉的东西。

## 验收主线

- 后端全绿（含改写的用例：名册两处列表、`providers_test` 的 outbox 契约、`http_test` 的排空端到端）。
- 前端 `npm test` / `typecheck` / `build` 全绿。
- 真浏览器走查：`session-configure` 不再出现在名册里（模型看不到它），而 composer 的选档器**仍然真的
  改会话档位**——`POST /api/model` 换一次，`GET /api/model` 读到的就是换过的档。
