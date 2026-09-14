# spec: hook 引擎（eval 收敛为它的控制面，memory 解散回各模块）

两件事同时发生：**运行期扩展机制从「eval 随便改」收窄为「hook 引擎」**，以及**为 eval 的读自省而立
的单一自省面（`harness.memory`）随之解散**，各段落回它原来的模块。

## 决策

- **hook 的形态 = 进程内引擎 + 少数真实触发点（2026-09-14，牛总裁定）。** 26 个 hook 点全部**登记为数据**
  （名字 / 时机 / payload 形态 / 是否门禁），没有触发源的点永不触发；只接线四个：SessionStart、
  PreToolUse、PostToolUse、Stop。命令沿用 general-harness 已定的形态：经钉住的 Git Bash spawn，
  payload 走 **stdin JSON**，退出码 **0 放行 / 2 阻断（stderr 回喂模型）/ 其他非零** 按点定的
  fail-open 或 fail-closed；触发落一条 **`hook/<point>` 审计行**。形状与名字沿用该 spec，不另立一套。
- **eval 收敛为 hook 引擎的运行期控制面（牛总裁定）。** 它能做的事被重新表述为两件：**动态改一个 hook**
  （本会话新增一条 / 覆盖一条同名声明）与**动态启动关闭一个 hook**。两条正交轴，与工具表同一套词汇与语义
  （presence 归 register/unregister，availability 归 disable/enable；**关闭不是隐藏**——被关的条目仍在表里、
  只是永不触发，agent 能读到它并自己打开）。
- **工具自我扩展保留为内部 API，退出的只是"对模型宣传"（牛总裁定）。** `session-register!` /
  `session-unregister!` / `session-disable!` / `session-enable!` / `session-require-approval!` 不动、
  不删、测试继续跑；变的是 `prompt.md` 的 **Self-extension 与 Session tools 两段退场**。
- **诚实声明（写进 spec 与 prompt.md）：eval 仍然能执行任意 Clojure。** "仅限于这些功能"约束的是它的
  **定位与文档承诺**，不是语言能力。`prompt.md` 的 secrets 纪律条款（api-key 禁读/禁暴露/禁返回）是这份
  收敛里**唯一不动的**部分——它从来不是为了自省，而是为了一条永远不会因为定位改变而失效的规矩。
- **memory 解散，各回各家（牛总裁定）。** `harness.memory` 这个"单一可自省面"是为 eval 的**读自省**立的；
  读自省在 `762b0d2` 已撤（只留"问现在"的两问一答），写自我扩展在本特征收敛为 hook——面本身没有存在理由。

  | 段落 | 落点 | 依据 |
  |---|---|---|
  | 冻结 prompt / `reset-prompt!` | `harness.llm` | `1d92d5e` 之前它就在这里 |
  | 工具表 + 会话 overlay + 审批状态 | `harness.tools` | 之前工具表就在这里；overlay 与审批是执行缝的状态 |
  | config.edn 解析、api-key、三档解析、provider 时间线 outbox | **`harness.providers`（新名）** | 它就是"本进程服务在哪个 provider 上"的答案 |
  | `log-path` | `harness.home` | 它就是 home 路径的派生 |
  | `active-project` | `harness.project` | 绑定住在那里 |
  | `active-provider` | `harness.providers` | 它是 provider 解析的回答 |
  | init 记账 | http 边（私有） | 唯一读写者就是写入者 |
  | `harness.memory` | 删除 | 没有剩下的东西 |

- **`harness.models` 并入 `harness.providers`（2026-09-14，牛总裁定）。** 目录（厂商 → model 表的形状与校验）
  与"谁赢"（三档折叠、密钥、会话级改动）讲的是同一件事，合成一个 ns 之后 provider 这件事在一个文件里讲完。

  | 段落 | 落点 |
  |---|---|
  | 目录：厂商 → model 表、选择形状、三档折叠、装配、wire | `harness.providers`（原样搬入） |
  | 三档谁赢、api-key、pin 与 session override、时间线 outbox | 同上（memory 那段搬进来） |

  **代价由 03 号票承担并写进 docstring**：两个 ns 时"读 model 目录的人不必经过密钥"由模块边界表达
  ——是**表达**，不是强制，eval 用 var-quote 与 resolve 能到任何 var，那条边界从 `0b131f0` 起就只是写下来的
  规矩。并成一个 ns 之后读目录形状的人也会路过 `.env` 解析那一段，所以 docstring 要分节，而"api-key 只在
  `resolve-provider` 的返回里挂上"这条纪律继续由测试守着。**这不算新债，是把原本就只是"写下来的规矩"的
  那条边界，从两处落成一处的说明。**

  **名字是 `harness.providers`，不是退回去的 `opaque`。** `opaque` 是与 `memory` 对偶造出来的
  ——"不可自省的那一半"——而那个对偶随 memory 一起没了。留下的是它真正装着的东西：
  本进程、本会话服务在哪个 provider 上（哪一档赢、密钥从哪来、进程侧有哪些会话级改动在排队）。
- **审批成为 hook 引擎的一个点（牛总裁定）。** 工具的 pre 相决策统一到 PreToolUse：一次调用有三个结局——
  **放行** / **阻断**（理由回喂模型，run 继续）/ **悬置**（等人点头，即今天的 park）。PermissionRequest 点
  让 hook（脚本或人）代答悬置的调用。**AG-UI 的 interrupt 帧形状不变**，批准/否决的客户端路径不动；
  `:requires-approval` 与 `session-require-approval!` 不删，收窄为"给这个工具装一条悬置型判定"的两种来源。

## 非目标

- 不接线其余 22 个 hook 点的触发源（登记为数据即止；无触发源 = 永不触发，这不是遗漏而是设计）。
- 不碰 MCP / skills / 子代理 / 任务 / 上下文压缩 / worktree（general-harness 的 P1 与 P3）。
- 不改内核事件种类、不改 AG-UI 帧形状、不改既有 JSONL 行的 schema（新增的只有 `hook/<point>` 一种行）。
- 不改 eval 工具自身的实现：仍是 in-process、常驻 `harness.user`、同时捕获 `*out*` 与返回值、
  `pr-str` 截断 8000、不设超时。
- 不做 hook 定义落盘、不做自动重放历史 eval、不引入任何常驻的新真相源。
- 不做真实安全围栏：hook 的阻断与审批一样是流程约定，不是边界（本仓 bash 已是任意代码执行）。

## 验收主线

离线全量 `harness.test-runner` 全绿。**搬迁票（01–05）断言数持平**（基线 189 tests / 930 assertions），
行为逐字节不变——搬家的验收条件就是"什么都没变"；**新能力票（06–12）只增**，唯一例外是 11
（审批链路重述），它要改的既有断言必须逐条列在落地说明里。

## 跨特性前置 / 推翻对照

- general-harness 的 **P2（Hook 引擎）此前只在纸上**；本特征是它的最小真实落地。hook 点数据形状、
  payload 字段约定（snake_case）、退出码语义、`hook/<point>` 审计行名字全部沿用该 spec。
  `harness.project/cwd-changed` 那个已锁定的 CwdChanged 事件源**本特征不接线**（它是 P2 清单里的另一个点，
  留着给接线它的那一票）。
- **推翻 `eval-introspection` 的"harness.memory 是单一可自省面"** —— 那个面是为 eval 的读自省立的，
  而 eval 的定位已经收敛到 hook。`762b0d2` 撤掉的只是"抄下来的副本"，本特征撤掉的是面本身。
- **不推翻 `tool-toggles`**：四个会话级入口与两条正交轴的语义原样保留，只是从"对模型宣传的能力"降级为
  内部 API。`tool-toggles` 对 `tools-lifecycle` 的那次推翻（remove 单调）也不受影响。
- **不推翻 `pre-tool-approval` 的 park/resume 语义**，只换触发它的判定点（`pre-tool-approval` 自己记的
  那条"eval 侧没有可用的伪造入口"仍然成立）。

## 交付顺序

Phase A（01–06）是 prefactor：先把各段送回原模块、并把 provider 的两半并成一个 ns，行为零变化；
Phase B（07–11）引擎与接线；Phase C（12）审批归位；Phase D（13）定位与文档收口。
Phase A 先落地，是为了让 hook 的接线段（10/11/12 都要改执行缝）与搬家票不同时改同一批调用点。

## 状态

- 01–13：未开工（2026-09-14 立票）

## 票清单（`.scratch/hook-engine/issues/`）

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 冻结 prompt 回 `harness.llm` | — | prompt 与冻结纪律回 provider 层，调用方与文档改指 |
| 02 | `harness.models` 并入 `harness.providers` | — | 目录那半先并好，03 才只是往里搬；旧 ns 与单数测试名一起退场 |
| 03 | provider 的解析、密钥与进程侧状态回 `harness.providers` | 02 | 三档解析 / api-key / pin 与 override / 时间线 outbox 入驻，init 记账归 http 边 |
| 04 | 工具表与会话 overlay 回 `harness.tools` | — | 注册表 + 四个会话级入口 + `*thread-id*` 回执行缝 |
| 05 | 审批状态回执行缝所在的模块 | — | park / decide / 一次性取用回 `harness.tools` |
| 06 | 收口：自省面归位并删除 `harness.memory` | 01, 03, 04, 05 | log-path 归 home、active-project 归 project、旧 ns 删除、README 模块图 |
| 07 | hook 点表与 hooks.edn 的两级装配 | 06 | 26 点登记为数据 + 两级装配 + 未知字段指名报错 |
| 08 | hook dispatch：spawn、退出码、超时、审计行 | 07 | stdin JSON payload、0/2/其他、超时不炸 run、`hook/<point>` 审计行、无声明即 no-op |
| 09 | 会话级 hook 的增删改与启停 | 08 | eval 的新面：presence 与 availability 两条轴、关闭不是隐藏、跨会话隔离 |
| 10 | SessionStart / PostToolUse / Stop 三个点接线 | 08 | 三个信息型点真的触发，不改帧序列 |
| 11 | PreToolUse 门禁：阻断与回喂 | 10 | 第一处门禁：退出码 2 不执行、stderr 回喂、run 继续 |
| 12 | 审批成为 PreToolUse 的一个结局 + PermissionRequest 代答 | 11 | 放行 / 阻断 / 悬置三出口；interrupt 帧形状不变 |
| 13 | eval 的定位与文档收口 | 09, 12 | prompt.md 与 README 重写、诚实条款、工具面内部保留 |

首次实现建议走 01–06（搬家与合并，行为零变化）→ 07 08 10 11（引擎与接线）→ 09 12（新面）→ 13（收口）；
09 与 10 都只依赖 08，可并行。01 / 02 / 04 / 05 四张互不相干，可以同时开工。
