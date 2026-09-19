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

**铁律：测试期间 `~/.clj-harness` 只读——一个字都不许写进去。** 跑用例、起 dev、走查，家一律自己造
（`--scripted` 的那对临时家、`with-temp-env`），不指着真应用连、不拿真家目录起第二个 harness。2026-09-18 的
一次走查里两个进程抢同一个 `harness.db`：应用侧拿到 `SQLITE_BUSY`，迁移把库判成「受损」并隔离重建，
**开发者自己那份 18M 的库当场被清空**（`harness.db.emptied-by-quarantine-*` 与 `.corrupt-*` 就是那次留下的）。
库只有一把锁：读没事，写就是把正在跑的应用一起带走。

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
- **临时目录一律 `temp-dir`，不要自己拼 `<tmpdir>/<名字>`**：它是 `Files/createTempDirectory`，名字由
  OS 取（同一个 label 两次是两个目录），目录交回来是**空的**，所以「先 delete 再 mkdirs」那两行要删掉。
  拼出来的名字上一个 run 用过、并排的另一个进程也在用——`java.io.tmpdir` 里那些前任留下的树就是这么来的。
  它返回**路径字符串**：`file-seq`，以及形参带 `^java.io.File` 提示的私有 helper，都要自己包一层
  `io/file`（不包报的是 `String cannot be cast to java.io.File`，且指不到真正那一行）。
- 起服务用现成的 wrapper（`with-server` / `with-declaring-server` / `with-resolved-config`），
  一律 `{:port 0}`；改那两个 override 用 `alter-var-root` 不要 `binding`——服务在别的线程上跑。
- 自己拉 JVM 的用例（fork 子进程）要把**两个家**都指过去：`CLJ_HARNESS_HOME` 给 root、
  `-Duser.home` 给 OS home；**答案不要从子进程的 stdout 读**（JDK 的原生访问告警混在里面），
  让它写进文件再读。

## 不可变数据与线程

服务跑在 http-kit 的线程池上：一个请求一条线程，一轮 run 里的工具调用各占一条线程
（`harness.kernel.loop/drive!`），hook 命令由 `harness.kernel.hooks.dispatch` spawn。
**「只有主线程会碰它」基本都是假的**——每次假设前先证一遍。

### 位置要有人认领

- 传数据传**值**。`atom` / `ref` / `volatile!` 只在确有一个位置要表达时出现，并写清它属于谁。
- 累积器不跨线程：一次 run 的 `history` 只由 run 的生产者线程碰，工具线程只往自己的 channel 里放。
- 共享的可变 Java 对象（集合、`Writer`、`StringBuilder`）要么线程内局部，要么自有锁。

### 进程级的容器必须按键分家

- 会话状态**以 `thread-id` 为键**。不许有一个全局单槽被两个会话共用。
- 键不许只在写的时候带上：读的那一侧也要带 `thread-id`。
- 正例：`harness.kernel.tools/turn-plan`（按键分家并带 token）、`harness.kernel.tools/overlays`、
  `harness.kernel.hooks/overlays`、`harness.cap.providers/session-overrides`。这些表里 `nil` 那个键
  是**进程级那一档**，是有意的，不是漏了。

### 两个原子操作之间不许夹副作用

- 不写 `(when-not (seen? x) (side-effect!) (mark-seen! x))`——副作用会跑两遍。换成一次
  `swap-vals!`（或 `swap!`）拿返回值判断。
- 读-改-写要在**同一个 `swap!` 的纯函数**里完成。先 deref 出 `before`、算完再 `swap!` 既会丢更新，
  也会让审计行写下一个**从未存在过**的状态。
- 正例：`harness.cap.providers/take-provider-changes!`、`harness.kernel.tools/take-decision!`。

### 锁的顺序是数据的一部分

- `harness.cap.hashline.store/with-session-lock` **永远是外层**，`with-path-lock` 在里层。
  反着拿就是死锁：一条消息里的两个工具调用会同时要这两把锁，而 `ReentrantLock` 的可重入只帮同一个线程。
- 新加一把锁时，把顺序写进那把锁的 docstring。

### 快照不是事实

- 从共享可变物上读到的一次拷贝（文件长度、某次 stat、某个 atom 的 deref）只说明
  「读到的那一刻它长那样」。**拿它去判决之前，先问掌管它的那一方**——尤其当判决的后果是
  **挪走或毁掉原件**时。
- 判一份数据是不是坏了、能不能用，由**持有它的那个引擎自己回答**；外面的检查只能当提示，
  不能当判决。
- 正例：`harness.infra.db` 的属主判定只读 100 字节头部，不打开别人的文件；「这份库还能不能用」
  交给 SQLite 自己的错误码（`damage?`）。

### 线程自己的东西给线程

- 每线程一个的用 `ThreadLocal`；只建一次的不可变产物用 `delay`（force 之后是不可变值）；
  按名字取的锁用 `ConcurrentHashMap` + `putIfAbsent`。三处正例都在 `harness.cap.hashline.anchors`
  与 `harness.cap.hashline.store` 里。

### 跨线程的动态绑定只有两条路

- **传参**，或者**进程范围地改根**（`alter-var-root`）。`binding` 只改**当前线程**的动态栈，
  在别的线程上被**静默忽略**。家目录那条见 `## 测试`——同一件事的另一面，不在这里重说。
