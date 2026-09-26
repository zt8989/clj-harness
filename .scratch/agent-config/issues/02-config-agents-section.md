# 02 — `config.edn` 的 `:agents`：第四节、默认档、校验、写盘

**What to build:** 给 `config.edn` 开**第四节** `:agents`，并把它读对、写对。

- **读**：`config.edn` 的顶层闭集由三节变四节（`providers/config-sections`，`src/harness/cap/providers.clj:433`），
  那句"must be a map of the sections (:default, :providers and :ui)"（`:461`）与 `written-header`（`:1854`）
  一起改成**四节**。`:agents` 的每一层**逐键校验**，坏值**按名**失败并说出**哪个文件、哪个键、怎么写**——
  照 `cap/editing.clj:90` 起那套 `{:ok predicate :legal "example"}` 的形状写，别发明第二种。
- **默认档在代码里**（决策 6）：家里没有这一节、或某一层缺席 ⇒ 用 01 目录里的默认，答案与今天逐字相同。
  文件里只写**与默认不同的格**，页面的勾也只想动它自己那一格。
- **项目级覆盖**（决策 5）：`<项目>/.harness/harness.edn :agents` 与 `config.edn :agents` 按**同名键**
  合并（project 胜，与 `hooks.edn` 的整键替换同一规则，`cap/hooks.clj:67` 是它的先例）。
- **写**：`write-config!`（`providers.clj:1903`）整份重写时，`:agents` 与 `:default` / `:providers` / `:ui`
  **互不污染**——写 `:agents` 时那三节**逐字不变**，反过来也一样。备份（`config.edn.bak`）照旧。
- **退役两处旧家**（决策 4）：
  - `harness.edn :editing {:mode}` —— 留着它的家**按名拒绝**，话里指向 `config.edn` 的
    `:agents .. :enhance :editing`；`:grep` 一格同理指向基础层的工具格；其余 `:editing` 键照旧。
  - `harness.edn :subagents`（`baseline` / `exclude`）—— 按名拒绝，指向 `config.edn :agents :subagents`；
    它今天的写通道（`cap/subagents.clj:810` `put-definition!` / `:838` `remove-definition!` /
    `:730` `write-user-file!`）随之退休，改写 `config.edn`。**这一条与 03 共用**（03 才拆掉读的那一半），
    本票负责"写到这里来 + 旧的读法点名拒绝"。

**要点：**

- **一份声明、多处选择**（决策 3）：本票**只**处理 `:agents` 里那些**选择**（能力名、`:enhance` 的取值、
  服务器名、技能名、`:hooks` 的 `:off` 名单）。hook 命令行、MCP 的 `:command`/`:url`、
  技能正文仍然住在 `hooks.edn` / `mcp.edn` / `SKILL.md`，`config.edn` 里**一个都不许出现**。
- **保存即拒的那两条硬规则**：子 agent 勾 `eval` 或委派工具；`:read-only? true` 的子 agent 勾了
  不能证明只读的能力。两条都在**写盘之前**按名拒（不是写进去再解析时说），话里说清是哪个名字、为什么。
- **`:hooks` 覆盖**（决策 14）：`:base :hooks {:off [..] :on [..]}`。为了让一条 `hooks.edn` 声明能按名点，
  声明多一个**可选** `:name`（`kernel/hooks.clj` 的 `check-declaration` 与 `declaration-keys`，`:189`）。
  没写名字的仍按今天的位置 id（`stop#0`）寻址；两种都认。
- **`SKILL.md` 的两个新键**（决策 12）**不在本票**：那是 04 的活，本票只把 `:compose :skills :load` 的
  一个**技能名**校验成"这个根里真有这个技能"（用 `cap/skills.clj` 的 `known-names`）。
- **不许静默兜底**：一节读不动（不是 map、键不认识、值不合法）就**按名拒绝**，绝不"当成没说"——
  `harness.edn` 那句"一个被忽略的配置和一个没说过话的配置从外面看一模一样"（`harness.edn.example` 开头）
  是本票的验收尺子。

**Blocked by:** 01（层与能力的目录、默认档、解析口径都由它给）

**Status:** ready-for-agent

## 现场

- 顶层闭集：`providers.clj:433` 的 `config-sections` = `#{:default :providers :ui}`，`:461` 的失败话术与
  `:1854` 的 `written-header` 是它的两个嘴。`providers.edn` → `:providers` 那次搬迁（`migrate-config!`，`:1934`）
  是"旧家按名拒绝、话里指向新家"的**现成先例**，本票照它写 `:editing {:mode}` 与 `:subagents` 两条。
- 配置读法：`home.clj` 的 `config-file`（`:79`，家目录那一份）与 `config-files`（`:343`，harness.edn 的
  user ⊕ project 两级）。`config.edn` **没有**项目级，所以项目覆盖走 `harness.edn`（决策 5）。
- 默认档来源：`cap/tools.clj` 的 `@built-ins`（`:53`）＋ 01 的目录；`:editing` 的默认（`:hashline`）
  今天写在 `cap/editing.clj` 的配置表里。
- 写盘：`providers.clj:1903` `write-config!`（原子落 ＋ `config.edn.bak`），
  `home.clj:101` `spit-atomically!`。

## 验收

- [ ] 一个只有三节的家照旧工作（默认档全对）；一个带 `:agents` 的家读出人写的那几格。
- [ ] 顶层多一个键 ⇒ 失败话术说的是**四节**（`:default` / `:providers` / `:agents` / `:ui`），并说出文件路径。
- [ ] `:agents :main :base :tools` 里放一个不认识的名字 ⇒ 按名拒，话里列出**认识的名字**；
      放一个已知名字、但那是**增强层成员**（比如 `replace`）⇒ 也拒，并说它属于 `hashline` 那一组、不可单独勾。
- [ ] `:enhance :editing` 放 `:vim` ⇒ 按名拒，话里给出两个合法取值。
- [ ] 子 agent 勾 `eval` / `agent` ⇒ 保存被拒（两条用例）；`:read-only? true` 的子 agent 勾 `write` ⇒ 保存被拒。
- [ ] 项目级 `.harness/harness.edn :agents` 覆盖同名键：一个项目写 `:enhance {:editing :str-replace}`，
      另一个不写 ⇒ 两个会话各看到自己的编辑模式（**这一条就是决策 5 要保住的按项目分档**）。
- [ ] 写 `:agents` 前后，`config.edn` 的 `:default` / `:providers` / `:ui` **逐字节相同**；写 `:default` 前后
      `:agents` 逐字节相同。
- [ ] 一个 `harness.edn` 还写着 `:editing {:mode :str-replace}` 的家 ⇒ **按名拒绝**，话里指向
      `config.edn` 的 `:agents .. :enhance :editing`；写着 `:editing {:grep false}` 的 ⇒ 指向基础层的 `grep` 一格；
      写着 `:subagents {..}` 的 ⇒ 指向 `config.edn :agents :subagents`。**三种话都要说清文件与键。**
- [ ] `hooks.edn` 里一条带 `:name` 的声明能被 `:base :hooks :off` 按名点掉；不带名字的仍能按位置 id 点。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（`providers_test` / `editing_test` / `subagents_test`
      里受影响的用例**改写成等价断言**，不许删）。
