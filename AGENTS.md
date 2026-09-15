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

### 家目录必须隔离，不许碰真实环境

**测试不得读写真实的 `~/.clj-harness`、`~/AGENTS.md`、`~/.agents/skills`。**

- config root 指向临时目录：`CLJ_HARNESS_HOME`，Clojure 侧由 `harness.test-runner/isolate!`
  统一做掉（临时目录、`harness.home/*root-override*` 都归它），测试体内不要再自己设。
- OS home 指向另一个临时目录：`harness.home/*user-home-override*`。Clojure 侧归 `isolate!`，
  e2e 侧起服务时自动做（`harness.e2e-server`）。它和 root 是**平级的两个临时目录，不要嵌套**。
- 自己拉 JVM 的测试（fork 子进程、`ui/test/support/harness.ts`）要自己把两个都指过去：
  `CLJ_HARNESS_HOME` 给 root，`-Duser.home` 给 OS home。
- 改这两处用 `alter-var-root`，不要 `binding`——服务在别的线程上跑。
- 跑完删掉临时目录。

### 端口由 OS 分配，永不写死

- `with-server` / `with-declaring-server` / `with-resolved-config` 一律 `{:port 0}`，
  再把 `(:local-port (meta stop))` 绑到 `*port*`。
- `api-call` / `post-run` 默认读 `*port*`；测试要自己拼 URL 时也读它，不写字面量。
- 新增测试要起服务时用现成的 wrapper；需要自己安排 config/provider 时，让 wrapper 起服务
  （`with-resolved-config` 就是这么做的），不要在测试体内写 `(http/start! {:port 8080})`。
