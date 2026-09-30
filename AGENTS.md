# AGENTS.md

This repo is configured for Matt Pocock's engineering skills (`to-tickets`, `triage`, `to-spec`, `domain-modeling`, `wayfinder`).

## Agent skills

### Issue tracker

Issues and specs live as local markdown under `.scratch/<feature-slug>/`. See `docs/agents/issue-tracker.md`.

### Triage labels

Five canonical roles, each label string equal to its name. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context: `CONTEXT.md` at repo root + `docs/adr/`. See `docs/agents/domain.md`.

## feature / hotfix 都在 worktree 里开

**主检出只留 `main`，不切分支**：feature 与 hotfix 一律先造一棵 worktree，在那里改、在那里测，
合完删掉。工作树放 `.worktrees/<slug>`（已 gitignore），分支同名、从 `main` 切出：

```bash
git worktree add .worktrees/<slug> -b <slug> main   # 开工
cd .worktrees/<slug>/ui && npm i --offline          # 新树没有 node_modules；只吃本机 npm 缓存
```

新树看不见主检出里**未提交**的改动（它从 `main` 切出）——主检出那份是权威，别两头改同一处。
`npm i --offline` 不联网、也不碰主检出那份 `node_modules`：缓存缺哪个包就点名报错，不许改成联网装。
装完照旧 `npm run build` / `npm run typecheck` / `npm test`。
只改文档、不动代码的那种不必开树——直接在主检出改、提交，省一趟 `npm i --offline`。

收尾：**合并之后三样一起清**——工作树、本地分支、远程分支；顺序别倒，工作树还占着这个分支时删分支会失败。

```bash
git worktree remove .worktrees/<slug>   # 里面还跑着东西就 --force
git worktree prune                      # 目录被手工删过时，拿它清账
git branch -d <slug>                    # -d 只肯删已合并的；-D 是「确认不要了」的手动挡
git push origin --delete <slug>
```

**合进 `main` 之前不删**：工作树是这一票的现场，提前删掉、回头想复看只能重新 `worktree add`。

## 测试

**铁律：测试期间 `~/.clj-harness` 只读——一个字都不许写进去。** 两个进程抢同一个 `harness.db` 的那次，
开发者 18M 的库被隔离重建清空了。后端 runner（`harness.test-runner`）已自动把配置根和 OS home
指到**每进程唯一的临时目录**（跑完即删），并行跑多个 JVM 也互不干扰——**不要**再手设
`CLJ_HARNESS_HOME` 指向固定路径，那反而让并行进程抢同一路径。
自己的 `dev/scratch_*.clj` 起手先调 `(harness.test-runner/isolate!)`，走同一协议。

跑完的判据分两半，因为它们说的不是一件事：**这个进程开过真家的库没有**（`harness.infra.db`
记着本进程解析过的每一个库路径——开过就是 `ISOLATION FAILURE`，按名字报出来），以及**那个文件
动没动**（`[bytes mtime]` 前后对一次）。文件动了而这个进程没开过它 ⇒ 那是别人写的：**你自己那个
活着的 harness 会话**就在往同一个库里写锚点、待办、会话行 ⇒ 只报一行 `ISOLATION NOTE`，
**不算失败**。见 `docs/rules/testing.md`。

### 单元测试（原生命令）

```bash
# 后端（Clojure）
clojure -M:test -m harness.test-runner

# 只跑几个命名空间：名字接在后面（走同一条协议，别自己拼 `(isolate!)` + `run-tests`）
clojure -M:test -m harness.test-runner harness.cap.todos-test harness.infra.db-test

# 前端
cd ui && npm test          # vitest
cd ui && npm run typecheck # tsc
cd ui && npm run build     # tsc + vite
```

**后端一轮跑有硬限制**：一个命名空间超 **300s**（`CLJ_HARNESS_TEST_NAMESPACE_TIMEOUT_SECS`）、整轮超
**1800s**（`CLJ_HARNESS_TEST_RUN_TIMEOUT_SECS`）就点名卡住的那家、打出它当时的栈，然后**退出 2**——
0 绿、1 红、2 撞限制。细则与「为什么这么设计」：`docs/rules/testing.md`。

### E2E 走查（脚本）

```bash
node scripts/dev.mjs --scripted                  # 起一个走查用的服务：隔离家、OS 分配端口、默认回放
                                                 # scripts/example.json —— 先 npm run build，页面由后端
                                                 # 从 ui/dist 发出。**它不驱动浏览器**（见下）
node scripts/dev.mjs --scripted my.json          # 换脚本（照 scripts/example.json 的形状改）

**动过 `ui/src/` 的改动，合之前跑一次 `node scripts/dev.mjs --scripted`，并自己开浏览器走一趟。**
它把页面建好、由一个地址发出来（`ui/dist`，后端自己发），**但不驱动浏览器**——打开它报的那个地址、
发一句话，脚本 provider 才会回放 `scripts/example.json`。机器门全绿挡不住「渲染看不到布局」的那一格
——2026-09-18 那次 i18n 合并，900 多条全过而侧栏标题全是空的。

细则（隔离怎么造、临时目录怎么取、用例要自己守什么）：`docs/rules/testing.md`。

## 热修复（不重启，让改动在这个进程里生效）

改完源码，**跑着的那个进程手里还是旧代码**：工具表要 `(require 'harness.cap.tools :reload)` 再
`(harness.cap.tools/install!)`，`prompt.md` 要 `(harness.kernel.llm/reset-prompt!)`（代价是一次冷 prefill）。
它**替代不了测试**——机器门照旧走 `harness.test-runner`；它也不追认任何已经落盘的字节。

步骤、怎么验、陷阱，以及「改了什么走哪扇门」：`docs/rules/hotfix.md`。

## README 只有四节

**介绍 / 前置准备 / 运行命令 / 配置说明——四节之外不写。** README 是**入口**，不是手册：
设计理由、实测数字、模块地图、接口清单一律进 `docs/`，历史决策进 `.scratch/<feature>/`。
写完往哪里放先问这一句，别把「我这次改了什么」倒进 README。

## 不可变数据与线程

服务跑在 http-kit 的线程池上，**「只有主线程会碰它」基本都是假的**——每次假设前先证一遍。

细则（位置认领、按键分家、swap 原子性、锁的顺序、快照不是事实、跨线程绑定）：
`docs/rules/concurrency.md`。

## 面板先拉一次存量，之后由推送走

屏幕上一栏里的一串东西——左边 Projects、右边子代理与作业、composer 上下的条子——**第一次拉一次存量**
（一次 GET，答「现在是什么」），**之后一律等服务端的推送**；不许拿定时器去问一个已经能被推的东西。
推送会丢、存量不会，所以两半缺一不可，**重连之后要补一次存量**。

细则（两半各自管什么、断了怎么办、唯一的例外、今天的两处欠账）：`docs/rules/panel-data.md`。
