# 03 — 远端服务器走 HTTP，与 stdio 分开声明

**What to build:** `mcp.edn` 里 `{:url "https://…"}` 的服务器与 `{:command …}` 的服务器**在同一个键空间里
并列**，用起来完全一样：握手、列工具、调用、超时、失败隔离、状态，一条都不少。区别只在 transport——
一个是本机子进程，一个是远端 HTTP；模型和工具缝都看不见这个区别。

**Blocked by:** 01

**Status:** ready-for-agent

## 决策

- **`{:command ..}` 与 `{:url ..}` 二选一，两个都给或都不给都指名失败**（01 已定），所以 transport 是
  声明**读出来的**，不是一个额外的字段：有 `:command` 就是 stdio，有 `:url` 就是 HTTP。
- **transport 不可混用，且这一点要写进代码而不是只写在文档里**：一条 http 声明**不会**被当成命令去 spawn，
  一条 stdio 声明**不会**被当成 URL 去 fetch。两种声明最终都收敛成一个 `MCP 连接` 接口（`request` /
  `close` / 状态），桥接层（工具表装配、`tools/call`、超时、结果拼接）**一个字都不分行**——这一票的验收
  就是「01 的每条断言在 http 上照样成立」。
- **流的形态是最小可用的那一种。** 请求 = `POST` 一个 JSON-RPC 消息（`Accept: application/json,
  text/event-stream`）；响应体是 JSON 就按 JSON 读，是 `text/event-stream` 就把 `data:` 行里的 JSON-RPC
  消息读出来、直到读到这条请求的 `id` 的应答。服务端回了 `Mcp-Session-Id` 头就**原样带在后续请求上**，
  没回就不带——它是服务器的事，不是我们的会话状态。
- **不 spawn 任何东西，所以也没有环境要传。** stdio 那条规矩（`:env` 的值永不入日志/端点）在 HTTP 上
  没有对应物；**本票刻意不带 `:headers`**，远端鉴权是后续的事。真要加时，值必须走同一条
  「永不入日志、永不入端点响应」的纪律——现在是**明确不做**，不是忘了。
- **超时与失败隔离沿用 02**：`connect`/`request` 都用 02 落下的那条超时纪律；连不上、HTTP 4xx/5xx、
  响应不是有效的 JSON-RPC 应答，**都只让这个服务器的工具缺席**，原因指名（server 名 + URL + HTTP 状态或
  解析到的那一行），落 `mcp/server` 审计行。一个远端服务器挂了和本机进程挂了，对 run 是一回事。
- **缓存与重连的身份里 URL 是身份的一部分**（02 定的是「项目身份 × server 名 × 声明形状」）——改了 URL
  就是另一条连接，不是复用。

## 验收

- [ ] `{:url ..}` 的服务器被连上、`tools/list` 的工具进表，模型经真 AG-UI run 能调用它并拿到文本结果
- [ ] 01 的**同一套缝级断言**在 http 服务器上再跑一遍：审批 park/resume、`PreToolUse` 阻断、
      `session-disable!`、`PermissionRequest` 代答——断言的是同一个执行缝，所以断言本身应当只是换了个 server 名
- [ ] `Mcp-Session-Id` 给了就带、没给就不带（两条断言，用假 HTTP 服务器记录收到的头）
- [ ] 响应是 `text/event-stream` 时也能拿到应答（假服务器两种响应形态各一条用例）
- [ ] 连不上（连接被拒）与 HTTP 500：工具缺席、别的服务器照常、run 不炸、`mcp/server` 行指名 URL 与状态
- [ ] 声明的形状校验：`{:command .. :url ..}` 同时给 → 指名失败；http 声明**不会**掉进 stdio 分支
      （反向同理）——两条断言
- [ ] 超时对 HTTP 同样生效（假服务器挂住不回话）
- [ ] 离线全量 `harness.test-runner` 全绿
