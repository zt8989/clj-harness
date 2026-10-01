# 09 — 策略旋钮：绝对保留预算、重试次数、自动开关、默认阈值

**What to build:** 参考实现 `dsh-compaction-basic` 的配置面里，我们少这四个旋钮（其余三个
——`thresholdRatio`/`retainRatio`/`maxTokens`/`maxOverflowRetries`——已经有了）：

| 键 | 参考实现 | 我们 |
|---|---|---|
| `retainTokens` | **绝对**保留预算，与 `retainRatio` 互斥（窗口小的模型用得上） | 只有比例 |
| `compactionRetries` | 折一次还不够就再来一次，直到真的低于阈值；用尽仍不够就抛 | 一次就放手，等下一轮触发 |
| `auto` | `false` 就完全不自动压缩，只留人工 `/compact` | 没有开关 |
| 默认 `thresholdRatio` | **0.8** | 0.7（`pressure/default-ratios`） |

**Blocked by:** —（可开始；`retainTokens` 与 `compactionRetries` 都动 `plan`/`perform!` 的边界，
建议先做这两个）

**Status:** ready-for-agent

- [ ] `compaction/block` 的已知键表跟着加（`check-keys!` 是那一处的唯一名单）；
- [ ] `retainTokens`：与 `retainRatio` **互斥**——两个都给是配置错误，按名拒绝；只给绝对数时
      `plan` 用它当保留预算（`plan` 现在只吃 `retain-ratio`，签名要跟着走）；
- [ ] `compactionRetries`：`perform!` 之后若估价仍 ≥ 阈值就再折一次，次数用尽仍不够 ⇒ 抛
      （参考实现同样的收尾）；默认 1（即最多两折）；
- [ ] `auto: false`：`compact-if-pressured!` 与 `relieve-pressure!` 直接返回 nil；人工入口照旧；
- [ ] 默认阈值：`pressure/default-ratios` 0.7 → 0.8（**这是一次行为变更**，要有 ADR 或 spec 记录：
      触发点推后 10% 的窗口）——或者明确决定「我们保留 0.7」并在此票写明理由；
- [ ] 回归：四个键各一条（含「未知键仍被拒」与「两个保留预算同时给被拒」）。

## Comments

2026-10-01 — 从 `.scratch/compaction-checkpoint` 的 delta 清单拆出来；`check-keys!` 见票 06 那条
修复（`compaction_test/a-compaction-key-nobody-reads-is-refused-by-name`）。
