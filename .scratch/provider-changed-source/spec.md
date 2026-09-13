# spec: provider/changed 行的可追溯字段

## 问题

`provider/changed` 行现在只记 `{:verdict :approved :before <slice> :after <slice>}`。两个观察都不可缺失：

1. **回放者分不清「session-configure 按下了 change」vs 「default tier 改了 config.edn 之后整段服务换人」**。后一种情形现在根本不落 `provider/changed` 行（config.edn 改完无信号），但即便将来落，它和 session-configure 按下的 change 长得一模一样——前后对比一样、回放者看不出哪次是授权动作哪次是配置漂移。
2. **`after` 只是 slice，不是 session override 的完整 shape**。若 override 同时改了 `:model` 和 `:reasoning-effort`，但本 run 仅消费了一次「单字段」change，回放者拿到 `:after` 只能看见 model 改了什么、reasoning-effort 没动——它没法在不读 opaque 的情况下重建「按完之后整个 override 长什么样」。

## 设计

**加两个字段，不动现有字段**：

- **`:trigger`** —— 枚举字符串，目前唯一合法值 `"session-configure"`，未来新增授权路径（例 `human-override`）时扩展。**值缺失视为旧日志，向前兼容**。
- **`:override`** —— 本次 change **之后**的 session override 完整 shape（fields ⊆ `[:protocol :base-url :model :reasoning-effort]`）。`:after` 保留作 slice 不动；`:override` 是「按完之后整个 override 是什么」。

**额外补一件事**：`provider/init` 行的 `:source` 已能区分 `:default` / `:request` / `:inline`，但**读不出来 init 行当初的「session 起始 override 是空还是已有内容」**——这是 tier 3 的影子状态。本票不补这个（init 是会话起点，session override 当时为空是定义，强行记 `:override nil` 反而冗余）；留给真正出现「init 之后立刻第一次 change」时再考虑。

**不补的事**：不补 `provider/changed` 的 `:source`（与 init 的 `:source` 语义层不同——change 是 tier 3 自身的事件，不是「哪档覆盖了哪档」）。回放者想知道「这手是谁按下的」用 `:trigger`，想知道「按完 override 长什么样」用 `:override`。

## 验收主线

- `provider_test.clj`：
  - `the-changed-line-names-the-trigger-and-the-full-override` —— change 行同时含 `:trigger "session-configure"` 与 `:override` 等于按完后完整 shape（包括同次 change 命中的多字段）。
  - `consecutive-changes-pin-trigger-and-override-throughout` —— 链式 change 每次都带 `:trigger "session-configure"`，且第 N 次的 `:override` 等于第 N 次按完后的 session override（即第 N-1 次的 `:override` 再叠加第 N 次的 patch）。
- `http_test.clj`：`a-session-configure-lands-as-a-changed-line` 扩展断言 change 行 `:override` 等价于「init 默认档 + 这次 change」的合并 shape；`:trigger` 字面 `"session-configure"`。
- 全量 `harness.test-runner` 101 → 至少 +2 绿，0 红。

## 跨特性前置

- 03（provider/init + provider/changed 行存在）已落地 `efaae75`。
- 04（session-configure 工具 + session override slot）已落地 `efaae75`。

## 状态

- 票 01：实现 + 测试 ✅（commit `f5f8c89`）

## 已验证到什么程度

- commit `f5f8c89`。
- 测试基线：101 tests / 467 assertions → **103 tests / 478 assertions**，+2 测试 +11 断言全绿。
- 端到端 `provider/changed` 行现在带 `:trigger "session-configure"` 和 `:override`（按完之后 session 完整 shape），覆盖 `session-configure` 工具链与 chained change。
- 旧 `:before` / `:after` slice 保留不变，向前兼容既有读者。