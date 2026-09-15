# 01 — 编辑模式落到配置：一个可读、可覆盖、可自省的开关

**What to build:** 「这个会话按哪种方式编辑文件」成为一个**问得出、改得了**的配置事实。默认
`str-replace`（今天的 `edit` 就是它），在 `~/.clj-harness/harness.edn` 里写
`:editing {:mode :hashline}` 就切到锚点模式；绑定项目下的 `.harness/harness.edn` 可以按**键**覆盖
（只想改 `:require-path` 不该被迫重抄整个 `:editing`）。改配置不需要重启——每次调用现读，与
config.edn / harness.edn 的既有纪律一致。

模型与人都能问出当前值：`(harness.memory/editing-mode harness.memory/*thread-id*)` 回一个**已应用
默认值**的完整 map，键与上面那份 harness.edn 一一对应：`:mode`（`:hashline` / `:str-replace`）、
`:auto-read`、`:anchor-grep`、`:require-path`、`:strict-input`、`:boundary-dedup`
（`:on` / `:strict` / `:off`）、`:diff-context-lines`（0–10 的整数）。

**Blocked by:** None — can start immediately.

**Status:** ready-for-agent

- [ ] 新命名空间负责这套配置的唯一真相：解析、默认值、校验。`harness.project/harness-config`
      的两级浅合并行为**一个字不改**（它今天已经是「项目整键盖用户」，本票不动它——按**键**合并
      是这一票在 `:editing` 这一键**内部**做的，落地位置在这一票自己的命名空间里）
- [ ] 两个键必须逐键覆盖而不是整块替换：用户层写了 `:mode :hashline`、项目层只写
      `{:auto-read false}` 时，模式仍是 `:hashline`，自动读被关掉。这条要有测试钉住
- [ ] `:mode` 取值只认 `:hashline` / `:str-replace`，其他值**指名报错**并说出收到的值和一个合法值。
      `:boundary-dedup`、`:diff-context-lines` 同理（范围也要校验）
- [ ] 坏配置是**点名失败**，不是静默回落默认值：`.harness/harness.edn` 不是 EDN、不是 map、
      `:editing` 不是 map 三种情形各有说得出路径的失败信息（沿用 `harness.project` 里已有的
      「缺失是空配置、损坏是硬失败」这条纪律）
- [ ] `harness.memory/editing-mode` 暴露已解析的 map，回答时**现读**（改了配置下一次调用就变）
- [ ] 缺省配置（两个 harness.edn 都不存在）时解析结果与今天的 `edit` 行为一致：这是本票的回归保证，
      也是后续各票「默认不动既有断言」的前提
- [ ] 离线全量 `harness.test-runner` 全绿（基线 189 tests / 930 assertions）

**给后续各票的地基：** 这份解析结果就是后面每一票的开关。02 起 hashline 各工具**只在
`:mode :hashline` 时注册**，因此本票落地后既有断言不会动，直到最后一票（11）翻默认。
