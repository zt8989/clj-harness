# 03 — 按 agent 档生效：主 agent 与子 agent 各自的工具表

**What to build:** 让 01 那条解析**真的决定一个线程拿到什么工具**，并把 `baseline`/`exclude` 拆掉。

- **agent 种类从库里读。** 一个线程属于哪个 agent，是**已有的一列**：`sessions.subagent` 为空 ⇒ 主 agent，
  否则就是那个名字（`cap/project.clj:361` 的 `sessions` 已经把它读出来，`infra/db.clj` 的
  `sessions-know-their-subagent` 迁移建的列）。本票**不新增**"现在是谁在跑"的进程内状态。
- **装一条 kernel 层**，它的答案按线程现算（`harness.kernel.tools/install!`，`kernel/tools.clj:176`）：
  - `:narrow` —— 这个 agent 的 `:served` 之外的名字**不在服务**。拒绝语取自 01 的 `:unserved`
    （哪一层、哪个键、怎么写回来、拿什么替代）。`served?`（`:728`）与 `unserved-message` 都已有形状，
    本票只把"回答者"从编辑模式换成"这个 agent 的配置"。
  - `:tools-for` —— **按会话现算**的那几个名字：增强层选中的一套（`hashline` 那四把与两张脸，
    照今天 `cap/tools.clj` 四处 mode 分叉的行为），加上复合层的 MCP 服务器（`cap/mcp.clj:979`）
    与技能带的工具。**只有 `:served` 里的名字才答得出来。**
- **拆掉 `baseline`/`exclude`。** `table-for`（`cap/subagents.clj:355`）不再"拿父会话的有效表去减"，
  改成"取这个子 agent 的解析结果"；`provable-read-only?`（`:330`）的只读判据搬进 01；
  `entry-keys`（`:115`）里 `:baseline` / `:exclude` 退休；`definitions`（`:252`）与 `wire-definition`（`:319`）
  跟着改成新的 entry 形状。**内置两条的默认**：`explore` 只勾能证明只读的那些（`:read-only? true`），
  `general` 与主 agent 同基础层（减 `eval`/`agent`）。
- **委派那一刻不再"冻一份表"**：`run-one`（`cap/subagents.clj:596`）今天把 `table-for` 的结果
  `session-register!` 进子会话（`kernel/tools.clj:256`）。本票把它换成"新会话一出生就按它自己的配置解析"
  ——子 agent 的线程一旦有了 id，`served?` / `tools-for` 就按它自己的 agent 档答，不需要父会话先冻一份。

**要点：**

- **三态的话必须分得开**（决策 7、13）：`在服务` / `不在服务`（配置没勾）/ `被关掉`（`:disable` 与会话开关）。
  一条拒绝里如果两件事同时成立（既没勾、又被层关），**两件都要说**——这是
  `kernel/tools.clj` `a-call-that-is-disabled-and-unserved-says-both` 那条既有钉子要求的，别弄丢。
- **`eval` / `agent` 是后置条件**（决策 9）：这一层对**任何**子 agent 线程都不服务它们，
  哪怕配置里勾了（01 已拒；这里是第二道保险，因为线程的 agent 档也可能从库里读出一个手改过的名字）。
- **`:read-only?` 的解析结果要能自证**（决策 10）：一个 `:source :mcp` 的名字、一个本会话
  `session-register!` 进来的名字，**都不进**只读子 agent 的 `:served`。
- **不动的三条**：进程级 `:disable`（看得见、拒调用）、会话级 `session-disable!`（`:283`）、
  以及本票动的"没勾 = 不在服务"——三条各自的"怎么说"照旧，本票只加第三种的语义来源。
- **`:editing {:grep false}` 的等价**（决策 4）：`grep` 是基础层的一格；**不复刻**这个键，
  02 已经让它按名拒绝并指向那一格。

**Blocked by:** 02（配置读得出来、默认档在、旧家点名拒绝）

**Status:** ready-for-agent

## 现场

- kernel 的门：`install!`（`kernel/tools.clj:176`）收贡献 map `{:name :tools :disable :planner :narrow
  :tools-for :disabled-for}`；`effective-tools`（`:404`）＝ 静态表 ⊕ 会话来源 ⊕ overlay；`served?`（`:728`）
  问所有 `:narrow`（按到达顺序，第一份说不服务的用它的拒绝语，`:83` 的注释是它的设计说明）；
  `run!`（`:996`）的拒绝顺序是 disabled → mode → missing-args → 审批 → `PreToolUse`。
- 组合根今天装五层（`edge/http.clj:5567-5582`）：`cap-tools/install!` → `cap-hooks/install!` →
  `system-prompt/install!` → `cap-mcp/install!` → `subagents/install!`。本票的层加在**最后**，
  让它的拒绝语在编辑模式之后开口（"先开口的那句更具体"这条照旧）。
- 编辑模式今天的分叉点（本票要把它们从"读一个全局 mode"改成"读这个 agent 的解析结果"）：
  `cap/tools.clj` 的 `t-read` / `t-write` 体、`read` 的 `:describe`、`write-face`；
  `cap/editing.clj` 的 `served?` / `unserved-message`。这四处**只有一处**该知道"这次是哪个 agent"。
- 子 agent 的活表：`live`（`cap/subagents.clj:390` 起的 `begin!`）、`runs`（`:418`）、
  `begin-subagent!`（`cap/project.clj:254`，新会话行 + 拷贝父的绑定）。线程一出生就有 agent 档。
- 委派工具：`agent-tool`（`cap/subagents.clj:678`）、`face`（`:559`，按会话列出可选名字）——
  `face` 里的可选名单要跟着 `:served` 走。
- 既有的 UI 一侧（本票不动，但会被它读到）：委派卡片、子 agent 面板读的是记录与
  `runs`，不是工具表。

## 验收

- [ ] 一个家配成"主 agent 有 `bash`、`explore` 没有"：主线程的 `specs` 含 `bash`，`explore` 的**不含**；
      在 `explore` 里调用 `bash` ⇒ 按名拒，话里有"哪个 agent、哪个键（`config.edn :agents :subagents explore
      :base :tools`）、怎么写回来"（真 HTTP 断言一次）。
- [ ] 同一个进程里主线程与子线程**同时**问同一个名字 `bash`：主答"在、能跑"，子答"不在服务"（一条用例断言两侧）。
- [ ] 增强层按 agent 档生效：主 agent `:hashline`、某个子 agent `:str-replace` ⇒ 两侧的 `read` 答案不同
      （一侧锚点行、一侧纯文本），且各自的 `specs` 名单对得上。
- [ ] `explore` 的只读自证：一个 `:source :mcp` 的名字与一个 `session-register!` 进来的名字
      **都不在** `explore` 的 `specs`（两条用例）；主 agent 里两者都在。
- [ ] `eval` / `agent` 在任何子 agent 里都不在 `specs`、调用被拒（两份名单交叉断言）。
- [ ] `baseline` / `exclude` 的旧用例**改写成逐层勾选的等价断言**（`subagents_test.clj`）：
      `:baseline :all` 等价于"基础层＝主 agent 的默认档"，`:baseline :read-only` 等价于 `:read-only? true`；
      一个"父会话刚 `session-register!` 一个工具"的用例断言它**不出现**在子 agent 手里（决策 8 的代价，写进用例）。
- [ ] 三态分得开：一条用例让一个名字**既没勾又被层关**，断言拒绝话里**两件都说了**。
- [ ] 委派跑通：一次委派、子 agent 用自己配置的表跑完、结论回到主对话；
      `~/.clj-harness/projects/<workspace>/<子 thread>.jsonl` 里能看到这一轮。
- [ ] `clojure -M:test -m harness.test-runner` 全绿。
