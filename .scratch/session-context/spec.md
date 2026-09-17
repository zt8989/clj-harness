# spec: 会话上下文块（只留 `<project>`，新增 `<env>`）

**这是对已落地的 `system-prompt-blocks` 的一次复议。** 那套机制一个字都不动——`SystemPrompt` 点、
三个来源（内建 / 文件 / 会话）、内建行也是行、每 run 现算、组装在 `harness.cap.system-prompt`。
动的是**内建行的集合**：三条变两条。

复议（2026-09-16，牛总）：**`<tools>` 与 `<provider>` 两行没必要**，`<project>` 保留，
**新增一行 `<env>`：这台机器长什么样。**

## 撤销

- ~~`builtin:tools`~~、~~`builtin:provider`~~。

**理由（牛总）：tools 在接口调用的时候就是自描述的。** wire 上的 `:tools` 数组每次都带着每个工具的名字
与描述（`harness.kernel.tools/specs` 就是它的来源），所以一块「点名」是把同一件事说第二遍——
而且它自己也知道这一点：`tools-block` 的 docstring 里那句「NO DESCRIPTIONS … This block is a roll call」
已经说它不复制描述，剩下来的就只是一份名册。

**这条推理的边界要说清，否则下一个人会把「能力一律不必说」当成规矩。** 自描述的是**名册**
（有哪些工具、各自是什么），不是**技法**（怎么把它们串起来用）。技法不在 wire 上，所以它得有地方说——
Action Fusion 的说明就是这样一块（`.scratch/action-fusion/`，本特征不实现它，只在这里留指针：
那行块属于那个特征，开关也在那里）。

## 停掉了什么（写清楚，别让它悄悄消失）

`<tools>` 与 `<provider>` 今天告诉模型三件 wire 上不会有的事。各自的去处：

1. **本会话关掉了哪些工具** → **按需回答，而且今天就已经如此**：调用一个被关掉的工具，
   缝的回答就是一句指名的话，还附带把它打开的确切写法（`disabled-message`）。一个能被问出来的答案
   不必常驻在 system 消息里。
2. **哪些工具来自外部程序（`mcp__<server>__<tool>`）** → **今天那半句是死代码**：MCP 一行都没落地，
   一个 `mcp__` 工具都不存在。它落地时由那一侧负责（前缀本身就在名字里，连不上还有一行指名失败）。
   本特征不留位。
3. **本会话服务在哪个 vendor / model / 思考档** → **这一条是净损失，如实写在这里**。
   更要紧的是它的**配方**也一起没了：`system-prompt-blocks` 落地时，prompt.md 的 secrets 一节里
   「想知道本会话由谁服务，问 `harness.cap.providers/active-provider`」那一颗被搬进了 `<provider>` 块，
   块没了，那颗就没有家。**接受**：`active-provider` 仍然可问（prompt.md 的「其余自己读」那条通着）、
   `/api/model` 仍然答，只是不再主动说。若真机验收显示模型因此常问错话，再决定把它放回去。

## 新增：`<env>`

**这台机器长什么样**——一句模型必须知道、而 wire 上永远不会有的话。三样：

- **平台**：windows / macos / linux。
- **shell**：`bash` 工具与 hook 命令**实际会被 spawn 的那个**，以及**它是什么**——
  Windows 上钉住的 Git Bash（连绝对路径）/ 系统自带的 bash / pwsh / cmd。
- **命令行增强工具**：一份**声明名单**里，这个 shell 看得见哪些（`rg` / `fd` / `jq` / `git`）。
  **给「有」，也给「没有」**——「写一条 `rg` 而机器上没有 rg」是这块唯一要防的错。

三条都是**机器的属性，不是会话的属性**：每进程解析一次并缓存，与 `harness.infra.shell/binary`
（它本来就是一个 `defonce`）同一个理由。**块本身仍每 run 现算**——那条纪律管的是会话事实。

**探测必须走同一个 shell**（经 `harness.infra.shell`），不是 `System.getenv`：spawn 的是 `bash -lc`，
登录 shell 会重新 source profile，它的 PATH 与 JVM 的 PATH 可以不同；用 JVM 的 PATH 探出一个
「模型真去跑的时候才发现不成立」的答案是这块最坏的失败形态。一次 spawn，结果缓存。

**Windows 上可能是 pwsh 或 cmd**，所以 `infra/shell.clj` 的解析从「找 Git Bash，否则 `bash`」变成一条
**写明的链**（Git Bash → bash → pwsh → cmd），并且**每种 shell 知道自己该怎么起**（`-lc` 是 bash 的拼法，
pwsh 要 `-NoProfile -Command`）。这条链是本特征的前置：没有它，`<env>` 就只能报一个在 Windows 上
可能是 WSL 启动器的字符串（见 `harness.infra.shell` 的 docstring：那个 `bash` 是另一个文件系统，
从 JVM 里静默失败）。

## 非目标

- **不动组装机制**：`SystemPrompt` 点、三个来源、装载/卸载、每 run 现算、原样进 prompt、不包装、
  退出 2 拒绝这次 run——`system-prompt-blocks` 的决策一条不改。
- **不动 prompt.md 的冻结开头**：本特征只在**块**这一侧做增删。
- 不新增 hook 点，不加 jsonl 行种类，不改 AG-UI 帧，不碰 UI。
- 不做 Action Fusion 的那一块（那个特征自己的票）。
- 不做 system 消息的自省端点、不做块的 UI 展示。

## 验收主线

离线全量 `harness.test-runner` 全绿。**要改写的既有断言逐条列明**——本特征不是「什么都没变」，
砍两行必然动到断言：

| 用例 | 今天断言 | 改成 |
|---|---|---|
| `cap/system_prompt_test` 的内建行集合 | `#{"builtin:tools" "builtin:project" "builtin:provider"}` | `#{"builtin:project" "builtin:env"}` |
| 同上，块顺序 | `<tools>` < `<project>` < `<provider>` < 声明块 | `<project>` < `<env>` < 声明块 |
| 同上，「关掉再打开」 | 拿 `builtin:tools` 当样本 | 拿 `builtin:env` 当样本（开关的性质不变，换个样本） |
| `cap/system_prompt_test` 的「一个不能回答的 thread 不出 `<provider>` 块」 | 断言没有 `<provider>` | **整个用例退场**——那个块不存在了；换成 `<env>` 的对应用例 |
| `edge/http_test` 的三来源在场 | 含 `<tools>` / `<project>` / `<provider>` | 含 `<project>` / `<env>` / 声明块；顺序断言同改 |
| `edge/http_test` 关掉内建行 | `session-disable! … "builtin:tools"` | 换 `"builtin:env"` |
| `kernel/hooks/install_test` 的行清单 | `["builtin:tools" "builtin:project" "builtin:provider"]` | `["builtin:project" "builtin:env"]` |
| `overview` 页引用的「`<tools>` 块不会过时」 | 说的是 `<tools>` | 换成 `<env>`（同理：它按机器现算，不会过时） |

## 票清单（`.scratch/session-context/issues/`）

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | 砍掉 `<tools>` 与 `<provider>` 两行 | — | 行集合三条变两条，上表断言逐条改写，三件停掉的事实各有着落 |
| 02 | `harness.infra.shell` 的解析链与起法 | — | 写明的候选链 + 每种 shell 自己的起法；纯函数可测，不必有 Windows |
| 03 | 新增 `<env>` 行 | 01, 02 | 平台 + 解析到的 shell + 增强工具名单；每进程算一次，走同一个 shell 探 |
| 04 | 收口：现状文档与全量 | 03 | hooks / overview / README 三处跟上，全量绿 |

## 状态

- 01–04：**ready-for-agent**（2026-09-16 立票，同日拆票）
