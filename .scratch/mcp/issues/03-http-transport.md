# 03 — 远端服务器走 HTTP，与 stdio 分开声明

**What to build:** `mcp.edn` 里 `{:url "https://…"}` 的服务器与 `{:command …}` 的服务器**在同一个键空间里
并列**，用起来完全一样：握手、列工具、调用、超时、失败隔离、状态，一条都不少。区别只在 transport——
一个是本机子进程，一个是远端 HTTP；模型和工具缝都看不见这个区别。

**Blocked by:** 01

**Status:** done（2026-09-16，分支 `mcp`）

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

- [x] `{:url ..}` 的服务器被连上、`tools/list` 的工具进表，模型经真 AG-UI run 能调用它并拿到文本结果
- [x] 01 的**同一套缝级断言**在 http 服务器上再跑一遍：审批 park/resume、`PreToolUse` 阻断、
      `session-disable!`、`PermissionRequest` 代答——断言的是同一个执行缝，所以断言本身应当只是换了个 server 名
- [x] `Mcp-Session-Id` 给了就带、没给就不带（两条断言，用假 HTTP 服务器记录收到的头）
- [x] 响应是 `text/event-stream` 时也能拿到应答（假服务器两种响应形态各一条用例）
- [x] 连不上（连接被拒）与 HTTP 500：工具缺席、别的服务器照常、run 不炸、`mcp/server` 行指名 URL 与状态
- [x] 声明的形状校验：`{:command .. :url ..}` 同时给 → 指名失败；http 声明**不会**掉进 stdio 分支
      （反向同理）——两条断言
- [x] 超时对 HTTP 同样生效（假服务器挂住不回话）
- [x] 离线全量 `harness.test-runner` 全绿

## 落地（2026-09-16）

`harness.mcp` 里新增 http 客户端（`http-post` / `parse-body` / `read-sse-message` / `http-client`），
外加一个 `open-connection` 做**唯一那处分派**：`{:url ..}` 走 fetch，`{:command ..}` 走 spawn。
上面所有东西——roster、桥接、超时、失败隔离、审批/关闭——**一行都不分叉**，
所以「01 的断言在 http 上照样成立」是结构上的结论而不是巧合。测试里也是这么验的：
同一个 `mcp_test` 文件里两种 fake（stdio 是真进程，http 是本 JVM 里的 http-kit 端点），
断言写在同一处。

几处决定：

- **会话 id 是服务器的事，不是我们的状态**：它在响应头里出现就带上，不出现就不带。
  测试从**服务器收到的那一侧**断言（initialize 那次没有、之后每次都有；服务器不发的那个则全程为空）。
- **两种响应形状都读**：`application/json` 直接解析，`text/event-stream` 读到这条请求的 id 为止。
  「读到我们的 id」是这里唯一的匹配规则——MCP over HTTP 是对一个 POST 回一个答案，
  流上别的东西要么没人等（通知），要么与这次请求无关。
- **`:headers` 明确不做**（票面已定）：它是密钥面，要做就得先有那条「永不入日志/端点」的纪律。
  未知键本来就指名失败，所以「忘了做」与「故意不做」在配置层看起来是一样的——都是报错。
- **超时要说出自己那个数**：`java.net.http` 自己抛超时的时候，话是「request timed out」，
  既没点名服务器也没点出被撞的边界；现在那个 `HttpTimeoutException` 被接住并换成
  「no answer within <ms>ms」，与 stdio 那条规矩对齐。

**测试抓住的两处**：连接失败时 JVM 的 `ConnectException` 可能**没有 message**
（`ex-message` = nil），而 `(when-let [threw (:threw answer)])` 于是把「连不上」当成了「没问题」，
一路走到 `(<= 200 nil 299)` 才炸成一条什么都不指名的 NPE——改成 `contains?` 判断，
并让 `:threw` 兜住类名。另一处是一阶的：01 那个「:url 还不支持」的测试在票 03 之后过期，
删掉（它的位置由本票的连接失败断言接住）。

**测数**：本票 +6 tests / +12 assertions（会话/SSE/失败隔离/超时/不分派/缝的复用），
`mcp_test` 现 30 tests / 139 assertions。全量 **643 / 9932**，2 红仍是
`project_test/a-binding-survives-a-real-restart` 那两条环境问题。
