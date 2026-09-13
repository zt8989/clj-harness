# 02: 测试隔离 —— 临时 home，永不触碰真实目录

**What to build:** 测试套件把配置根指到一个**临时目录**，从而彻底隔离读写。

**为什么必须做**：现在 `http_test.clj:59` 直接写 `(System/getProperty "user.home")/.lisp-harness/logs`——**单测真的在污染家目录**。在沙盒环境里这个写入被重定向，于是出现「测试绿、日志在宿主机看不见」的怪象（已实测：全盘 find 0 个 jsonl，服务却正常流回帧）。**这是本特征要根除的体验问题。**

**机制选型（已调研，别再选别的）**：JVM 里 `System/getenv` **不可写**，社区唯一做法是反射改 `ProcessEnvironment.theEnvironment` 的 backing map（原作者自己标注 "Definitely not safe"）。**不采用**——脆弱、依赖 JDK 内部结构。

**采用 `binding` + dynamic var**：

```clojure
;; harness.home
(def ^:dynamic *root-override* nil)

(defn root []
  (or *root-override*
      (System/getenv "CLJ_HARNESS_HOME")
      (str (System/getProperty "user.home") "/.clj-harness")))
```

生产路径：`*root-override*` 恒为 nil，只有 `CLJ_HARNESS_HOME` 生效——**无第二份真相源**。测试路径：`(binding [home/*root-override* tmp-dir] ...)`——零依赖、线程安全、自动还原、不泄漏。

**为什么这不算第二份真相源**：`*root-override*` 是**绑定作用域内的临时值**，不是常驻可变状态，出了 `binding` 就消失。它更像「测试用的参数注入」而非「另一个配置来源」。

**做法**：一个 fixture 包住全量测试，在临时目录里**播种最小 `config.edn`**（否则 run 起不来），跑完删目录。

**Blocked by:** 01 的配置根 ns + 调用点改造

**Status:** ready-for-agent

- [ ] `harness.home/*root-override*` 为 dynamic var，`root` 优先级 = override → `CLJ_HARNESS_HOME` → `~/.clj-harness`
- [ ] 测试套件用 `binding` 把根指向临时目录；结束后自动还原（`binding` 的天然语义，**不需要手工清环境变量**）
- [ ] 临时目录里播种最小 `config.edn`（让 http 集成测试能起来）
- [ ] **断言：跑完全量测试后，真实 `~/.clj-harness` 与 `~/.lisp-harness` 均无新增文件**（跑前后比对文件数/mtime）
- [ ] `http_test.clj:59` 的 `log-dir` 改为从配置根派生，不再硬编码 `user.home`
- [ ] 测试**可在任意 cwd 下运行**（不依赖从仓库根启动）——文档说明 + 至少一条断言锁住
- [ ] 清理失败不掩盖测试失败：`finally` 里删除失败只打警告，不再抛
- [ ] 引入**零新依赖**（binding 是 Clojure 原生）；若最终仍需依赖，必须在票面写明为何 binding 不够
- [ ] README 或测试文件头注明「测试用临时 home，不碰真实数据」
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
