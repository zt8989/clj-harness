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

**用脚本跑，不要自己拼命令。**

```bash
node scripts/test.mjs             # 后端全量 + 前端构建 + 前端套件
node scripts/test.mjs --backend   # 只跑后端全量
node scripts/test.mjs --build     # 只跑前端构建（tsc + vite）
node scripts/test.mjs --ui        # 只跑前端套件
node scripts/test.mjs --ns harness.edge.http-test,harness.cap.todos-test
                                  # 只跑几个命名空间（隔离照旧生效）

node scripts/dev.mjs --scripted   # 界面行为这一层：真浏览器走查
node scripts/dev.mjs --scripted my.json --ui-port 5211   # 换脚本、换前端端口
```

家目录隔离、端口由 OS 分配、跑完收摊，都是**调用方式**的事，手拼一次就漏一次。每条在守什么、
漏掉会怎样，写在脚本自己的头注释里，这里不复述（`scripts/test.mjs`、`scripts/dev.mjs`）。

**动过 `ui/src/` 的改动，合之前跑一次 `node scripts/dev.mjs --scripted` 走查。** 上面那几套是机器门，
界面行为这一层只有这一条真走查，两者不是一回事：2026-09-18 那次 i18n 合并，859 + 36 全绿、
`tsc` 与打包都过，而侧栏每一行的标题都是空的。渲染那一格现在有套件守了
（`ui/test/suites/sidebar.tsx`，把一行渲染成字符串再读它说什么），但**渲染看不到布局**——类名、截断、
间距、有没有行盒，都只有一个真浏览器说得清。

一轮 run 的记录是**边跑边写**的，而 `--scripted` 的那对临时家**退出即删**：要看
`projects/<workspace>/<thread>.jsonl` 就在它开着的时候看，路径它报在启动横幅里。

### 写新用例时要自己守的（脚本管「怎么跑」，这几条它管不到）

- 要 home / 项目目录 / 配置目录的用例**自己造**：`harness.test-support/with-temp-env` 给它一对临时
  root + OS home（跑完连目录一起删掉），项目目录用 `temp-dir`；临时 root 里它会种一份最小
  `config.edn`，否则 run 会被「没有 `:default` provider」拒掉。**不要往 `isolate!` 那对里写**——
  它是整个 JVM 共用的，留下的文件会变成下一条用例的输入（凭空多出的 `<instructions>` / `<skills>` 块）。
- 起服务用现成的 wrapper（`with-server` / `with-declaring-server` / `with-resolved-config`），
  一律 `{:port 0}`；改那两个 override 用 `alter-var-root` 不要 `binding`——服务在别的线程上跑。
- 自己拉 JVM 的用例（fork 子进程）要把**两个家**都指过去：`CLJ_HARNESS_HOME` 给 root、
  `-Duser.home` 给 OS home；**答案不要从子进程的 stdout 读**（JDK 的原生访问告警混在里面），
  让它写进文件再读。
