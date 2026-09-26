# 01 — 能力目录与三层解析（纯数据 ＋ 纯函数）

**What to build:** 两份**数据**和一条**纯函数**：

1. **能力目录** —— 把今天散在各处的"这个 harness 有哪些工具"收成一张表（住新 ns，建议
   `harness.cap.agent_config`）：每个能力有一个名字、归属**哪一层**、以及"它是不是能证明只读"。
   - **基础能力**：一个名字一个工具，名字就是工具名（`read` / `write` / `bash` / `glob` / `grep` /
     `web_fetch` / `web_search` / `todo_write` / `skill` / `job` / `job_output` / `job_kill` / `eval` …）。
     名单的**唯一来源**是 `harness.cap.tools` 的 `@built-ins`（`src/harness/cap/tools.clj:53`，`register!` 攒出来的），
     目录只加"层"与"默认装不装"这两列，**不另抄一份名单**。
   - **增强能力**：一个能力是**一套组合**（`hashline`：四个锚点工具 + `read`/`write` 的两张锚点脸）。
     它由 `harness.cap.editing/families` 那份数据说话（`src/harness/cap/editing.clj`），本目录只说
     "它是一组一选里的哪一个取值"。
   - **复合能力**：`skill` 与 MCP 服务器。MCP 是**按服务器**的（名字来自 `mcp.edn` 的声明，
     `src/harness/cap/mcp.clj:225`），不是目录里写死的一串。
2. **三层解析** —— `(resolve agent-config)` → 一个**只读的答案**：

   ```clojure
   {:served   #{name ..}          ; 这个 agent 这一刻服务的名字（已过 :narrow 的口径）
    :faces    {name :hashline}    ; 需要换脸的名字 → 用哪一张（read/write）
    :hooks    {point-kw [decl ..]}
    :unserved {name {:by :base|:enhance|:compose :key "…" :message "…"}}
    :read-only? bool}
   ```

   解析是**逐层折叠**：基础层的 `:tools` 定起点，增强层选中的那一组把它的名字并进来、并声明它接手谁，
   复合层的服务器名单与技能展开成名字。**每一个不在 `:served` 里的名字都要有一句 `:unserved`**，
   话里带 `:by`（哪一层）、`:key`（`config.edn` 里怎么改）与 `:message`；这是决策 7 那张表的机器可读形状。

**要点：**

- **纯函数、现算、不缓存。** 没有 per-thread 的载体、没有 memo：`config.edn` 与 `harness.edn` 都是每次现读
  （本仓的老规矩），解析只是把它们折成一张表。谁拿这个答案谁自己决定要不要留（03 是每通调用问一次）。
- **`eval` 与委派工具不是"默认不勾"，是后置条件。** `forbidden`（`cap/subagents.clj:103`，今天 `#{"eval" "agent"}`）
  演进成解析的**后置条件**：任何子 agent 的 `:served` 里都不许出现它们，配置里勾了就**在解析这一步拒**
  （保存那时也拒，见 02）。主 agent 不受这条约束。
- **`:read-only? true` 的解析只留能证明只读的**：`(:source tool) = :builtin` 且 `(:read-only tool) = true`
  且这个名字**不是本会话 `session-register!` 进来的**。判据从 `cap/subagents.clj:330` 的
  `provable-read-only?` 搬过来（那份是 `table-for` 用的，随 `baseline` 一起退休）。
- **两层目录与界面共用一份。** 05 的页面要答"这一行为什么是灰的、谁决定的"，答案就取自这里的
  `:faces` 与 `:unserved`——**页面不另算一份判断**（这是 `tool-switchboard/03` 已经定下的规矩，本票接着用）。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场

- 工具从哪来：`cap/tools.clj` 的 `@built-ins`（`:53`）经 `register!`（`:55`）攒起来，`install!`（`:1673`）
  **整张**装进 kernel 一层。名字之间今天没有"谁属于哪一层"这回事。
- 编辑那套今天**一半是函数体里的 if、一半是配置值**：`cap/tools.clj` 的 `t-read` / `t-write` 与
  `read` 的 `:describe`、`write-face` 四处按 `(= :hashline (:mode (editing/editing-mode thread-id)))` 分叉；
  `cap/editing.clj` 的 `served?`（`:203` 起的注释区）拿同一个 mode 判"这个名字服不服务"。
  本票**不动**这套（03 才让它按 agent 档生效），只把"它是一组一选的一个取值"写进目录。
- 子 agent 的范围派生今天在 `cap/subagents.clj`：`provable-read-only?`（`:330`）、`table-for`（`:355`）、
  `served?`（`:467`）、`unserved-message`（`:477`）。本票只**搬走只读判据**，退役发生在 03。
- 收窄已经是**多份**（`kernel/tools.clj:83` 的 `installed-narrowings`，按到达顺序问，第一份说不服务的
  用它的拒绝语）——这是"编辑模式的收窄"与"子 agent 的能力边界"能并存的原因，本特性接着用，不新开机制。

## 验收

- [ ] 目录覆盖 `@built-ins` 的每一个名字（用例交叉断言两个集合相等），且每个基础能力都标了层与"装不装"的默认。
- [ ] `(resolve <主 agent 的默认配置>)` 的 `:served` 含除 `eval` 外的全部基础名字，`:hooks` 与今天逐字相同。
- [ ] `(resolve <子 agent 的配置>)` 里 `:served` **一个 `eval`、一个 `agent` 都没有**；配置里勾了它们 ⇒
      解析按名拒（一条用例各勾一个）。
- [ ] `:read-only? true` 的解析：一个 `:source :mcp` 的名字、一个 `session-register!` 进来的名字
      **都不在** `:served`（两条用例）。
- [ ] 增强层取值 `:hashline` ⇒ `:faces` 里 `read` / `write` 是 `:hashline`，`edit` 进 `:unserved`；
      取值 `:str-replace` ⇒ 两个名字不在 `:faces`、`edit` 在 `:served`、四把锚点名字在 `:unserved`。
- [ ] `:served` 里每个名字都有定义（拿 `harness.kernel.tools/effective-tools` 交叉断言），
      且**没有一个 `:unserved` 的名字也在 `:served` 里**。
- [ ] 解析是**纯的**：同一份配置解两次答案相同；解一百次不写任何 atom（用例断言相关 atom 前后不变）。
- [ ] `clojure -M:test -m harness.test-runner` 全绿。
