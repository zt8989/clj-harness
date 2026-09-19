# 不可变数据与线程

服务跑在 http-kit 的线程池上：一个请求一条线程，一轮 run 里的工具调用各占一条线程
（`harness.kernel.loop/drive!`），hook 命令由 `harness.kernel.hooks.dispatch` spawn。
**「只有主线程会碰它」基本都是假的**——每次假设前先证一遍。

## 位置要有人认领

- 传数据传**值**。`atom` / `ref` / `volatile!` 只在确有一个位置要表达时出现，并写清它属于谁。
- 累积器不跨线程：一次 run 的 `history` 只由 run 的生产者线程碰，工具线程只往自己的 channel 里放。
- 共享的可变 Java 对象（集合、`Writer`、`StringBuilder`）要么线程内局部，要么自有锁。

## 进程级的容器必须按键分家

- 会话状态**以 `thread-id` 为键**。不许有一个全局单槽被两个会话共用。
- 键不许只在写的时候带上：读的那一侧也要带 `thread-id`。
- 正例：`harness.kernel.tools/turn-plan`（按键分家并带 token）、`harness.kernel.tools/overlays`、
  `harness.kernel.hooks/overlays`、`harness.cap.providers/session-overrides`。这些表里 `nil` 那个键
  是**进程级那一档**，是有意的，不是漏了。

## 两个原子操作之间不许夹副作用

- 不写 `(when-not (seen? x) (side-effect!) (mark-seen! x))`——副作用会跑两遍。换成一次
  `swap-vals!`（或 `swap!`）拿返回值判断。
- 读-改-写要在**同一个 `swap!` 的纯函数**里完成。先 deref 出 `before`、算完再 `swap!` 既会丢更新，
  也会让审计行写下一个**从未存在过**的状态。
- 正例：`harness.cap.providers/take-provider-changes!`、`harness.kernel.tools/take-decision!`。

## 锁的顺序是数据的一部分

- `harness.cap.hashline.store/with-session-lock` **永远是外层**，`with-path-lock` 在里层。
  反着拿就是死锁：一条消息里的两个工具调用会同时要这两把锁，而 `ReentrantLock` 的可重入只帮同一个线程。
- 新加一把锁时，把顺序写进那把锁的 docstring。

## 快照不是事实

- 从共享可变物上读到的一次拷贝（文件长度、某次 stat、某个 atom 的 deref）只说明
  「读到的那一刻它长那样」。**拿它去判决之前，先问掌管它的那一方**——尤其当判决的后果是
  **挪走或毁掉原件**时。
- 判一份数据是不是坏了、能不能用，由**持有它的那个引擎自己回答**；外面的检查只能当提示，
  不能当判决。
- 正例：`harness.infra.db` 的属主判定只读 100 字节头部，不打开别人的文件；「这份库还能不能用」
  交给 SQLite 自己的错误码（`damage?`）。

## 线程自己的东西给线程

- 每线程一个的用 `ThreadLocal`；只建一次的不可变产物用 `delay`（force 之后是不可变值）；
  按名字取的锁用 `ConcurrentHashMap` + `putIfAbsent`。三处正例都在 `harness.cap.hashline.anchors`
  与 `harness.cap.hashline.store` 里。

## 跨线程的动态绑定只有两条路

- **传参**，或者**进程范围地改根**（`alter-var-root`）。`binding` 只改**当前线程**的动态栈，
  在别的线程上被**静默忽略**。家目录那条见 `docs/rules/testing.md` 的铁律——同一件事的另一面，
  不在这里重说。
