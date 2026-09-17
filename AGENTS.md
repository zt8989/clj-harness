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

- 进程级两个 override 由 `harness.test-runner/isolate!` 在开跑前指到临时目录：
  `harness.home/*root-override*`（config root）与 `*user-home-override*`（OS home），
  两者**平级、不嵌套**。这**是底线，不是一个测试的场地**。
- **要 home / 项目目录 / 配置目录的测试自己造**：`harness.test-support/with-temp-env`
  给这一个测试一对临时 root + OS home（跑完连目录一起删掉），项目目录用
  `harness.test-support/temp-dir`。**不要往 isolate! 那对里写**：它是整个 JVM 共用的，
  留下的文件会变成下一条用例的输入（凭空多出的 `<instructions>` / `<skills>` 块）。
  临时 root 里 `with-temp-env` 会种一份最小 `config.edn`，否则 run 会被「没有 `:default` provider」拒掉。
- 自己拉 JVM 的测试（fork 子进程、`ui/test/support/harness.ts`）要自己把两个都指过去：
  `CLJ_HARNESS_HOME` 给 root，`-Duser.home` 给 OS home。**答案不要从子进程的 stdout 读**——
  JDK 的原生访问告警混在里面；让子进程写到一个文件里再读。
- 改这两处用 `alter-var-root`，不要 `binding`——服务在别的线程上跑。
- 跑完删掉临时目录。

### 端口由 OS 分配，永不写死

- `with-server` / `with-declaring-server` / `with-resolved-config` 一律 `{:port 0}`，
  再把 `(:local-port (meta stop))` 绑到 `*port*`。
- `api-call` / `post-run` 默认读 `*port*`；测试要自己拼 URL 时也读它，不写字面量。
- 新增测试要起服务时用现成的 wrapper；需要自己安排 config/provider 时，让 wrapper 起服务
  （`with-resolved-config` 就是这么做的），不要在测试体内写 `(http/start! {:port 8080})`。
