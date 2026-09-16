# MCP：外部服务器作为工具来源

一个 MCP 服务器是一个**外部的程序**，它声明自己会做哪些事，而 harness 把它们变成工具表里的行。
这一页是现状：怎么声明、什么时候连、一次调用经过什么、出问题是什么样子。

## 一句话

**桥出来的工具是一条普通的行。** 它进同一张表、过同一个执行缝、走同一套审批与关闭语义、落同一组
审计行。执行缝读的是**表**，它不关心谁把这一行放进去的——这是整个设计的支点，也是为什么
「MCP 工具能不能被审批拦住」这类问题不需要 MCP 专用代码来回答。

## 声明：两级，两个文件

```edn
;; ~/.clj-harness/mcp.edn（用户级）与 <project>/.harness/mcp.edn（项目级）
{:servers {"workshop" {:command "node" :args ["/abs/path/server.js"]
                       :env {"SOME_TOKEN" ".."} :timeout 60000}
           "depot"    {:url "https://example.com/mcp"}}}
```

- **`:command` 与 `:url` 二选一**，两个都给或都不给都指名失败。所以 transport 是声明**读出来的**，
  不是一个额外的字段——**URL 永远不会被当成命令去 spawn**，反之亦然。
- **项目级整表替换用户级的 `:servers`**（`:servers` 是文件唯一的顶层键）。与 `hooks.edn` 逐点替换、
  `harness.edn` 逐键替换同一条纪律。
- **server 名必须匹配 `^[A-Za-z0-9_-]+$` 且不含 `__`**：`mcp__<server>__<tool>` 要能唯一反解回一个服务器，
  否则两个服务器的同名工具会撞进一个名字里，而模型看不见「撞了」。
- **`:env` 的值永不入任何日志行、永不进任何端点响应。** 与 api-key 同一条纪律：账本说一个服务器
  **被配置了**，从不说**配的是什么**。`GET /api/mcp` 与 `mcp/server` 审计行都不带它。
- 每次现读（config.edn 纪律）：改 `mcp.edn` 下一次用它生效，不重启。

## 连接

**一个服务器一个长活进程**（stdio）或一条 HTTP 会话，第一次装配这个会话的工具表时建立。
连接的身份是**（项目的 canonical 身份 × server 名 × 声明的形状）**：

- **同一个项目的多个会话共享一个服务器进程**（键是项目的**身份**，所以一个目录的两种写法共享一个）；
- **声明变了就是另一条连接**——文件是现读的，连接不能装作文件没改过；
- **进程死了、或它写了不是 JSON 的一行** → 下一次用它**重连**（重新 spawn、重新握手、**重新列工具清单**），
  不是「复活」一段坏掉的对话；
- **声明被删了** → 工具离开表，进程被**收掉**（`reap!`：工具离开是白来的，进程不会自己消失）。

**收尾杀的是一棵树**，不是那个子进程：我们握着的是 shell，而命令常是包一层的东西（`npx` 是常态，
它自己再起真正的服务器）。先收集子孙（父一死它们就被重新挂到 init），再让直接子进程停下，
还站着的直接杀掉。

**超时按服务器配**（`:timeout`，默认 60s）。超时之后**连接被丢掉**：一条还在飞的请求不会乖乖消失，
它迟早会答，而它之后每条消息都会错位一格。回到模型的那句话点名 server、tool 与毫秒数。

## 一次调用

```
模型：mcp__workshop__echo {text: "hi"}
  → 执行缝（同一个）→ 桥接的 :run
  → tools/call 到服务器
  → content 里的 text 片段按序拼接 = 这次调用的结果
服务器回 isError / 报错 / 超时 / 连不上
  → 抛 → 执行缝把它变成这次调用的【错误结果】，run 继续
```

**工具名**：`mcp__<server>__<tool>`，拼出来超长（provider 的 `function.name` 上限 64）或字符集不合的
工具**指名跳过**，绝不截断——截断会把两个工具变成一个名字。被跳过的名字与理由出现在账本里。

**:parameters 是服务器的 `inputSchema` 原样**（它本来就是 JSON Schema），**:required 从 schema 里的
`required` 派生**——这一份是执行缝判 `missing-args` 用的，两者不一致就会对模型说「你少给了」而它明明给了。

## 反过来问人：elicitation

服务器可以在**一次调用中途**问用户一件事（`elicitation/create`，通常是一张表单）。这时：

- 这次调用**悬置**（run 以 interrupt 收尾，reason 是 `"elicitation"`），**不是**「先调完再补一条消息」；
- interrupt 上只有 id / reason / message（问题本身）——**schema 不在 wire 上**，因为 AG-UI 的 interrupt
  是严格形状、多一个键客户端就拒。表单的**形状**从 `GET /api/elicitation?interruptId=..` 取；
- 答复走**既有的 `resume`**：`resolved` + 表单值 → `{action: "accept", content: {...}}`；
  `cancelled` + `{action: "cancel"|"decline"}` → 对应的动作。一个新通道都没加；
- **调用会被重新发起**，所以服务器会**再问一次**——那第二次就是被记录的答复所回答的那次。这是这套设计的
  价钱（服务器在 elicitation 之前做过的活会重做一遍），写在这里而不是等人踩到；
- **问题有截止时间**（该服务器的 `:timeout`）：服务器正拿着一个请求等我们回话，过了点它就不在问了。
  人回来得太晚**什么都不回传**——编一个答复是把谎话写进协议——连接丢掉，这次调用得到指名超时的错误结果。
- **HTTP transport 上的 elicitation 没有接线**：MCP over HTTP 的服务器请求会出现在响应的那一条流上，
  而这个客户端为一次 POST 只读一条流。它那边会以这次请求的指名超时收场，不是 park。

## 管理边

| 路由 | 干什么 | 落审计行 |
|---|---|---|
| `GET /api/mcp?threadId=..` | 账本：每个声明过的服务器、transport、状态、原因、工具清单 | 无（只读） |
| `POST /api/mcp {threadId, server, enabled}` | 本**会话**启停一个服务器 | `mcp/server`，带 `disabled`，runId null |
| `GET /api/elicitation?interruptId=..` | 某个悬置问题问的是什么、要填什么 | 无（只读） |

**账本的状态词**：`connected` / `failed`（**带原因**）/ `disabled`（本会话关掉的）/ `idle`（声明了还没用过）。
**失败与关掉是两件事，界面上不合并**：failed 的下一次用它会自己重连，disabled 的不会，直到人打开。

**关闭不是隐藏**（与工具表、hook 表同一句话）：关掉一个服务器会收掉它的进程，但它的**工具仍在表里**
（模型仍看得见），调用被执行缝以 `:disabled` 拒掉，拒绝的话指名**哪个服务器**被关了以及怎么打开。
工具清单取自它上一次报出的那份；一个从未连过就被关掉的服务器没有清单，于是没有工具——
账本说它是 `disabled`，而不是假装它没被声明过。

## 审计行

`mcp/server` 一行，写入者仍是**边**（`harness.http`）：mcp 层只登记事实（一个 outbox，抄
`providers/take-provider-changes!` 的先例），边在 `:run/done` 排空它。**只有变化才落行**——
装配在每次 LLM 请求的路上都会发生，每次都写就变成噪音而不是记录。它落在终端帧**之后**，
因为服务器是在本 run 第一次装配工具表时才连上的。

## 界面

设置页（侧边栏底部）里的 **MCP servers** 一块：每个服务器一行（名字、transport、状态、失败原因、
工具清单、被跳过的名字），每行一个开关。**它是一个快照**，刷新键与打开设置时重取。

## 代码在哪

| 命名空间 | 是什么 |
|---|---|
| `harness.mcp` | 声明装配与校验、连接的生命周期、两种 transport、工具桥接、会话级启停、账本 |
| `harness.parked` | 悬置调用的登记表：park / decide / take，以及 `suspend!`（停下来并抛出） |
| `harness.shell` | 长活进程的 spawn 与**按树收尾**（`start` / `kill-tree!`） |
| `harness.tools` | 一处盖章 `:source :builtin`；`effective-tools` 折进外部来源；`session-disabled?` 一并问服务器开关 |
| `harness.http` | 三条管理路由；`mcp/server` 行的写入者 |
| `ui/src/components/mcp-panel.tsx` | 面板；`ui/src/lib/elicitation.ts` 是表单的纯规则 |
