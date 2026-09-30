# spec: 会话生命周期 —— 归档（单个/批量）与删除

**一句话**：归档早就有（`sessions.archived` + `POST /api/threads/<stem>/archive`，侧栏在用）；
这次补的是**批量**与**删除**——删除会把这条会话留下的一切一起收走：行、锚点、待办、认领，以及
**它自己的 jsonl**。后端已落地（含用例）；设置界面那半仍是一张票。

## 事实（实读代码，不是推测）

- **归档是"藏"，删除是"没了"**，两者必须分开说：`project/archive!` 只写一列，且它的 docstring 与
  一条用例都钉着"**记录一个字节都不动**（字节数 + mtime）"。删除是唯一会动记录的动词。
- 删除链路原来**完全不存在**：全仓没有 `DELETE FROM sessions`，没有删会话 jsonl 的代码，
  hashline 只有按 `(thread, path)` 的 `forget-file!`，`projection` 只有 `forget!`（private，`rebuild!`
  的前半）。`harness.cap.jobs/delete-record!` 删的是**作业记录**，跟会话无关。
- 客户端今天是**故意**没有删除的（`thread-list.aui.tsx:30` 写明 "deletion is deliberately absent"），
  项目那一栏的"移除"也刻意叫 unbind 不叫 delete（`remove-project!` 的 docstring 说得很清楚）。
  **所以这次是把一条既有的立场反过来**——这就是它需要一份设计和一份测试的原因。

## 票 01（**已落地**）：删除 + 批量归档的后端

**一条删除要收走的东西**（每一样都在某个 namespace 里，所以有 `harness.edge.forget` 这一个门）：

| 东西 | 在哪 | 谁删 |
|---|---|---|
| 记录 `.jsonl` | 项目树里，`replay/locate` 找得到 | `harness.edge.forget/record-file` + `io/delete-file` |
| `sessions` 行 | store | `project/delete-session!`（**未知 id 按名字拒绝**） |
| 内容副本 `messages` / `tool_calls` / `projection_offsets` | store | `projection/forget-session!` |
| `hashline_ownership` / `hashline_snapshots` / `hashline_sessions` | store | `hashline/forget-thread!` |
| `todos` | store | `todos/forget!` |
| `session_claims` | store | `claims/forget!` |

**故意留下的**（写在 `harness.edge.forget` 的 ns docstring 里）：`hashline_undo`（按 **path** 而不是
按 thread 键，撤销属于下一个持有该文件的人）；`projects` 行（那是目录的，不是会话的）。

**顺序 = 先 store 后文件**，这是 ADR 0008 当指令读：有行没记录 ⇒ 这个家会永远列出一条背后什么都没有
的会话；有记录没行 ⇒ 目录树还看得到、`rebuild` 会重新登记。**丢副本可恢复，丢会话不可恢复**；文件
放最后，因为它是唯一"没法从剩下的东西重试"的一步（前面每一步都幂等，所以中途失败可以再删一次）。

**拒绝**：本进程有 run 在飞 ⇒ 按名字拒绝（run 正在写的就是要被删的字节，且它的落地回调会写进一张
行已不在的表）。别家进程持有的会话**不**拒绝（看不见别家的 run，也不该去动不是自己持有的认领）。
未知 id ⇒ 按名字拒绝（与 `archive!` 同一个理由）。

**批量**：`POST /api/sessions/archive`（`{threadIds, archived}`）与 `POST /api/sessions/delete`
（`{threadIds}`），都答 `{:results [..]}`，**一条 id 一行、按发送顺序**：成功那行给
`:archived` / `:forgotten`，被拒那行给 `:error <服务器原句>`。**不跨行回滚**——设置面板管的是一个
选择集，"九次里成了八次"是它必须能说出口的话；整批回滚会把成的那几行也拖下水，反而没得画。
空选择集、或 `archived` 没给方向 ⇒ 400（"悄悄什么都没做"与"做成了"长得一模一样）。

**验收**：`harness.edge.http-test` 123 tests / 1346 assertions 绿，含两条新用例
（批量归档逐行成败；删除把记录、锚点、sessions 行都收走，再删一次按名字拒绝）。

## 票 02（**已落地**）：设置界面里的批量会话管理

**做了什么**：设置面板加了「会话」页（`settings-panel.tsx` 的 `PAGES` + `SessionsPage`），列表来自侧栏同一个
`listSidebar`（不另造接口），逐行一个复选框 + 全选，三个批量动词（归档 / 取消归档 / 删除），删除走确认框。
画的部分单独成 `components/session-management.tsx`，理由写在它的文件头：设置面板 import 了 `lib/i18n.ts`
（加载即碰 `document`），而无 DOM 的 UI 用例要能渲染这些句子；`DeleteSessionsConfirmBody` 再分一层，因为
Radix 的 portal 在无 DOM 的 run 里渲染成空串。

**一个动作一套说法**：归档 / 取消归档 / 已归档 / 运行中 读的是 `shell` 目录里侧栏已经在用的四个 key，
只有本页自己的句子（说明、全选、删除与它的确认框）进 `settings` 目录——同一屏上同一个动作两种叫法就是这样来的。
失败按行显示**服务器原句**（不翻译），写完重读一次存量（推送之外的补一次，`docs/rules/panel-data.md`）。
删除按钮在选中集合里有运行中的会话时置灰（`lib/session-status.ts` 的既有规矩，服务器那边也会按名字拒）。

**要做**：`settings-panel.tsx` 的 `PAGES` 加一页（现四页：general / models / mcp / subagents），
列出会话、支持多选、**批量归档 / 取消归档 / 删除**（删除要有确认对话框——今天唯一的确认框是
"移除项目"那个，`sidebar-remove-confirm` 可以照抄形状）。

**已经定好的接口**（票 01）：`POST /api/sessions/archive` `{threadIds, archived}`、
`POST /api/sessions/delete` `{threadIds}`，答 `{:results [{:threadId ... :archived|:forgotten|:error}]}`。
客户端今天**没有** DELETE 方法（破坏性动作一律 POST），照旧用 POST。

**要守的既有规矩**（都是实读出来的，不是猜的）：

- `ui/src/lib/*.ts` 每面一个 endpoint 模块，`API_BASE` 来自 `@/lib/threads`；失败时
  **服务器 `{:error}` 的原句优先且不翻译**，没有才用本端句子。
- i18n 加 key 必须**两种语言同时加**，且 key 要在 `src/**` 里有人字面量写出来（`i18next.d.ts`
  是类型来源，`ui/test/suites/i18n.ts` 有两条用例守着）。
- 组件的用例是 SSR 的（`renderToStaticMarkup`，**没有 DOM**），fetch 驱动的用例走
  `ui/test/e2e.ts` 的 harness；`ui/test/ui.test.ts` 把用例**总数钉死**（`EXPECTED_CASES = 188`）
  ——加用例必须一起改那个数，`npm run typecheck` 会替你抓没改的 key。
- 归档已经在 `SessionSummary` 上是一等公民（`:archived`，侧栏用它分"进行中/已归档"两块）——
  面板里"归档"就是写这一列，不要另造一套语义。
- 删除成功后，侧栏那份列表要重读一次（`subscribeHost` 的推送之外补一次存量——
  `docs/rules/panel-data.md` 的规矩）。

**验收**：`cd ui && npm run typecheck && npm test && npm run build`；`node scripts/dev.mjs --scripted`

**走查发现、本票没做、建议另开一票**：删掉的如果正是**屏幕上正读着的那条会话**，面板把它删了之后页面仍然停在
那条已经不存在的会话上（`localStorage` 里记的还是它的 id，`GET /api/projects` 已经不再列它，主栏还画着它、
输入框还能发，发出去只会吃 run edge 的「未知会话」拒绝）。侧栏对「归档掉正在读的那条」有明确规矩
（`movesThePage`），而这个面板没有：`SettingsPanel` 只拿到 `open/onOpenChange/threadId`，没有让页面挪窝的回调，
推送也不会替它挪。形状大概是「面板把删成功的 id 交回 `sidebar.tsx`，由侧栏像 archive 那样挪一步」。
起服务、自己开浏览器走一趟（设置页点开、选几行、归档、删一条、确认框、以及删除后列表少了一行）。
