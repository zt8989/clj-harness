# spec: 配置家目录与项目更名（clj-harness）

干两件同源的事：① 把所有**运行期配置与产物**收进一个可被环境变量覆盖的家目录；② 把项目从 `lisp-harness` 更名为 `clj-harness`。

两件事合并，因为 `~/.lisp-harness` → `~/.clj-harness` 的搬迁本来就是改名的一个切面——分开做会把同一批文件改两遍。

## 问题（实测）

**根因一：没有单一的配置根。** 三处病灶：

1. **散落在两个根**：`config.edn` / `prompt.md` 从**当前工作目录**读（`memory.clj:23`、`memory.clj:197` 用裸 `slurp`），而 jsonl 日志写在 `~/.lisp-harness/logs/`（`http.clj:43`）。测试从不同 cwd 跑就读到不同配置。
2. **`.env` 依赖 cwd**：`lynxeyes/dotenv` 默认在**当前工作目录**找 `.env`（`opaque.clj:25`）。服务从别的目录启动就拿不到 key；测试沙盒里 cwd 不同更拿不到。
3. **测试写真实数据**：`http_test.clj:59` 直接把 `(System/getProperty "user.home")/.lisp-harness/logs` 当输出目录——**单测真的在写家目录**。沙盒环境下这个写入被重定向，于是「测试通过但宿主机看不见日志」的怪象（已实测：全盘 find 0 个 jsonl，服务却正常流回帧）。

**根因二：项目名过宽泛。** `lisp-harness` 让「Lisp」泛指一族语言，而本仓实为 Clojure 项目（`.clj` / `harness.*` ns / `deps.edn`）。实测引用 13 处（详见 01）。

## 决策

- **单一配置根 `~/.clj-harness/`，由环境变量 `CLJ_HARNESS_HOME` 覆盖。**
  ```
  ~/.clj-harness/
  ├── config.edn      默认档
  ├── providers.edn   具名注册表（eval-self-extension/05 引入）
  ├── .env            HARNESS_API_KEY
  └── logs/*.jsonl    会话日志
  ```
- **项目更名为 `clj-harness`**（2026-09-13 牛总裁定）。范围：
  - **仓库目录本身暂不改名**（牛总裁定）——当前会话的工作目录就是它，物理重命名会让会话路径失效。**代码与文档内的引用全部改完**，目录你以后自己改。
  - UI 子项目：`lisp-harness-ui` → **`clj-harness-ui`**（package.json / package-lock.json / index.html 标题全改）。
  - **不留别名、不做兼容读**：`~/.lisp-harness` 与 `lisp-harness` 字样在代码中彻底消失。
- **`prompt.md` 留在仓库根**（牛总裁定）。它是被 review 的代码资产，改它必须有 git 历史；搬进家目录会让它脱离版本控制。它是本特征**唯一的例外**，且理由明确。
- **不做迁移，新目录冷启动**（牛总裁定）。`~/.lisp-harness` 只有历史 logs，直接改用新路径，不搬运。旧目录不删、不读、不管。
- **缺文件时报错并指名路径**（牛总裁定）。不自动播种、不静默回退默认值——「自动写用户家目录」不可接受。报错要能直接告诉人「该在哪建什么」。
- **一份根，处处派生**：路径构造集中在一个 ns，`log-path`、`config`、`providers`、`.env` 全从它派生。**禁止任何裸 `slurp` 相对路径**。
- **测试隔离靠 `binding`，不靠环境变量硬设**：JVM 的 `System/getenv` **不可写**，社区唯一做法是反射改 `ProcessEnvironment`（原作者自标 "not safe"）——**不采用**。改用 `harness.home/*root-override*`（dynamic var）+ `binding`：零依赖、线程安全、自动还原，生产恒为 nil 故不构成第二份真相源。

## 非目标

- 不做配置迁移工具（牛总已裁定不迁移）。
- 不做配置的热重载通知（保持现有的「每次重读」规矩即可）。
- 不改 prompt.md 的读取语义（仍冻结一次 + `reset-prompt!`），只改它的**位置决策**（留仓库）。
- 不做多环境 profile 切换（一份 config.edn + providers.edn 足够）。
- **不改 git 历史、不改仓库目录名**——目录重命名由牛总自行执行。

## 验收主线

离线全量 `harness.test-runner` 全绿，且**跑测试不再向真实 `~/.clj-harness` 或 `~/.lisp-harness` 写任何文件**（用临时目录断言）。

## 与 eval-self-extension 的关系

本特征是 `eval-self-extension/05`（`providers.edn`）的前置：05 新增的注册表要有个确定的家。05 的路径须依本特征而定，故本特征先落地。

## 已验证到什么程度（2026-09-13，提交 `92febbc`）

票 01、02 已落地，验收项逐条覆盖：

- **配置根**：`harness.home` 建立，解析顺序 `*root-override*`（测试）→ `CLJ_HARNESS_HOME` → `~/.clj-harness`，`root` 每次现算不缓存。
- **调用点**：`memory/config`、`http/log!`、`opaque/api-key` 三处改造完成；**全仓已无裸相对路径读配置**。`lynxeyes/dotenv` 依赖**已移除**（实测它从 cwd 载入 `.env` 并缓存在 `def`，既指不到家目录也读不到运行期修改）；改为自解析并保留其优先级语义。
- **sanitize 收口**：`home/sanitize` 一份，`home/log-file` 双 arity；`replay/log-file` 保留 `[dir thread-id]`，仍是不知道 home 的纯读侧。
- **更名**：代码与文档中 `lisp-harness` 引用 **0 处残留**（`grep -ri` 全仓验过，产物目录除外）。`config.edn` → `config.edn.example` 模板；运行时配置在 `~/.clj-harness/`（已为牛总建好）。
- **测试隔离**：`test_runner` 在任何 ns 加载前 `alter-var-root` 指向临时目录，跑完清理。**实测跑完全量后 `~/.clj-harness/logs/` 为空、`~/.lisp-harness` 无新增、临时目录无残留。**
- **全量**：`70 tests / 348 assertions, 0 failures`（与基线持平，本特征不增不减断言——`tools_test` 那条改写了内容但数量不变）。

**落地中的两处判断（记在案）**：

1. **`alter-var-root` 而非 `binding`**：动态绑定不跨线程，而 http 测试的服务在别的线程写日志——`binding` 会让隔离静默失效。测试进程是一次性的，改根正是本意。
2. **`tools_test` 的 pwd 断言要规范化**：Git Bash 报 POSIX 路径（`/c/Users/...`）而 JVM 报 `C:\Users\...`，直接比较会挂。规范化后半段比较，且**不再依赖目录名**。

## 落地后的收尾

- [x] **`.workbuddy/memory/MEMORY.md` 已同步**（标题 + 日志路径）——在代码落地后改的，未伪造现状。
- `.workbuddy/memory/2026-09-*.md` 日志文件未改（历史事实）。
- **仓库目录改名**（`lisp-harness` → `clj-harness`）仍未执行，由牛总自行操作；代码与文档引用已全部改完，测试不依赖目录名。

## 状态

- 01（配置根 ns + 环境变量覆盖 + 调用点改造 + 项目更名）：**已完成** `92febbc`（票已删）
- 02（测试隔离：临时 home + 断言不写真实目录）：**已完成** `92febbc`（票已删）
