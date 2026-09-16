# 02 — 轨迹的读侧与端点

**What to build:** 把一条会话的**记录**按轮折回来，并从管理边吐出去。本票只到边为止，没有界面。

从用户视角：`GET /api/threads/<stem>/trajectory` 回来的就是「模型每一轮到底看到了什么」——
包括对话页签里永远看不到的那两样（system 消息、注入的上下文），以及每次工具调用的参数与结果。

**Blocked by:** 01（词：轮 / 模型调用——折法就是按这两个单位切的）

**Status:** ready-for-agent

## 验收

- [ ] 读侧住**新 ns `harness.trajectory`**，与 `harness.frames` / `harness.replay` 并排（日志的读侧）。
      它**不学着知道 root 在哪**：拿的是目录/行，与 `replay` 同一立场，docstring 里写明。
- [ ] 折法是**对记录序列的纯函数**（不吃文件、不吃服务）：能用 `evals_test` 那种 `record` 构造器
      手搓一小段记录来断言——这是本票可测性的关键，别把它写成只有起服务才能跑的东西。
- [ ] **轮**：`input` 的客户端消息里出现**新的** `role: "user"`（id 不在上一个 `input` 里）才开新的一轮。
      悬置恢复那种「同一个 `runId` 的第二个 `input`、没有新用户消息」**归给当前轮**，不新开一轮。
- [ ] **提交侧与返回侧分清**：一个 run 的提交侧 = 它的 `input` 行与它第一个 `event` 行之间的 `message` 行；
      返回侧 = 它的终帧**之后**的那些（`harness.replay` 的 docstring 已写明返回的尾巴落在终帧之后）。
- [ ] 提交侧的行**按序**与「本 run `input.messages` 去掉 `role: "reasoning"` 的那些」对位
      （`harness.ag-ui/inbound` 把推理并进助手消息，所以请求体里没有单独的推理条）；
      **前面**多出来的（插在 system 之后的指令文件与技能清单）与**末尾**多出来的（本 run 的 context）
      标成 `context`，`source` 分别是 `"opening"` / `"run"`。
- [ ] 提交侧只贡献两种条：**本轮新增的用户消息**与**上下文**。
      历史回显（带 id、已在别的轮里出现过）与「本轮自己的输出被重发」**一律不列**——
      否则悬置恢复那一轮会把同一批工具调用列两遍。
- [ ] 返回侧的条按序取 assistant / tool；同一个 `toolCallId` 只出一条，
      `outcome` 取 `tools/pre-execute` 那条的，另给 `executed`（有没有 `tools/execute`：
      被否决的调用**没有**这一条，别把它画成「执行了 0 秒」）。
- [ ] **system 条只在第一轮出现**（标成初始），此后**只在字节变了的那一轮**再出现——
      这是为在办的 `system-prompt-blocks` 写的：同一会话的 system 消息会逐轮不同，变了必须看得见。
- [ ] **顺序照记录，不照参考图**：开场块本来就在用户消息之前、技能正文插在要它的那条之后，如实排。
      参考图里「用户在前、上下文在后」是另一个 harness 的排法，不复制。
- [ ] 一个**真的跑过**的会话折出来的顺序是：`系统` → `上下文`（若有）→ `用户` → 输出（助手/工具交替）。
- [ ] **容忍半截的 run**：最后一个 run 没有终帧时**不抛**（`replay/ensure-complete!` 会抛，那是重建的规矩，
      轨迹恰恰最常在看正在跑的会话时打开），照实返回并在 `incomplete` 里标出来。
- [ ] 端点：`GET /api/threads/<stem>/trajectory`。这是**这条形状上的第一个 GET**
      （今天 `thread-verbs` 的每一个动词都是 POST，理由写在 dispatch 里：每一个都有副作用）——
      集合照旧是**闭集**，往里加 `"trajectory"`，并改掉那句「每一个都是 POST」以及 405 的立场：
      `GET .../rebuild` 仍然 405，只是不再覆盖全部动词。词表与注释一起改，不留一句过期的话。
- [ ] 端点**不新造寻址方式**：用其他 stem 路由同一条缝把 stem 落到目录（`replay/locate` 那一套），
      找不到就是它自己的那个拒绝，不另发明一个。
- [ ] 返回形状（本票定下，后面三张票往上加字段）：

      {"threadId": "<stem>",
       "incomplete": false,
       "turns": [
         {"index": 1,
          "items": [
            {"kind": "system",    "text": "…", "initial": true},
            {"kind": "context",   "text": "…", "source": "opening"},
            {"kind": "user",      "text": "…", "id": "3146…"},
            {"kind": "assistant", "text": "…", "reasoning": "…"},
            {"kind": "tool",      "toolCallId": "call-…", "name": "read",
             "argsText": "{\"path\": …}", "result": "…", "error": null,
             "outcome": "pass", "executed": true}]}]}

- [ ] 没有日志 / 会话存在但没跑过（`harness.replay` 的 docstring 里那种只有 `project/bound` 的日志）：
      返回空 `turns`，**不报错**（那是一个全新会话的诚实答案，不是截断）。
- [ ] **清单是闭的，读侧是它的账**：spec 的「记录清单」列出视图每一格的来源，只有两种——
      记录里已有，或本特征补的那两行（`model/start` / `model/end`，04 与 05 补）。
      本票把**今天已有的那些行**读出来；要画一格而清单里没有它，**先补记录再画**
      ——不许在客户端算、不许拿别的行凑、不许用今天的值冒充当时的。
      本票交付时，读侧返回的每一个字段都要指得到清单里的某一行（这条同时是评审时的检查表）。
- [ ] 四样东西**故意留在派生**（轮边界、轮序号、调用序号、等待段）：它们记一份就是同一件事实的第二份，
      两份会漂（`CONTEXT.md` 的「库不镜像日志」）。所以它们必须**能用手搓的记录断言**，
      而不是「跑一遍看看像不像」。
- [ ] 测试落在**新 `test/harness/trajectory_test.clj`**：折法用手搓记录断言（含悬置恢复、被否决的调用、
      半截的 run 三种情形各一条），端点用现成的 `with-server` + `api-call` 走真 HTTP
      （端口由 OS 分配，不写死——见 `AGENTS.md`）。测试文件要注册进 `harness.test-runner`。
- [ ] **协议那侧一个字都没动**：本票不加任何事件、不加任何帧；`harness.frames` 折出来的对话逐字节不变。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（基线以落地当次为准，报数带上分支与提交）。
