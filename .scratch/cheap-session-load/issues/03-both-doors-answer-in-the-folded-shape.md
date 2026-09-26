# 03 — 两扇门改用投影：侧栏点开与刷新回来的载荷不再带整场对话

**做什么**

把票 01 的投影接到**打开一场会话的那两条路**上，让载荷从「整场对话，推理与工具调用都在」变成
「折好的轮 + 最后一轮原样」。

两条路：

1. **侧栏点开** → `POST /api/threads/<stem>/rebuild`（`rebuild-post`）。它今天自己写着答案形状是
   *"the AG-UI message list (seed + every recorded frame, reasoning and tool calls included)"*。
   改成投影的形状。
2. **刷新回来** → `GET …/sofar`（`sofar-get`）与 `GET …/page`（尾页）。settled 的会话上 `sofar` 答的
   就是 `rebuild` 那份消息表，所以两扇门一起改。

**客户端这一半是同一票的另一面**：`thread.aui.tsx` 今天用 `useStepFold`（`fold === "step" && "hidden"`）
把旧轮**藏起来**——那是「数据已经到了、只是不画」。改成：**服务端说折了就是折了**，客户端画服务端给的
那张卡（`data` part，`ui/src/lib/injections.ts` 已经在画同类的东西），步骤根本不在手里。

**Blocked by:** 01

**Status:** ready-for-agent

- [ ] `rebuild` / `sofar` / `page` 三条答的都是投影的形状：折好的轮 + **最后一轮原样**。
      `test/harness/edge/http_test.clj` 钉三条门的形状一致（同一条会话、逐条比）。
- [ ] **最后一轮永远原样**，包括 `state = :running` 的那一格：一个还在跑的会话拿到的尾页里，
      正在被写的那一轮一步不少。
- [ ] **`rebuild` 的写没丢**：一个断掉的 run 仍先被 `close-off-open-run!` 收口，投影不吃掉这条写
      （`replay_test` / `http_test` 里既有的那条断口用例保持绿）。
- [ ] 客户端不再对「服务端已经折好的轮」做折叠：`thread.aui.tsx` 的那条 `hidden` 判据换成看服务端的卡；
      `ui/test/suites/turns.ts`、`turn.ts` 里不适用于新形状的用例跟着改，**不是删掉了事**。
- [ ] 客户端**仍会折**它自己看着长出来的那些轮（它们是原样来的，见规格决策 8）；这两半的规则由共享用例表
      钉住，`ui/test/suites/` 与新加的 Clojure 用例读同一份。
- [ ] 现场数字进 `evidence/`：同一场 65 MB 会话，`rebuild` 的载荷字节数前后各多少、从点击到画出来多久。
- [ ] ADR 0004 的边界里那句「不改 `WindowFrame` 的形状」被本票取代——**在那里写明**（或新开一条 ADR）。
