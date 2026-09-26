# 04 — 按 agent 档生效：hooks 层、`UserPromptSubmit`、技能带 command/hooks

**What to build:** 让 hooks 也**按 agent 种类分家**，并把"技能在用户发送时注入"从特例变成一条真的 hook 点。

1. **基础 hooks 层**（决策 14）：这个 agent 的 `:base :hooks {:off [..] :on [..]}` 说的是
   "哪些声明跑、哪些不跑"。默认（键缺席）＝**每一个声明的 hook 每个 agent 都跑**，与今天逐字相同。
   为能按名点一条 `hooks.edn` 声明，声明多一个**可选** `:name`
   （`kernel/hooks.clj` 的 `declaration-keys`，`:189`）；没写名字的仍按位置 id（`stop#0`）寻址。
2. **`UserPromptSubmit` 接线**（决策 12）：它在 `points` 表里已经登记
   （`kernel/hooks.clj:78`／`:abwl` 起，`{:name "UserPromptSubmit" :payload #{:prompt} :gate? true :on-error :block}`），
   今天 `src/` 里**一处 emit 都没有**。本票在"一个用户轮到达、模型还没看到它"那一刻 emit，
   并给它加上 **`:stdout :content`**——让它成为继 `SystemPrompt` 之后**第二个**把 stdout 当内容的点。
   注入块落在**提问之后**（`derived-injections` 今天落的那一格，`cap/skills.clj:519`），不改位置规矩。
3. **技能带 command 与 hooks**（决策 12）：`SKILL.md` 的 frontmatter 多认两个键
   （`cap/skills.clj:111` 的 `frontmatter-keys`，今天只认 `#{:name :description}`）：
   - **`hooks`** —— 一份与 `hooks.edn` 同形的声明表 `{point-kw [decl ..]}`；
   - **`command`** —— 一条命令，等价于在 `UserPromptSubmit` 上挂一条 `{:command ..}`。
   由**复合层**替这个 agent 装（技能正文被 `:compose :skills :load` 选中的那些），
   声明的 id 带 `skill/<技能名>/…` 前缀，读一张表时看得出是谁带来的。
4. **`:compose :skills :load`**（决策 12）：哪些技能的正文**在用户发送时自动注入**。今天只有人的 `/name`
   做这件事；这个键把"自动注入"变成配置。`/name` 与模型调 `skill` 两条路**照旧**（`slash-request`，`:428`；
   模型那条是工具结果，不是注入）。

**要点：**

- **`:declarations` 从单格变链——本票唯一动的缝。** `kernel/hooks.clj` 的 `install!`（`:395`）今天把
  `:declarations` 当**单格、后装赢**（`:::declarations` 那个 slot，`fold-layers` 里
  `#(or (:declarations layer) %)`）。今天只有一家用它（`cap/hooks.clj:76` 的 `install!`），
  所以后装赢看不出问题；本特性要加第二家（agent 档的覆盖），而后装赢会让第二家把 `hooks.edn`
  **整个吞掉**——"两份声明"变"一份"正是这个 bug 的形状。改成**一条链**（像 `kernel/tools.clj` 的
  `:tools-for` / `:disabled-for` 那样，收集成向量、按到达顺序折），**只有一家时行为逐字不变**，
  且这条是**加法**（一格变多格，不是换门）。
- **顺序**：本层在 `cap-hooks/install!` **之后**装（组合根 `edge/http.clj:5567-5582`，本层加在最后），
  于是 `hooks.edn` 是底、agent 档的覆盖在上面。装反了 `hooks.edn` 会赢，那与本决策相反。
- **会话那一轴不动**：`session-add!` / `session-disable!`（`kernel/hooks.clj:467` / `:509`）仍是
  **按线程**的，优先级最高（`source-tier`，`:594`）。本票加的是**按 agent 种类**的一轴，两层不同。
- **"关掉"仍要看得见**（`effective-hooks`，`:556`，`disabled?` 标记）：被 agent 档关掉的声明**仍在表里**
  带 `:disabled? true`，不是消失——与工具表同一条规矩（`tool-toggles` 定的）。
- **`gate? true` 的语义照旧**：一条 `UserPromptSubmit` 声明 exit 2 就**拦住这一发**（例如一个不许发的
  策略 hook），exit 0 就放行；它的 stdout 同时是注入的文本。两件事一个点，是 `SystemPrompt` 已有的形状。
- **不新增 hook 点**：本票只把 `UserPromptSubmit` 从"登记过、没接线"变成"接线了"。其余 P2/P3 点不动。
- **`SKILL.md` 不能借 `hooks` 扩张工具集**：`allowed-tools` 仍然是"读而忽略"
  （`frontmatter-keys` 的 docstring 明写原因），本票只加**行为**（跑命令），不加**权限**。

**Blocked by:** 02（`:base :hooks` 与 `:compose :skills` 的读法与校验）。
与 03 并行；"这个线程属于哪个 agent"的查法两者共用，**谁先落谁把它放进 01 的 ns**（03 的验收里点了一次，
别各写一份）。

**Status:** ready-for-agent

## 现场

- 点表：`kernel/hooks.clj:78`（27 行，`:abwl` 起）；`UserPromptSubmit` 那一行今天
  `:stdout` 那一格**空着**，`:gate? true`。`SystemPrompt`（`:Tybc` 那一行）是唯一
  `:stdout :content` 的先例，照它加。
- 发声：`kernel/hooks/dispatch.clj` 的 `emit`（`:195`）与 `fire`（`:209`）；`fire` 已经会把
  `:blocks` 收成有序的一串（为 `SystemPrompt` 写的），第二个内容点直接接进去。
- 组合根：`edge/http.clj:5567-5582`（今天的五层）。`cap/hooks.clj:76` 的 `install!` 把
  `(config thread-id)` 交给 kernel（`cap/hooks.clj:67`，user ⊕ project，project 整点替换）。
- 注入：`cap/project.clj:841` 的 `before-llm` 今天 `(-> history (skills/derived-injections (skill-roots
  thread-id)) (jobs/before-llm thread-id))`（`:867-869`）；`derived-injections` 在 `cap/skills.clj:519`，
  `skill-message`（`:389`）造那条 `<skill name="…">` 的 user 消息。
- 技能读取：`frontmatter-keys`（`cap/skills.clj:111`）、`parse-frontmatter`（`:hnpp` 起，手写的窄解析器，
  扁平 `key: value` ＋ 两个块标量）、`skill-for`（`:332`，名字变路径**唯一**的地方）、`load-text`（`:498`）。
- 子 agent 的 hook 面今天只有两条 **builtin** 行：`SubagentStart` / `SubagentStop`
  （`cap/subagents.clj:629` / `:636`）；system prompt 那条 `<subagent>` 块经 `hooks/install!` 的
  `:builtins`（`cap/subagents.clj:898-903`）。

## 验收

- [ ] 一个 `hooks.edn` 带两条声明（一条有 `:name`、一条没有）：`config.edn` 把有名字的那条写进
      `:base :hooks :off` ⇒ 主 agent 不跑它，**另一条照跑**；不带名字的按 `stop#0` 也能点掉。
- [ ] 默认（`:hooks` 缺席）：主 agent 与任一子 agent 跑**同一批**声明，与今天逐字相同
      （拿 `effective-hooks` 的 id 集合交叉断言）。
- [ ] **`UserPromptSubmit` 真的发声**：发一条用户消息 ⇒ 一条 `hook/user-prompt-submit` 审计行；
      挂一条 `command "exit 2"` 的声明 ⇒ 这一发被拦（拒绝话来自 stderr）。
- [ ] **它是第二个内容点**：挂一条 `printf 'INJECTED'` 的声明 ⇒ 模型**看得到** `INJECTED`，
      且它在**提问之后**（拿历史断言位置，不是只看"有没有"）。
- [ ] 技能带 `command`：一个 `SKILL.md` 写了 `command: <跑起来会写文件的一条>`，被 `:compose :skills :load`
      选中 ⇒ 用户发送时它**真的跑了**；从 `:load` 里去掉 ⇒ 不跑。
- [ ] 技能带 `hooks`：一份 `{pre-tool-use [{:command …}]}` ⇒ 那条声明在 `effective-hooks` 里，
      id 以 `skill/<技能名>/` 开头；`hooks.edn` 里原有那条**仍在**（链没被吞——这是本票最硬的一条钉子）。
- [ ] `:declarations` 变链之后**只有一家时行为不变**：不装 agent-config 那一层时，
      `effective-hooks` 与今天逐字相同（照 `kernel/install_test.clj` 既有的 teardown 用例写一条）。
- [ ] 被 agent 档关掉的声明**仍在表里**（`:disabled? true`），不是消失；会话 `session-enable!` 之后
      它**仍然**按 agent 档关着（两轴各管一段，一条用例断言）。
- [ ] `clojure -M:test -m harness.test-runner` 全绿（`hook_*_test` 里读 `:points` 形状的断言跟着改）。
