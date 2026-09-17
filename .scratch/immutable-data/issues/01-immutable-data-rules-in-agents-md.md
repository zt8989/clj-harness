# 01 — 把不可变数据的纪律写进 `AGENTS.md`

**What to build:** `AGENTS.md` 多出一节 `## 不可变数据与线程`，与 `## 测试` 并列，写**规矩本身**
以及每条规矩的一个**正例**。这一节是给后来者的**要求**，不是审计报告：里面**不出现任何
`file:line`**，也不出现「票 NN」这种会过期的话。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 为什么要有这一节

这份代码在 http-kit 的线程池上跑：**一个请求一条线程**；一轮 run 里的工具调用**各占一条线程**
（`kernel.loop/drive!`，`loop.clj:113-125`）；hook 的命令由 `kernel.hooks.dispatch` spawn。
所以「只有主线程会碰它」在这份代码里基本都是假的，而这条事实今天**一个字都没写在任何地方**——
`AGENTS.md` 只有测试两节，`docs/architecture/overview.md` 的「状态存在哪」只给了进程内存一行。

## 要写的六组规矩（措辞照 `## 测试` 那节的风格：短句、要求句）

### 位置要有人认领

- 传数据传**值**。`atom` / `ref` / `volatile!` 只在**确有一个位置要表达**时出现，并且写清它属于谁。
- 累积器不跨线程：一次 run 的 `history` 只由 run 的生产者线程碰，工具线程只往自己的 channel 里放。
- 共享的可变 Java 对象（集合、`Writer`、`StringBuilder`）要么线程内局部，要么自有锁。

### 进程级的容器必须按键分家

- 会话状态**以 `thread-id` 为键**；**不许**有一个全局**单槽**被两个会话共用。
- 键不许只在写的时候带上：读的那一侧也要带 `thread-id`。
- 正例：`kernel.tools/overlays`、`kernel.hooks/overlays`、`cap.providers/session-overrides`。
  这些表里 `nil` 那个键是**进程级那一档**，是有意的，不是漏了。

### 两个原子操作之间不许夹副作用

- `(when-not (seen? x) (side-effect!) (mark-seen! x))` 会把副作用跑**两遍**。换成一次
  `swap-vals!`（或 `swap!`）拿返回值判断。
- 读-改-写要在**同一个 `swap!` 的纯函数**里完成。先 deref 出 `before`、算完再 `swap!` 的写法
  既会丢更新，也会让审计行写下一个**从未存在过**的状态。
- 正例：`cap.providers/take-provider-changes!`、`kernel.tools/take-decision!`。

### 锁的顺序是数据的一部分

- `store/with-session-lock` **永远是外层**，`with-path-lock` 在里层。反着拿就是死锁：
  一条消息里的两个工具调用会同时要这两把锁，而 `ReentrantLock` 的可重入只帮**同一个线程**。
- 新加一把锁时，把顺序写进那把锁的 docstring。

### 线程自己的东西给线程

- 每线程一个的用 `ThreadLocal`；只建一次的不可变产物用 `delay`（force 之后是不可变值）；
  按名字取的锁用 `ConcurrentHashMap` + `putIfAbsent`。三处正例都在 `cap.hashline.anchors`
  与 `cap.hashline.store` 里。

### 跨线程的动态绑定只有两条路

- **传参**，或者**进程范围地改根**（`alter-var-root`）。`binding` 只改**当前线程**的动态栈；
  服务在别的线程上跑时它会被**静默忽略**。（`## 测试` 那节的家目录那条是同一件事的另一面，
  两条之间留一个指针，别把话抄第二遍。）

## 正例为什么写在这里、反例不写

正例修完还是正例，反例修完就变成假话。这一节会被每个 agent 读、而且**没人会回头改**，
所以反例与它们的 `file:line` 全部留在 `.scratch/immutable-data/spec.md`（那是历史，带日期）。
本票**不要**在 `AGENTS.md` 里提任何一处违规的位置。

## 验收

- [ ] `AGENTS.md` 有 `## 不可变数据与线程` 一节，与 `## 测试` **并列**（二级标题），风格一致：短句、要求句，不解释为什么
- [ ] 六组规矩都在：位置要有人认领 / 进程级容器按键分家 / 两个原子操作之间不夹副作用 / 锁的顺序 / 线程自己的东西给线程 / 跨线程动态绑定两条路
- [ ] 每条规矩至少点到一个**正例**，而这个名字在代码里真的存在：
      `grep -rn "take-provider-changes!\|take-decision!\|with-session-lock\|with-path-lock\|ThreadLocal\|anchors/.*delay\|putIfAbsent\|session-overrides" src/harness | head` 逐条对得上
- [ ] 这一节里**没有 `file:line`、没有票号、没有「今天/目前」**这类会过期的话
      （`grep -nE "[a-z_/-]+\.(clj|md):[0-9]+|票 [0-9]" AGENTS.md` 在这一节里没有输出）
- [ ] 不重抄 `## 测试` 那节已有的字面；跨线程绑定那条只留指针
- [ ] `docs/architecture/`、`docs/agents/` 一个字不动（规矩是要求，现状在别处）
- [ ] 后端全量套件不受影响：`timeout 900 clojure -M:test -m harness.test-runner`
      的失败**用例名**与基线一致（这台机器的基线本来不绿，失败条数逐次浮动，比名字不比条数）
