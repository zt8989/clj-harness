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
cd .worktrees/<slug>/ui && pnpm install --prefer-offline   # 新树没有 node_modules；吃 pnpm store
```

新树看不见主检出里**未提交**的改动（它从 `main` 切出）——主检出那份是权威，别两头改同一处。
**包管理是 pnpm**（`ui/`，`packageManager` 钉在 `package.json` 里，锁文件是 `pnpm-lock.yaml`）。
`pnpm install --prefer-offline` 走本机 store，不联网、也不碰主检出那份 `node_modules`：store 缺哪个包
就点名报错，不许改成联网装。装完照旧 `pnpm run build` / `pnpm run typecheck` / `pnpm test`。
只改文档、不动代码的那种不必开树——直接在主检出改、提交，省一趟安装。

pnpm 的 `node_modules` 是**严格**的：只有写在 `package.json` 里的包能 import。npm 会顺手把传递依赖
提升到顶层，于是「代码 import 了某个传递依赖」在 npm 下能过、换 pnpm 就报 module not found——
这类不是迁移要修的东西，是迁移**照出来**的欠账（2026-10-06：`@ag-ui/core` 与 `@assistant-ui/core`
本来就被 import 却没被声明，补进 `dependencies` 才是修法）。

**先验收，再合并**（owner 定的规矩，2026-10-03）：活干完**不等于**该合进 `main`。干完就停下，把现场
交代清楚——工作树在哪、分支叫什么、怎么看（起服务的命令、要看的那一页、你测过的数字）——然后
**等人说一句「验收过了」**。没等到，这一票就**留在工作树里**：`main` 一个字不动，下面那三样也不清。

*为什么*：`main` 是这个家**正在用**的那份代码（跑着的 harness 就挂在它上面）。一次没人看过的合并，
既没人验过、又要人回头 `reset`；而留在工作树里的东西，没人看也跑不掉。**机器门全绿不是验收**，
那只是够格进入验收。**不要**因为「活干完了、测试全绿、顺手」就自己合了、顺手把树清了。

收尾（**验收过了才做**）：**合并之后三样一起清**——工作树、本地分支、远程分支；顺序别倒，工作树还占着这个分支时删分支会失败。

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
cd ui && pnpm test          # vitest
cd ui && pnpm run typecheck # tsc
cd ui && pnpm run build     # tsc + vite
```

**后端一轮跑有硬限制**：一个命名空间超 **300s**（`CLJ_HARNESS_TEST_NAMESPACE_TIMEOUT_SECS`）、整轮超
**1800s**（`CLJ_HARNESS_TEST_RUN_TIMEOUT_SECS`）就点名卡住的那家、打出它当时的栈，然后**退出 2**——
0 绿、1 红、2 撞限制。细则与「为什么这么设计」：`docs/rules/testing.md`。

**全量那一轮不要用 `Bash` 等它**：实测一轮要好几分钟，而 `Bash` 的单次上限是 600s
（`config.edn`的 `:session :tools :bash-max-timeout-ms`，写更大的值会被**拒绝**，不是悄悄放宽）。
用 **`Job`** 起它，然后 `job_output` 去取——那才是等一个长命令的地方。
**一次只跑一个**：两个 JVM 同时跑全量会抢同一批端口（本轮 4477/4484/4488/4490……），
表现为**某一处失败、重跑一次就干净**——不是你的改动坏了，是端口撞了。
要缩小范围就按上面的写法点几个命名空间，那才是并行安全的。

### E2E 走查（脚本）

```bash
node scripts/dev.mjs --scripted                  # 起一个走查用的服务：隔离家、OS 分配端口、默认回放
                                                 # scripts/example.json —— 先 pnpm run build，页面由后端
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
