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

**铁律：测试期间 `~/.clj-harness` 只读——一个字都不许写进去。** 两个进程抢同一个 `harness.db` 的那次，
开发者 18M 的库被隔离重建清空了。后端 runner（`harness.test-runner`）已自动把配置根和 OS home
指到**每进程唯一的临时目录**（跑完即删、结束校验真家未动），并行跑多个 JVM 也互不干扰——
**不要**再手设 `CLJ_HARNESS_HOME` 指向固定路径，那反而让并行进程抢同一路径。
自己的 `dev/scratch_*.clj` 起手先调 `(harness.test-runner/isolate!)`，走同一协议。

### 单元测试（原生命令）

```bash
# 后端（Clojure）
clojure -M:test -m harness.test-runner

# 前端
cd ui && npm test          # vitest
cd ui && npm run typecheck # tsc
cd ui && npm run build     # tsc + vite
```

### E2E 走查（脚本）

```bash
node scripts/dev.mjs --scripted                  # 真浏览器走查（隔离家、OS 分配端口、跑完收摊）
node scripts/dev.mjs --scripted my.json --ui-port 5211   # 换脚本、换前端端口
```

**动过 `ui/src/` 的改动，合之前跑一次 `node scripts/dev.mjs --scripted` 走查。** 机器门全绿挡不住
「渲染看不到布局」的那一格——2026-09-18 那次 i18n 合并，900 多条全过而侧栏标题全是空的。

细则（隔离怎么造、临时目录怎么取、用例要自己守什么）：`docs/rules/testing.md`。

## 不可变数据与线程

服务跑在 http-kit 的线程池上，**「只有主线程会碰它」基本都是假的**——每次假设前先证一遍。

细则（位置认领、按键分家、swap 原子性、锁的顺序、快照不是事实、跨线程绑定）：
`docs/rules/concurrency.md`。
