# 03 — `todo_write`：本会话的清单进库

**What to build:** 模型把"我要做的几件事"写下来，那份清单**落进 sqlite**（`<root>/harness.db`），
并按会话归属：同一个会话下一次调用读到的是它自己那份，另一个会话读到的是另一份。
清单会跨 run、跨进程活着——进程重启之后它还在，这是"进了库"与"留在上下文里"的区别。

从用户视角：模型调一次 `todo_write`，工具的答案把这份清单印回来（它刚写下的东西，
连同每一项的状态），于是它下一步能照着清单干活；对话里也就看得见这份清单长什么样。

**Blocked by:** 01

**Status:** ready-for-agent

## 形状

- 工具名 `todo_write`。参数一个：`todos`，**数组**，每项 `{content, status}`。
  `status` 取值 `"pending"` / `"in_progress"` / `"completed"`。
- **整份替换**：这次送来的是本会话**完整的**清单，不是增量。空数组 = 清空。
  顺序就是数组的顺序（清单的次序本身是信息）。
- **不设「读」工具**：清单就在上一次调用的答案里，模型每次都送完整的，没有"读一半再改一半"这种用法。
  读侧（`harness.todos/items-for`）留给 `eval`、测试与将来的面板。
- 不属于任何编辑家族，两种模式都服务它；`:fence-paths` 不适用（它不碰文件系统）。
- 不加帧、不加事件种类：它的三相审计行与别的工具一样。

## 决策

- **落库的形状照库自己的先例：一个会话一行，清单整存整取。**
  `hashline_snapshots.anchors` / `line_checksums` 就是"一个值、写整取整、从不按元素查"；
  清单一模一样（工具**替换**它，读它的人渲染整个列表）。逐条一行在这里买不到东西，
  只把一次写拆成 N 条，还多一个"位置"列要维护。
  表：`todos(thread_id TEXT PRIMARY KEY NOT NULL, items TEXT NOT NULL, updated_at INTEGER NOT NULL)`。
- **它是状态，不是记录**，这段话要写在 `db_test` 的 `declared-state-tables` 旁边
  ——那条元断言就是"新表进库，必须有人先写下它为什么是状态"。判据：**能被整份改写的是状态**；
  清单每次都整份被改写，而"改动发生过几次"没有任何人需要。
- **一个回合只许一次。** 同一条消息里两个 `todo_write` 各自"整份替换"，不存在合并；而一个回合的
  工具调用是并发跑的，于是后写的赢、两个都报成功——那是 `harness.tools` 的批那一节写明不许发生的事。
  所以第二个被**指名拒绝**，说清"一条消息一次清单"。判据用现成的 `register-turn!`（它已经拿着整个回合）。
  顺序回合（两条消息）不受影响。
- **没有会话在作用域里就指名拒绝**，不像 `session-configure` 那样落到进程级槽位：
  `thread_id` 是这份清单的主键，没有会话就变成一份没有名字、谁也读不回来的清单。
- **迁移是追加一个具名步骤**：`{:name "todos" :present? #(table? % "todos") :run todos-step}`。
  已落地的五步一个字不改；老库打开时自己长出这张表，**没有"太新"这种拒绝**；
  `user_version` 依然只是遗迹，没有任何判断读它。

## 验收

- [ ] `harness.db/migrations` 末尾追加具名步骤 `todos`，步骤体建 `todos` 表；
      **既有五步的名字与内容逐字不变**（`schema_steps` 是名字记账，改名即重跑）。
- [ ] 新命名空间 `harness.todos`：`write!`（整份替换）与 `items-for`（读回）。
      它落在 `harness.db` 之上，照 `harness.hashline.store` 的先例；`harness.tools` require 它。
- [ ] `db_test/sessions-hold-no-conversation-content` 的 `declared-state-columns` 加上 `todos`，
      并写下一句它为什么是状态；`db_test/no-table-in-the-store-mirrors-a-log` 的
      `declared-state-tables` 加上 `"todos"`。两条都要真的跑过：
      `clojure -M:test -m harness.test-runner` 里 `harness.db-test` 全绿。
- [ ] `todos` 的表名与列名过 forbidden 正则（`items` / `thread_id` / `updated_at` 都不在词表里；
      若实现时想加列，先过这条正则再谈）。
- [ ] 新命名空间 `harness.todos-test` 写进 `harness.test-runner/test-namespaces`，覆盖：
      写入后**从库里读回**（不是从内存）、整份替换（旧的项不在）、空数组清空、
      两个会话互不可见、同一会话两次写只有一行。
- [ ] **跨进程**：另起一个 JVM 打开同一个 home 能读到那份清单。
      子进程**把答案写进一个文件**、父进程读那个文件——不要比 stdout，
      这台机器的 JDK 会在 sqlite-jdbc 加载原生库时往 stdout 打四行 WARNING
      （先例与原因见 `spec.md` 的"状态"，以及既有那条正在失败的环境用例）。
- [ ] 指名拒绝，逐条有用例：`todos` 不是数组 / 某项不是对象 / `content` 空白 /
      `status` 不在三个取值里（拒绝里报出三个合法取值）/ 超过上限（上限是一个具名常量）/
      同一回合第二个 `todo_write` / 没有会话在作用域里。每条都**不抛异常**，
      按执行缝的规矩作为工具结果回给模型。
- [ ] 同一回合**只有一个** `todo_write` 时正常写入（拒绝规则不误伤常见用法）。
- [ ] `tools_test/specs-expose-every-base-tool` 两份名单各加一个 `todo_write`（排在 `skill` 与
      `undo_last_replace` 之间）。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（除基线的两条环境失败）。
