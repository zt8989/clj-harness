# spec: security-sensitive-paths（敏感路径清单：命中即 park，界面可改）

给 `harness.cap.project` 的围栏补一条**更紧**的规矩：本机有一份「敏感路径」清单（云凭证、私钥、
容器与包管理器凭证），**文件工具落到清单里的路径上就 park 等一个人点头**——哪怕那条路径本来在围栏之内。
清单写在 config.edn（这一家自己的配置），并在设置面板里可增删、可恢复默认。

## 背景与问题

围栏今天是**一张自由路径表**：项目目录、配置家、临时目录、技能根、`:approval {:allow ..}` 之外一律 park。
它管的是「**这是不是本项目的地盘**」，不是「**这条路径本身危不危险**」。两种情形因此漏掉：

- 项目就绑在 `$HOME` 上（或 `:allow` 声明得宽）：`~/.ssh/id_rsa`、`~/.aws/credentials` 都**在围栏之内**，
  read/grep 直接跑，不问人；
- 会话没有绑定：围栏整个不在，任何绝对路径都直接跑。

敏感清单补的正是这个「路径本身危不危险」的维度，而且它是**这一家（home）的**政策，不是某个项目的：
项目不该能把它关掉。

## 决策

- **清单住在 config.edn 的 `:security {:sensitive-paths [..]}`**，第四段。理由有两条，都不是偏爱：
  1. 它是**用户级**事实（我的机器上哪些文件是秘密），项目级 harness.edn 的浅合并会让一个项目的
     `:approval` 整段盖掉用户级同键——**一份能被项目悄悄关掉的安全清单不是清单**；
  2. 界面写配置这条路**已经存在且只对 config.edn**（`providers/write-config!`：先整体 `check-config`
     再原子写 + 留一代 `.bak`），harness.edn 至今没有写入口。
- **命中即 park，不是硬拒绝。** 与围栏同一条能力边界、同一张审批卡、同一个 resume 契约：决定权在人手里。
  新增的只是 park 的**原因** `:sensitive-path`，好让人和审计看得出「这条是本机点名的秘密」，
  而不是「这条不在本项目里」。
- **清单带内置默认值**（`providers/default-sensitive-paths`，16 条），config.edn 里写了就**整份取代**它。
  这不是静默兜底：`GET /api/security` 答 `:source :default | :config`，面板按来源说话；默认表就在
  `harness.cap.providers` 里，和内置 provider 表同一套规矩（config.edn 点名一个内置 provider 不需要任何条目）。
- **`~` 指操作系统家目录**（`home/user-home`），不是配置家。清单里写 `~/.ssh/`，落到 `/home/你/.ssh/`。
  相对条目按任何工具路径一样**对项目目录解析**（`project/resolve-path`），未绑定则原样通过——一条规矩，
  不新造第二条。
- **判据是「重叠」，不只是「落在里面」**：目标路径在敏感路径之内（或相等）⇒ park；敏感路径在目标路径之内
  ⇒ **也** park。后一半是给 glob / grep 这类**成目录读**的工具留的：`grep ~` 读的正是 `~/.ssh/id_rsa` 的内容，
  只说「路径不在清单里」等于没管。代价写清楚：项目绑在 `$HOME` 上时，对 `$HOME` 本身做目录级操作会 park
  ——那正是要防的那件事，不是误伤。
- **管哪些工具：今天带围栏的每一件文件工具**（read / write / edit / replace / insert / undo_last_replace /
  search(grep) / glob）——即 `:park-reason (fence ..)` 这一族，一处规矩一处实现，不按工具逐个点名。
  `bash` **不在内**：它本来就没有围栏，在它前面摆一道门是装样子（CONTEXT.md 已写死的判断）。
- **不绑定的会话也守这条规矩。** 围栏是项目的，敏感清单是这一家的：一个「没有项目就自动失效」的保护等于没有保护。
  这是本次**有意**改掉「未绑定 = 逐字节旧行为」的那一条不变量，只在命中时改变行为；system prompt 的
  `<project>` 块在**两种**形状下都把清单说出来，否则块就在说谎。
- **清单是围栏的一部分，所以只有一个来源。** `<project>` 块从 `harness.cap.project/sensitive-paths` 读，
  门禁从同一个函数判——两处各写一份，模型就会以为某条路径自由而实际被拦。
- **命中的原因写作 `:sensitive-path`，并且排在 `:out-of-bounds` 前面。** 两个都成立时（正常绑定的会话读
  `~/.ssh/id_rsa`）报更具体的那一个：它说的是「这是本机点名的秘密」，而围栏那句在另一处仍然会拦。
- **工具描述（每个工具自己那句）不动。** 规则由每次 run 现算的 `<project>` 块说；把敏感清单抄进 8 条工具
  描述里，等于给它 8 个会漂的副本。

## 非目标

- 不合并配置文件（harness.edn → config.edn、只留 config + hooks）：那是另一张 spec，本次不做。
- 不做硬拒绝 / 不可放行；不做按项目覆盖清单；不做路径的 glob 模式（清单是**路径**，不是通配符）。
- 不给 `bash` 加围栏（顺带也不给它的 `workdir` 加）。
- 不做敏感清单的审计行 / 历史面板；park 原因照旧只落在既有的待决记录上。
- 不新增帧类型、不改 resume 契约、不新增配置来源。

## 验收主线

后端全量只增不减，且：

1. `~/.ssh/id_rsa` 在**项目绑在 `$HOME`** 的会话里 `read` ⇒ park，原因 `:sensitive-path`（此前直接跑）；
2. 同一条路径在**未绑定**的会话里 `read` ⇒ park，原因同上；
3. 命中「敏感路径在目标之内」（`grep` 一个含 `~/.ssh` 的目录）⇒ park；
4. 清单之外的路径：行为与现状逐字节相同（绑定会话照旧按围栏判，未绑定照旧直接跑）；
5. config.edn 写 `:sensitive-paths []` ⇒ 这一家自己把它关掉，且 `GET /api/security` 答 `:source :config`；
6. config.edn 写一个不是字符串列表的值 ⇒ 下次读**按名字失败**（不是静默忽略）；
7. `POST /api/security {sensitivePaths: [..]}` ⇒ 校验通过才写，写前留一代 `.bak`，答回来的是新的生效清单；
8. 设置面板：清单能看、能删一条、能加一条、能恢复默认，中英两种文案都在。

## 落地与验证记录（2026-09-29）

- 后端：`harness.cap.providers-test` / `project-test` / `system-prompt-test` 共 **155 tests / 889 assertions 全绿**；
  `subagents-test` + `layers-test` 共 28 tests / 337 assertions 全绿；`approval-test` 那一批（21 tests / 104 assertions）也全绿。
- **这台机器上跑不完的命名空间（与本次改动无关，已在改前的基线上复核）**：`harness.edge.http-test`
  （7275 行，300s 上限撞墙；基线同样撞，放宽到 1200s 仍挂在 `mux_run_BANG_`）、`harness.infra.shell-test`
  （基线时一次全量就是它先撞 300s）。因此 `POST/GET /api/security` 的用例写在 http-test 里（跟随既有惯例），
  它的判据由下面的浏览器走查补上；`kernel.tools-test` 也因 jobs 记录读取挂住，未拿到判据（其改动只有文档字符串）。
- 浏览器走查（`node scripts/dev.mjs --scripted`，隔离家 + 临时 OS home）：
  1. 清单里**没有**这条路径时，脚本 turn 的 `read /home/zhouteng/.ssh/id_rsa` **直接执行**（Done）——对照组；
  2. 在设置面板把 `/home/zhouteng/.ssh/` 加进清单 ⇒ `config.edn` 写入 `:security`、留 `config.edn.bak`，面板改成
     「这份清单是这一家自己写的」并出现「恢复内置清单」；
  3. 再发同一句话 ⇒ 那次 `read` **挂起等审批**（卡片显示 Approve / Deny），而该会话是**未绑定**的
     （记录落在 `projects/.unbound/`）——未绑定也照守，正是本次要的那条；落地记录里 `tools/pre-execute` 的
     `outcome=needs-approval`；
  4. 按 **Deny** ⇒ run 照常跑完（模型拿到被否决的工具结果）；
  5. 按**恢复内置清单** ⇒ 清单回到 17 条内置、自定义那条消失。
- 前端机器门：`npm run typecheck` ✓、`npm run build` ✓。
- **没做**：这个浏览器后端到不了 `127.0.0.1`，走查是经本机 `eth0`（172.20.x）地址访问的；另外 CORS/多标签页等
  与本次无关的路径没有覆盖。
- **`cd ui && npm test` 在这台机器上本来就红**，两个方向都量过：本次改动 9 failed / 166 passed，改前基线
  10 failed / 165 passed，失败集合基本重合（`turn` / `approval` / `elicitation` / `mux` / `concurrent` / `client`），
  原因是 `POST /api/sessions` 拿到 `[SQLITE_BUSY] database is locked` 与 120s 超时——环境问题，与本改动无关：
  这几个套件里没有一个碰设置面板或 `/api/security`，而两次跑的失败条数本身就不同（13 → 9）。
  因此**前端这一格我没有拿到全绿的判据**，只有 typecheck / build / 走查三样，按仓库规矩如实记在这里。
