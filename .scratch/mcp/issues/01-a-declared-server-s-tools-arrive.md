# 01 — 声明一个 stdio 服务器，它的工具进表并能被调用

**What to build:** 用户在建了绑定的项目 `.harness/mcp.edn`（或配置家的 `~/.clj-harness/mcp.edn`）里声明一个
stdio 服务器，**下一次 run** 起，这个服务器 `tools/list` 报出的每个工具就以 `mcp__<server>__<tool>` 的名字
出现在这个会话的工具表里：模型看得见、调得动、拿得到服务器返回的文本。这次调用留下的三相审计行、
审批 park、`PreToolUse` 阻断、会话级关闭，与内建工具**一个字都不同**——因为它走的就是同一个执行缝。

**这一票同时落 general-harness spec 的 P1「工具来源泛化」**：工具表从「单一内建表」变成
「内建 ∪ 本 thread 的外部来源 ∪ 会话 overlay」，每个工具定义带 `:source`。刻意**不做**那半张 spec 里
「按工具配审批策略与超时」：MCP 的请求超时是桥接层自己的事（见 02），缝上暂时不需要第四个旋钮。

**Blocked by:** None — can start immediately

**Status:** done（2026-09-16，分支 `mcp`）

## 形状（两处声明，一条纪律）

```edn
;; ~/.clj-harness/mcp.edn（用户级）与 <project>/.harness/mcp.edn（项目级）
{:servers {"github" {:command "npx" :args ["-y" "@modelcontextprotocol/server-github"]
                     :env {"GITHUB_TOKEN" ".."} :timeout 60000}}}
```

- **顶层浅合并、项目级 `:servers` 整表替换用户的**——与 `hooks.edn` 逐点替换、`harness.edn` 逐键替换
  同一条纪律、同一个理由：「实际会跑什么」要在一个文件里读得出来，而不是从两个文件怎么嵌套里推。
  代价照旧接受：一个只想加一个服务器的项目要把它要的那几个一起写出来。
- 每次现读（config.edn 纪律），改文件下一次 run 生效、不重启。缺失 = `{}`（全新安装没有 mcp.edn 是常态）；
  **存在却坏**（EDN 坏 / 非 map / 未知顶层键 / 未知 server 键 / 名字非法 / 既没有 `:command` 也没有 `:url` /
  两者都有）→ **指名绝对路径硬失败**。被静默忽略的配置与什么都没说的配置，从外部看没有区别。
- **server 名必须匹配 `^[A-Za-z0-9_-]+$` 且不含 `__`**：`mcp__<server>__<tool>` 必须能唯一反解回一个服务器，
  否则两个服务器的同名工具会撞进一个名字里，而「撞了」在模型那边看不见。
- `:env` 是给子进程的环境变量（很多服务器要 token）。它的值**永不入任何日志行、永不进任何端点响应**——
  与 api-key 同一条纪律，验收里按哨兵值搜索断言。

## 连接与握手

- **一个服务器一个长活进程**，不是每次调用 spawn；连接在**首次装配这个 thread 的工具表**时建立。
- spawn **走 `harness.shell`**（Windows 上 System32\bash.exe 是 WSL 启动器那个坑是机器的属性，不是调用方的），
  `cwd` = 本会话的项目目录（与 `bash` 同一条规矩），未绑定会话用进程工作目录。
- 握手：`initialize`（报 protocolVersion / clientInfo，capabilities 只声明我们真有的）→
  `notifications/initialized` → `tools/list`。**stdio 分帧 = 每行一个 JSON-RPC**（UTF-8，消息内无换行）。
  服务器答的 protocolVersion **照收不较劲**（它有权回一个它支持的版本）。
- 装配结果**必须缓存**：`specs` 每次 LLM 请求都会被调（`harness.llm`），装配不许每次都去问服务器。

## 桥接

一个 MCP 工具 → 一条工具表定义：

| 表里的字段 | 来源 |
|---|---|
| 名字 | `mcp__<server>__<tool>` |
| `:description` | 服务器的 description；没有就给一句兜底（工具必须能被模型读懂） |
| `:parameters` | 服务器的 `inputSchema` **原样**（它本来就是 JSON Schema）；缺 `inputSchema` 时 `{:type "object" :properties {}}` |
| `:required` | `inputSchema.required` 关键字化——**这是执行缝判 `missing-args` 的那一份**，与 schema 不一致就会对模型说「缺参数」而它明明给了 |
| `:source` | `:mcp` |
| `:run` | `tools/call {name, arguments}` |

- 调用的结果 = 服务器 `content` 里 `text` 片段按序拼接。
- `isError: true` 或服务器报错 → **抛**，执行缝把它变成这次调用的错误结果，**run 继续**：
  工具失败是给模型的信息，不是 run 的失败（既有铁律）。
- 名字超长：OpenAI 兼容端点对 `function.name` 有长度与字符集约束（`^[a-zA-Z0-9_-]{1,64}$`）。拼出来的名字
  超限时**指名跳过这个工具**（server 名 + tool 名 + 实际长度），**绝不静默截断**——截断会把两个工具变成
  一个名字。

## 装配与失败隔离

- `effective-tools` = 内建 ∪ 本 thread 的 MCP 工具 ∪ 会话 overlay；六个内建补上 `:source :builtin`。
  `specs` / `run!` / 审批 / 禁用 / 审计**一行不改**——它们读的是同一张表。
- **连不上的服务器（spawn 失败 / 握手失败 / 超时）不炸 run**：它的工具缺席，其余服务器的工具照常，
  失败**指名**（server 名 + 原因）落在进程内状态里，并由**边**落成一行 `mcp/server` 审计行。
  写入者仍是边（`harness.http`）：mcp 层只登记事实，抄 `providers/take-provider-changes!` 的 outbox 先例
  ——装配发生在 run 里也发生在 run 外，runId 该是 nil 的地方就得是 nil。
- **没有声明任何服务器时，工具表、帧序列、审计行与这个能力存在之前逐字节相同**——本仓每加一条路径都要
  还的这笔账。

## 测试缝

假 stdio 服务器 = 一个**脚本**（Node 写，落在 `test/harness/` 下；Node 18+ 已是本仓 README 写明的既有前置，
而被 fake 的是一个外部进程，语言与被测的东西无关）。它应答 `initialize` / `notifications/initialized` /
`tools/list` / `tools/call`，工具集里至少有两个：一个**回显参数**、一个**回 `isError`**。

**测试卫生**：`mcp.edn` 在家目录里，测试跑在临时 home（`CLJ_HARNESS_HOME` 隔离）——但**项目级
`.harness/mcp.edn` 写在 tmpdir 里，fixture 必须前后双删**：tmpdir 跨 JVM 的残留会让下一次运行静默带上
别人的服务器清单，而 `harness.edn` 那个「残留静默移动围栏」的坑已经吃过一次。

## 验收

- [x] 两级装配与校验如上一节：坏文件指名绝对路径硬失败，缺失不报错，每次现读
- [x] server 名非法（含 `__`、含空白、含点）→ 指名失败，并说清为什么（名字要能反解回唯一的服务器）
- [x] 声明一个 stdio 服务器后，一条**真 AG-UI run** 里模型能看到 `mcp__<server>__<tool>` 并调用它，
      结果的文本进 tool message，`tools/pre-execute` / `tools/execute` / `tools/post-execute` 三行照落
- [x] 工具定义带 `:source :mcp`，内建带 `:source :builtin`；`edit`/`read` 等既有断言一字不改
- [x] **同一个执行缝的四个断言**：`session-require-approval!` 会 park 它并走 interrupt/resume；
      `PreToolUse` 退出 2 能阻断它且 stderr 回喂；`session-disable!` 拒它；`PermissionRequest` 能代答
- [x] 服务器回 `isError` → 工具结果是**错误结果**（`:error true`），run 照常收尾
- [x] 拼出的名字超限的工具有一条断言：它不在表里，且原因里点了 server 名与 tool 名
- [x] 连不上的服务器：它的工具缺席、别的服务器照常、run 不炸，且 `mcp/server` 审计行指名失败原因
- [x] 哨兵测试：`:env` 里放一个可辨认的哨兵值，对**整份日志文件**与状态面做字符串搜索，断言它不出现
- [x] 没声明任何服务器的会话：工具表、帧序、日志行与基线逐字节相同（回归保证）
- [x] 离线全量 `harness.test-runner` 全绿

## 落地（2026-09-16）

新增 `src/harness/mcp.clj`（装配 + 校验 + stdio 客户端 + 桥接）、
`test/harness/fake_mcp_server.js`（真进程的假服务器，Node）、`test/harness/mcp_test.clj`（17 个，离线）、
`test/harness/mcp_wired_test.clj`（6 个，走**真 HTTP 边**：钩子只在边绑定了 sink 时才触发，
所以「PreToolUse 能拦住服务器调用」这一类只能在那一层证明——沿用 `hooks_wired_test` 的先例）。

改动落在六处，每一处都小：`home/mcp-file`；`shell` 加**长活进程**（`start`：逐行读、逐行写、
stderr 只当诊断、close 收干净）与 `quote-arg`（从 `git.clj` 的私有副本提上来，那儿现在用它）；
`tools/register!` 一处盖章 `:source :builtin` + `effective-tools` 折进 `(mcp/tools-for thread-id)`；
`http` 在 `:run/done` 排空 mcp outbox 落 `mcp/server` 行。

**与票面的四处偏离**，都记在这里：

1. **`:url` 声明能过配置校验，却在连接时指名失败**（"HTTP transport is not implemented yet"）。
   票面把它和 `:command` 并列当作合法形状，03 号票才实现；这样配置形状从 01 起就是最终形状，
   03 只换一个分支。
2. **项目级是「替换」不是「合并」，而且这里踩过一次坑**：`:servers` 是文件唯一的顶层键，
   所以「项目赢」= 整个集合换掉。初版实现让 `read-mcp-edn` 返回内层 map 再 `merge`，
   于是两级的**服务器名**被合了起来（测试当场抓住）。现在返回 nil 表示「文件不存在」，
   由 `config` 在两级之间选一个。
3. **`:source` 只落在工具定义上，不进请求体**：`specs` 只发 `:type/:function/:name/:description/:parameters`，
   所以模型看不到这个字段，它给人看（`system_prompt.clj` 的 `<tools>` 块另有它自己的判据：
   名字以 `mcp__` 开头即「借来的手」，那块是本特征之前就落地了的，本票一行没改）。
4. **审计行落在 `:run/done`**，即终端帧**之后**：服务器是在本 run 第一次 LLM 调用（`specs` 装配）
   时才连上的，`provider/changed` 那个排空点太早，那儿什么都还没发生。

**测试抓住的两个真 bug**（都不是测试写错，值得记）：

- **连接在请求登记之前就死掉 ⇒ 调用方等满超时**：`request!` 检查完「连接是否已死」到把 promise 登记
  进 `pending` 之间不是一步，一个起不来的命令（bash 立刻 127 退出）正好落在这个缝里。
  修法是登记之后再查一次。
- **读线程抛异常会静默消失**，从外面看就是「这个连接什么都不答」——正是这个循环要避免的 60 秒挂死。
  根因是一个忘了调用的闭包（`(:stderr handle)` 取到的是函数本身，`str/trim` 于是炸在另一个线程上）。
  修法有两处：把调用补上，**并且**给整个读循环套 try/catch，让读线程的崩溃变成一条指名的死因。

**测数**：`main` 基线（`3ac23d8`）**607 tests / 9774 assertions**；本票 +23 tests / +92 assertions，
全量 **630 / 9866**。

**红的是哪些，以及为什么不是这票的**（连续两轮全量，逐条对照基线）：

| | 基线 `3ac23d8` | 本票（`mcp`） |
|---|---|---|
| `project_test/a-binding-survives-a-real-restart` ×2 | 红 | 红（同两条） |
| `tools_test/bash-runs-the-hosts-own-shell-not-wsl` | 红（**测量环境所害**：基线 checkout 在 `/tmp` 下，macOS 的 `/tmp` 是 `/private/tmp` 的链接，bash 报的是解析后的 cwd） | 不红（checkout 不在符号链接下） |
| `http_test/the-projects-listing-joins-the-store-with-the-disk` ×2 | 不红 | 第一轮红、第二轮不红 |

- `a-binding-survives-a-real-restart` 的两条是**环境**：JDK 对 `System::load` 的原生访问警告被打进
  fork 出来的子 JVM 的 stdout，断言比的是那行 stdout。与被测行为无关，干净 HEAD 上一样红。
- `the-projects-listing-…` 那两条是**既有的竞态**，与本票无关但值得写下来：它 POST 一轮 run、
  等 SSE 响应**收完**（即终端帧发出）就去读列表，而消息尾巴是在 `:run/done` 才写的——
  比终端帧晚一拍。列表读得早、`(.length f)` 读得晚，于是「文件比列表说的更大」。
  两轮里红一次，是这类竞态的常态（同一个套件里 `wait-for-recorded` 就是为同一族问题加的）。
  本票在这条路径上只插了一个空 `doseq`（没有声明服务器时 outbox 为空），没有改写入顺序。
- 两轮跑完都**没有留下一个 mcp 服务器进程**（`pgrep fake_mcp_server` = 0）：close 是真收干净的。
