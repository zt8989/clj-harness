# 边：`harness.http`

一个 http-kit 服务器，两条边共用一个 handler：流式的 **AG-UI 边**（`POST /`）与
普通的 JSON **管理边**（`/api/*`）。CORS 只放行 `http://localhost:5173`（那是契约，不是偏好）。

## AG-UI 边

`POST /` 收一个 `RunAgentInput`，以 SSE 回帧。两条 http-kit 的规矩必须同时成立：

- **status 与 headers 骑在第一次 `send!` 上**，不能先单独发一次 header；
- **最后一帧带 `close-after-send?`**——单独走一条 close 路径会丢掉缓冲里没冲出去的body。

body 是 **UTF-8 字节**（本机 JVM 默认 GBK，交字符串给 http-kit 等于对非 ASCII 掷硬币）。

**每次 run 一个 converter、一个 emitter。** converter（`ag_ui/outbound`）持有「哪条消息开着」的状态机，
逐事件重建它会把每条消息 id 重置、重复发 START 帧——AG-UI 客户端视为致命。

### bind 的 hook sink

这是**唯一**同时知道「这是哪个线程」和「审计行写哪」的地方，所以 run 作用域的 hook sink 在这里绑定：

```clojure
(binding [hook/*sink* {:thread-id .. :audit (fn [payload] (log! ..)) :run-id ..}] ...)
```

**没绑 = hook 不触发**，这是刻意的默认值：离线工具、replay、直接驱动内核的测试都没有审计写入者，
而一个没人记录的 hook 判定比没有 hook 更糟——它会**静默地**改变一次 run。

## 管理边：路由表

| 路由 | 动词 | 干什么 | 落审计行 |
|---|---|---|---|
| `/` | POST | AG-UI run（流式） | 下面那些 |
| `/api/model` | GET | 本会话服务的模型收什么、出什么、多大 | 无（只读） |
| `/api/project` | GET | 绑定目录（未绑定答 `null`） | 无 |
| `/api/project` | POST | 绑定 / 换绑 / 解绑（`dir: null`） | `project/bound` |
| `/api/project/pick` | POST | 开 OS 原生目录对话框，**不绑任何东西** | 无 |
| `/api/threads` | GET | 日志树的原始清单（诊断用） | 无 |
| `/api/threads/<stem>/rebuild` | POST | 重建对话交还客户端 | `session/rebuilt` |
| `/api/threads/<stem>/archive` | POST | 归档 / 取消归档（一个路由两个方向，body 说方向） | 无（日志必须一字节不动） |
| `/api/projects` | GET | 侧边栏的数据：每个项目 + 它的会话 | 无 |
| `/api/projects` | POST | 让一个目录成为项目（find-or-create） | 无 |
| `/api/projects/<canonical-path>/remove` | POST | 移除项目（= 解绑它的会话，不删日志） | 无 |

规矩三条：

- **审计行跟着「日志」走，不跟着「写入」走。** 会动日志的路由留痕（绑定搬日志、重建读日志）；
  **只改库里一行的路由一行都不写**——归档 / 取消归档、添加项目、移除项目都属此类。
  归档这条尤其是有意的：它必须让 jsonl **逐字节、逐 mtime 不动**，写一行审计就会毁掉
  「归档不是删除」的那条证明。所有 GET 都是只读，同样一行不写。
- **校验失败不留痕**，而且发生在任何写入之前——一条被拒的绑定不该在磁盘上留下半个痕迹。
- **审计行的 `runId` 为 `null`** 表示这件事发生在任何 run 之外（绑定、重建）。

路径匹配是两段式：先是精确串匹配（上表前几行），然后是**带动词的通用形状**
（`/api/<collection>/<stem>/<verb>`，跟着一张 verb → handler 的表；两个 collection 的动词都是**闭集**）。

那个 stem 是各 collection 给行起的名字：**thread 用会话 id**（它同时是日志的文件名 stem），
**project 用目录的 canonical 路径**（不是那个整数 id——canonical 路径才是这个边里项目在各处的身份）。
GET 打在这个形状上由这里答 405，而不是掉进 run 端点——那正是它从前会变成一个「body 根本不存在的 500」的原因。

`*directory-chooser*` 是测试缝：真实对话框要等人，测试里换 stub。
用 `alter-var-root` 而不是 `binding`，因为服务在**另一个线程**上跑（见 [client](client.md)）。

**归档为什么一个路由两个方向**：它是同一列的一次写入，两个方向只差一个布尔，两条路由就是两处会漂移的
机会。路径说动作，body 说方向。**重建与归档的差别也值得知道**：重建必须**找到**日志（它从日志里重建对话），
归档**不开文件**——那句话是会话的属性不是文件的属性，所以日志被手工挪走或删掉的会话照样能归档。

## jsonl 审计行

一个线程一个文件，写在**它项目的 workspace** 里。每行 `{ts, runId, kind, payload}`：

| kind | 何时 |
|---|---|
| `input` | 收到的 RunAgentInput，原样 |
| `event` | 发出的每个 AG-UI 帧 |
| `message` | LLM 真实看到/返回的 provider 形状消息，**逐字** |
| `tools/pre-execute` / `execute` / `post-execute` | 工具生命周期三相，按 `toolCallId` 键控，**不上 wire** |
| `approval/decided` | 人对一个 park 调用的答复 |
| `provider/init` | 每 thread 恰好一行，首次 run |
| `provider/changed` | 会话中 provider 档变更，before → after |
| `project/bound` | 绑定变更，before → after（可读成目录时间线） |
| `session/rebuilt` | 重建动作，落**被重建的那份日志**上 |
| `hook/<Point>` | 一次 hook 触发（`hook/PostToolUse`…） |

几条支撑性的事实：

- **append 由一把锁串起来。** 大多数写入来自 run 的单个消费线程，但 hook 在它自己的点上触发
  （`PostToolUse` 跑在那次调用的线程上），两条线可能同时在飞。半行不是更短的记录，是一个坏掉的文件。
- **`provider/init` 记的是「解析结果」，不是事后重算的结果。** 目录会变（base-url 改了、model 表更新），
  拿今天的目录去重算旧日志，读出来的就是今天的答案而不是那天的。
- **api-key 只以 `:api-key :stripped` 出现**——是「被剥掉了」这个事实，永不出现值。
- 读日志的代码只认 `input` / `event` 两种行；其余是审计轨迹，不是对话的一部分。

## 不在生产路由里的东西

**服务端不为测试长路由。** `npm test` 要控制模型说什么，用的是**文件**：`dev/harness/e2e_server.clj`
在遇到**新的 threadId** 时重读脚本文件。生产边一个测试专用路由都没有。
