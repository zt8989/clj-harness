# 01 — `todo_read`：把本会话的任务清单读回来

**What to build:** 模型用一条命令把本会话的任务清单**读回来**：每一项一行、带状态标记，末尾一句总数与
状态分布。清单由 `todo_write` 写、存在库里、跨 run 与跨进程活着，所以「读回来」在压缩过一轮、换了一个
进程、或另一个调用写过之后都成立。从会话界面看：这一步照常是一行工具卡，图标与 `todo_write` 同族。

**Blocked by:** None (can start immediately)

**Status:** ready-for-agent

## 形状

- 工具名 `todo_read`，**没有参数**——清单属于本会话，没有第二个问法。
- 答案一段：每项一行，`- [x] 内容` / `- [ ] 内容` / 进行中那项用它与 `todo_write` 同一套措辞里的标记；
  末尾一句 `N item(s)` 加状态分布（照 `todos/render` 的行文，不另造第二套词）。
- 本会话从没写过、或写过空数组：**同一种答法**（`items-for` 已经把这两种事实并成一答，照着它说）。
- 没有会话在作用域里 → **指名拒绝**（`{:reason :no-session}` 家族，与 `todo_write` 同一句话的写法），
  作为工具结果回给模型，不抛穿。
- 两种编辑模式都服务它；`:fence-paths` 不适用（不碰文件系统）；**不声明 `:read-only`**（子代理的只读
  基线名单不动）。

## 决策

- **读法不是回执。** `CONTEXT.md`「回执」那条讲的是**写**类调用：不回放模型刚送进去的东西。这只手交付的
  正是模型手里没有的东西，所以别把它的答案做成一句「存了几项」——`todos/render` 那条「不回放」的规矩
  **不管它**。
- **渲染住在 `harness.cap.todos`**（与 `items-for` 同一处），工具体只做「取 `kernel-tools/*thread-id*`
  → 渲染」，与 `t-todo-write` 对称。规则（拒绝、上限）不在工具里，这在 `cap.todos` 的模块 docstring 里
  已经立过。
- **翻掉旧决定**：`.scratch/tool-parity/issues/03-todo-write.md` 的「**不设「读」工具**」（连同它那句
  「读侧留给 `eval`、测试与将来的面板」）——**划线 + 日期注**，写明为什么现在需要它（清单跨 run、跨进程
  活着，上下文一丢就再也拿不回来）。`.scratch/tool-parity/spec.md` 决策 14 的「不做 UI 面板 / HTTP 端点」
  **不动**。

## 验收

- [ ] `todo_read` 注册进工具表，两套工具集都服务它；`tools_test/specs-expose-every-base-tool` 的两份名单
      与 `editing_mode_tools_test` 的三份名单各加 `todo_read`，排序后逐名对得上
- [ ] 用例：写一份三项（`pending` / `in_progress` / `completed` 各一）→ `todo_read` 逐条读回，**顺序与
      状态与写进去的一致**（顺序本身是信息）
- [ ] 用例：**跨进程**——另起一个 JVM 打开同一个 home 读到同一份清单（子进程把答案写进文件、父进程读
      文件，别比 stdout）
- [ ] 用例：没写过 → 一条不报错的答句；写空数组之后 → **同一句**（逐字）
- [ ] 用例：没有会话在作用域 → 指名拒绝
- [ ] 用例：连调两次答案逐字相同（读不改状态；`todos` 那行的 `updated_at` 不动）
- [ ] `ui/`：`todo_read` 进 `TOOL_ICONS`，与 `todo_write` 同一族图标；`ui/test` 有一条认得出来的断言；
      `npm test` + `npm run typecheck` 绿
- [ ] 跟着改的谎话（这一票落地当天就不成立的）：`CONTEXT.md` 的工具名清单加名（**顺带补上今天漏掉的
      `skill`**，闭清单核到逐名相等）、「任务清单」词条改成「由 `todo_write` 写、由 `todo_read` 读」；
      `docs/architecture.md` 的模块地图与工具数、`docs/system-prompt.md` 的名单、README 那一节各加名并
      **加一**（18 → 19）
- [ ] 定向跑 `harness.cap.todos-test` / `harness.kernel.tools-test` / `harness.cap.editing-mode-tools-test`
      全绿
