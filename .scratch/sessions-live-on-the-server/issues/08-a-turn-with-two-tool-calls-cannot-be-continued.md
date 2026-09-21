Status: needs-triage

# 一轮里调了两个工具，这场会话就再也发不出去

发现于把 `main` 合进 `brand-header` 的过程（2026-09-21），**不是那次合并带出来的**：
`src/harness/kernel/llm.clj`、`src/harness/kernel/loop.clj`、`src/harness/edge/sessions.clj`
在合并树里与 `main` 逐字节相同（`git diff main -- <那三份>` 0 行），下面这条路是这张 feature
自己新开的。

## 症状

某个会话的**一轮**里，模型在**同一条 provider 消息**里发出两个工具调用。那一轮本身跑得好好的
（两个工具都回来、回答也画出来），但**下一轮发不出去**，浏览器上是一条 RUN_ERROR：

    RUN_ERROR: this run's history leaves 1 tool call unanswered and no parked approval
    in this process can answer it (c1)

一次只调一个工具不触发。

## 怎么复现

    clojure -M:test .scratch/sessions-live-on-the-server/evidence/08-two-tool-calls-repro.clj

从仓库根跑，有 bug 时 **exit 1**、修好后 exit 0。脚本自己隔离配置根（`runner/isolate!`），不碰
`~/.clj-harness`；它不依赖任何测试命名空间的 fixture，只用一个 scripted provider：一轮里回一条
带 `c1`/`c2` 两个工具调用的 assistant 消息。2026-09-21 在合并后的 `main`（`3df6f48`）上跑，
它打印出来的是：

    :run1-terminal RUN_FINISHED
    :session-shape [["user" "u1" []]
                    ["assistant" "…-m0" ["c1"]]
                    ["assistant" "…-m1" ["c2"]]
                    ["tool" "…-t2" []] ["tool" "…-t3" []]
                    ["assistant" "…-m4" []]]
    :run2-terminal RUN_ERROR
    :run2-message this run's history leaves 1 tool call unanswered and no parked approval
                  in this process can answer it (c1): an OpenAI-shaped vendor refuses a
                  request whose assistant message with tool_calls is not followed by a
                  tool message for each 'tool_call_id', so the run was refused before the
                  provider was called.
    :VERDICT BUG PRESENT -- a second action on a session that used two tool calls is refused

两个工具调用**被拆成了两条 assistant 消息**，工具结果成对跟在后面 —— 这就是病根。

## 原因（已经追到）

- `harness.edge.ag_ui/outbound` 每个工具调用发一条 `TOOL_CALL_START`/`TEXT_MESSAGE`，所以会话自己
  折出来的历史里，一轮里的两个调用落在**两条** assistant 消息上。
- `harness.kernel.llm/unanswered-tool-calls` 把厂商那条规矩实现成**相邻**判定：工具消息必须紧跟在
  「点名了它的那条 assistant 消息」后面。`c1` 后面紧挨着的是另一条 assistant，于是 `c1` 被判成
  无人回答。
- `harness.kernel.loop/drive!` 在下一轮开跑之前发现历史里有无主的工具调用，连 provider 都没调用就
  直接 RUN_ERROR。

在这条路出现之前，历史是**客户端传上来**的，客户端给的 assistant 消息本来就同时带着那一轮的
全部工具调用，相邻成立 —— 所以这个撞车是「会话在服务端继续、历史由服务端折出来」新引入的。

## 要决定的事（选一条）

- **A（倾向这条）**：让 fold / outbound 保住「一条 assistant 消息带它那一轮的全部工具调用」的形状，
  或者让 `unanswered-tool-calls` 认「同一条 assistant 里并列的多个调用」。内核那条规矩不动，历史
  形状与 provider 回来的东西一致，重放/重建出来的会话也与当初一致。
- **B**：放宽内核的判定（不再要求相邻，改成「后面任意位置存在对应结果」）。改动最小，但那条规矩是
  照着厂商的消息形状写的，放宽之后可能放过真的漏答。

## 为什么没在合并里修

合并提交只做合并：它不该同时改内核的工具调用规矩。先立票，修完把上面那条命令的 exit code 当判据。
