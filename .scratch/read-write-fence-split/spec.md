# spec: read-write-fence-split（围栏只管写；读只由本家的敏感清单拦；内置清单不可删）

一句话：把「出界即挂起」的**项目围栏只留给写**——`write` / `edit` / `replace` / `insert` /
`undo_last_replace`；**读**（`read` / `grep` / `glob`）不再因为「出了项目」挂起，只被这一家的敏感清单
（`config.edn` 的 `:security :sensitive-paths`）拦住。同时那份清单从「写一份就整份取代内置」改成
**内置 ∪ 自定义**：内置条目永远在、面板上不可删，只有自定义的那些能增删。

## 背景与问题

围栏今天是**一张按路径算的规矩**，不分这道调用是读还是写：绑定了项目的会话里，件件带围栏的文件工具
（`read` / `write` / `edit` / `replace` / `insert` / `undo_last_replace` / `grep` / `glob`）只要目标落在
自由集（项目目录、配置家、临时目录、技能根、`:approval {:allow ..}`）之外，就 park 等一个人点头；
敏感清单是更紧的另一半，命中即 park。

这个「不分读写」在一次真实使用里挡了路（2026-10-01）：一场绑在 `…/workspace/lisp-harness` 的会话，
agent 去读同级的 `…/workspace/pi-hashline-edit-pro/src/…`，`read` 与 `grep` 接连 park，
记录里是 `tools/pre-execute outcome=needs-approval`。人真正想拦的是**写**；读一条本来就能经 `bash`
（`cat` / `rg`）读到的文件，却在 `read` 上停下来问人——这道门挡的是手滑，不是越权，而它问的次数太多，
多到人会习惯性点「批准」。

所以这一票改的是**分工**，不是松紧：

- 写侧一寸不让：出界照旧 park，敏感清单照旧在上头；
- 读侧只剩一条规矩——本家点名敏感的路径不能直接读（照旧 park、批准即可读），别的一律直接读；
- 而敏感清单本身要**保得住**：今天它是「写了就整份取代内置，写 `[]` 就是这一家什么都不守」，
  一处手滑（或一个 `[]`）能把凭证保护整个关掉。它要改成**内置永远在 ∪ 本机自己写的**。

## 决策

### A. 围栏按调用种类分家

- **两半规矩都留在 `harness.cap.tools`**，紧挨着今天那个 `fence`：
  - `fence`（照旧，写侧）：`:sensitive-path` 先判，`:out-of-bounds` 后判；
  - 新增 `sensitive-fence`（读侧）：只判 `:sensitive-path`。
  两条都只是把「问哪几个谓词」摆出来，谓词仍是 `harness.cap.project/sensitive-path?` 与
  `out-of-bounds?`——**一处规矩一处实现**，不新造第三份判据。
- **哪件工具带哪半**，只写在工具自己的 `:park-reason` 上（今天就是这么接的）：
  - 读：`read` / `grep` / `glob` ⇒ `sensitive-fence`；
  - 写：`write` / `edit` / `replace` / `insert` / `undo_last_replace` ⇒ `fence`（不变）。
- **`harness.cap.project` 一个字不改语义**：`out-of-bounds?` 与 `sensitive-path?` 还是那两个问题，
  `fence`（那份自由路径数据）还在，只是今天问它的只剩写。`:out-of-bounds` 从此**不可能**是读的原因。
- **读侧的三条工具描述必须跟着改**，否则描述在说谎（写侧的五条一个字不动）。改法是**不抄清单**，
  只说规矩：读不受项目限制，落在这一家点名的敏感路径上会先挂起等批准。清单本身仍由每 run 现算的
  `<project>` 块说——那是唯一的出处。
- **`bash` / `job` 照旧不带围栏**，这是明示接受的逃逸面，本次不动（也正是它让「读要拦」的论据站不住）。
- **`:approval {:strict true}` 与 `:approval {:allow ..}` 从此只作用于写**：strict 拿掉项目目录那一格，
  读侧看不见它（读本来就不问自由集）。

### B. 敏感清单 = 内置 ∪ 自定义，内置不可删

- `config.edn` 的 `:security :sensitive-paths` **只装自定义那半**（今天的含义是「整份清单」）。
  生效清单 = `harness.cap.providers/default-sensitive-paths` **前** + 文件里那些**后**（顺序只是显示，
  判据是重叠，与顺序无关）。
- `providers/sensitive-paths-config` 的答案换成三方：**生效的（as written）/ 内置的 / 自定义的**。
  `:source` 那个键退休——它的意思是「这份清单是内置还是你自己写的」，两组一分就不成立了。
- `providers/sensitive-paths`（`~` 展开后的生效清单）签名不变，只是它的来源多了一半。
- `set-sensitive-paths!` 只写自定义那半；**写 `[]` 不再是「什么都不守」**，而是「没有自定义条目」，
  内置照旧。因此**没有任何一条配置能把内置清单关掉**——这是本次有意丢掉的一条旧能力
  （旧 spec 曾把 `[]` 当决策；新决策是：一份能被一个 `[]` 关掉的凭证清单不是清单）。
- 旧配置里写过整份清单（含内置条目）的：行为变成「内置 ∪ 那些」，重复条目只是重复，不报错、不改写文件。
- 老文件不迁移：`:security` 的形状没变，变的只是它的**解释**。这一点必须写进文档与工具提示，
  因为它是一处静默的行为变化。

### C. 界面与边

- `GET /api/security` 分别答**内置**与**自定义**两组（`~` 原样、as written）；`POST /api/security`
  的 body 键仍旧，写的只是自定义那半。
- 设置面板那一行画两组：**内置（不可删除）**与**本家自己写的**（可删、可加），
  「恢复内置清单」这个按钮的意义随之消失，换成**清空自定义**（自定义为空时禁用）。
- 中英两种文案都在。

## 非目标

- 不给 `bash` / `job` 加围栏（`workdir` 也不加），也不给它们的命令内容做任何判定。
- 不为读另造一张「允许读」的白名单——读没有围栏，只有这一家的敏感清单。
- 不做「关掉/覆盖内置清单」的开关，不做按项目覆盖清单。
- 不改敏感清单的判据（重叠：在里面，或含有它）、`~` 指向 OS 家目录的规矩、相对条目按项目根解析的规矩。
- 不改 `harness.cap.project/out-of-bounds?` / `sensitive-path?` / `fence` 的语义与签名。
- 不为 park 造新的帧、新的 reason 词汇（读侧只用 `:sensitive-path`，写侧仍是那两个）。
- 不顺手修「项目级配置」那批过时文档（`docs/architecture/home-and-storage.md` 的「两级装配」、
  `cap/project.clj` 的 ns 注释说 `.harness/harness.edn` 还能加 `:allow`）——另开一票。
- 不做 park 理由的界面/审计行（今天 `tools/pre-execute` 只记 `needs-approval`，卡片只说
  「Approve \`read\`? arguments: …」）。这条欠账在本次之后只会更疼，但另算。

## 验收主线

后端整轮 `clojure -M:test -m harness.test-runner` 只增不减，且：

1. 绑定会话里 `read` / `grep` / `glob` 打在**项目外、又不在敏感清单**上的路径 ⇒ **真的执行**，
   无 `:run/interrupt`、无 parked 记录；同一条路径的 `write` ⇒ 照旧 park，原因 `:out-of-bounds`。
2. 绑定会话里 `read` 一条**敏感清单**里的路径 ⇒ park，原因 `:sensitive-path`（与今天一致）；
   `grep` 一个**含有**敏感路径的目录 ⇒ 也 park（重叠判据不变）。
3. 未绑定会话：读一律直接跑（敏感清单除外）；写本来就没有围栏，两种形状逐字节同今天。
4. `:approval {:strict true}` 只收紧写：strict 项目里 `read` 项目内的文件照旧跑，`write` 它照旧 park；
   `:approval {:allow ..}` 放行的是一条**写**。
5. `:security :sensitive-paths ["~/my/secret/"]` ⇒ 生效清单 = 内置 17 条 + 这一条；把这一条删掉
   （写回 `[]`）⇒ 内置 17 条照旧在；配置里**没有任何写法**能让内置条目消失。
6. `GET /api/security` 答得出内置与自定义两组、且两者都是 as written（`~` 原样）；
   `POST /api/security {"sensitive-paths": [..]}` 只写自定义那半，写前留一代 `.bak`，答回来是新的生效清单。
7. 设置面板：内置一组（每条**没有**删除控件）、自定义一组（可删）、能加一条、能清空自定义；中英文案都在。
8. `<project>` 块把两半说对：自由路径那半明说是**写**的规矩；读侧那条（只由本家的敏感清单拦）
   在**绑定与未绑定两种形状**里都说得出来。
9. `cd ui && npm run typecheck` / `npm run build` / `npm test` 三样与基线相比只增不减（这台机器上
   `npm test` 的既有失败按 `.scratch/security-sensitive-paths` 记录的环境原因处理，不新增失败）。
10. `node scripts/dev.mjs --scripted` 起来的服务，人自己开浏览器走一趟：把它读项目外一个文件
    ⇒ 不再弹卡；对它写同一个目录 ⇒ 照旧弹卡。

## 测试与缝

**缝选在最高的两处既有点上，不新造缝。**

- **执行缝**（`test/harness/approval_test.clj`，今天的围栏用例就在这里）：一次真 run、真的
  `harness.kernel.tools/run!`。要改的是 `a-bound-session-parks-an-out-of-bounds-path`（它今天用
  **`read`** 演「出界即 park」——换成 `write`），并新增：同一条路径的 `read` 跑通、`grep`/`glob`
  跑通、读一条敏感路径 park 且原因 `:sensitive-path`。**这是本次最重要的一条缝**：它同时钉住
  「写没松」与「读松了」。
- **清单**（`test/harness/cap/providers_test.clj`）：`sensitive-paths` 的并集、`[]` 不再关掉内置、
  `set-sensitive-paths!` 只写自定义那半、坏形状按名字失败。
- **谓词**（`test/harness/cap/project_test.clj`）：`out-of-bounds?` / `sensitive-path?` 的行为照旧
  （本次不改它们的语义，这几条是回归网）。
- **块**（`test/harness/cap/system_prompt_test.clj`）：`<project>` 块两种形状的措辞。
- **边**（`test/harness/edge/http_test.clj`）：`GET/POST /api/security` 的真 HTTP 形状与 `.bak`。
- **界面**：`ui/src/lib/securitySetting.ts` 的形状解析（纯函数，vitest），加一条新 suite 把那一行
  **渲染成字符串**读回 `data-slot`（先例：`ui/test/suites/sidebar.tsx`、`subagents.tsx`）；
  内置行没有删除控件这件事只有渲染得出来。这一行今天**没有任何 UI 用例**，是新缝，但先例齐全。

**什么是好用例**（与本仓既有纪律一致）：只用外部行为说事——一场 run 的帧序列、结果里有没有这次调用、
parked 记录上的 `:reason`、路由答回来的 JSON、渲染出的字符串；不去断言内部函数怎么分工。

## 拆票

| 票 | 内容 | 依赖 |
|---|---|---|
| 01 | `cap.tools`：读侧换成 `sensitive-fence`，三条读工具的描述改写 | — |
| 02 | `cap.providers`：清单合成内置 ∪ 自定义、`[]` 的新解释、`check-config` 措辞、`config.edn.example` | — |
| 03 | 边与面板：`GET/POST /api/security` 的新形状、设置面板两组、中英文案 | 02 |
| 04 | `<project>` 块与文档：`system-prompt` 的块、`projects.md` / `providers.md` / `system-prompt.md` / `CONTEXT.md` | 01、02 |
| 05 | 全量验收：后端整轮 + UI 三样 + 走查，逐条对上面的验收主线 | 01–04 |

## 状态

- 01 / 02 / 03 / 04 / 05：全部落地（分支 `read-write-fence-split`）。

## 落地与验证记录

### 改了什么

- **`src/harness/cap/tools.clj`**：新增 `sensitive-fence`（只答 `:sensitive-path`，从不答
  `:out-of-bounds`）；`read` / `grep` / `glob` 的 `:park-reason` 从 `fence` 换成它，三条描述改写
  （明说读不受项目围栏）。`write` / `edit` / `replace` / `insert` / `undo_last_replace` 仍用 `fence`。
- **`src/harness/cap/providers.clj`**：`configured-sensitive-paths` 只返回自定义那半；新增
  `distinct-by-paths` / `effective-sensitive-paths`（内置 ++ 自定义，按展开路径去重）；`sensitive-paths-config`
  改成 `{:paths :builtin :custom}`（退休 `:source` / `:defaults`）；`set-sensitive-paths!` 只写自定义，
  `[]` 有效且不关内置；`check-config` / `config-sections` / `security-keys` 措辞同步。
- **`src/harness/edge/http.clj`**：`security-wire` 答三键；`security-get` / `security-post` 文档与 400 句改写。
- **`src/harness/cap/system_prompt.clj`**：`<project>` 块明说围栏是**写**闸、读不受它约束；敏感那半说
  「读只为这个原因 park」；strict 句限定为写。
- **界面**：`lib/securitySetting.ts` 换新形状（`sensitive-paths` / `builtin` / `custom`）；安全行抽到
  `components/security-paths.tsx`（画内置一组不可删、自定义一组可删 + 「清空我加的」）；`settings-panel.tsx`
  改为挂载它；中英文案改写。
- **文档**：`docs/architecture/{projects,providers,system-prompt}.md`、`CONTEXT.md`、`config.edn.example`。
- **测试更新**（旧写法断言的是旧语义，逐条改成新语义，不新增「关掉守则」的后门）：`approval_test`、
  `providers_test`、`project_test`、`system_prompt_test`、`http_test`、`glob_test`、`read_test`、
  `delegation_test`；新增 UI suite `security-paths`（4 例，`EXPECTED_CASES` 211 → 215）。

### 验证

- **后端**：`clojure -M:test -m harness.test-runner` ⇒ 1427 tests / 14572 assertions，失败的只有
  既有/环境两类，且**在未改动的 `main` 上以同一类面目出现**（`main` 整轮 = 1431 tests / 14604 assertions，
  同样 2 处 store + 1 处 http 计时用例）：
  - `cap/hashline/store_test` 的 `a-store-with-the-home-tables-migrates-without-losing-a-row`（2 处）：它写死的
    表清单缺 `model_calls`，而 `model_calls` 在我这条分支之前就已在 `infra/db.clj` 里 —— 与本次无关，未动。
  - `edge/http_test` 里**每次不同的**一个计时用例（`heals-on-the-next-read` / `a-record-that-cannot-be-written` /
    `the-settings-endpoint-...-never-the-key`，分别在只跑/整轮/再整轮里各挂一次，且同一用例随后又能过）：
    `http_test` 整跑单跑过一次 0 失败，改动没有触碰这些路径。
  本次新增与改写的用例（读/写分缝、清单并集、`<project>` 块、`/api/security` 三键、`glob`/`read`/
  `delegation` 的读侧断言）**全绿**。
- **界面**：`npm run typecheck` 通过；`npm run build` 通过；`npm test` ⇒ 215 passed（含新增 4 例）。
- **走查**：`node scripts/dev.mjs --scripted` 起服务（http://127.0.0.1:8114），开浏览器进设置页 ——
  敏感路径那行画两组：内置一组每条带「内置」标签、**没有**删除按钮；输入 `~/my/secret/` 点「添加」后，
  自定义组出现该行且带「移除」按钮，同时出现「清空我加的」；内置组不变。

### 已知欠账（本次不做）

- `tools/pre-execute` 只记 `needs-approval`、审批卡片不说 reason，读侧 park 与写侧 park 在界面/审计里
  仍分不出 `:sensitive-path` / `:out-of-bounds`。
- `cap/project.clj` 的 ns 注释与 `docs/architecture/{projects,home-and-storage}.md` 里「项目级配置两级装配」
  的说法过时（config-merge 已合成单层），另开一票。
