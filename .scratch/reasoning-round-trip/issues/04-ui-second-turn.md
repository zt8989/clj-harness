# 04 — UI 套件补「第二回合」：推理确实被送回去了

**What to build:** 今天 UI 套件里唯一和推理有关的用例（`ui/test/suites/client.ts` 的
`reasoning-and-tool-calls-survive-the-wire`）只发**一次** `runAgent`，然后看客户端消息里有没有那条
`reasoning` 消息。**它从不发第二个回合**——而「推理在第二回合被送回给服务端」正是这次 400 的现场。
这一票补上那条用例：

- 后端起**严格思考模式**（01 那一档，经 `dev/harness/e2e_server.clj` 的脚本文件选中）；
- 同一 thread 跑**两个回合**（第一回合模型调工具，第二回合带上一轮的历史）；
- 断言第二回合**没有被 400**，并且服务端收到的历史里那条 assistant 消息带着
  `reasoning_content`。

从用户视角：**客户端那条路（`@assistant-ui/react-ag-ui` 把推理拆成 `reasoning` 角色消息、
服务端再折回去）从此有套件钉住**——它今天是对的，但没有任何用例会在它坏掉时报警。

**Blocked by:** 01（严格档要在 e2e server 里可选）

**Status:** ready-for-agent

## 验收

- [ ] e2e server 能起严格档，并且**只在脚本文件说了要的时候**才严格（既有套件一行不改）。
- [ ] 新用例在两个回合之间**真的重发了历史**（`@ag-ui/client` 的行为：新回合把整条历史送回；
      用例要能证明这一点，而不是只发两条独立的消息）。
- [ ] 断言的是**服务端侧的事实**：第二回合服务端收到的 assistant 消息带 `reasoning_content`
      （不是只看客户端内存里有没有那条推理消息——那是今天已有的那条用例干的）。
- [ ] **证明它会红**：临时把客户端那一步（`reasoning` 角色消息）去掉，这条用例必须变红
      （手验一次，把这一步与结果写进落地记录）；恢复后转绿。
- [ ] `cd ui && npm test` 的用例总数按本票的增量更新（`EXPECTED_CASES` 是契约，别忘），
      类型检查与构建 0 error。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单逐条不变。
