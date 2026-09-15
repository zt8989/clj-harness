# spec: hashline-edit（按锚点编辑，默认实现）

把 `pi-hashline-edit-pro`（MIT，RimuruW / Yugimob）的整套按锚点编辑搬进本仓：`read` 的每一行
带一个 4 字符锚点，编辑按锚点定位而不是按内容模糊匹配；被编辑过的行锚点保持、删掉的行锚点释放、
新行分配新锚点；锚点对不上就拒绝并**当场把当前区间连新锚点回给模型**，让重试不必先重读。

原版 `edit`（str_replace）保留为实现之一，由配置选择；本次的最后一票把默认切到 hashline。

> **本 spec 2026-09-15 被 `project-sidebar` 修订**：存储从"每文件一份 EDN sidecar + 原子写"改成
> **共用的 sqlite** `~/.clj-harness/harness.db`（界面元数据与锚点存储同库），建库与迁移链归
> `project-sidebar` 01。库装状态、文件装记录，配置（含本特征的 `:editing`）仍是文件——这条边界见
> `project-sidebar` 决策 2。受影响的是下面的「落盘持久化」、偏离表第一行与非目标里"不做跨进程锁"
> 那一条，三处都已就地改写。**票 02 因此新增阻塞边：`project-sidebar 01`。**

## 为什么要这一套

`old_string` 匹配有三个反复出现的问题，锚点各自解掉一个：

- **重复文本**：`old_string` 出现多次就得让模型自己想办法加长上下文，长出来的那一截又是一次猜测。
  锚点按行分配，天然唯一。
- **陈旧匹配**：模型凭记忆写 `old_string`，写错了却恰好命中别处，改坏了还不报错。锚点携带行校验和，
  文件在 read 之后变过就拒绝。
- **改完不知道下一笔怎么下**：str_replace 改完只回一句 "edited"，模型要改相邻行就得重读整个文件。
  改后的答复直接带上改动区间的新锚点。

## 决策

- **模式是配置，不是开关 API。** `~/.clj-harness/harness.edn` 的 `:editing`，可被绑定项目的
  `.harness/harness.edn` 逐键覆盖（复用 `harness.project/harness-config` 已有的两级浅合并）。
  每次调用现读，与 config.edn / harness.edn 的既有纪律一致——改配置不需要重启。

  ```edn
  {:editing {:mode :hashline        ;; :hashline | :str-replace
             :auto-read true         ;; write 之后附一段锚点读
             :anchor-grep true       ;; 用 anchor_grep 顶替内置 grep
             :require-path false     ;; replace/insert 必须带 path
             :strict-input false     ;; 拒绝可自动修正的输入而不是修完给 warning
             :boundary-dedup :on     ;; :on | :strict | :off
             :diff-context-lines 1}}
  ```

  **不放 config.edn。** 那份文件的形状是「三个旋钮，且只有三个」，写第四个进去是点名失败而不是悄悄
  丢弃；编辑策略是策略，归 harness.edn。

- **模式决定工具集的形状（**推翻 tool-toggles 的一条已裁定决策**）。** hashline 模式下这份工具表里
  **没有** `edit`；str-replace 模式下**没有** `replace` / `insert` / `anchor_grep` / `undo_last_change`。
  tool-toggles 明写「本特性之后，任何工具都不能再从工具表里消失」，理由是模型会把「被策略关掉」误读成
  「这能力不存在」然后去找 bash 绕路。本次推翻它，因为那条理由在这里换个方式成立得更好：

  - **拒绝要说得出替代品。** 调用一个本会话不服务的名字（陈旧的模型、重放的日志）得到的是指名拒绝，
    说的是「本会话按锚点编辑，用 replace；要改回去写 :editing {:mode :str-replace}」——能力去哪了、
    怎么拿回来，一句话说清。模型不会以为能力不存在，也不必自己摸。
  - **留在表里代价是实打实的。** 两个名字同时在场时，描述里必须花篇幅解释「哪一个在本会话是开的」，
    而模型看到 `edit` 就会用 `edit`；参考实现同样选择移除。
  - **实现面收窄到一处。** 这条豁免只归编辑模式解析器所有：它同时决定「服务哪张表」与「拒绝了怎么说」。
    `session-disable!` 那条通用轴一个字不动，它仍然是「可见但被拒」。

- **锚点表照搬，不自己造。** 4 字符、两位一段、按 tokenizer 实测筛过的 1353139 个条目，随仓带为压缩
  资源。造一份自己的 4 字符表很容易，但那样就丢了这张表唯一的意义——`anchor│` 在现代 tokenizer 上
  稳定切成三个 token。**这是 vendored 资产，MIT 许可，必须在 NOTICE 里署名 RimuruW and Yugimob。**

- **校验和换 SHA-256，其余规范化规则照搬。** 参考实现用 xxhash-wasm 的 h64；本仓不为一处校验和引入
  第三方哈希。契约的重心在规范化（去掉所有 `\r`、去掉行尾空白、超过 500 字节截断）与「同一行同一
  校验和」，不在具体摘要算法。取 SHA-256 的前 16 个十六进制字符。**两份存储互不相通，换了算法不产生
  兼容问题。**

- **落盘持久化（牛总裁定）。** 锚点归属与撤销记录跨进程重启保留——会话是长命的（jsonl 日志 + replay），
  重启后日志里的锚点还该能用。位置是 home 的元数据库 **`~/.clj-harness/harness.db`**（sqlite，与界面
  元数据同库；建库、`user_version` 上的前向迁移链与损坏隔离重建都在 `project-sidebar` 01），本特征占
  三张表：

  | 表 | 内容 |
  | --- | --- |
  | `hashline_snapshots` | 该文件上次被服务时的整文件校验和、行数、逐行锚点、逐行校验和（主键：文件绝对路径的 sha256） |
  | `hashline_undo` | 该文件最近一次 replace/insert 之前的正文、BOM、行尾、锚点、改后正文（主键同前） |
  | `hashline_sessions` | 本会话归属的事件折叠（session / allocate / free / minted / clear），按 thread-id 的 sha256 索引 |

  写入都在**一个事务**里（一笔编辑的临界区：推进归属 + 落撤销，要么都成要么都不成），不再需要"原子写"
  这套自制设施。损坏的处置从"改名单文件"变成"库级隔离重建"，说话的是库的日志行。**跨进程并发现在由
  sqlite 保证**——原先写的"不做跨进程锁"已推翻：参考实现引 SQLite 的第一动机仍然不成立（它是要解决
  一个本仓没有的多进程快照问题），但库已经在手，顺带得到的"两个写者要么串行要么报锁"比"两个写者静默
  损坏"好，所以从非目标里划掉。

- **会话归属仍然是 per-session 的。** 快照按路径存，归属按 thread-id 存：两个会话读同一个文件拿到不同
  锚点，互不踩。归属的持久折叠就是崩溃/重启后还能接着改的原因。

- **不新增日志行。** 编辑的每一次调用与结果本来就落在该 thread 的 message 行里（tool_call + tool 结果），
  锚点是可再生的推导物，不是新事实。工具生命周期沿用既有三相行，只多一个 outcome 取值。与
  tool-toggles / eval-self-extension 的「一份真相源」一致。

  **库的那三张表也是状态，不是索引**（`project-sidebar` 决策 2 画的那条线：库装状态、文件装记录）。
  它们装的是"现在这一行归谁、能退回哪一态"，不是"日志里发生过什么"：编辑的每一次调用与结果不镜像进库，
  它们属于那个 thread 的 jsonl；锚点与校验和也不是内容，是从源文件可再生的推导物。**唯一进库的文件内容
  是撤销记录里的那一份正文**，而且是刻意的、有界的——它按路径只保留最近一次、一次 `write` 就清空，它
  的用途是"把文件退回去"而不是"再放一份文件"。这条边界由 `project-sidebar` 01 的元断言守着（库里不得
  有任何装消息 / 帧 / 日志行的表）。

- **`write` 是锚点的边界，也是大文件的出路。** 一次 write 之后该文件所有锚点释放（内容已经与模型看到
  的不是一回事），撤销记录清除，`replace`/`insert` 下次要说「先 read」。同时**拒绝回显锚点的 write**：
  模型从 read 输出里连锚点一起复制进 content 是很自然的动作，写进去会污染文件、破坏后续 read 与 edit。

- **改后的答复是渲染出来的 diff，不是一句 "edited"。** `+锚点│行` / ` 锚点│行` / `-    │行` 三态，
  上下文行数由 `:diff-context-lines` 决定；被删掉的那行如果锚点还在同一份 diff 里活着，前缀位留空以免
  误导；边界去重剥掉的行以 `dedup│` 行显示且**不可当锚点用**。模型照着 `+` 与空格行的锚点就能下下一笔
  编辑，而不必重读。

- **一条消息里对同一文件的多次编辑合成一次提交。** 同一回合的多个工具调用在本仓是并发跑的
  （`harness.loop/drive!`），同一文件的两笔各自针对 read 时的同一个基态成立，后写覆盖先写且不报错——
  这是静默数据丢失，不是体验问题。因此：按解析后的目标路径分组、区间必须互不相交、全有或全无、最后一次
  调用给出合并后的 diff、一次撤销退掉整批；并且**按路径串行**，让两个会话也不能交错写同一个文件。

- **`anchor_grep` 走 ripgrep。** 沿用参考实现的 flags（`--json --line-number --color=never --hidden
  --glob !.git --max-count`）与 10 秒超时，另加指数级正则的拒绝。`rg` 不在 PATH 上是**点名失败**，
  不是静默降级成爬目录。

- **模式与工具有自省面。** 模型可以问本会话在哪个模式（`harness.editing/editing-mode`），prompt.md 的
  文件工具段落改成模式中立的说法并点出这个入口——prompt 是冻结的，按模式拼两套会破坏 prefill 缓存，
  而工具描述本来就是 per-thread 的，锚点语法归它们去讲。

## 与参考实现的有意偏离

| 参考实现 | 本次 | 为什么 |
| --- | --- | --- |
| SQLite（`node:sqlite` / `bun:sqlite`） | 共用的 home 元数据库 `~/.clj-harness/harness.db` | **这条 2026-09-15 改了**：原先写"本仓不引入数据库"，依据是"单进程拥有 home，不需要事务级并发"。`project-sidebar` 推翻了那个依据——一次编辑要在同一个临界区推进归属与撤销记录，事务是刚刚好的工具，而库已经在手（界面元数据要它），于是同库不同表 |
| xxhash-wasm h64 | SHA-256 前 16 位十六进制 | 不为一处校验和引第三方依赖；规范化契约不变 |
| `/hashline-config` TUI 覆盖层 + `config.json` | harness.edn 的 `:editing` | 本仓的配置面是文件；这里没有 pi 的 TUI |
| `pi.setActiveTools`（把内置 `edit` 从活动表里摘掉） | 编辑模式解析器决定服务哪张表 | 本仓没有扩展 API，但不需要：`harness.tools/specs` 本来就在组装时读 thread-id，模式只是它的又一路输入 |
| `tool_call` 钩子上的 `registerWriteHook` | hook-engine 已落地，`PreToolUse` 是现成的点（`{:tool_name :tool_input}`，exit 2 即拒绝）；但守卫**先长在 `write` 的工具体内** | 一个形态相关、只服务于 write 的改动，长在它服务的那个体里比长成一条钩子声明更好读；将来要挪是一次机械搬迁 |
| 多进程快照的 `withFileMutationQueue` | sqlite 自己的串行化 | 第一动机（多进程快照）本仓仍不需要，但库已经在手，"两个写者要么串行要么报锁"白拿 |

## 非目标

- 不改 `RunAgentInput`、不改 AG-UI 帧形状、不新增 jsonl 行种类。
- 不做多 harness 实例共享一个文件的并发编辑协议（库保证的是不写坏，不是"两笔编辑合并"）。
- 不把 `bash` 挡在编辑之外（锚点是编辑纪律，不是沙箱；真正的边界是审批 park）。
- `write` 的守卫长在工具体内，不长成一条 `PreToolUse` 钩子声明——它是 write 自己的形态约束，不是一条
  用户可以自行取舍的策略。hook-engine 的 `PreToolUse` 点已经存在（`{:tool_name :tool_input}`，exit 2
  即拒绝并回传原因），要挪过去是一次机械搬迁。
- 不给 `read` 加图片视觉：非文本一律点名拒绝（参考实现在这一点上是转交给 pi 的内置 read，本仓没有
  那个宿主能力）。
- 不做 `anchor_grep` 之外的新搜索工具。

## 验收主线

离线全量 `harness.test-runner` 全绿。**开发期 hashline 是 opt-in**（`harness.edn` 里显式写
`:editing {:mode :hashline}`），默认仍走 str-replace，因此逐票落地时既有断言不动；**最后一票翻默认**，
那时才改写既有断言，逐条列在那一票里。

端到端要覆盖三段真实路径（真实 HTTP 端点 + 脚本化模型）：

1. read 拿到锚点 → 按锚点改 → 改动区间的新锚点直接用于下一笔编辑（不重读）；
2. 文件在 read 之后被外部改过 → 编辑被拒且当场拿到新锚点 → 用新锚点重试成功；
3. 撤销把文件与锚点一起退回前一态；重启进程后（清内存、从 `harness.db` 重读）同一批锚点仍然可用。

## 状态

**01–11 是 opt-in 落地**（`:editing {:mode :hashline}` 才生效），既有 247 tests / 1221 assertions
一条不动；**12 翻默认**，那时才改写既有断言。**存储那一票（02）另有一条跨特征阻塞边：
`project-sidebar` 01 先落地**（它建库与迁移链），本特征只是往那条链上再加三张表。

| # | 票 | 阻塞于 | 状态 |
| --- | --- | --- | --- |
| 01 | 编辑模式落到配置：一个可读、可覆盖、可自省的开关 | — | **已完成**（260 tests / 1405 assertions 全绿） |
| 02 | 锚点本身：算法、归属、以及能跨重启活下来的存储 | 01, **project-sidebar 01** | 未开始 |
| 03 | 模式决定服务哪张工具表，以及被拒的名字怎么解释自己 | 01 | **已完成**（273 tests / 1454 assertions 全绿） |
| 04 | `read` 带锚点出行：看得见、可翻页、非文本点名拒绝 | 02, 03 | 未开始 |
| 05 | `replace`：按锚点改一段，改完就把下一笔要用的锚点交回手里 | 02, 03, 04 | 未开始 |
| 06 | 编辑被拒时，把「重试所需的一切」一起交回去 | 05 | 未开始 |
| 07 | `write` 是锚点的边界：释放、清撤销、拒绝回显、以及改完随手能接着改 | 02, 03, 04 | 未开始 |
| 08 | `undo_last_replace`：把文件**和锚点**一起退回前一态 | 05, 06 | 未开始 |
| 09 | 同一回合的多笔编辑：合成一次提交，或者一次都不提交 | 05, 07, 08 | 未开始 |
| 10 | `insert`：插在哪一行前后，锚点不动 | 05, 06 | 未开始 |
| 11 | `anchor_grep`：搜出来的命中行可以直接改 | 04 | 未开始 |
| 12 | 翻默认：hashline 成为默认实现，prompt 与文档跟上，既有断言重写 | 03–11 全部 | 未开始 |

### ⚠ 开发中期的不完整状态（03 之后、05–07 之前）

`:mode :hashline` 今天会**拿走 `edit` 而给不出任何替代**——锚点工具还没落地，那一族的名字一个都不在
册。这不影响任何人（那是显式配置才到得了的状态，默认是 str-replace），但**票 12 绝不能在这个状态下
翻默认**：一个没有任何编辑工具的模式不是「另一种实现」，是坏了。12 的验收里已经把「两种模式各自跑
通 read → 改 → 撤销」写成硬条件，这条记录是为了说清那道闸门为什么必须在。

顺带一条是被这次落地顺手修掉的既有缺陷：`approval-reason` 对**没有 path 的围栏工具调用**会去问
`project/out-of-bounds?` 一个 nil，然后在 java.io 深处抛 NPE，把真正有用的「缺参数」信息盖掉
（`{"function":{"name":"edit","arguments":"{}"}}` 打到绑定会话上就是这条）。现在 `some?` 先挡一道，
缺参检查得以上报。

### 01 落地记录

`src/harness/editing.clj` 是解析的唯一真相：`defaults` + 两级 `:editing` 块逐**键**合成，校验分两步
——未知键**两份文件都查**（被盖住的错字也要报，因为它是「从没打算被当真」的请求，不是一次合并的
输家），取值**只查生效结果**（否则项目层无法修好用户层写坏的值）。失败信息一律带**级别的名字与绝对
路径**：`harness.edn :editing 必须是 map，但 user 级在 <路径> 说的是 :hashline`。

`harness.project` 抽出了 `harness-edn-levels`（两个未合并的层 + 各自的文件路径），`harness-config`
改成它的浅合并消费者——**行为一字未动**，有测试钉住（`harness-configs-shallow-merge-is-untouched`）。
抽取的理由是「缺失是空配置、损坏是硬失败」这条纪律必须有**一份**实现：`harness.editing` 借的是它，
不是又抄一遍。

测试在 `test/harness/editing_test.clj`（13 tests / 184 assertions），并进了 `test-runner` 的
`test-namespaces`。用例覆盖：默认即 str-replace（回归保证）、逐键覆盖、未绑定只看用户层、每次现读、
三种坏配置各点名文件、未知键哪怕被盖住也报、七种取值各自的指名失败、以及「信息里说的合法值就是真正
接受的值」这条正反两面的对账。

### 03 落地记录

`harness.editing/families` 把「哪个模式服务哪些工具名」写成数据，`family-of` 从它派生，`served?` 是
唯一的判定——所以这机制**没有一件工具一行代码**：04–07 把锚点工具注册进来，过滤器自动就认它们。名字
不属于任何一家的工具（`bash` / `read` / `write` / `eval` / hooks…）恒被服务。

`tools/specs` 是唯一做减法的地方（`filter served?`），`tools/run!` 在执行缝的第二支做同样的判定，
消息由 `editing/unserved-message` 产出：**名字、本会话按什么编辑、替代工具、改配置的写法**，四件事各一
句。`.scratch/tool-toggles` 那条「工具永不从工具表消失」由此被推翻，理由写在 `families` 的注释里——
被拒的名字说得出替代品与恢复路径，比「可见但被拒」给的信息更多；而两个编辑工具同时在场的代价是实打实
的（模型看到 `edit` 就会用 `edit`，两套锚点归属随即打架）。

执行缝的顺序定成 **disabled → 模式 → 缺参 → 审批 → PreToolUse 闸门**。disabled 先于模式是
tool-toggles 要的顺序，含义也真实：会话自己扔的开关压过它继承的策略，所以答案先给「你自己能撤销的那
件」。但**第二件事实不被隐瞒**——`disabled-message` 在两个都成立时把两件都说了，并且**不做
「re-enable 就好」的假承诺**（对模式减掉的工具，re-enable 之后再打一次还是同一个答案）。

测试在 `test/harness/editing_mode_tools_test.clj`（13 tests / 49 assertions）。锚点那一族的断言用
**会话注册的替身**跑——本票要验的是过滤器，不是那四个工具的躯体；替身还顺带证明了「按名字生效、没有
per-tool 代码」这条性质。用例覆盖：默认表逐条不变、hashline 拿走 `edit`、str-replace 不服务锚点族、
两套模式按会话隔离（两个项目、同一个相对路径、只有一边能改到文件）、拒绝信息四个要素齐全且永不说
`unknown tool`、拒绝先于缺参与审批（不 park）、disable 压过模式且两件事实都说、以及坏配置在 `specs`
就炸而不是悄悄换一种编辑方式。

## 备注

- 参考实现：<https://github.com/YuGiMob/pi-hashline-edit-pro>，v4.2.11，MIT。
  锚点表（`src/hashline/anchor-table.json`）随仓引入，署名进 NOTICE。
- 上游工具名是 `undo_last_change`；本次按牛总的口径叫 `undo_last_replace`——撤销的单位就是一次
  replace（或一次 insert），名字把单位说清楚。
