# spec：注入物的统一外形 + 轨迹照 dsh 的账本

**一句话**：把「每次调用前摆进历史的那些块」统一成**一条 `<system-reminder>` 包着的纯文本**——标签层去掉、
多个 AGENTS.md 合成一条、作业结束走同一条；轨迹则**照搬 dsh**：一条平铺的账本，
`system_prompt` 在最前，之后 `turn/start` → `message` → `context` → `assistant` → … → `turn/end`。

牛总 2026-09-30 的原话拆成五条：

1. 所有 context 注入用 `<system-reminder>` 包起来，**特别是 job_end**；
2. `<system-reminder>` 里面不要再次包括 xml；
3. 多个 AGENTS.md 合并成一个注入；
4. 按轮次折叠的时候要把上下文注入一起折叠；
5. 轨迹**按照 dsh 照搬**：`system_prompt + turn/start message context assistant... turn/end`。

已拍：范围 = 指令文件 / 技能目录 / 运行上下文条目 / 技能正文 / 作业结束通知（**不含** `instruction-update`）；
内层全改纯文本，来源/路径/命令/id 用**纯文本行**表达（像 dsh 的 `Instructions from: …`）。

> 第 4、5 条讲的是**轨迹**，不是会话栏。改写前我把 4 读成了会话栏的轮折叠，作废——见决定 6 与票 05。

---

## 一、现状（2026-09-30，读代码所得）

### 注入物，与它们现在的样子

| 注入物 | 现在长什么样 | 谁写 |
|---|---|---|
| 指令文件（AGENTS.md） | **每个文件一条** user 消息，`<instructions path="…">…</instructions>` | `harness.cap.preamble/instruction-message` + `messages` |
| 技能目录 | 一条 user 消息，`<skills>…</skills>` | `harness.cap.preamble/skills-message` |
| 会话上下文条目 | 一条 user 消息，**没有标签**，`- 描述: 值` 的列表 | `harness.edge.ag_ui/context-entry`（id `session-context`） |
| 技能正文（人的 `/name`） | 一条 user 消息，`<skill name="…">…</skill>` | `harness.cap.skills/skill-message` |
| 作业结束通知 | 一条 user 消息，`<job-ended id="…" by="user">[exit N]</job-ended>` + `<command>…</command>` + 一句读法 | `harness.cap.jobs/notice` |

### 谁在读那些标签（改外形会连带碰到的读者）

| 读者 | 现在靠什么 | 位置 |
|---|---|---|
| 记录里的 `:source` 分类 | **内容前缀**：`<skill name=`→`skill`、`<job-ended `→`job`、`<instructions`/`<skills`→`opening`、否则 `injection` | `harness.edge.http/returned-source`（~L866） |
| 「这条消息是不是会话持有的 entry」 | 上面那个 `:source`：`#{client injection opening}` 且（client 或**有 id**） | `harness.edge.replay`、`harness.edge.trajectory/entry-row?` |
| 技能正文的幂等（一个名字只加一次） | `(?s)<skill name="([^"]*)">.*` 正则读历史 | `harness.cap.skills/loaded-names`（~L385） |
| 注入卡片的标题 | 首行标签名（`tagOf`） | `ui/src/lib/injections.ts` |

**`:source` 是承重墙**。`skill` / `job` 两种 `:source` 故意不在 entry 集合里——派生注入**每轮重算、不算会话的 entry**。
外形的改法一旦让 `returned-source` 认错，派生注入就会被当成 entry 去重、再发一遍。所以「去掉标签」必须同时换掉**分类依据**。

### 轨迹：现在的折法

`harness.edge.trajectory/one-run` 先用第一条新 user 消息 `open-turn`，紧接着
`append-last [(system-item …)]`，所以 `:turns[0].items[0]` 就是 system。这是折法自己的选择
（`trajectory.clj` 的 docstring：「THE SYSTEM MESSAGE IS SHOWN ONCE, and again whenever its bytes change」），
**不是事件顺序错误，也不是渲染错误**。payload 是 `{:turns [{:index :items :calls}] :incomplete}`。

### 轨迹：dsh 的样子（本机实读）

**代码**：`@deepseek-ai/dsh-client-ui-trajectory@0.1.0-rc.6`
（`~/.npm/_npx/1e7f6d9597241db0/node_modules/@deepseek-ai/dsh-client-ui-trajectory/`）。
**真记录**：`~/.dsh/sessions/--Users-zhouteng-Documents-workspace-clj-harness--/session-*/session.v3.jsonl.zstd`。

- 记录是**一条平铺的账本**，按发生顺序一行一条：
  `system/message`、`turn/start`、`step/start`、`user/message`、`request/context`、`assistant/message`、
  `tool/call`、`tool/result`、`step/end`、`turn/end`。
- 视图把它折成 `TrajectoryTurnModel { turn: number|null, groups: [{title, cells}] }`
  （`lib/types/client/layout.d.ts`）；`turn === null` 是 **`Between turns`**——独立跑的压缩那种没有轮的请求。
- 一格是 `TrajectoryCellProps { index, kind, text, opensTurn?, … }`，
  `TrajectoryCellKind = 'system' | 'user' | 'context' | 'compacted' | 'message' | 'tool' | 'subtool'`
  （`trajectory-record.d.ts`）。轮边界是**粗分割线**（`Turn N` / `Between turns`），行内小标记是步。
- 工具栏有 `Collapse turns` / `Expand turns`、`Collapse calls` / `Expand calls`；`system` 那格的字是
  `Initial System Prompt`；assistant 那格的字是 `Message`。
- **AGENTS.md 不合并**（每个文件一条 `user/message`，各带一个 `<system-reminder>`，
  首行 `Instructions from: …` / `Additional instructions from: …`），但**牛总要合并**——见决定 4。
- dsh 的技能目录 reminder 里嵌着 `<available_skills>`，作业通知是**裸纯文本**、没包 reminder。
  牛总要的是「都包、里面都纯文本」——见决定 2、3。

---

## 二、决定

### 决定 1：一个写手，住在 `harness.cap.reminder`

新命名空间 `harness.cap.reminder`，只做一件事：

```
(wrap ["第一行" "" "正文"]) =>
"<system-reminder>\n第一行\n\n正文\n</system-reminder>"
```

**为什么是新命名空间**：要用它的四家是 `cap.preamble`（指令/目录）、`cap.skills`（正文）、`cap.jobs`（通知）、
`edge.ag_ui`（上下文条目）。`preamble` 已经 require `skills`，`jobs` 谁都不 require；把 `wrap` 塞进 `preamble`
会让 `skills`/`jobs` → `preamble`（而 `preamble` → `skills`）成环。一个只依赖 `clojure.string` 的小叶子是唯一不造环的位置。

### 决定 2：里面只有纯文本

reminder 里**不出现任何 XML 元素**，包括原来那些语义标签。实参是**行**的序列，写手不解析、不转义。

### 决定 3：机器可读的那半，从「标签」改成「首行标签行」

标签原来同时干两件事：给模型看的框 + 给代码读的锚。去掉标签之后，锚放**首行纯文本标签行**——
记录里只有消息字节，别的读者（会话表、轨迹）也只认字节。逐条规定：

| 注入物 | reminder 内首行（**锚**） | 之后 |
|---|---|---|
| 指令文件（**合并后一段**） | `Instructions from <绝对路径>` | 空行，然后是正文；下一个文件同形，段间空行 |
| 技能目录 | `Available skills` | 目录正文（`cap.skills/catalog-text`） |
| 会话上下文条目 | `Session context` | 原来的 `- 描述: 值` 列表 |
| 技能正文 | `Skill <name>` | 正文 |
| 作业结束 | `Background job <id> ended: <status>` | `by: user`（人停的才有）、`Command: <原样命令>`、一句读法 |

- `returned-source` 改读这组首行：`Instructions from` / `Available skills` → `opening`；`Skill ` → `skill`；
  `Background job ` → `job`；其余 → `injection`（`Session context` 落这里，与今天一致）。
- `loaded-names` 改读 `Skill <name>` 首行。
- 两个读法都写成**一张表 + 一个函数**，别让两处各拼一遍前缀；**旧标签前缀兜底**（老记录、老会话）。
- 模型侧用 dsh 的措辞做参照（`Instructions from: AGENTS.md`、`Additional instructions from:`），但路径给**绝对**。

### 决定 4：多个 AGENTS.md 合成一条注入

`harness.cap.preamble/messages` 从「每个文件一条」改成「**所有指令文件一条** + 技能目录一条」。
`gather` 的字面不变（还是 `[{:path :content} …]`，读盘失败的策略不变），改的只是折叠。

- 出生时写进会话的开场条目因此从 `N+1` 条变 `2` 条：`session-opening-0`（指令，内含 N 段）、
  `session-opening-1`（技能目录，若没有可用的技能则没有它）。
- `opening-entries` / `opening-entry?` / `isOpeningEntryId` 不用改（前缀与正则本来就与条数无关）。
- `preamble/report` 的「每块来源与字符数」改成「合并块的总字符数 + 逐文件的 `:path` 名」。

### 决定 5：轨迹照搬 dsh 的平铺账本

`GET /api/threads/<stem>/trajectory`（及 `.worktrees/trajectory-on-the-downlink` 那条下行）的 payload
从 `{:turns …}` 换成**一条平铺的账本**：

```
{:threadId "…"
 :incomplete false
 :cells [ {:index 0 :kind "system"    :turn nil :text "<prompt.md + 本会话事实>" :initial true :tools […]}
          {:index 1 :kind "turn-start" :turn 1}
          {:index 2 :kind "user"      :turn 1 :text "…" :id "…" :at 1759…}
          {:index 3 :kind "context"   :turn 1 :text "<system-reminder>…</system-reminder>"}
          {:index 4 :kind "message"   :turn 1 :text "…" :reasoning "…" :call 0}
          {:index 5 :kind "tool"      :turn 1 :toolCallId "…" :name "bash" :argsText "…" :executed true}
          {:index 6 :kind "turn-end"  :turn 1}
          {:index 7 :kind "compacted" :turn nil :text "…"}          ; Between turns
          … ]}
```

- **`system` 在最前、`turn` 为 `nil`**：它就是 `system_prompt`，字节变了再出一条（`initial: false`），
  位置就在**它所属轮的 `turn-start` 之前**（第一条在账本最前）：放在哪里由它在序列里的位置说，不再加字段。
- `turn-start` / `turn-end` 是**账本里的两条**（牛总点名要的形状）。dsh 把它们画成**粗分割线**而不是行，
  所以 `:kind` 里它们与其余并列，由视图决定怎么画。
- 其余映射：`user`（client 的 user 消息，开新轮）、`context`（每一条注入，不论 opening/技能/通知/上下文条目）、
  `message`（assistant 消息）、`tool`（工具调用/结果，一次调用一条）、`compacted`（压缩摘要）。
  dsh 的 `subtool` 本仓没有，不照搬。
- **轮内顺序照记录**：`user` 在前，`context` 在后，`assistant`/`tool` 随后——就是牛总写的
  `message context assistant`。
- `:call` 指针、工具的 `:arrivedAt/:resumedAt/:executedAt/:closedAt/:executed/:outcome/:error`、
  assistant 的 `:reasoning`、`system` 的 `:tools`——**字段原样保留**（这是本仓已有的自包含读数）。
- `turn === null` 的格 = dsh 的 **`Between turns`**：独立跑的压缩、以及任何不属于任何一轮的行。

### 决定 6：轨迹按轮折叠时，`context` 跟着折

dsh 的账本有 `Collapse turns`（`toolbar.collapseTurns`）。照搬之后：**一轮折上，轮内的 `context` 格与
`message` / `tool` 一起收进摘要行**；展开才画。`system` 格在轮外，永远不折进轮；`turn-start`/`turn-end`
是分割线，折起来只剩轮标题 + 一行摘要（`N steps · M tool calls`，dsh 的 `summarizeTurn` 同一个形）。

**会话栏那套轮折叠不动**（`turn-steps.tsx` 的 `A CARD IS NOT A STEP` 保持原样）。牛总那句「按轮次折叠」
指的就是这条轨迹折叠；会话栏若也要，另开一票。

### 决定 7：范围不含 `instruction-update` 与 `<project>`/`<env>`

- `instruction-update`（`role: "developer"`）不在外形统一里（已拍）。
- `<project>` / `<env>` 在 **system 消息**（message[0]）里，是「本会话的事实」，不是 context 注入，形状不动。

---

## 三、形状对照（注入物）

### 指令文件（合并前 → 后）

前（两条 user 消息）：
```
<instructions path="/Users/zhouteng/AGENTS.md">
# 全局 AGENTS.md
…
</instructions>
<instructions path="/Users/zhouteng/Documents/workspace/clj-harness/AGENTS.md">
# AGENTS.md
…
</instructions>
```

后（一条 user 消息）：
```
<system-reminder>
Instructions from /Users/zhouteng/AGENTS.md

# 全局 AGENTS.md
…

Instructions from /Users/zhouteng/Documents/workspace/clj-harness/AGENTS.md

# AGENTS.md
…
</system-reminder>
```

### 作业结束（前 → 后）

前：
```
<job-ended id="j1" by="user">[exit 0]</job-ended>
<command>npm test</command>
A person stopped it from the pane; read what it said with job_output {"job": "j1"}.
```

后：
```
<system-reminder>
Background job j1 ended: [exit 0]
by: user
Command: npm test
A person stopped it from the pane; read what it said with job_output {"job": "j1"}.
</system-reminder>
```

### 其余三样

```
<system-reminder>
Available skills
…catalog-text…
</system-reminder>

<system-reminder>
Session context
- Project: /Users/zhouteng/Documents/workspace/clj-harness
</system-reminder>

<system-reminder>
Skill tdd
…SKILL.md 正文…
</system-reminder>
```

---

## 四、非目标

- **不改注入的位置、顺序、次数**（`.scratch/context-frames` 决定 7、`.scratch/session-opening`）。
- **不动 `<project>` / `<env>`**、**不动 `instruction-update`**。
- **不改记录格式**：注入仍各是一条 `message` 行，信封仍带 `:source`。
- **不改 `job_output` / `job_kill` / `bash` 语义**，不改通知的「一个作业只说一次」。
- **不照搬 dsh 的 `subtool`**、不照搬它的虚拟滚动 / 补页（本仓已有自己的轨迹时间线与走查）。
- **不动会话栏的轮折叠**（决定 6）。
- **不重做注入卡外观**；只改标题来源（首行标签行）。

---

## 五、验收

- **外形**：有 AGENTS.md 的会话（无论几份）只产生**一条**注入消息、一个 `<system-reminder>`；
  里面第一行是 `Instructions from <绝对路径>`，且**没有** `<instructions …>` 这类标签。
- **分类不变**：技能正文 / 作业通知的 `:source` 仍是 `skill` / `job`（不是 entry、每轮重算）；
  `opening` / `injection` 的 entry 语义与今天逐字相同。
- **卡片**：会话栏里每个注入的折叠行标题来自首行标签行（不再是 `system-reminder`），字节数不变。
- **轨迹（payload）**：顶层是 `:cells`；`cells[0].kind == "system"` 且 `:turn` 为 `nil`；
  每一轮由 `turn-start` … `turn-end` 夹出；轮内 `user` 在 `context` 之前；`message` / `tool` 随记录顺序；
  独立压缩是 `turn: nil` 的 `compacted` 格。字节变了的系统提示词在**它所属轮的 `turn-start` 之前**多出一条。
- **轨迹（视图）**：粗分割线标轮与 `Between turns`；`Collapse turns` 折上后轮内的 `context` 一起收，
  展开回来；`system` 格不在任何轮的折叠里。
- **旧记录**：老会话里的注入仍是老标签、老 payload 读法要能兜底——升级后老会话不能读错。
- **机器门**：`clojure -M:test -m harness.test-runner`（定向 `preamble` / `skills` / `jobs` / `ag-ui` /
  `http` / `replay` / `trajectory` / `stats` / `loop` / `normalize`，再全量）；`ui && npm run typecheck &&
  npm test && npm run build`；`node scripts/dev.mjs --scripted` 起服务、**自己开浏览器走一趟**。

---

## 六、落地的票

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | `plain-text-reminder-writer` | — | `cap/reminder.clj`（写手 + 首行标签表）；`preamble` 指令合并成一条、目录一条、上下文条目包一层；`preamble-test` / `ag-ui-test` / `http-test` 的条数与字节断言 |
| 02 | `skill-body-and-job-notice` | 01 | `skills/skill-message` + `jobs/notice` 换形；`loaded-names` 与 `returned-source` 改读首行标签行（旧标签兜底）；`skills-test` / `jobs-test` / `http-test` 的分类断言 |
| 03 | `the-card-reads-the-label-line` | 01, 02 | `ui/lib/injections.ts` 的标题来源 + `context-card.tsx` + i18n + `ui/test/suites/injections.ts` |
| 04 | `trajectory-as-a-flat-ledger` | — | `edge/trajectory.clj` 出平铺 `:cells`（system 在轮外、turn-start/turn-end、between-turns、映射表）；`ui/lib/trajectory.ts` 类型；`trajectory-test`；`.worktrees/trajectory-on-the-downlink` 的 payload 约定跟着改 |
| 05 | `the-ledger-viewer-and-turn-collapse` | 04 | `trajectory-view.tsx` / `trajectory-timeline.tsx` 改画账本：轮分割线、`Collapse turns`（`context` 一起折）、`system` 在顶、`Between turns`；前端套件 |
| 06 | `docs-and-gates` | 01–05 | `CONTEXT.md`（注入、轨迹）、`docs/architecture/skills-and-instructions.md` / `edge.md` / `client.md`；两套全量报数；真浏览器走查记录 |

01–03 与 04–05 两条线互不依赖，可以先做任一条。

---

## 七、与既有决定的关系

- `.scratch/context-frames` 决定 5/6/7：**位置与顺序一字不改**；改的只是每条的外形。它那句「卡片标题取首行标签名」
  由票 03 换成「首行标签行」。
- `.scratch/job-endings` 决定 7（「界面一个字都不用改」）早已被 `.scratch/context-frames` 取代；
  本特征再动通知的**字节**，不动它的**时机与次数**。
- `.scratch/trajectory` / `.scratch/turn-and-model-events`：payload 的**读者换了形状**（票 04 起不再有 `:turns`），
  折法的**判据**（一轮的边界、`:incomplete`、工具的四时刻）原样搬进账本。
- `.worktrees/trajectory-on-the-downlink`：那条线把轨迹搬到下行；它的帧里带的是 `:turns`，
  **票 04 之后要带 `:cells`**。两条线要合的时候，先定 payload。
- `ADR 0006` / `ADR 0011`（轮与步的两级）：账本的 `turn-start`/`turn-end` 与步的分组照这两条 ADR 的读数，
  不新造判据。
- 上游对照：`@deepseek-ai/dsh-client-ui-trajectory@0.1.0-rc.6`。**照搬**的是账本形状与折叠，
  **不照搬**的已列在非目标。
