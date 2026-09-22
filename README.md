# clj-harness

## 介绍

一个 Clojure 写的 agent 内核，唯一对外接口是 **AG-UI**；前端是 TypeScript + React + assistant-ui，
经 `@ag-ui/client` 直连后端。会话历史由**客户端持有**，服务端每轮现收现算，jsonl 只是记录。

**本文只讲怎么装、怎么配、怎么起。** 它是什么、内部怎么转、接口有哪些见
[`docs/architecture.md`](docs/architecture.md)。文档分工：README 是入口，`docs/architecture/` 是现状，
`.scratch/<feature>/` 是历史（当时的决策，**不作现状读**）。

## 前置准备

- **Java 17** —— 本机默认字符集是 GBK，代码里所有字节 ↔ 字符串边界一律显式 UTF-8
- **Clojure CLI**
- **Node.js 18+ / npm**（前端与 UI 测试）
- **`bash` / `rg`**
- **Git Bash**（仅 Windows 必需：已钉 `C:\Program Files\Git\bin\bash.exe`；`System32\bash.exe` 是
  WSL 启动器，从 JVM 调用会静默空输出）

## 运行命令

```bash
node scripts/dev.mjs             # 一条命令起两个：后端 + vite（两个端口都问 OS，横幅打印出来）
node scripts/dev.mjs --port 8080 # 钉死后端端口；--ui-port 5199 钉死前端端口
node scripts/dev.mjs --tmux      # 两个进程分到左右窗格（要在 tmux 里跑）
node scripts/dev.mjs --scripted  # 脚本厂商替身：默认回放 scripts/example.json；不要 api-key、不要模型、家目录临时、跑完即删
```

**只起后端，页面发构建产物**（一个进程、一个地址）：

```bash
cd ui && npm install && npm run build   # 前置：一次性；改过 ui/src 要重跑
cd .. && clojure -M:run                 # http://localhost:8080 既是页面也是 API
```

- 页面默认取**当前目录**下的 `ui/dist`；`--ui-dist DIR` 换一个目录。
- 没有 `index.html` 就只服务 `/api`（`GET /` 的 404 会写明该跑什么命令）。
- REPL 形态：`clojure '-J-Dfile.encoding=UTF-8' -M:repl`。

**分开起 + 热更新**（改 `ui/src` 立刻见效）：

```bash
clojure -M:run --port 0   # 启动横幅会打印真正绑到的端口，以及发的是哪份 dist
cd ui && HARNESS_BACKEND_URL=http://127.0.0.1:<那个端口> npm run dev
```

停止：`Ctrl+C`，或 `Get-Process clojure,node | Stop-Process`。

验证：`clojure -M:test -m harness.test-runner`（后端；只跑几个命名空间就把名字接在后面）、
`cd ui && npm test`（前端 vitest）。**别自己拼 `(isolate!)` + `run-tests`**——家目录隔离、判据、
跑完删临时目录都挂在 runner 上，理由见 `AGENTS.md`。

## 配置说明

运行期配置与产物都住在**一个目录**里，默认 `~/.clj-harness/`（换位置设 `CLJ_HARNESS_HOME`）：

```
config.edn    唯一一份配置：:default（三个旋钮）+ :providers（厂商目录），每轮重读
harness.edn   用户级 harness 配置（可选）：编辑模式、围栏、技能根、指令文件
hooks.edn     hook 声明（可选；不存在 = 这个点没人监听）
mcp.edn       MCP 服务器声明（可选；不存在 = 一个都没声明）
.env          密钥：一家厂商一把 <ID>_API_KEY，全局 HARNESS_API_KEY 兜底；优先于真实环境变量
harness.infra.db         sqlite：项目 / 会话归属 / 归档 / 文件锚点
projects/<项目>/*.jsonl  会话日志，按项目分目录
```

- **缺失与空是同一件事**（= 什么都没说）；**存在却写坏一律指名绝对路径硬失败**。四个 `.edn` 都现读，
  改完不用重启；旧形状的 `config.edn` 启动时自动挪进 `:default`（旧的那份留作 `config.edn.bak`）。
- **`config.edn` 不用自己造**：第一次启动会替你写一份带注释的空配置；`*.edn.example`（config /
  harness / hooks / mcp）是带完整注释的参考起点。
- **技能与指令读的是 OS 家目录**（`~/AGENTS.md`、`~/.agents/skills/`），**不跟随 `CLJ_HARNESS_HOME`**。
- 首次使用：往 `~/.clj-harness/.env` 填 `HARNESS_API_KEY`（或某家厂商自己的 `<ID>_API_KEY`）。
- **要给厂商缓存做分析时才打开 LLM 流量日志**：设 `CLJ_HARNESS_LLM_DEBUG=1`（每次调用重读，不必重启），
  之后每一次模型调用都往 `<配置家>/logs/llm-debug.jsonl` 追加一行 JSON —— 请求是**发出去的原样字节**
  （前缀缓存认的就是这些字节，重编码过就不是同一个事实了），响应带厂商报的那份 `usage`（含 `cached_tokens`）。
  不设就一个字节都不写：正文会整段落盘（含文件内容），看完记得关。整棵树封顶 32MB，满了滚一份 `llm-debug.1.jsonl`。

### provider 与 model

会话由**三个旋钮**描述，写在 `config.edn`，逐旋钮合并（`:default` → 本会话覆盖 → 本次 run 的请求）：

```edn
{:default {:provider :openrouter :model "anthropic/claude-sonnet-4.5" :reasoning-effort "high"}}
```

`:providers` 里每个厂商是 endpoint + 一张 model 表，每个 model **必须**声明 `:input` / `:output`
（两个数字可选）。没登记过的 endpoint 直接 inline 描述一个，不必命名 provider：

```edn
{:protocol :openai-completions :base-url "https://some-endpoint/v1" :model "some-model"}
```

侧边栏「设置」的 General / Models 两页能改这三样与厂商目录：写 `config.edn` 前先校验整份、再原子落盘，
密钥只进 `.env` 且**永不回显**。形状与校验细节见 [`docs/architecture/providers.md`](docs/architecture/providers.md)。

### 其余旋钮

```clojure
;; ~/.clj-harness/harness.edn；项目级在 <项目>/.harness/harness.edn，整键替换用户级
{:editing      {:mode :hashline}                 ; 默认按锚点；:str-replace 是原版 edit
 :instructions {:files ["AGENTS.md"]}            ; 整表替换默认值；相对路径按项目根解析
 :skills       {:roots ["/abs/skills" ".agents/skills"]}}
```

`:editing` 是**唯一逐键**合成的块，全部键与默认值都在 `harness.edn.example`。

- **hook**（`hooks.edn`）：一个 hook 点上一行声明，`:command`（经 shell）或 `:run`（进程内函数，
  只有配置家与本会话能写）。payload 走 stdin JSON，退出码 **0 放行 / 2 阻断**（stderr 回喂模型），
  超时与崩溃都不炸 run。见 [`docs/architecture/hooks.md`](docs/architecture/hooks.md)。
- **MCP**（`mcp.edn`）：`{:servers {"workshop" {:command "node" :args ["…"]} "depot" {:url "https://…"}}}`，
  工具以 `mcp__<server>__<tool>` 进表，与内建工具走同一个执行缝；连不上不拖死任何人。见
  [`docs/architecture/mcp.md`](docs/architecture/mcp.md)。

### 工具

- `glob` 按**名字**找文件；`todo_write` 记本会话的任务清单（一次送**完整**清单，空数组即清空），
  **答案只报存了几项、各自什么状态**——清单就是刚送进去的那一份，不念回来。同理，`write` 的答案是
  `wrote N chars to PATH` 加一句「锚点已释放，去 `read`」：**写不是读**，刚写进去的内容不必回放第二遍。
- `web_fetch` 取 URL 正文（**有损的文本抽取器**，不是渲染器）；`web_search` 的键按
  **Brave → Exa → Tavily**（`BRAVE_API_KEY` / `EXA_API_KEY` / `TAVILY_API_KEY`）顺序取，全都没有就指名拒绝。
  两个出网工具**不带审批**——这是决定（`bash` 今天就能 `curl`），要这道坎的会话自己装规则。
- `bash` 有 `timeout`（默认 120000ms，到点**连子孙一起**停）、`stdin`（写完随即关掉，读它的命令看到
  EOF）与 `workdir`（不给就是本会话的项目目录）。**答案有上界**：默认带命令输出的最后 8000 字节
  （stdout 与 stderr **各算各的**），超出时整份落成一份记录，答案里写着**省略了多少字节、那份记录在哪**——
  再大的输出也读得回来（`bash` / `read` / `grep` 读同一个路径）。
  **不等的另一半是 `job`**：调用立刻返回，答案是 job id 与记录路径，命令在后台跑到自己结束
  （它没有时限，也不吃 `stdin`——两个键在那儿会被指名拒绝）。
- 读它用 `job_output`（头一行是状态，正文是它说过的最后一段；`offset` 从头翻，`wait: true` 挂到它结束，
  超时答 `[running]` 而不是报错）；停它用 `job_kill`（不等到进程死透，停过之后**再问一次照样答**）。
  记录在 `<配置家>/jobs/<会话>/句柄-进程戳.log`，末行 `[exit N]` / `[stopped]`，**没有末行 = 还在跑**；
  名字里那截进程戳说的是「哪一次运行写的」，所以同一个会话重启之后不会写到上一次的记录上。**记录活过
  写它的那个进程**：JVM 退出只收走作业与句柄，文件留在配置家，`read` / `grep` 照样读得到（昨天下班前那次
  长跑，今天还查得回来）；整棵树按字节封顶，超了从最旧的一份开始删。
- **它结束了会告诉你**：没人等的作业跑完之后，它的结局会在**你下一次开口之前**摆在上下文里——
  一条 `<job-ended id="…">[exit N]</job-ended>` 加一行「用 `job_output` 读它」的注入，**两样事实，
  与记录多大无关**，说一次；不是推送（不唤醒、不新起一轮）。自己 `job_output` / `job_kill` 看过的结局不再重复说。
  「轨迹」那一栏（会话界面里）看得见这条注入。
- **注入物在「会话」那一栏里也看得见**：每一条注入在会话里画成一张**可折叠的卡**（与工具卡同一套壳，
  折着只有一行 `上下文注入 · <首行> · N 字节`，点开是那些字节）。刷新之后还在——它由记录里的帧重建；
  而**下一轮请求里没有它**（客户端不回发），所以会话照旧是干净的。「轨迹」那一栏照旧。

设计与代价（system 消息怎么拼、技能怎么加载、围栏与审批、日志每一行长什么样）见
[`docs/architecture.md`](docs/architecture.md)。
