# parallel-sessions 的证据

这份文件记的是**这一次真正跑过的东西**，以及**没跑成的东西和原因**。
两份分开写，是因为把「没做」写成「做了」比什么都不写更坏——本仓的走查文件里
（`.scratch/composer-status/evidence/`、`.scratch/attachments/…`）也一直是这个规矩。

## 一、机器门（跑了，全过）

工作树 `.worktrees/parallel-sessions`，分支 `parallel-sessions`。

### 1. 前端类型与构建

```
cd ui && npm run build          # tsc --noEmit && vite build
```

```
✓ built in 1.25s
dist/assets/index-B0DyEvMe.js   1,349.99 kB │ gzip: 380.70 kB
```

`tsc --noEmit` 一次都没红过，这是这次 React 重构的机器门（`app.tsx` 整个换了形状）。

### 2. agent 层套件（真 HttpAgent + 真后端 + 脚本厂商）

```
cd ui && npm test
```

```
 Test Files  1 passed (1)
      Tests  32 passed (32)
```

**32 = 基线 31 + 1**，多出来的这一条就是票 06 补的并发用例：

```
✓ concurrent > two-conversations-run-at-once-and-each-keeps-its-own-log
```

它两个 thread-id **同时**发（`Promise.all`，不是 `await` 一条再发另一条），
断言两条都到 `RUN_FINISHED`、都没有 `RUN_ERROR`、**两份 jsonl 各自完整**：
每一份的 `input` 行只带自己的用户话（`saidA` / `saidB`，这是这对会话唯一不同的东西），
`POST /api/threads/<stem>/rebuild` 读回来也只有自己那一场。
`ui/test/ui.test.ts` 的 `EXPECTED_CASES` 已从 31 改成 32（忘了改它，新用例就等于不存在）。

### 3. 后端全量（`harness.test-runner`）

**基线**（工作树刚建好、一个字没改时）：

```
timeout 900 clojure -M:test -m harness.test-runner
Ran 855 tests containing 11252 assertions.
0 failures, 0 errors.
EXIT=1        # 只因下面那条 ISOLATION FAILURE，见「没跑成的」一节
```

**改完之后**（逐字见本目录 `after.txt`）：

```
Ran 859 tests containing 11264 assertions.
0 failures, 0 errors.
EXIT=1        # 同一条 ISOLATION FAILURE，见「没跑成的」一节
```

**855 → 859**：多的 4 条是这次给前置票（`turn-plan` 按 thread-id 分家）写的用例，
断言数 11252 → 11264；**失败用例的集合与基线一致——两边都是空的**。

### 4. 前置票：`turn-plan` 按 thread-id 分家

`parallel-sessions` 的票 02 阻塞在 `.scratch/immutable-data/issues/02-turn-plan-per-turn.md` 上
（两个会话同时跑正是那个全局单槽互相清计划的场景）。那一票也落了地：

```
timeout 300 clojure -M:test -e "(require 'harness.test-runner) (harness.test-runner/isolate!) \
  (require 'harness.kernel.tools-test 'harness.cap.hashline.batch-test) \
  (let [r (clojure.test/run-tests 'harness.kernel.tools-test 'harness.cap.hashline.batch-test)] \
    (System/exit (if (zero? (+ (or (:fail r) 0) (or (:error r) 0))) 0 1)))"
```

```
Ran 36 tests containing 147 assertions.
0 failures, 0 errors.
```

以及所有碰 `turn-plan` 的套件一起跑：

```
Ran 118 tests containing 473 assertions.
0 failures, 0 errors.
```

（`tools` / `loop` / `install` / `todos` / `hashline.batch` / `hashline.insert` /
`hashline.grep` / `glob`——`hashline.batch-test` 与其余用 `(tools/forget-turn!)` 复位缝的
用例**一个字没改**地通过。）

新增的四条用例在 `test/harness/kernel/tools_test.clj`：
键按 thread-id 分家、`forget-turn!` 只清 token 还属于自己的那条、
`batch-role` 只认自己那轮的 call id、以及**「缝在工具线程 spawn 之前就被告知整轮」**
（用一层会睡的 planner 把这件事做成确定的，不赌调度）。

## 二、走查（**没跑成**，原因在下面）

`.scratch/parallel-sessions/spec.md` 要求界面行为用**真浏览器**走查，票 02/03/04/05
各有一条。**这一次一条都没跑成**，所以那几票的走查验收项**仍然是空的**。

### 为什么

这台机器上**这个仓库的另一个实例正在跑**，并且占着她自己的两个端口：

```
$ lsof -i :8080 -i :5173        # （等价观察）
java  … clojure … harness.…      → :8080
node  … vite …                   → :5173
```

于是：

- `harness.e2e-server --port 8080` **起不来**（`java.net.BindException: Address already in use`），
  而这 8080 是 `ui/src/lib/threads.ts` 里写死的那一个——换端口就同时破了 CORS 契约
  （后端只放行 `http://localhost:5173`）与那个常量。
- `npm run dev` 同样起不来（`Port 5173 is already in use`）。

用**别人正在跑的那个实例**做走查是不行的，而且已经证明有害：它用的是**真实的
`~/.clj-harness`**，而我在确认端口之前先 `POST /api/projects {"dir":"/tmp/ps-evidence/proj"}`
探了一次——那一下写进了**真实的 store**（列表里出现了 `/private/tmp/ps-evidence/proj`，
`~/.clj-harness/harness.db` 的 mtime 当场变了）。**已经撤回**：

```
POST http://localhost:8080/api/projects/%2Fprivate%2Ftmp%2Fps-evidence%2Fproj/remove
{"path":"/private/tmp/ps-evidence/proj","unbound":0}
# 再查 GET /api/projects：ps-evidence entries: []
```

真实的 `~/.clj-harness` 里**没有留下任何本项目的东西**。那条 `ISOLATION FAILURE`
（基线那次也在）就是那个实例在同一段时间写真实 store 造成的，与本工作树的代码无关。

顺便记一条给下一个人的话：**这个仓库的 `--port 8080` 走查，先确认 8080 是空的**。
AGENTS.md 那条「端口由 OS 分配」管的是测试；走查里 8080 是应用的常量，只能先把旧的停掉。

### 下一次怎么跑（环境已经备好，只差端口）

临时家目录与脚本文件按下面这几条命令现建（**这一次跑完后已删掉**，本仓的规矩是临时目录不留）：

```
EV=/tmp/ps-evidence
printf '%s\n' '{:default {:protocol :fake :base-url "http://offline.invalid/v1" :model "seeded"}}' > $EV/home/config.edn
cat > $EV/script.json <<'JSON'
{"turns": [
  {"content": "", "tool-calls": [{"id": "slow-1", "name": "bash", "arguments": {"command": "sleep 10"}}]},
  {"content": "这一轮跑完了。"}
]}
JSON
CLJ_HARNESS_HOME=$EV/home clojure -M:dev -m harness.e2e-server \
    --script-file $EV/script.json --port 8080 --user-home $EV/userhome
cd ui && npm run dev
```

**「慢」不用改 `harness.fake`**：脚本第一轮调一次 `bash` 跑 `sleep 10`
（一轮工具调用 = 两次模型调用），那 10 秒里 run 是真的在飞——
够切走、够在另一场里发一条、够切回来看它还在长。

然后（这一段的每一步都对应一条验收项）：

1. 侧边栏加一个项目（`POST /api/projects {dir}` 也行，绕开原生选目录窗），
   点 **New task** → 发一条 → **切走**（再点一次 New task 就是另一场）→ 发一条 →
   看侧边栏**两行都亮**（票 03）→ 停在 B 上，A 那行**仍然亮** → 切回 A：
   那一轮完整（工具行、思考行都在），不是空壳（票 02）。
2. 两场都在跑时点 A 那一行的归档 → **拒**，句子在 A 那一行；再点 C（没在跑的）→ 放行（票 04）。
3. 用一条 `:requires-approval` 的工具让 A 停在审批门上 → A 那行说 `Waiting on you`、
   A 的 composer 关着；切到 B：**B 的 composer 能用且能发**；回 A 答掉它，A 跑完（票 05）。
4. 两场各一条 run 的日志对得上（`threads/<stem>.jsonl` 各只有自己的话，都到 terminal）。

## 三、还开着的东西（一条不藏）

- **票 01 根本没做。** 它要的是一个**可以扔掉的原型**加四个问题的实测答案
  （挂着的 host 里 run 还活着吗、换 provider 会不会重挂 `<Thread/>`、只传 threadId 够不够、
  N 份 host 的代价）。这次是**读上游代码**得到形状并直接落地的：
  `useAgUiRuntime` 每次调用自造一份 core 存在 ref 里
  （`ui/node_modules/@assistant-ui/react-ag-ui/dist/useAgUiRuntime.js:22`），
  `history` 适配器每个 core 只 `load()` 一次（`AgUiThreadRuntimeCore.js:123-152`）。
  **「结构上应该行」不等于「看着它跑完」**，所以票 01 的四个答案仍然没有证据。
- **票 02/03/04/05 的真机走查**：见上一节，端口被占，没跑。
- **一处设计判断没有实测背书**：不显示的 host **只挂 runtime、不渲染 `<Thread/>`**
  （整列跟着 `visible` 上下）。composer 的草稿活在 runtime 的 core 里，所以切走再切回来字还在；
  但**滚动位置**与 `turn-steps` 的折叠态活在 DOM/组件里，切走会丢。
  票 01 的第 2 问我没试，所以这一条是**推论**，不是实测——真机走查时要专门看一眼。
- **`run/terminal` 不在会话 jsonl 里。** spec 的票 02 验收写「两边都到 terminal（`run/terminal`）」，
  但 `harness.edge.http` 把 `:run/terminal` 交给 `harness.infra.log/info!`（滚动日志文件），
  会话 jsonl 里那一行是 `{"kind":"event","payload":{"type":"RUN_FINISHED"|"RUN_ERROR"}}`。
  所以并发用例断的是后者——**同一个事实的另一个写法**，写在 `suites/concurrent.ts` 的文件头。
