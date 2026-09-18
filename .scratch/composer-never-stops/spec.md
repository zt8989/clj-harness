# spec: 一轮结束 composer 不停 —— dev 环改直连（vite 反代会丢 SSE 最后一截）

**现象**（起点是牛总那句话）：「一轮 turn 结束，composer 还是没有停止」。带着工具调用的那一轮跑完，
composer 仍停在停止/Cancel 上，send 不回来；侧边栏那一行一直转。纯文本轮正常。刷新页面后记录是完整的
——后端那边早就写完了。

**一句话**：dev 环里页面走 vite 的反代（`/api` 前缀 → `HARNESS_BACKEND_URL`），而 vite 8.3.0 自带的
http-proxy-3 1.23.3 转发 SSE 时**偶发丢掉最后一个 chunk**：帧一个不少、终止块 `0\r\n\r\n` 永远不来
——浏览器的 `fetch` 因此永不落地，`@ag-ui/client` 那次 run 的 promise 不 settle，运行时的 `isRunning`
一直是 true。改成**页面跨域直连后端**，后端那条 CORS 放行由 `--ui-origin` 按 vite 实际端口放行。

## 定位：责任段是「浏览器与后端之间那一段」

三条路各打同一发：一个带工具调用的脚本回合（`read deps.edn` → 两段回答），抓原始字节看有没有收尾。

| 走哪条路 | 次数 | 丢 |
| --- | --- | --- |
| 直连后端（curl） | 8 + 12 + 16 | 0 |
| 经过 `vite dev` 的反代 | 16 | **3** |
| 经过手写的最小 Node 反代（带/不带 keep-alive agent） | 28 | 0 |

- 挂住的那几次**帧一个不少**（18 帧全到），就是没有 `0\r\n\r\n` 那个终止块。
- 后端自己的日志每次都有 `terminal event=RUN_FINISHED`；Playwright 那侧 `requestfinished` 在挂住的轮
  **不触发**。所以既不是「后端没收尾」，也不是「客户端没读」——是中间那一跳把收尾吃掉了。
- 手写的反代一次不丢 ⇒ 问题不在「反代」这件事，在 vite 内置的那个转发器。

**代价就是用户看到的那一幕**：`thread.isRunning` 永不复位，`AuiIf` 一直渲染 Cancel；侧栏那一行一直转。

## 决策

1. **dev 环页面直连后端。** `VITE_AGENT_URL` 填后端**自己报出来的**那个绝对地址——`ui/src/lib/threads.ts`
   本来就支持这个模式（`HARNESS = import.meta.env.VITE_AGENT_URL ?? "/"`），不需要新机制。
2. **后端的放行按请求里的 `Origin` 判**，不是按启动时定下的一个值：本机的页面一律放行并把它
   **原样答回去**，别的 origin 一个头都不给。（第一版是「只放行一个、由启动参数指定」，当天就按
   牛总的话改成现在这样，见下面「后续」。）
   - 判断只看**主机名**：`localhost` / `127.0.0.1` / `[::1]`，**整串匹配、不比后缀**（`localhost.example.com`
     不是本机），**端口不参与**——所以没有任何一个数字要两边一起改。
   - 不带 `Origin` 的请求（curl、套件、同源部署）答 `ui-origin`，也就是 `--ui-origin` 命名的那个
     （`start!` 收 `:ui-origin`）。那个选项现在只剩一种用处：**UI 不在本机**时把它的 origin 明说。
   - **决定只有一处**：`handler` 把这三个头并到路由交回来的响应上（含那条 500 的兜底路），
     `api-response` 因此完全不碰 CORS 头。唯一的例外是 SSE——它的状态与头**骑在第一帧上**、不走
     ring 响应（见 `runner`），所以那条路由把同一个头交给 `runner`。
   - 用 atom 不用 var/binding：每个请求都在 http-kit 的线程上，`binding` 在别的线程上静默失效
     （AGENTS.md）；用 def 也不行，头部 map 在加载时就把字符串烘进去了（`.scratch/tool-parity/spec.md`
     记过 `alter-var-root` 来得太晚的那个坑）。
3. **`scripts/dev.mjs` 只把后端地址告诉页面**（`VITE_AGENT_URL`）。前端在哪个端口是 **vite 自己的事，
   不再报给后端**（第一版是后端拿 `--ui-origin` 收下 vite 的端口，见「后续」）。
   `HARNESS_BACKEND_URL` 照旧喂给反代规则，所以**手动** `npm run dev` 那条路仍然指得对。
4. **`ui/vite.config.js` 那条 `/api` 规则留着**，注释降级为「手动起 dev server、且没设
   `VITE_AGENT_URL` 时走的路」。
5. **`scripts/test.mjs` 不用动。** 前端套件是在 node 里 `fetch()` 直连 harness 的
   （`ui/test/e2e.ts` 的 `runUrl()` 是绝对地址），**压根不经过 vite**，既没有代理缺陷也没有跨域限制。
   `ui/test/support/harness.ts` spawn 后端时也没必要传 `--ui-origin`（默认值与它无关）。

## 验证

- **浏览器走查**：`node scripts/dev.mjs --scripted` 起（后端交给 OS 挑、前端 5211），Playwright 连跑
  **20 轮**带工具调用（6 + 14），**全部 `cancel:false`**、每一轮 `requestfinished` 都触发。
  改前同一个脚本走反代：16 次丢 3 次。
- **后端**：`(require 'harness.edge.http 'harness.e2e-server :reload)` 加载通过；
  `clojure -M:run --ui-origin ...` 与 `--ui-origin` 透传都走了一遍。
- **脚本**：`node --check scripts/dev.mjs` 通过。
- **`node scripts/test.mjs`**（全量三条腿）：
  - 前端 build：过。
  - 前端套件：**44/44 过**（真后端、真 `@ag-ui/client`，走的就是这套改动之后的进程）。
  - 后端 leg：**红**，但那是**这台 Windows 机器上的预存篮子**——同一个提交在干净树上的对照见下。
  - **`harness.edge.http-test` 跑了（49 个命名空间里的第 48 个），0 失败**——改的就是这个文件。

### 后端 leg 的对照（2026-09-18，本机 Windows）

| 树 | 报数 | ISOLATION FAILURE |
| --- | --- | --- |
| 第一版改动（`--ui-origin` 对端口那版，21:31 跑） | 902 tests / 11514 assertions，**167 failures / 19 errors** | **有** |
| 干净 HEAD `db11a42`（另一个 worktree 单跑 `--backend`） | 902 tests / 11519 assertions，**169 failures / 17 errors** | 无 |
| 第二版（本机任何端口都放行，22:26 跑，最终形态） | 903 tests / 11533 assertions，**170 failures / 17 errors** | 无 |

- 两边同一量级，差 1～2 例——README 已经写明这个条数每次都可能不同（`http_test` 里那个真竞态的
  `the-projects-listing-joins-the-store-with-the-disk`），所以**比对看名字，不看总数**。
- **上面那一版最终形态的报数**（`node scripts/test.mjs --backend`，输出留在
  `evidence/backend-final.log`）：**903 tests / 11533 assertions，170 failures / 17 errors**，
  **没有 `ISOLATION FAILURE`**。903 与 11533 比基线多出的一测试 / 十四断言就是新加的那条用例。
- **名字级比对**（唯一一次做全的两份完整输出）：`evidence/failures-prev.txt` 与
  `evidence/failures-final.txt` 各 60 项「文件 \| 用例名」，**逐项相同**，只差 `jobs_test` 那两个用例
  **换了失败形态**——上一版以 `FileInputStream` 抛错记 2 行，这一版以断言失败记 3 行
  （`jobs_test.clj:138 / 241 / 245`）。两者都是「Windows 上没读到子进程写出的作业记录文件」，
  与 CORS 无关，且这正是「同一条用例报数不同」的来源。
- 篮子的形状很一致，都是 POSIX 假设落在 Windows 上（本版按文件聚合，前几名）：`dispatch_test` 7、
  `hooks_wired_test` 5、`glob_test` 5、`skills_test` 4、`jobs_test` 3、`hooks_test` 3；
  `layers_test` 拿 `\` 拼路径当命名空间、fence 用例拿 `/etc` 当「界外」、hook/dispatch/mcp 用例要
  spawn `bash` 脚本、`project`/`undo` 用例 fork 子 JVM、`tools_test` 那条把 `C:\Users\...` 的反斜杠
  吃掉（仓库根上那个 `CUserszhoutengAppDataLocalTempharness-tools-testredirected.txt` 就是它吐的）。
- 也就是说 README 里那句「0 failures, 0 errors」说的是另一台机器上的基线；**在本机报数时要带上
  「Windows，约 169 例本机篮子」**。

### 那条 ISOLATION FAILURE 查到了是别的进程

它报的是**真家** `~/.clj-harness/harness.db` 的 mtime 在跑测期间变了（`1789738531286` = 21:35:31）。
查真家留下的痕迹：一个**用真家目录**的 harness（`root=C:\Users\zhouteng/.clj-harness`）从 20:39:37 起
在 4834 端口上听，**21:35:44 才停**；会话期间它还在被 HTTP 驱动——`projects/…/2afa043f….jsonl` 与
`45078dbf….jsonl` 里一串 `project/bound`（`via: http`，21:35:09 → 21:35:31）。这轮全量套件 21:31:01 起跑，
所以那 4 分钟里两个进程同时活着：正是 README 写的那种「这台机器上另开的一个实例」。
基线那次 21:44:35 才起跑，那个进程已经没了，因此没触发。

**教训照抄 AGENTS.md**：跑套件之前，本机上不许有任何用真家目录的 harness 活着——库里只有一把锁。

## 代价与遗留

- dev 下浏览器真的在发跨域请求了（有 preflight）。构建产物**仍然不带我们的地址**：
  `VITE_AGENT_URL` 只在 dev server 里注入，换到任何部署自己的反代后面都一样。
- **手动 `npm run dev`（不设 `VITE_AGENT_URL`）仍然走反代**，因此仍可能撞上那个丢 chunk 的缺陷。
  要收掉它，就得让 `vite.config.js` 在 `command === "serve"` 时给一个默认的 `VITE_AGENT_URL`
  （来源就用那条规则已有的 `HARNESS_BACKEND_URL ?? 8080`）——**没做**，因为那就等于同一件事有两个
  说了算的地方，而 `scripts/dev.mjs` 是仓库里指定的那一处。README §3 已经把「要直连就带
  `VITE_AGENT_URL`」写明了。
- 丢 chunk 的那件事**没有向 vite 报**（本机 vite 8.3.0 / http-proxy-3 1.23.3）。上面的数字就是复现步骤。
- **走查用的那个 dev 环不会自己停**（`dev.mjs` 等 Ctrl-C）。第一版验证起在 5211 的那个环活到 52 分钟后
  才被收掉，期间第二次走查换端口时被告知「5211 被占」——占它的就是上一轮自己。**下一轮起之前先看一眼
  端口，或把上一轮的环停干净**；判定端口归属时 `netstat` 与 `Get-NetTCPConnection` 这台机器上会给出
  互相矛盾的说法，别在归属上纠缠，换个端口更快。

## 后续（同一天）：放行改成按请求判，本机任何端口都放行

牛总当场加了一句：「后端改成对所有 localhost 端口 cors 都放行」。第一版要两边对端口，是**能跑但没必要**
的一件事——于是规矩改成：**本机的页面一律放行**（`localhost` / `127.0.0.1` / `[::1]`，主机名整串匹配、
不比后缀，**端口不参与**），并把它**原样答回去**；别的 origin 一个头都不给。

改动落在四处：

- `harness.edge.http`：`allowed-origin` 改名 `named-origin`（它现在只是「按名字放行的那个」，与「按规则
  放行的那一堆」并列）、新增 `request-origin` / `origin-host` / `localhost-page?` / `cors-origin`，
  `cors-headers` 改成收一个 `Origin` 的**函数**。`api-response` **不再**带 CORS 头：合并只发生在
  `handler` 一处（`with-cors`），OPTIONS 那条路由因此连头都不用自己写。SSE 走 `runner` 的第一帧，
  所以 `handle-run` → `run-agent!` → `runner` 多传一个 `origin`。
  - `URI.` 解析 `Origin` 值：只认 `http`/`https`，主机名去掉 IPv6 的方括号再比；
    `null`（沙箱 iframe / `file://`）解析不成 URL，自然落进「不放行」。
- `scripts/dev.mjs`：**把 `--ui-origin` 和 `uiOrigin` 删掉**。前端端口从此刻起只是 vite 的事，
  没有任何人要跟它对齐。（`uiEnv` 仍然给页面 `VITE_AGENT_URL`，也给反代规则 `HARNESS_BACKEND_URL`。）
- `dev/harness/e2e_server.clj`：`--ui-origin` 透传**整个撤掉**，退回本次改动之前的样子——
  套件在 node 里直连，没有 origin 可用，这个选项在仓库里没有任何调用方。
- `--ui-origin` 留在**生产入口**（`-main` / `start!`）：它只剩「UI 不在本机」这一种用途，
  docstring 已按这个说法改写。

### 这一版的验证

- **线上矩阵**（curl 直打后端，`--ui-origin` 一次都没传）：

  | `Origin` | 答 |
  | --- | --- |
  | `http://localhost:5212`（真实前端端口） | 原样答回 |
  | `http://localhost:9999`（没人配过的端口） | 原样答回 |
  | `http://127.0.0.1:3000` | 原样答回 |
  | `http://evil.test` | **无 CORS 头** |
  | `http://localhost.evil.test` | **无 CORS 头**（后缀不算本机） |
  | 不带 `Origin` | 答 `ui-origin`（默认 `http://localhost:5173`） |

  preflight（`OPTIONS` + `Origin: http://localhost:5212`）→ `204`，三个头齐全。
- **新用例**（`test/harness/edge/http_test.clj`，`says-which-origin-per-request-and-not-once-per-process`）：
  上面那张表的每一行都钉住了，另加一条只有这个位置能看见的——**带工具调用的那一轮，SSE 第一帧上
  `Access-Control-Allow-Origin` 正好一个**（`handler` 并的是 ring 响应、`runner` 写的是第一帧，
  重复头要在这里才看得见）。
- `node scripts/test.mjs --ns harness.edge.http-test` → **64 tests / 708 assertions，0 失败 0 错误**。
- **浏览器再走查一遍**（`--ui-port 5212`，`dev.mjs` 不再传 `--ui-origin`）：一个带工具调用的回合，
  采样两个按钮 → `0ms cancel=true` → `1000ms` 工具卡片到 → `1600ms cancel=false, send=true`，
  页脚「1 轮 · 2 次模型调用」。证据：`evidence/composer-back-to-send.png`、
  `evidence/walkthrough-2026-09-18.md`。

### 留下的取舍

- 放行的是**主机名**，不是「本机这个 IP」：一台把页面从 LAN 地址（`http://192.168.x.x:5173`）打开的
  浏览器**不**在放行之列——那种 origin 猜不出来，要用 `--ui-origin` 明说。这是有意的：规则要能一眼看懂。
- `ui/vite.config.js` 那条 `/api` 反代规则还在，手动 `npm run dev` 时仍会走它（因此仍可能撞上「丢最后一个
  chunk」那个缺陷）。上面的「代价与遗留」里那一条仍然成立。

## 收尾（同日）：把测试里写死的端口拿掉

规矩改了之后，测试里还留着一块旧契约的化石，牛总点到：`test/harness/edge/http_test.clj` 自己声明了
`(def ^:private ui-origin "http://localhost:5173")`。它错在两头：

- **同一件事有了第二个说的人。** 那个值的出处是 `harness.edge.http/ui-origin`，测试另抄一份，改一边
  就有另一边在说假话。
- **测试里写死了端口。** 这正是同一文件 `*port*` 的 docstring 花一整段拒绝的事（AGENTS.md：起服务一律
  `{:port 0}`——字面端口会让同一台机器上的两次跑互相撞，而它们**经常**同时跑）。

改法：五处断言改读 `harness.edge.http/ui-origin`。**关系仍然钉着**——一个不带 `Origin` 的请求，答的是
**这个进程启动时那个** origin，而 `with-server` 只给 `{:port 0}`；名字级的断言因此从「等于 5173」
升级成「等于那个唯一出处」。原处留了一段说明，免得下次有人把字面量「顺手」加回来。新用例的示例 origin
里也不再出现 5173，另加一行**根本不写端口**的 `http://localhost`——「端口不参与判断」由它说得更直。

**同一物种还有一处，比它还危险**：`ui/test/e2e.ts` 的 `url()` 在没配置时回落到写死的默认端口。这与
`ui/test/support/harness.ts` 头注释里写明的意图**直接冲突**（那里写着「一次跑测不能被碰巧听着那个端口的
旧服务满足」，所以后端一律 `--port 0`、端口从 `PRINT-READY` 行读回来）。没配置就悄悄用字面端口，等于让
套件去和**别人**的服务说话，还可能就此通过。现在它跟同文件另外三个取值口一样：读不到就**抛**。

验收：`--ns harness.edge.http-test` → **64 tests / 708 assertions，0 失败 0 错误**；`--build --ui` →
build 过、套件 **44/44 过**。全仓再筛一遍：`test/` 里剩下的带端口字面量（`http://127.0.0.1:1/mcp`、
`http://localhost:11434/v1`）都是**夹具 URL**（「永远没人应答」的端口 1、provider 的 base-url），
不是去绑的端口；所有 `http/start!` 一律 `{:port 0}`。

## 状态

已落地（本提交）。全量三条腿（`node scripts/test.mjs`）：前端 build **过**、前端套件 **44/44 过**、
后端 **170 failures / 17 errors**（本机 Windows 篮子，与干净基线的失败**名字**逐项相同，见上）。

`main` 与本分支的基点同在 `db11a42`、没有另一条线要合，所以合入是一次**快进**：落进 `main` 的就是
这一个提交。
