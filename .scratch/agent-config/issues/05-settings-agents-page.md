# 05 — 设置面板的「Agents」页：三层分栏，主 agent 一个 tab、子 agent 各一个

**What to build:** 设置面板多一页「Agents」，一本**左边的 agent 名册**（`主 agent` ＋ 每个子 agent，
底部一个"新建子 agent"），右边是选中那个 agent 的**三层**：

- **基础层**：一行一个能力（`bash` / `read` / `write` / `glob` / `grep` / `web_fetch` / `web_search` /
  `todo_write` / `skill` / …），**每格一个单独开关**。一行底下用小字写它的**名字**（模型说的话）。
  下面再一段是**基础 hooks**：`hooks.edn` 的每一条声明一行，开关就是 `:base :hooks :off` 里的成员。
- **增强层**：**一组一选**。今天是「编辑」一组，两个取值（`hashline` / `str-replace`）一次只能选一个；
  选中 `hashline` 时，它名下那四个锚点工具与 `edit` **列出来但不能各自点**，写成灰行并写明"由这一组决定"。
- **复合层**：两段——**MCP 服务器**（每个 `mcp.edn` 已声明的服务器一行一个开关，写进 `:compose :mcp :servers`）、
  **技能**（每个可用技能一行一个开关，写进 `:compose :skills :load`，意思是"在用户发送时自动注入"）。

**要点：**

- **页面 ↔ 端点**：`GET /api/agents?threadId=` 答"三层各自的当前值 ＋ 每一项的**状态**（在服务 / 被上一层接管 /
  被拒）＋ 这一行为什么不能点、谁决定的 ＋ 值来自哪一档（代码默认 / `config.edn` / 项目覆盖）"；
  `POST /api/agents` 写。与 MCP 页同一个形状（**自己的端点**，不挤进 `/api/settings`，
  `tool-switchboard/03` 已经定过这条并给了理由）。写**只写 `config.edn` 的 `:agents`**。
- **灰行的那份判断来自 01，不在这里重算**：谁接管了谁（`:faces`）、谁为什么不在（`:unserved` 的
  `:by` / `:key` / `:message`）——页面只如实摆出来。**派生行禁止可点**（把结果写成配置是 02 明令不许的）。
- **项目级覆盖只读显示**（决策 5）：`:agents` 可能被 `<项目>/.harness/harness.edn` 覆盖。
  这一页**只写家目录那份 `config.edn`**；被项目覆盖的格显示成只读一行 ＋ 一句"这个项目覆盖了它，
  要改去 `<项目>/.harness/harness.edn`"。理由照 `tool-switchboard/03`：一页写两个文件就有两个真相，
  与其如此不如写明去哪儿改。
- **名册的增删在这里**：今天的「Subagents」页（`settings-panel.tsx` 的 `PAGES`，`:1516`）
  把 roster 编辑搬进本页的左边栏（名字 ＋ 描述 ＋ `:read-only?` ＋ 新建/删除），**旧页退休**。
  `:read-only?` 勾上时，写能力的格**当场变灰并说明为什么**（保存也会被拒，见 02）。
- **文案两种语言**：组名、开关名、灰行的说明走 i18n（`ui/src/locales/{zh,en}/settings.json`）；
  **后端来的话不翻译**（"哪个键、怎么写回来、谁接管的"照旧是后端给的字符串）。
- **`GET /api/agents` 与真表交叉**：页面上"在服务"的名单必须与 `tools/specs` 过滤出来的名单一致
  ——拿同一个线程各算一次、断言相等，别让页面自己再折一遍表。
- **不是为了显示而存在的状态**：本页**不列** MCP 的工具（它们有自己的启停路，写一句去哪儿找）、
  **不列** `eval` 与委派工具在子 agent 里（永不出现）、**不列**增强层组合内部的成员为可点项。

**Blocked by:** 03、04（每一层都要先真的生效，页面才有内容可答）；03/04 都还依赖 02。

**Status:** ready-for-agent

## 现场

- 面板今天四页（General / Models / MCP / Subagents）：`PAGES`（`ui/src/components/settings-panel.tsx:1516`）、
  `SettingsPanel`（`:1529`）；打开时并行读 `getSettings` 与 `registryFor`（`:1590`）。
- MCP 页是"自己端点"的现成样板：`ui/src/components/mcp-panel.tsx`（每服务器一个按钮，
  `GET /api/mcp` ＋ `POST /api/mcp`，`edge/http.clj` 的 `mcp-get` / `mcp-post`），
  它明写"不改 `mcp.edn`、重启即恢复"——本页相反：**它写 `config.edn`**，要写得像 General 页
  （明说写的是哪个文件哪一节）。
- 名册今天在 `ui/src/lib/subagents.ts`（`listSubagents` / `putSubagent` / `removeSubagent`，
  `GET/POST /api/subagents`、`POST /api/subagents/<name>/remove`）与
  `ui/src/components/subagent-list.tsx`（`DefinitionRows` / `DefinitionButtons`、
  `BASELINE_LABELS`、`rangeText`）。`:baseline` / `:exclude` 退休之后，那一半（`rangeText` 等）跟着删。
- **渲染看不到布局**：`ui/test/suites/` 的套件把一行渲成字符串再读它说什么，布局只有真浏览器说得清
  （AGENTS.md 那条铁律）。本票至少钉住"灰行 ＋ 说明"这一格。

## 验收

- [ ] `GET /api/agents` 答主 agent 与每个子 agent 的**三层**：基础层逐项（当前值 ＋ 值来自哪一档）、
      基础 hooks 逐条、增强层的**分组与取值**、复合层的服务器与技能；名单与 01 的目录交叉断言，一个不差。
- [ ] `POST /api/agents` 关掉一个基础能力 ⇒ **下一次 run** 的 `specs` 里没有它（真 HTTP 断言），**不重启**；
      勾回来也一样。
- [ ] 增强层换面：选 `hashline` ⇒ `specs` 里四把锚点都在、`edit` 不在，页面上 `edit` 那行是**灰的**且写明
      是这一组撤的；选 `str-replace` ⇒ `edit` 回来、四把不在，那四行变灰、写明是这一组没选。
- [ ] **`config.edn` 的 `:agents` 里只有能力名与选择，没有 id、没有成员名**：写盘前后读文件断言，
      里面不出现 `replace` / `insert` / `grep`（锚点那个）/ `undo_last_replace` 各自的格，
      也不出现任何 `<层>/<名字>` 形状的 id。
- [ ] 子 agent 那份：`:read-only? true` 时写能力的格**当场灰**并说明原因；POST 它 ⇒ 后端按名拒
      （与 02 的同一条规则，两边各断言一次）。
- [ ] 写盘前后：`config.edn` 的 `:default` / `:providers` / `:ui` **逐字节不变**；坏掉的 `config.edn`
      **先拒绝**并说出文件，**不覆盖、不重建**。
- [ ] 项目覆盖只读显示：一个项目写了 `.harness/harness.edn :agents` ⇒ 那一格是只读的，页面写它**不动**
      项目文件；写家目录那份时，被覆盖的格**不假装生效**（答案里明说"此刻由项目覆盖决定"）。
- [ ] 左边栏能新建一个子 agent（名字 ＋ 描述 ＋ `:read-only?`）、能删一个自定义的；**内置两条删不掉**
      （照 `cap/subagents.clj:308` 的 `built-in?`）。
- [ ] i18n 两种语言的文案都在；后端的话不翻译。
- [ ] UI 套件有渲染断言（至少钉住"灰行 ＋ 说明"这一格）；`cd ui && npm run typecheck && npm test` 全绿。
- [ ] **动过 `ui/src/` ⇒ 跑一次 `node scripts/dev.mjs --scripted` 并开浏览器走一趟**：三个 tab、
      三层的分栏、灰行、一格的开关真的改了下一轮的表。
