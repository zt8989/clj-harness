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

**铁律：测试期间 `~/.clj-harness` 只读——一个字都不许写进去。** 跑用例、起 dev、走查，家一律自己造，
不指着真应用连、不拿真家目录起第二个 harness。两个进程抢同一个 `harness.db` 的那次，开发者 18M 的库
被隔离重建清空了。

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

家目录隔离、端口由 OS 分配、跑完收摊，都是**调用方式**的事，手拼一次就漏一次。

**动过 `ui/src/` 的改动，合之前跑一次 `node scripts/dev.mjs --scripted` 走查。** 机器门全绿挡不住
「渲染看不到布局」的那一格——2026-09-18 那次 i18n 合并，900 多条全过而侧栏标题全是空的。

细则（隔离怎么造、临时目录怎么取、用例要自己守什么）：`docs/rules/testing.md`。

## 不可变数据与线程

服务跑在 http-kit 的线程池上，**「只有主线程会碰它」基本都是假的**——每次假设前先证一遍。

细则（位置认领、按键分家、swap 原子性、锁的顺序、快照不是事实、跨线程绑定）：
`docs/rules/concurrency.md`。
