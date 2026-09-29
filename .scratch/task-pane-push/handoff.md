# Handoff — 右侧任务视图「首拉 + 推送 + 起止时间」(task-pane-push)

写给下一个 agent。**当前焦点**:把 `.worktrees/task-pane-push` 那一票收尾——功能已全部写完、
UI 与相关后端命名空间已绿、**只剩一个 bug 和一个临时探针**。

---

## 1. 现在在哪

| 位置 | 说明 |
|---|---|
| `C:\Users\zhouteng\Documents\workspace\lisp-harness` | 主仓库,当前分支 **main**(HEAD 已前进到 `29eff3c`,上面还有用户自己的提交) |
| `.worktrees/task-pane-push` | **本票的工作区**,分支 `task-pane-push`,基线 `4a97153`(= main 当时的样子) |
| 票 | `.worktrees/task-pane-push/.scratch/task-pane-push/issues/01-task-pane-pull-once-push-after-with-times.md` |
| 走查脚本 | 同目录 `walkthrough.mjs` + `script.json`(脚本第一轮会起一个 `sleep 8` 的后台作业) |

**已合并进 main 的前两件工作**(worktree 已被删,内容在 main 里):
- 侧栏「首次 HTTP、后续 WebSocket」+ 运行状态入 SQLite(`.scratch/sidebar-ws-and-run-state/`,
  见该目录 `spec.md`;分支 `sidebar-ws-and-run-state` 的提交被 cherry-pick 进 main)。
- 会话数字(轮次/步数/缓存/上下文)入 SQLite + 推送空载荷 bug 的修复 + `model/start` 的初始化估算
  (`.scratch/session-numbers-in-the-store/spec.md`;merge 提交 `45c5b69`)。

## 2. 这一票已经做完的部分(全部已跑绿)

**服务端**
- `harness.cap.jobs`:`:endedAt` 在 `write-last-line!` 里盖章(唯一「记录闭合」的地方),`listing`
  输出 `{:id :command :status :startedAt :endedAt :path}`;新增 `set-change-hook!` / `announce!` 缝。
  **注意一个刚修掉的坑**:盖章必须是「条目还在时才盖的自查 swap」——`assoc-in` 会凭空创建路径,
  而会话被收走 / 套件每用例 reset registry 时,泵还在收尾 → 会留下 `{:ended-at …}` 这种**不是作业**的
  幽灵条目,`shutdown!` 撞上它就 NPE(`jobs-test` 抓到的)。
- `harness.cap.subagents`:活表 `:finished-at` 记忆(`finished`,上限 `finished-kept`)+ 同样的通知缝,
  `runs` 输出 `{:thread-id … :delegated-at … :finished-at … :running …}`。
- `harness.edge.http`:`task-body` / `task-send!`(**会话 socket 上的第四族** `{:type "task" …}`,
  整包、无游标),在 `start!` 里用两个 capability 的缝装到 `task-send!`。

**前端**
- `lib/mux.ts`:`familyOf` 新增 `"task"`、`TaskFrame`、`subscribeTasks`、`taskSubscriptions` 表。
- `hooks/use-task-pane.ts`:**整个重写**——轮询没了,改成「挂载/切会话/可见性回来时各读一次快照 +
  `subscribeTasks` 订阅」;唯一的计时器是本地滴答 `TASK_PANE_TICK_MS`(只在**有东西在跑**时开,
  不发请求),`useTaskPane` 现在返回 `{jobs, subagents, now}`。
- 行:两条载荷都带 `endedAt`/`finishedAt`;`task-pane-jobs.tsx` 画「开始时间 + 持续时间」,
  `task-pane-subagents.tsx` 画「已跑 / 耗时」;`relative-time.ts` 导出 `AGO_KEY` 供两处共用。
- 目录键(en/zh `shell.json`):`jobStarted` / `jobTook` / `subagentElapsed` / `subagentTook`。

**已验证**
- `cd .worktrees/task-pane-push/ui && npm run typecheck && npm test` → **166 passed**(含改写的
  `test/suites/right-pane.tsx` 源钉:断言**没有**轮询、有 `subscribeTasks`、有本地 tick、行上有两组时钟)。
- `clj -M:test -m harness.test-runner harness.cap.jobs-test harness.cap.subagents-test` → **69 tests / 0 失败**
  (新增 `a-run-carries-its-two-clocks`,并给作业行加了 `:endedAt` 的两个断言)。
- `harness.edge.http-test` 单独跑 → **115 tests / 0 失败**。

## 3. 剩下的两个动作

### (a) 删掉临时探针
`src/harness/edge/http.clj` 的 `task-send!` 里有一行诊断,**必须删**:
```clojure
(log/info! :task/push-probe {:thread-id thread-id :channels (count channels)})
```

### (b) 修「作业结束后前端仍显示 [running] / 已跑」的 bug
浏览器走查(`node scripts/dev.mjs --scripted .scratch/task-pane-push/script.json --ui-port 5322`
然后 `node .scratch/task-pane-push/walkthrough.mjs http://127.0.0.1:<port>/`)的结果:

```
ok  the pane read its routes, and read them ONCE each
ok  no poll: the pane asked nothing while the job ran
ok  a running job's duration is drawn / GROWS between two samples / cost no request
RED and shows how long it TOOK (not 'so far') -- 已跑 36 秒      ← 作业早已结束,行还停在 running
```

**服务端是对的**:`GET /api/threads/<id>/jobs` 此时答
`{"status":"[exit 0]","startedAt":…,"endedAt":…}`(已核实)。

**探针给出的定位**(server log 的 `push-probe` 两行):
```
20:44:12.658 push-probe channels=1 thread-id=e208ca82-…   ← 作业开始:1 条连接在听(帧发出去了)
20:44:21.767 push-probe channels=0 thread-id=e208ca82-…   ← 作业结束:0 条连接在听 ⇒ 这一帧没人收到
```

⇒ **客户端的订阅在中间掉了,而且没有随重连补回来。** 根因基本锁定在 `ui/src/lib/mux.ts`:

- `wantedThreads()` 只收 `subscriptions.keys()`(window)+ `runSubscriptions.keys()`,
  **`taskSubscriptions`(以及 `factSubscriptions`)不在里面**;
- 于是 socket 重开时,握手 URL 与 `declare({subscribe: declaredSet()})` 声明的集合里**没有那个会话**,
  服务端的 watch 就丢了 → 之后的 `task` 帧没有 channel 可发。

**建议的修法**(与本仓 `docs/rules/panel-data.md`「断了怎么办」一致):
1. `wantedThreads()` 把 `taskSubscriptions.keys()`(和 `factSubscriptions.keys()`)并进去,
   让重连的声明带着它;
2. `subscribeTasks` 增加一个「重连后」的通知路径(参考 `subscribeRun` 的 `whenOpen`/重新声明),
   让 pane 在 socket 重开时**再读一次快照**(`use-task-pane` 的 `read()` 已经是幂等的);
3. 走查脚本里那个 RED 应当变绿,然后把它固化成断言(现在是失败态)。

**注意**:`ui/test/suites/right-pane.tsx` 里我已把源钉改成「没有轮询」,但那批用例**不断言重连**;
`ui/test/suites/mux.ts` 是 mux 家族的纯用例所在,重连声明集合的新断言应该加在那里。

## 4. 收尾清单(做完上面的,按仓库约定走)

1. `node scripts/dev.mjs --scripted .scratch/task-pane-push/script.json --ui-port 5322`
   + `node .scratch/task-pane-push/walkthrough.mjs <url>` → 全绿(动过 `ui/src/` 的硬要求,
   AGENTS.md 写明「合之前必须跑一次并自己开浏览器走一趟」)。
2. 后端全量 `clj -M:test -m harness.test-runner`(**一个命名空间 300s、整轮 1800s 上限**;
   慢机器上 `http_test` 可能撞线,那会 exit 2 且没有判定——单独重跑它即可)。
3. 前端 `cd ui && npm run typecheck && npm test && npm run build`。
4. **按约定删票**(`.scratch/<feature>/issues/NN-*.md` 删掉)并把决定写进
   `.scratch/task-pane-push/spec.md`(参照前两个 feature 的 spec.md 写法),证据图入
   `.scratch/task-pane-push/evidence/`。
5. 提交;是否合并进 main 由主人决定(前两次他都要求「合并进 main」)。

## 5. 这个仓库里必须遵守的几条(踩过的坑)

- **测试隔离**:`~/.clj-harness` 只读,一个字都不许写。后端 runner 已自动隔离;自己写
  `dev/scratch_*.clj` 时先 `(harness.test-runner/isolate!)`。跑完若报 `ISOLATION NOTE`(真家文件变了
  而本进程没开过它)是**正常**的——主人自己那个活着的 harness 会话在写它。
- **不要手设 `CLJ_HARNESS_HOME`** 指向固定路径(会让并行进程抢同一个库)。
- 测试有两个**已知 flake**,与改动无关、重跑即绿:`http_test` 的
  `a-running-session-reads-what-has-arrived-and-nothing-is-written` 与
  `an-overflow-refusal-compacts-aggressively-and-retries-in-one-turn`;负载高时
  `hooks-wired` / `system-prompt` / `delegation-line` 也可能偶发。
- **docstring 里不要写双引号**:Clojure 字符串会在那里提前结束(本会话踩过两次)。
  `{:type task}` 这种要写成反引号包住的、不带双引号的形式。
- **新 worktree 没有 `ui/node_modules`**:先 `cd ui && npm install`。
- 票面/文档风格:中文、先讲「为什么」再讲「怎么做」;README 只四节。
- Windows + git-bash:本会话的工具链里 **bash heredoc 不可靠**(静默不执行)——
  多行脚本一律先写到 `$TEMP/*.py` 再 `python $TEMP/xx.py`。

## 6. 一条需要知道的历史(避免误会)

`45c5b69`(把 session-numbers 合进 main 的那个 merge)把我误落在 main 的提交和**当时你未提交的
文件**(`docs/architecture.md`、`docs/architecture/system-prompt.md`、`docs/rules/hotfix.md`、
`src/harness/cap/tools.clj`、`test/harness/kernel/tools_test.clj`)**一起提交了**;你随后已自己
提交了 `prompt.md`/`AGENTS.md`(`4a97153`)。如需拆分那段历史,main 当时还未推送。

## 7. Suggested skills

| 场景 | skill |
|---|---|
| 接着改这条前端 + 后端的小 bug | 直接开工;要找测试/规矩读 `docs/rules/testing.md`、`docs/rules/panel-data.md` |
| 想先把范围/验收再钉一遍 | `grill-me` 或 `to-tickets`(票已存在,可先 `grill-with-docs`) |
| 收尾时怀疑自己写歪了 | `code-review`(对着 `4a97153` 起 diff 走一遍 Standards 与 Spec) |
| 需要新会话接续 | `handoff`(就是本文件) |
| 排查这类「推送没到」的问题 | `diagnosing-bugs`(本文件的第 3 节已经写到定位那一步) |
