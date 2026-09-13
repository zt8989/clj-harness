# 01: 配置根 ns + `CLJ_HARNESS_HOME` 覆盖 + 调用点改造 + 项目更名

> 本票是**一次动刀**：配置根与项目改名合并做（`~/.lisp-harness` → `~/.clj-harness` 本就是改名的切面），避免同一批文件改两遍。

## A. 配置根

**What to build:** 一个配置根，所有路径从它派生。

**新 ns `harness.home`**（或并入 `harness.memory`，实现时定——但**必须是单一来源**）：

```clojure
(def ^:dynamic *root-override* nil)   ; 测试专用；生产恒为 nil（见 02）

(defn root [] (or *root-override*
                  (System/getenv "CLJ_HARNESS_HOME")
                  (str (System/getProperty "user.home") "/.clj-harness")))
(defn config-file    [] (io/file (root) "config.edn"))
(defn providers-file [] (io/file (root) "providers.edn"))
(defn dotenv-file    [] (io/file (root) ".env"))
(defn logs-dir       [] (io/file (root) "logs"))
(defn sanitize       [thread-id] (str/replace (str thread-id) #"[^A-Za-z0-9._-]" "_"))
(defn log-file
  ([thread-id]     (io/file (logs-dir) (str (sanitize thread-id) ".jsonl")))
  ([dir thread-id] (io/file dir (str (sanitize thread-id) ".jsonl"))))
```

`root` **每次现算**（读环境变量是廉价的），与 `mem/config` 的「每次重读」规矩一致；**不 defonce 缓存**，否则测试里的 `binding` 与运行期的环境变量都不会生效。

`*root-override*` 是 dynamic var，**只服务测试隔离**（02 用它 `binding`）：绑定作用域内临时值，出 `binding` 即消失，不构成第二份真相源。生产代码永不设置它。

**三处调用点改造：**

1. `memory.clj:197` `(edn/read-string (slurp "config.edn"))` → 读 `(home/config-file)`。
2. `http.clj:43` `(io/file (str (System/getProperty "user.home") "/.lisp-harness/logs") ...)` → 改用 `(home/log-file thread-id)`。
3. `opaque.clj:25` `(dotenv/env "HARNESS_API_KEY")` → 需指定 `.env` 路径。**实测 `lynxeyes/dotenv` 1.1.0 的 API**：若它只支持默认 cwd 查找，则改为**自己读 `(home/dotenv-file)` 解析**（保持 `dotenv/env` 的优先级语义：`.env` 里的值优先于真实环境变量），并把该决定记入票面。

**同源收口（有限度，别过头！）**：`replay.clj:25` 与 `http.clj:43` 各有一份 sanitize 正则。**只收口 sanitize 这个纯函数，不收口路径拼接。**

**理由（`replay.clj:25-32` 的 docstring 已明写，不得推翻）**：replay 的 `log-file` 签名是 `[dir thread-id]`，dir 由调用者给——它是**纯函数式读侧**，不应该知道 `harness.home`。共享整个函数会让**内核长出读侧**，而「内核不得读自己的日志」是本仓铁律。**收口 sanitize（两侧必须逐字一致的唯一部分）即可；路径拼接各留各的。**（上方 `log-file` 保留双 arity 就是为此。）

`log-path`（04 引入）包在 `harness.memory` 侧，调 `(home/log-file thread-id)`。

## B. 项目更名 `lisp-harness` → `clj-harness`

**实测引用清单（13 处 + 编译产物，勿遗漏）**：

| 文件 | 行 | 处理 |
|---|---|---|
| `README.md` | 1 | `# lisp-harness / minimal-kernel` → `# clj-harness` |
| `README.md` | 68 | `~/.lisp-harness/logs/...` → `~/.clj-harness/...` |
| `src/harness/http.clj` | 43 | 上一段 A 已改（路径搬家） |
| `test/harness/ag_ui_test.clj` | 207, 212 | 测试数据里的 `"lisp-harness"` → `"clj-harness"`（两处成对） |
| `test/harness/http_test.clj` | 59 | 上一段 A 已改（log-dir），顺带改名 |
| `test/harness/llm_test.clj` | 20 | 注释里的路径 `lisp-harness/src/...` → `clj-harness/src/...`（或去掉仓库名前缀） |
| `test/harness/tools_test.clj` | 48 | `(is (str/includes? (:content (call "bash" {:command "pwd"})) "lisp-harness"))` —— 意图是「bash 的工作目录是项目根」。**目录未改名所以它仍会通过，但项目已更名后这条断言就是在说谎**：它断的是目录名含旧名。改为与真实 cwd 比较（如 `(= (str (System/getProperty "user.dir")) (str/trim content))`，注意 bash 可能给 POSIX 风格路径，需要规范化），票面注明所选写法 |
| `ui/index.html` | 6 | `<title>` → `clj-harness` |
| `ui/package.json` | 2 | `"name": "lisp-harness-ui"` → `"clj-harness-ui"` |
| `ui/package-lock.json` | 2, 8 | 同上（**手改或重跑 npm install**，票面注明用哪种） |
| `ui/cljs-out/` | — | **编译产物**，内容含旧名与否不重要——**应确认它是否该被 git 忽略**（若未忽略则加入 `.gitignore`） |

**目录名不改**（牛总裁定）：`tools_test.clj:48` 因此仍会通过——但**它断言的是「目录名含 lisp-harness」，在项目已更名后属于说谎的断言**，必须改写成不依赖仓库名的形式（见上表）。

## 缺文件的行为（承接 A）

`config.edn` 缺失时抛**指名路径**的异常（`"config.edn not found at <abspath>; create it or set CLJ_HARNESS_HOME"`）。`providers.edn` 缺失本票不管（属 eval-self-extension/05）。`.env` 缺失不算错误（离线/脚本 provider 不需要 key），API key 取不到时的现行为不变。

**Blocked by:** None

**Status:** ready-for-agent

- [ ] `CLJ_HARNESS_HOME` 设置后，config / logs（以及 05 之后的 providers）全部从该目录读写；未设置时用 `~/.clj-harness`
- [ ] `root` 每次现算、**不缓存**；`*root-override*` 的 `binding` 与真实环境变量都能立即生效（各写一条断言锁住）
- [ ] `memory.clj:197`、`http.clj:43`、`opaque.clj:25` 三处改造完成，**代码中不再存在任何裸相对路径 `slurp` 配置**（grep 可验）
- [ ] `sanitize` 收口到 `harness.home` 一份；**`replay.clj` 的 `log-file` 保留 `[dir thread-id]` 签名与纯读侧性质**（不得改成依赖 `harness.home`）；断言两处对同一 thread-id 产出同一文件名
- [ ] `.env` 从 `(home/dotenv-file)` 读取；**优先级语义保持**（`.env` 值优先于真实环境变量），并有一个断言证明**从非 cwd 也能读到 key**
- [ ] `config.edn` 缺失时报错信息**含绝对路径**且提示可用 `CLJ_HARNESS_HOME` 覆盖
- [ ] **上表 13 处引用全部改完**：`grep -ri "lisp-harness" src test ui README.md` 结果为空（`ui/dist/` 与 `ui/cljs-out/` 除外——它们是产物，重建即可）
- [ ] `ui/package-lock.json` 的改名方式在票面注明（手改 vs 重跑 `npm install`）
- [ ] `ui/cljs-out/` 若未被 git 忽略则加入 `.gitignore`；确认 `ui/dist/` 已在忽略中
- [ ] `tools_test.clj:48` 的断言不再依赖仓库目录名（目录未改名，但断言应更稳）
- [ ] `prompt.md` 仍从仓库根读（本特征的唯一例外，注释里写明理由：它是被 review 的代码资产）
- [ ] README 更新：项目名 `clj-harness`、新增「配置家目录」章节（目录结构、`CLJ_HARNESS_HOME`、首次使用的建目录步骤、`prompt.md` 为何例外）
- [ ] 离线全量 harness.test-runner 全绿（只增不减）
