# spec: config-merge（harness.edn / mcp.edn 收进 config.edn，只留 config + hooks）

Status: ready-for-agent

一句话：**配置家只剩两份能改的文件**——`config.edn`（所有配置）与 `hooks.edn`（声明表）。
`harness.edn` 的七个顶层键收进 `config.edn` 的 `:session` 段，`mcp.edn` 的 `:servers` 收进 `:mcp` 段；
**项目级那一档去掉**；开机时老文件的内容并进新段、**原文件改名成 `.bak`**。

## 背景：今天是什么形状

一套配置散在四份可选文件里，且每一份都各有一个项目级副本：

| 今天 | 顶层键 | 项目级 |
|---|---|---|
| `~/.clj-harness/config.edn` | `:default` / `:providers` / `:ui` / `:security` | 无（`.scratch/security-sensitive-paths` 特意如此） |
| `~/.clj-harness/harness.edn` | `:editing` / `:compaction` / `:llm` / `:approval` / `:skills` / `:instructions` / `:subagents` | `<项目>/.harness/harness.edn`（顶层键浅合并，项目赢） |
| `~/.clj-harness/mcp.edn` | `:servers` | `<项目>/.harness/mcp.edn`（整份取代） |
| `~/.clj-harness/hooks.edn` | 点名 → 声明 | `<项目>/.harness/hooks.edn`（按点取代） |

四份的理由各自成立（当时一条一条定的），合起来却要一个新人打开四个文件才知道「我的 harness 是什么样」。
`sensitive-paths` 那一轮已经先把「清单」放进了 config.edn：再加一段的成本，比多开一份文件低。

## 决策（本次已定）

1. **形状**：`config.edn` 顶层从此**六段**——`:default` / `:providers` / `:ui` / `:security` / `:session` / `:mcp`。
   - `:session` 装 harness.edn 今天的七个顶层键，**原样**搬：`:editing`、`:compaction`、`:llm`、
     `:approval`、`:skills`、`:instructions`、`:subagents`。键名与各键的形状（含「未知键按名字失败」）
     一律不变——搬的是**住址**，不是内容。
   - `:mcp` 装 `{:servers {..}}`，即今天 mcp.edn 的顶层。
2. **目前没有项目级。** `.harness/harness.edn` 与 `.harness/mcp.edn` **不再被读**；`harness-config` 只剩一级。
   后续要恢复时，形状是**同一段在两个文件里、浅合并、项目赢**——写在这里，免得下次从头想。
   （`.harness/hooks.edn` 不在此列，见 4。）
3. **迁移：并进去，然后把老文件改名。** 开机时（与 `ensure-config!` / `migrate-config!` 同一处）若
   `harness.edn` / `mcp.edn` / `.harness/harness.edn` / `.harness/mcp.edn` 存在：
   - 内容**逐键并入**新段，`config.edn` 已有的键**赢**（新文件是真相，老文件是历史）；
   - **原文件改名**成 `<原名>.bak`——不是「留一份副本」，是**搬走**：那个名字从此不存在；
   - 打印一行说清「搬了哪几个键、哪些因为 config.edn 已经有了而没搬、`.bak` 在哪」；
   - 读不动（不是 EDN、不是 map）的文件**一个字节都不动**，只把读取器那句按名字的失败打出来——
     迁移不能让一份人写的文件消失在自己的解析错误里。
4. **`hooks.edn` 不合并。** 它是**声明表**（点名 → 声明，退出码决定放行、stdout 是内容），不是配置；
   它两级同名、**按点取代**的规矩也与配置的合并规矩不同。只留 config + hooks——`hooks` 就是留下的那一个。
5. **`:security` 与 `:providers` 永远用户级。** 今天没有项目级了，这条自动成立；**将来恢复项目级时不许**
   让它们回来——一份能被项目关掉的凭证清单不是清单（`.scratch/security-sensitive-paths`）。

## 归属表：谁在读哪一段（改动的依据）

| 今天的键 | 谁读它 | 合并后 |
|---|---|---|
| `:editing` | `harness.cap.editing`（两级**逐键**合成） | `config.edn` 的 `:session :editing`，单级 |
| `:compaction` | `harness.edge.compaction/config`（逐键） | `:session :compaction` |
| `:llm` | `harness.edge.llm-timeout`（逐键） | `:session :llm` |
| `:approval` | `harness.cap.project/fence` | `:session :approval` |
| `:skills` | `cap.project/skill-roots` → `cap.skills/root-layers` | `:session :skills` |
| `:instructions` | `cap.project/preamble-files` → `cap.preamble/instruction-files` | `:session :instructions` |
| `:subagents` | `harness.cap.subagents`（设置面板也写它） | `:session :subagents` |
| mcp.edn 的 `:servers` | `harness.cap.mcp/config`（项目级整份取代） | `:mcp :servers`，单级 |
| hooks.edn | `harness.cap.hooks`（两级、按点取代） | **不动** |

读取侧要动的入口：`cap.project/harness-edn-levels`（今天的 `{:user :project :files}`）、
`cap.project/harness-config`、`infra.home` 里那几份 `*-file` 路径、`cap.mcp/read-mcp-edn`。
**键的语义一行不改**：`editing` 的逐键合成、`:compaction` 的两个比例、`:approval` 的围栏、
`:skills` / `:instructions` 的路径解析，全部照旧——单级之后那些「两级逐键」的机械自然退化成一次读。

## 非目标

- 不合并、不读 `.harness/hooks.edn`（唯一留下的项目级）。
- 不动 `.env`（密钥）、`prompt.md`（仓库里的代码资产）、`.harness/subagents/` 与 `.harness/skills/`（保留目录）。
- 不改 `config.edn` 其余四段的语义与形状。
- **不做兼容读**：读到还叫 `harness.edn` / `mcp.edn` 的文件不按它配置，只在日志里警告一行（迁移没跑过，
  或跑失败了），**不失败**——离线工具、只读的调用者不该因为一份历史文件而跑不起来。
- 不新增配置**来源**（`:session` 就是老那七个键的新住址），也不新增界面。

## 与 `.scratch/plugin-tree` 的先后

`plugin-tree`（24 张票，四层 → 三类服务的宽重构）要把这些命名空间整个搬家（`cap.mcp` → `ctx.mcp` 接缝、
instructions/skills 归 `ctx.systemPrompt` 那一带）。**config-merge 先落地**：它是**文件形状**的小改，
不动命名空间的归属；反过来先做 plugin-tree，它的归属表与守卫表就会把今天这份四文件形状写成既成事实，
再搬一次。两者的交集（mcp / instructions / skills）以 config-merge 为准。

## 验收主线

1. 配置家里「能改的」只有两份：`config.edn` 与 `hooks.edn`（`.env` / `harness.db` / `logs/` 照旧）；
2. 一个**老的家**（harness.edn + mcp.edn 都在，且与 config.edn 有重叠键）开机后：新段里有内容、
   重叠的键按 config.edn 赢、两个老文件变成 `.bak`、日志一行说清搬了什么；
3. `:session :approval {:strict true}` 与今天的 `:approval {:strict true}` 行为**逐字节相同**（围栏那几条测试）；
4. `<项目>/.harness/harness.edn` 里放什么都不再影响任何会话（放了也不报错，只是没人读）；
5. 全量套件只增不减（本机跑不动的那几个——`http-test` / `shell-test` / `npm test` 的环境性红灯——如实记，
   见 `.scratch/security-sensitive-paths/spec.md` 那一节）。

## 实现进度（2026-09-30：全部落地）

**票 02–07 全部落地**（票按仓库规矩删掉，留下的只有这份 spec）。合并前的分支 `config-merge`
上的票 01 也一并带过来了；`main` 已整个合进本分支（合并时只有一处冲突：`project.clj` 里两边各加了
一个函数，`migrate-legacy-project-config!` 与 `listed-dir`，两个都留）。

判据（合并后的树上，逐个命名空间）：

| 批次 | 规模 | 结果 |
|---|---|---|
| 配置相关 15 个命名空间 | 425 tests / 2535 assertions | 0 失败（修掉两处**我的**断言：`skills`/`preamble` 的失败句子现在说 config.edn） |
| 其余 47 个命名空间（edge.\* / kernel.\* / infra.\* / cap.\* / session-tools / evals / test-runner） | 743 tests / 10041 assertions | 1 失败，**在 main 上同样红** |
| 更早那两批（edge 一批、其余一批） | 225 + 227 tests | 0 失败 |

**两个与本次无关的既存失败（两条都在 main 上复现过）**：

- `harness.kernel.hooks-test`：`PreCompact` 点带着 `:stdout :content`，而那条断言要求只有
  `SystemPrompt` 带 `:stdout`——main 新加的 compaction 点没跟着改这条测试。
- `harness.cap.claims-test`：`a-second-jvm-owns-a-conversation-until-it-goes-away` 里等子 JVM
  写那行日志只给 5 秒，这台机器上不够。

**这台机器上跑不动的那些（与本次无关，改前基线即如此）**：`harness.edge.http-test`（300s 撞墙，
基线同样）、`harness.infra.shell-test`（基线时全量就是它先撞）、`harness.kernel.tools-test`（栈停在
`bind!` 的 sqlite commit）、`harness.cap.mcp-wired-test`（8 条 run 用例，基线上同样 8 条）、
`cd ui && npm test`（`SQLITE_BUSY` + 120s 超时，基线同样红）。

**前端**：`npm run typecheck`、`npm run build` 绿。默认起服务那一步的浏览器走查见
`.scratch/security-sensitive-paths/spec.md`（敏感路径那一轮做过；本轮改的是设置面板的文案与
subagents 的写入路径，`npm run build` + 面板文案的人工核对已做，**没有**再走一遍完整走查）。

## 实现进度（2026-09-29）

- **票 01 已落地并删票**（本分支，绿）：`providers` 的段表收进 `:session` / `:mcp`，形状检查只做
  「未知键按名字失败」这一层，两个读取入口 `session-config` / `mcp-servers`（保留 nil 与 {} 的区分）。
  判据：`providers-test` 96 tests / 547 assertions；`project-test` + `system-prompt-test` + `editing-test`
  + `mcp-test` 118 tests / 737 assertions——**0 failures**。
- **票 02 起了头、停在半路**：分支 `config-merge-02-wip` 上一个 WIP 提交（`edac6d8`）——
  `project.clj` 的读取侧已改（删 `read-harness-edn` / `harness-edn-levels`，`harness-config` 改读
  `providers/session-config`，新增 `harness-config-path`），`compaction.clj` 与 `llm_timeout.clj` 已单级。
  **但 `editing.clj` 与 `subagents.clj` 还在调已被删掉的 `harness-edn-levels`——那个分支编译不过**，
  接着做先跑 `clojure -M -e "(require 'harness.edge.http)"`。
- **03–07 一行没动。**
- 为什么停在这里：票 02 顺带要求把约十个测试夹具从「写 harness.edn」改成「写 config.edn 的 `:session`」，
  而其中多数今天靠**项目级** harness.edn 设 `:editing` / `:skills` / `:instructions` / `:approval`
  ——项目级一去掉，它们的写法全要改（那也是票 02 验收里「`.harness/harness.edn` 写什么都不再影响会话」
  这一条要钉住的东西），再加十几个命名空间的测试轮次，不是一次坐得完的量。
