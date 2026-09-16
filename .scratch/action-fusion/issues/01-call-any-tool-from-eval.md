# 01 — 名字 + 参数 map：从 eval 里调用任何工具

**What to build:** 在 eval 里**一行**调用工具表里的任何一个工具，拿到与顶层调用同样的结果 map——
不用手写 provider 形状、不用自己 JSON 编码参数。于是「读完二十个文件」「改完一个文件再跑测试」
在一个 eval 里是两三行 Clojure，按返回值分支与循环都是语言本来就有的东西。

这一票不新增工具、不改任何工具的定义：它加的是 `harness.tools` 里的**一个函数**。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 形状

```clojure
(harness.tools/call! "read" {:path "deps.edn"})
;; => {:content "…" :error false}          ;; 与顶层调用同样的结果 map

(mapv #(harness.tools/call! "read" {:path %}) ["deps.edn" "README.md"])
```

- **参数是关键字键的 Clojure map**：JSON 编码是缝自己的事，不该是调用者的仪式。
- **`thread-id` 默认 `harness.tools/*thread-id*`**，也就是当前会话。这一条是承重的：相对路径的重根、
  `bash` 的 cwd、围栏的判定都挂在会话绑定上，默认到当前会话意味着**内层调用与顶层调用遵守同一套规矩**，
  而不是「从 eval 里发出去的调用碰巧忘了绑会话，于是对着进程 cwd 裸奔」。
- 三参形态（显式 thread-id）留给测试与工具内部要对另一个会话说话的情形。

## 为什么不是「模型自己 def 一个」

它当然可以，而且这恰好证明能力是真的。但那样每个会话会各写一份**略有出入**的版本——忘了传 thread-id、
忘了对结果判 `:error`、把参数 map 当成 JSON 已经编好——而这些差异的代价是路径落错地方。
一个入口、一处 docstring、一套断言，是这个仓一贯的做法（`harness.shell` 存在的理由与它一模一样：
决定 spawn 哪个 shell 的坑是机器的属性，不是调用方的）。

## 验收

- [ ] `call!` 收「工具名 + 关键字键的参数 map」，返回与 `run!` 同样的结果 map（`:content` / `:error`，
      以及被拒时的其它键照旧）
- [ ] 不传 thread-id 时默认当前会话：绑定到项目的会话里，相对路径的 `read`/`write` 落在项目目录、
      `bash` 的 cwd 是项目目录、出界路径照旧 park 或照旧放行——**与顶层调用的行为逐条相同**
- [ ] 一次 eval 里循环调用 N 个工具并返回它们的结果（批量读一条断言；写一条断言）
- [ ] 按返回值分支：一个调用失败时序列据此改道（断言失败分支被执行过）
- [ ] 未知工具 / 本会话关闭的工具经 `call!` **返回**错误结果，不抛——按值交给序列
- [ ] **`specs` 与改动前逐字节相同**（每个工具的名字 / 描述 / 参数 schema 一字未动）。
      这是本特征的第一条硬断言：融合不往工具定义上加东西
- [ ] `run!` 本身一字不变：provider 形状的调用照旧，既有断言一条不改写
- [ ] 离线全量 `harness.test-runner` 全绿（`clojure -M:test -m harness.test-runner`，退出码是信号；
      新开的测试命名空间记得进 `test-runner/test-namespaces`，漏了会**静默不跑**）

## 测试卫生

探针与测试里踩过的一条：`CLJ_HARNESS_HOME` 必须指向一个**已经存在**的目录，否则每个碰项目的工具都以
`SQLITE_CANTOPEN` 失败，看起来像工具坏了。
