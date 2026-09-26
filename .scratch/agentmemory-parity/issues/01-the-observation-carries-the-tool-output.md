# 01 — `:post-tool-use` 把已经在手里的 `result` 传给 hook

**What to build:** `PostToolUse` 这一行的 `:payload` 声明的是三件（`src/harness/kernel/hooks.clj:105`：
`:tool_name :tool_input :result`），而 emit 只传了前两件（`src/harness/kernel/tools.clj:894`）。
`result` **就在同一个 `let` 里绑着**（`tools.clj:875` 的 `[result err]`）——所以这是「忘了传」，
不是「拿不到」。补上它。

**Status:** ready-for-agent

## 为什么要紧

agentmemory 的 `post-tool-use.mjs` 把 `tool_response` 原样交给服务器，服务器用它拼观察的
`narrative` 与摘要。今天这条线是空的，于是**每条观察 = 工具名 + 参数，没有输出**——半条观察。
留档证据：`.scratch/agentmemory-parity/evidence/measurements.md` 第二节那条观察的 `narrative`
只有 `{:path "src/x.clj", :content "hi"}`。

## 决策

- **传 `(str result)`，就是模型看到的那一句**：同一条缝往下几行，模型拿到的正是
  `{:content (str result) :error false}`。让 hook 看到的「发生了什么」与 run 看到的**是同一句**，
  比让它们各自渲染一遍强——两遍渲染就是两个会漂移的方言。
- **引擎侧不截断。** 内建工具大多已经自己 `clip` 到 8000 字符（`src/harness/cap/tools.clj:66`），
  消费者也各自有上限（agentmemory 那边是 8000）。在缝里再加一道上限是第三种意见，而它挡不住
  真正大的那几家（bash 的输出走 jobs 那条线）。**这条要写进注释**，否则下一个人会以为是漏了。
- **失败不进这个字段。** `err` 的时候这条 emit 根本不发（`when-not err`），失败是
  `:post-tool-use-failure` 的事——见票 04。

## 验收

- [ ] 真跑一次工具调用：`~/.clj-harness/hooks.edn` 里 `:post-tool-use` 的声明，stdin 上那个 JSON
      **有 `result` 键**，内容是工具的输出
- [ ] 库里那条观察带上了输出：`curl -s "http://localhost:3111/agentmemory/observations?sessionId=<id>"`
      里 `narrative`（或 `toolOutput`）含工具输出，不再只有参数
- [ ] `test/harness/kernel/hooks/dispatch_test.clj` 补一条：fact 带 `:result` 时 wire 里有它，
      且**逐字**等于 `(str result)`（不是 `pr-str`、不是 JSON 编码过的字符串）
- [ ] **加一条静态守卫**（可选但值得）：会触发的点里，`hook/emit` 传的键要覆盖点声明的 `:payload`。
      今天用一次性的 grep 数出来是九个点全对、只有这一处漏；守卫能让它以后也全对
- [ ] 全量 `clojure -M:test -m harness.test-runner` 绿

## 落地提示

- `src/harness/kernel/tools.clj:875` 绑 `[result err]`，`:post-tool-use` 的 emit 在 894–895。
  这一票只动这一处 + 一条测试。
- 文档**不用改**：`docs/architecture/hooks.md` 的 payload 列写的就是 `:payload` 声明的那三件，
  这一票是让那句话变成真的。
