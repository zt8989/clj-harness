# AGENTS.md

This repo is configured for Matt Pocock's engineering skills (`to-tickets`, `triage`, `to-spec`, `domain-modeling`, `wayfinder`).

## Agent skills

### Issue tracker

Issues and specs live as local markdown under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, each label string equal to its name. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at repo root + `docs/adr/`. See `docs/agents/domain.md`.

## 测试

### 端口由 OS 分配，永不写死

**测试不许写死端口。** `with-server` / `with-declaring-server` / `with-resolved-config` 用 `{:port 0}`
让 OS 分配，再把 `(:local-port (meta stop))` 绑到 `*port*`；`api-call` / `post-run` 默认读它，
测试要自己拼 URL 时也读它，而不是写一个字面量。

理由不是洁癖：写死的端口要求「这台机器上此刻只有我在跑这套测试」。而实际不是——开发者的会话
就在旁边、上一张票留下的 e2e server 还开着、另一个 worktree 同时在跑同一套 suite。撞上时你收到的是
`java.net.BindException: Address already in use`，报在一个**跟肇事者毫无关系**的用例上
（一度是 `answers-the-cors-preflight` 要的 8099），而那个用例的代码与被测的东西都没错。

新增一个测试要起服务时，用现成的 wrapper；确实需要自己安排 config/provider 时，让 wrapper 起服务
（`with-resolved-config` 就是这么做的）——不要在测试体内写 `(http/start! {:port 8080})`。
