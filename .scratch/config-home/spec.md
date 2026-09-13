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

## 落地后的收尾（不在本特征的票内，但别忘）

- **`.workbuddy/memory/MEMORY.md` 需在 01 落地后同步**：标题 `# lisp-harness — 项目长期约定` → `# clj-harness — 项目长期约定`；日志路径 `~/.lisp-harness/logs/...` → `~/.clj-harness/...`。**必须等代码改完才改它**——它描述的是现状，先改就成了伪史。
- **`.workbuddy/memory/2026-09-*.md` 的日志文件不改**：那是历史事实，改了就是伪造记录。
- **仓库目录改名**（`lisp-harness` → `clj-harness`）由牛总自行执行——本特征只保证代码与文档内的引用改完，目录名不影响测试通过。

## 状态

- 01（配置根 ns + 环境变量覆盖 + 调用点改造 + 项目更名）：未开始
- 02（测试隔离：临时 home + 断言不写真实目录）：未开始
