# 测试细则

铁律和「怎么跑」在 `AGENTS.md`；这里放细则。走查脚本守什么、漏掉会怎样，写在
`scripts/dev.mjs` 的头注释里，这里不复述。

## 铁律的完整版：`~/.clj-harness` 只读

跑用例、起 dev、走查，家一律自己造（`--scripted` 的那对临时家、`with-temp-env`），不指着真应用连、
不拿真家目录起第二个 harness。2026-09-18 的一次走查里两个进程抢同一个 `harness.db`：应用侧拿到
`SQLITE_BUSY`，迁移把库判成「受损」并隔离重建，**开发者自己那份 18M 的库当场被清空**
（`harness.db.emptied-by-quarantine-*` 与 `.corrupt-*` 就是那次留下的）。库只有一把锁：读没事，
写就是把正在跑的应用一起带走。

### 判据为什么是两半（2026-09-20）

**「真家未动」这句话有两个读者，而只有一个能归到测试头上：**

- **这个进程开过真家的库没有**——`harness.infra.db/store-paths-opened` 记着本进程解析并打开过的
  每一个库路径，`isolate!` 之后清空一次。开过就是 `ISOLATION FAILURE`，**按名字**报出那个路径；
  连"只读没动字节"也算失败：读到别人的行/配置/锚点，这次运行就已经不干净了。
- **那个文件动没动**——`[bytes mtime]` 前后对一次。**动了而这个进程没开过它 ⇒ 只报一行
  `ISOLATION NOTE`，不算失败**：那说明写它的是另一个进程，而最平常的另一个进程就是**开发者自己那个
  活着的 harness 会话**（锚点、待办、会话行都在那个库里；实测一次带锚点的 `read` 就能把 mtime 推走，
  而一次什么都不碰的全量跑前后逐字不变）。

**为什么必须分开**：2026-09-20 之前判据只有后半截（文件有没有动），于是「我在改文件、同时在跑全量」
那种日子里，**绿的跑会被报成红的**，而报告里那句话（"some code path resolved the store against the
developer's real home"）指向一个根本不存在的代码路径。两半一分开，"是谁写的"就有一个能站得住的名字。
两个分支各有用例：`test/harness/test_runner_test.clj`（纯输入，不碰任何库）。

**判据函数会用大写打 `ISOLATION FAILURE` / `ISOLATION NOTE`，所以驱动它的用例必须自己接住 stderr**
（`binding [*err* (java.io.StringWriter.)]`，见 `test_runner_test.clj/verdict+`）。裸写成
`(is (false? (runner/isolation-verdict …)))` 的话，那两行会落到**跑这一轮的人自己的终端**上：实测
2026-09-22 一轮**全绿**的跑里出现 6 行 `ISOLATION FAILURE`/`NOTE`（路径是夹具的
`/nowhere/the-developer/...`，不是真家）。后果不是吵，而是**信号没了**——真出事时打的那一行，和夹具
自己在说话，在输出里长得一模一样。**判据：一轮绿跑的输出里不该出现 `ISOLATION` 这个词。**

### 定向跑：名字接在 runner 后面，不要自己拼（2026-09-20）

只跑几个命名空间时，名字接在 runner 后面：`clojure -M:test -m harness.test-runner harness.cap.todos-test`。
**不要**写成 `clojure -M:test -e "(isolate!) (run-tests 'x)"`：那是协议里方便的那半截——不查「真家
动没动」的判据（于是判据挡的那条失败上照样报绿），也不收摊（每次调用留下一个临时 root）。
`run-suite!` 存在的理由就是这个：指纹真家、隔离、require、跑、判据、收尾、给退出码，一次做完。

**也没有哪个套件可以写死端口**，一律 `{:port 0}` 让 OS 分配：写死的端口要求「此刻这台机器上只有
我在跑这套测试」，而开发者的会话、上一张票留下的 e2e server、另一个 worktree 都在同一台机器上。

### 硬性限制：超时即报错退出（2026-09-20）

套件停不下来这件事以前是**没痕迹**的：2026-09-20 那次 `harness.kernel.tools-test` 卡了十几分钟，
日志停在最后一行 `Testing …` 就再没有下文，定位靠三次 `jstack`；没人看着的那次进程活了四天。
现在一轮跑有两个硬限制，撞了就**点名 + 打栈 + 退出 2**：

| 限制 | 默认 | 环境变量 | 管什么 |
|---|---|---|---|
| 一个命名空间 | 300s | `CLJ_HARNESS_TEST_NAMESPACE_TIMEOUT_SECS` | 单个命名空间（以及 `require` 整张名单那一次，它没法点名单个名字，栈里那个文件就是答案）跑多久；超了按名字报出来，并打出它当时的栈 |
| 整轮 | 1800s | `CLJ_HARNESS_TEST_RUN_TIMEOUT_SECS` | 在**两个命名空间之间**查；超了不再开新的，把没跑到的名字列出来 |

三个退出码含义不同，别混：**0 绿、1 红**（用例失败或判据失败）、**2 撞了限制**——第三种下一步做的事
和第二种完全不同（去看那个卡住的命名空间，不是去看那条红的断言），所以不能都叫「失败」。

几个刻意的地方：

- **数字是余量，不是目标**：健康的全量约 110s、最慢的命名空间约 16s（2026-09-20 在这台机器上量的），
  300s 是它的十九倍。机器慢就放宽——限制一旦变成 flaky 的来源，就会被调到形同虚设。
- **已经在跑的命名空间不会被整轮预算打断**：能点名它的是它自己那个预算。所以一轮的最坏墙钟是
  「整轮预算 + 一个命名空间的预算」。
- **超时报告走 stderr**：有一种卡住就是 stdout 没人读了（管道那头走了，写的人停在写里），
  报告如果也写进那条流，就是那条永远到不了的消息。判据的失败行同理。
- **跑在别的线程上**：不返回的工作从外面中断不了（那次卡住是循环里的反射调用，没有 sleep 也没有锁
  可以去中断），所以交给它一个 daemon 线程，然后**不再等它**——线程不会被杀（也没人能安全地杀它），
  是**被丢下**的。
- 每个命名空间跑完打一行 `[16.3s] harness.kernel.tools-test`：慢的那家要能事后看出来；卡住的时候，
  那行 `Testing` 底下**没有**这一行，缺的那行就是它。
- 几个分支由 `test/harness/test_runner_test.clj` 直接驱动（永不返回的 thunk + 纯 map），
  不需要真卡一次才能验。
- 一轮结束的那两行汇总（`Ran … tests containing … assertions.`）现在是 runner 自己打的：clojure.test
  的 `run-tests` 是「一串命名空间一份汇总」，那样就没法把限制挂在**某一个**命名空间上，所以改成逐个
  `test-ns`，计数自己加。输出与以前逐字一样。

## 界面走查：机器门替代不了的那一格

2026-09-18 那次 i18n 合并，859 + 36 全绿、`tsc` 与打包都过，而侧栏每一行的标题都是空的。渲染那
一格现在有套件守了（`ui/test/suites/sidebar.tsx`，把一行渲染成字符串再读它说什么），但**渲染看不到
布局**——类名、截断、间距、有没有行盒，都只有一个真浏览器说得清。所以动过 `ui/src/` 的改动，
合之前跑一次 `node scripts/dev.mjs --scripted`，**然后自己开浏览器**走一趟。那个脚本只把页面建好、
由一个地址发出来（`ui/dist`，后端自己发），**它不驱动浏览器**：回放 `scripts/example.json` 的是你发的
那一句话，不是脚本。

## 看 run 的记录要趁它开着

一轮 run 的记录是**边跑边写**的，而 `--scripted` 的那对临时家**退出即删**：要看
`projects/<workspace>/<thread>.jsonl` 就在它开着的时候看，路径它报在启动横幅里。

## 写新用例时要自己守的（runner 管「怎么跑」，这几条它管不到）

- 要 home / 项目目录 / 配置目录的用例**自己造**：`harness.test-support/with-temp-env` 给它一对临时
  root + OS home（跑完连目录一起删掉），项目目录用 `temp-dir`；临时 root 里它会种一份最小
  `config.edn`，否则 run 会被「没有 `:default` provider」拒掉。**不要往 `isolate!` 那对里写**——
  它是整个 JVM 共用的，留下的文件会变成下一条用例的输入（凭空多出的 `<instructions>` / `<skills>` 块）。
- **临时目录一律 `temp-dir`，不要自己拼 `<tmpdir>/<名字>`**：它是 `Files/createTempDirectory`，名字由
  OS 取（同一个 label 两次是两个目录），目录交回来是**空的**，所以「先 delete 再 mkdirs」那两行要删掉。
  拼出来的名字上一个 run 用过、并排的另一个进程也在用——`java.io.tmpdir` 里那些前任留下的树就是这么来的。
  它返回**路径字符串**：`file-seq`，以及形参带 `^java.io.File` 提示的私有 helper，都要自己包一层
  `io/file`（不包报的是 `String cannot be cast to java.io.File`，且指不到真正那一行）。
- **收摊不用你写**：`temp-dir` 交出去的每一棵树都在登记表里，一轮 run 结束由
  `harness.test-support/wipe-temp-dirs!` 全部删掉，进程被从外面停掉时还有 shutdown hook 兜底——用例的树
  一个（`ensure-cleanup-hook!`），run 自己那对一个（`harness.test-runner` 里的同名私有函数）。**主线程的
  `finally` 兜不住这件事**：SIGTERM / Ctrl+C 走的是 JVM 的有序关停，hook 跑完就 halt，`finally` 不是其中
  一员（2026-09-22 用一个 sleep 在 `try` 里的两行程序实测过）。所以**不要**在 `finally` 里手删——多删一次
  无害，但那是把「谁来删」这件事又摊回了每个调用点。**唯一不进登记表的是 `isolate!` 那对**
  （`:track? false`，由 `cleanup!` 和它自己的 hook 管）：它是整个 run 的环境，不能被任何一次 wipe 顺手
  带走。历史账：只做了一半的时候，一天的 run 就在 `java.io.tmpdir` 里留下 19,512 个 `clj-harness-*`
  目录、409MB（2026-09-22 实测）。
- **测「被中断的 run 会不会收摊」要用 SIGTERM，不要用 `kill -INT`**：非交互 shell 后台起的进程会继承
  `SIGINT` 为忽略（POSIX 如此，JVM 看到 `SIG_IGN` 就不装自己的处理器），发了也是白发——我在这上面空转了
  两轮。`scripts/dev.mjs` 停后端用的就是 SIGTERM。
- **自己拼路径的目录也要登记**：允许拿 `temp-dir` 的路径再拼一个兄弟目录（`(str (temp-dir "git")
  "-detached")`），但那个名字 `temp-dir` 没见过，得在**造它的那个 helper 里**调一次
  `harness.test-support/track-temp-dir!`，否则 sweep 收不到它。实测（2026-09-22）：sweep 上线后一轮全量
  还剩 9 个，全是这个形态——git-test 的 `scratch-repo` 7 个、http-test 的 listing/archive 各 1 个。
- **别在用例里调空参的 `wipe-temp-dirs!`**：所有命名空间是**先全部加载、再开跑**，所以那些 `(def root
  (temp-dir "project"))` 加载期造的树，在第一条用例跑的时候就已经在登记表里了。空参一调就把它们全删掉，
  那些还没轮到的命名空间随后报 `no such directory: .../clj-harness-project-...`——2026-09-22 实测：
  4 failures + 55 errors，全是这句话。要用就 `(wipe-temp-dirs! [自己的树 ...])`，带集合那档只动你交出去
  的那些。空参那档是 run 末尾和退出钩子的。
- 起服务用现成的 wrapper（`with-server` / `with-declaring-server` / `with-resolved-config`），
  一律 `{:port 0}`；改那两个 override 用 `alter-var-root` 不要 `binding`——服务在别的线程上跑。
- 自己拉 JVM 的用例（fork 子进程）要把**两个家**都指过去：`CLJ_HARNESS_HOME` 给 root、
  `-Duser.home` 给 OS home；**答案不要从子进程的 stdout 读**（JDK 的原生访问告警混在里面），
  让它写进文件再读。
