# spec：注入物的统一外形 + 两处按轮折叠 + 轨迹照 dsh 的账本

**一句话**：把「每次调用前摆进历史的那些块」统一成**一条 `<system-reminder>` 包着的纯文本**——标签层去掉、
多个 AGENTS.md 合成一条、作业结束走同一条；**会话栏折轮时把注入卡一起折进去**；轨迹则**照搬 dsh**：
一条平铺的账本，`system_prompt` 在最前，之后 `turn/start` → `message` → `context` → `assistant` → … → `turn/end`。

牛总 2026-09-30 的原话拆成五条：

1. 所有 context 注入用 `<system-reminder>` 包起来，**特别是 job_end**；
2. `<system-reminder>` 里面不要再次包括 xml；
3. 多个 AGENTS.md 合并成一个注入；
4. **会话栏**按轮次折叠的时候要把上下文注入一起折叠；**轨迹**也要（dsh 的 `Collapse turns`）；
5. 轨迹**按照 dsh 照搬**：`system_prompt + turn/start message context assistant... turn/end`。

已拍：范围 = 指令文件 / 技能目录 / 运行上下文条目 / 技能正文 / 作业结束通知（**不含** `instruction-update`）；
内层全改纯文本，来源/路径/命令/id 用**纯文本行**表达。

> **修正**（2026-09-30，实读 dsh 记录后）：第一版我写「dsh 的 AGENTS.md 不合并」——**错的**，见决定 4 的实测。
> 第一版还把第 4 条读成只讲轨迹——也错，**会话栏与轨迹都要折**，见决定 6。

---

## 一、现状（2026-09-30，读代码 / 读 dsh 记录所得）

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

### 会话栏：折轮不折卡

`ui/src/components/turn-steps.tsx` 的注释把现状写死了：
「A CARD IS NOT A STEP … `thread.aui.tsx` draws a card part even in a folded step or head」。
所以一轮折上之后，**注入卡还挂在屏幕上**（除非它是那一轮的「结论」）。牛总要它跟着轮一起折。

### dsh 的样子（本机实读）

**代码**：`@deepseek-ai/dsh-client-ui-trajectory@0.1.0-rc.6`
（`~/.npm/_npx/1e7f6d9597241db0/node_modules/@deepseek-ai/dsh-client-ui-trajectory/`）。
**真记录**：`~/.dsh/sessions/--Users-zhouteng-Documents-workspace-clj-harness--/session-*/session.v3.jsonl.zstd`。

**注入物**：

- **同一级的指令文件合并进一条** `<system-reminder>`。实测 `session-edc108e2-81b8-…` 的**同一条** `user/message`、
  **同一个** reminder 里有：

  ```
  <system-reminder>
  The following workspace instructions may be relevant to your work. Use them as guidance when applicable.
  More specific instructions take precedence over broader ones. They do not override system, developer, or
  direct user instructions.

  Instructions from: ~/.dsh/AGENTS.md

  # 全局 AGENTS.md
  …

  Instructions from: AGENTS.md

  # AGENTS.md
  …
  </system-reminder>
  ```

  ——**`Instructions from: <路径>` 是每文件一段，段间空行，全在一个 reminder 里**。
- **作用域更窄的**（worktree 那种子目录里的）自出一条 message，首行是 `Additional instructions from: <路径>`；
  文件中途改了，出一段 `Updated instructions from: <路径>`。
- 技能目录也是一条 reminder（里面嵌着 `<available_skills>`——牛总要改成纯文本）。
- 运行上下文快照（approval/file policy）是一条 user 消息，**不包 reminder**。
- **作业通知是裸纯文本、没包 reminder**（`background job bash-20 (bash: …) finished [status: …]. Read its output with job_output.`）。
  牛总要「都包」——这是**对 dsh 的一处有意加严**。

**轨迹**：

- 记录是**一条平铺的账本**，按发生顺序一行一条：
  `system/message`、`turn/start`、`step/start`、`user/message`、`request/context`、`assistant/message`、
  `tool/call`、`tool/result`、`step/end`、`turn/end`。
- 视图把它折成 `TrajectoryTurnModel { turn: number|null, groups: [{title, cells}] }`
  （`lib/types/client/layout.d.ts`）；`turn === null` 是 **`Between turns`**——独立跑的压缩那种没有轮的请求。
- 一格是 `TrajectoryCellProps { index, kind, text, opensTurn?, … }`，
  `TrajectoryCellKind = 'system' | 'user' | 'context' | 'compacted' | 'message' | 'tool' | 'subtool'`
  （`trajectory-record.d.ts`）。轮边界是**粗分割线**（`Turn N` / `Between turns`），行内小标记是步。
- 工具栏有 `Collapse turns` / `Expand turns`、`Collapse calls` / `Expand calls`；`system` 那格的字是
  `Initial System Prompt`，assistant 那格的字是 `Message`。

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
（这是一处对 dsh 的**加严**：dsh 的技能目录里嵌着 `<available_skills>`。）

### 决定 3：机器可读的那半，从「标签」改成「首行标签行」

标签原来同时干两件事：给模型看的框 + 给代码读的锚。去掉标签之后，锚放**首行纯文本标签行**——
记录里只有消息字节，别的读者（会话表、轨迹）也只认字节。逐条规定：

| 注入物 | reminder 内首行（**锚**） | 之后 |
|---|---|---|
| 指令文件（**合并后一段**） | `Instructions from: <绝对路径>` | 空行，然后是正文；下一个文件同形，段间空行 |
| 技能目录 | `Available skills` | 目录正文（`cap.skills/catalog-text`） |
| 会话上下文条目 | `Session context` | 原来的 `- 描述: 值` 列表 |
| 技能正文 | `Skill <name>` | 正文 |
| 作业结束 | `Background job <id> ended: <status>` | `by: user`（人停的才有）、`Command: <原样命令>`、一句读法 |

- `returned-source` 改读这组首行：`Instructions from` / `Available skills` → `opening`；`Skill ` → `skill`；
  `Background job ` → `job`；其余 → `injection`（`Session context` 落这里，与今天一致）。
- `loaded-names` 改读 `Skill <name>` 首行。
- 两个读法都写成**一张表 + 一个函数**，别让两处各拼一遍前缀；**旧标签前缀兜底**（老记录、老会话）。
- 措辞照 dsh（`Instructions from: <路径>`、开头的 `The following workspace instructions may be relevant…`），
  但路径给**绝对**；`Additional instructions from:` / `Updated instructions from:` 本仓今天没有对应概念
  （见非目标），不写。

### 决定 4：多个 AGENTS.md 合成一条注入（dsh 就是这么做的）

`harness.cap.preamble/messages` 从「每个文件一条」改成「**所有指令文件一条** + 技能目录一条」。
`gather` 的字面不变（还是 `[{:path :content} …]`，读盘失败的策略不变），改的只是折叠。

- 形状照 dsh：一个 reminder，每个文件一段 `Instructions from: <绝对路径>` + 空行 + 正文，段间空行。
- 出生时写进会话的开场条目因此从 `N+1` 条变 `2` 条：`session-opening-0`（指令，内含 N 段）、
  `session-opening-1`（技能目录，若没有可用的技能则没有它）。
- `opening-entries` / `opening-entry?` / `isOpeningEntryId` 不用改（前缀与正则本来就与条数无关）。
- `preamble/report` 的「每块来源与字符数」改成「合并块的总字符数 + 逐文件的 `:path` 名」。
- **与 dsh 的差别只在一处**：dsh 按**作用域**分（同级合并、更窄的另出 `Additional instructions from:` 一条）；
  本仓的 `:instructions` 只是一个列表、没有作用域概念，所以**整个列表合并成一条**——正是牛总要的。

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

### 决定 6：两处折轮都要把注入收进去

**(a) 会话栏**（`turn-steps.tsx` + `thread.aui.tsx` + `lib/turns.ts`）：一轮折上时，**属于本轮的注入卡不画**，
只留摘要行；展开才画。「属于本轮」= 那张 `injected-context` 的 card-only 消息与本轮相邻（在轮的边界之间，
或紧贴开轮那条之前）。**压缩卡（`compacted-context`）不动**——它是「历史在这里被折过一次」的边界，
`.scratch/compaction-frames` 刻意让它折了也画。摘要行的步数不变（卡不是步）。

**(b) 轨迹**（票 05）：账本的 `Collapse turns` 折上时，轮内的 `context` 格与 `message` / `tool` 一起收进摘要；
`system` 格在轮外，不折进任何轮；`turn-start`/`turn-end` 收成轮标题 + 一行摘要
（`N steps · M tool calls`，dsh 的 `summarizeTurn` 同一个形）。

### 决定 7：范围不含 `instruction-update`、`<project>`/`<env>`、`Updated instructions from:`

- `instruction-update`（`role: "developer"`）不在外形统一里（已拍）。
- `<project>` / `<env>` 在 **system 消息**（message[0]）里，是「本会话的事实」，不是 context 注入，形状不动。
- dsh 的 `Additional instructions from:` / `Updated instructions from:` 本仓今天没有对应概念
  （指令文件只在出生写一次，见 `.scratch/session-opening`），不造这个概念。

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

后（一条 user 消息，照 dsh）：
```
<system-reminder>
The following workspace instructions may be relevant to your work. Use them as guidance when applicable.
More specific instructions take precedence over broader ones. They do not override system, developer, or
direct user instructions.

Instructions from: /Users/zhouteng/AGENTS.md

# 全局 AGENTS.md
…

Instructions from: /Users/zhouteng/Documents/workspace/clj-harness/AGENTS.md

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

后（这是对 dsh 的加严——dsh 是裸文本）：
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
- **不动 `<project>` / `<env>`**、**不动 `instruction-update`**、**不造 `Updated instructions from:`**。
- **不改记录格式**：注入仍各是一条 `message` 行，信封仍带 `:source`。
- **不改 `job_output` / `job_kill` / `bash` 语义**，不改通知的「一个作业只说一次」。
- **不照搬 dsh 的 `subtool`**、不照搬它的虚拟滚动 / 补页。
- **不动压缩卡的折叠行为**（决定 6a）。
- **不重做注入卡外观**；只改标题来源（首行标签行）与折叠时机。

---

## 五、验收

- **外形**：有 AGENTS.md 的会话（无论几份）只产生**一条**注入消息、一个 `<system-reminder>`；
  里面每个文件一段 `Instructions from: <绝对路径>`，且**没有** `<instructions …>` 这类标签。
- **分类不变**：技能正文 / 作业通知的 `:source` 仍是 `skill` / `job`（不是 entry、每轮重算）；
  `opening` / `injection` 的 entry 语义与今天逐字相同。
- **卡片**：会话栏里每个注入的折叠行标题来自首行标签行（不再是 `system-reminder`），字节数不变。
- **会话栏折叠**：一轮折上之后，属于它的注入卡**不画**；展开又有；摘要行的步数不变；压缩卡**仍画**。
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
| 01 | `plain-text-reminder-writer` | — | `cap/reminder.clj`（写手 + 首行标签表）；`preamble` 指令合并成一条（照 dsh 的分段与前言）、目录一条、上下文条目包一层；`preamble-test` / `ag-ui-test` / `http-test` 的条数与字节断言 |
| 02 | `skill-body-and-job-notice` | 01 | `skills/skill-message` + `jobs/notice` 换形；`loaded-names` 与 `returned-source` 改读首行标签行（旧标签兜底）；`skills-test` / `jobs-test` / `http-test` 的分类断言 |
| 03 | `the-card-reads-the-label-line` | 01, 02 | `ui/lib/injections.ts` 的标题来源 + `context-card.tsx` + i18n + `ui/test/suites/injections.ts` |
| 04 | `trajectory-as-a-flat-ledger` | — | `edge/trajectory.clj` 出平铺 `:cells`（system 在轮外、turn-start/turn-end、between-turns、映射表）；`ui/lib/trajectory.ts` 类型；`trajectory-test`；`.worktrees/trajectory-on-the-downlink` 的 payload 约定跟着改 |
| 05 | `the-ledger-viewer-and-turn-collapse` | 04 | `trajectory-view.tsx` / `trajectory-timeline.tsx` 改画账本：轮分割线、`Collapse turns`（`context` 一起折）、`system` 在顶、`Between turns`；前端套件 |
| 06 | `the-conversation-fold-takes-the-injection-card` | 03 | `ui/lib/turns.ts` + `turn-steps.tsx` + `thread.aui.tsx`：会话栏折轮时注入卡一起折，压缩卡不动；`ui/test/suites/turns.ts` |
| 07 | `docs-and-gates` | 01–06 | `CONTEXT.md`（注入、轨迹）、`docs/architecture/skills-and-instructions.md` / `edge.md` / `client.md`；两套全量报数；真浏览器走查记录 |

01–03 与 04–05 两条线互不依赖，可以先做任一条；06 要 03 的形状先定。

---

## 七、与既有决定的关系

- `.scratch/context-frames` 决定 5/6/7：**位置与顺序一字不改**；改的只是每条的外形。它那句「卡片标题取首行标签名」
  由票 03 换成「首行标签行」。
- `.scratch/job-endings` 决定 7（「界面一个字都不用改」）早已被 `.scratch/context-frames` 取代；
  本特征再动通知的**字节**，不动它的**时机与次数**。
- `.scratch/compaction-frames`：压缩卡「折了也画」的规矩**保留**（决定 6a）——这正是它那条钉住的反例用例。
- `.scratch/trajectory` / `.scratch/turn-and-model-events`：payload 的**读者换了形状**（票 04 起不再有 `:turns`），
  折法的**判据**（一轮的边界、`:incomplete`、工具的四时刻）原样搬进账本。
- `.worktrees/trajectory-on-the-downlink`：那条线把轨迹搬到下行；它的帧里带的是 `:turns`，
  **票 04 之后要带 `:cells`**。两条线要合的时候，先定 payload。
- `ADR 0006` / `ADR 0011`（轮与步的两级）：账本的 `turn-start`/`turn-end` 与步的分组照这两条 ADR 的读数，
  不新造判据。
- 上游对照：`@deepseek-ai/dsh-client-ui-trajectory@0.1.0-rc.6` 与真记录 `session-edc108e2-…`。**照搬**的是
  指令合并的分段、账本形状与折叠；**不照搬**的（加严或不做）已列在非目标。

---

## 八、落地（2026-09-30）

七张票全部落地；票按约定删除。分支 `system-reminder`，落地点见下面各条。

### 报数

- **后端**：`clojure -M:test -m harness.test-runner` → **1396 例 / 14393 断言 / 12 红 1 错**。
  这 13 条**不是本特征带来的**：同一条命令在**主检出（未改动的 `main`）**上跑出同样的一批
  （`tools_test` 的 278 / 605 / 142–145 / 693、`a-bound-session-roots-relative-paths-at-its-project`
  的 NPE、`mcp_wired_test` 227–229、`claims_test` 382、`hooks_test` 108），逐条同名同位置。
  根因是这台机器的会话配置（`grep` 不在这套编辑模式下服务）与既有的 `PreCompact` hook，
  与注入物外形、轨迹账本无关。
- **前端**：`npm run typecheck` 绿；`npm test` → **191/193**，两条红的（`elicitation` 的
  `a-servers-question-parks-the-run-and-the-answer-finishes-it`、`subagents` 的
  `the-endpoint-answers-in-the-shape-both-screens-read`）在主检出上**同样红**，既存；`npm run build` 绿。

### 真浏览器走查（`node scripts/dev.mjs --scripted`，端口 OS 分配，临时家）

1. **新会话出生**：会话栏里**只有一张注入卡**（两份 AGENTS.md 已在一条 reminder 里，512 B），
   标题是 **`Instructions from: <绝对路径>`**，不再是 `system-reminder`、也不是每个文件一张。
   记录里那一条 `message` 行的正文逐字核对过：`<system-reminder>` + 前言 + 每个文件一段
   `Instructions from: …`，**内层没有任何 XML**。
2. **会话栏折轮**：折上那一轮 → 注入卡**不画**；展开 → 回来。
3. **作业结束**：`job` 起一条 `sleep 2`、等它结束、再发一句 —— 记录里多出的那条
   `message` 行 `:source` 是 `job`，正文是
   `<system-reminder>\nBackground job j1 ended: [exit 0]\nCommand: …\nRead what it said with job_output {"job": "j1"}.\n</system-reminder>`；
   会话栏那张卡的标题是 **`Background job j1 ended: [exit 0]`**。
4. **轨迹**：最上是 `系统` 格（**在任何轮之外**——牛总报的「系统提示词被包进第一轮」就是这条），
   下面 `第 1 轮` / `第 2 轮` 的粗分割线；轮里是 `用户 → 上下文 → 助手 → 工具`；
   工具栏的 **`折起全部轮`** 折上那一轮后，轮里的 `上下文` 格**一起消失**（只剩 `4 个条目 · 3 次模型调用`
   的摘要行），而最上面那个 `系统` 格**不受影响**；`展开全部轮` 回来。
   证据：`evidence/trajectory-ledger.png`。
   **没走到的**：一条真的有压缩的会话（`compacted` 格落在 `Between turns`）——`trajectory-test` 的两条
   用例钉着它（`a-compaction-is-a-cell-between-turns`），走查这一趟没有可压缩的记录。

### 走查里发现并当场修掉的一条

**票 06 的规矩漏了出生那一张卡。** `thread.aui.tsx` 的折法规矩按 `turnBounds` 算，而
`turnBounds` 只把**连续的 assistant 消息**算作一轮——出生写的那条 opening entry 是 **user** 消息，
自成一「轮」（`first === last`，不可折），所以折轮时它**照旧画**。补法是 `turn-steps.tsx` 的
`useFollowingTurnFolded`：卡片**往前看**它下面那一轮折没折（卡片本来就不在任何轮里，只能往前看），
折上就不画。`lib/turns.ts` 一行没改。

### 与票面写的、或与计划的出入

- **`fold-trajectory` / `trajectory-emit-step` 删了**。它们是「一轮一行」那套流式发射器，
  账本之后没有「一轮」这个发射单位：取而代之是 `final-count`（开放轮之前的格不再变）+ `drift`
  （这一批发什么：先是新定的格，然后整条开放尾部再来一遍），路由与客户端共用一个规则。
- **「首行标签行」不等于「框里第一行」**。指令块照 dsh 是**一个** reminder 装**几个**文件，
  前言那句先说，所以第一行是前言。`cap.reminder/labels` 与 `ui/lib/injections.ts` 的
  `LABEL_PREFIXES` 是同一张表的两份，两边都**按表找第一行**（找不到才退回第一行 / 旧标签），
  卡片标题因此是 `Instructions from: …` 而不是那段前言。这一条是票 01/03 的原话里没有点明的。
- **I18n 只加了要用的键**：`between.label` / `summary.turn` / `toolbar.collapseTurns` /
  `toolbar.expandTurns` / `kind.message`（原 `kind.assistant` 改名）/ `kind.compacted` /
  `facts.written` / `facts.messages`。票 05 列过 `collapseCalls` / `expandCalls` 与
  `system.initial`：这个视图没有「按次调用折叠」这件东西（`system` 格的标记照旧走 `kind.system`），
  照 `lib/catalogs.ts` 的规矩**不加没人用的键**。
- **`:calls` 骑在 `turn-start` 格上**（票面只说「字段原样保留」，没说落哪一格）：一次调用是**整轮**的事实，
  而 `turn-start` 是开放轮里唯一一个会被重发的头，所以调用中途落下的那次也送得到读者手上；
  条目的 `:call` 还是指向它。
- **没结束的轮没有 `turn-end` 格**：一条轮结束的凭据只有「后一轮开了」或「记录最后一帧是终态」，
  比这更弱的证据不足以让账本说「这轮完了」。视图因此得容得下「只有轮头」的一轮（`sectionsOf` 末尾收口）。
- `.worktrees/trajectory-on-the-downlink` 那条线**没动**：它还没有代码（只有 spec 与票），
  合的时候照本票的 `:cells` 来。

---

## 九、code review 之后（2026-09-30）

两轴各跑一次（Standards / Spec）。**改掉的**：

1. **`cap.reminder/first-label` 按表序找，不按行序** —— 一张 skill 正文里只要有哪一行以
   `Instructions from: ` 开头，`kind-of` 就会把它判成 `opening`、`skill-name` 返回 nil，
   `loaded-names` 随即失明、同一份正文每轮重注（正是 spec 自己警告过的那条路）。改成
   **逐行问表**：第一个命中表的行就是标签行。UI 那份同名表本来就是按行序的，两端因此说同一句话。
2. **`cells-of` 里的 `atom`** —— 在惰性管道里 `split-with @pending` + `reset!` 是读-改-写，
   与 `docs/rules/concurrency.md`（位置要有人认领、传值不传位置）相抵。改成
   `reduce` 把「还没落位的轮间行」当 accumulator 带着走，纯函数。
3. **`turnsOf` 一处** —— 视图与时间线各自 `sectionsOf(...).flatMap(...)` 是两份答案；
   收进 `lib/trajectory.ts`，两处都调它。
4. **轮分割线真的画粗** —— `TurnHead` / `BetweenHead` 从 `border-y` 改成 `border-y-2`：
   spec 说的是「粗分割线」，原来与行内那条细线一样粗。
5. **前端套件里那份手写账本的轮内顺序** —— 原来写成 `context → user`（老布局），
   改成 `user → context`，与验收单「轮内 `user` 在 `context` 之前」和真记录一致。

**没改、但记在这里的**（review 点到，判定为不必改）：

- **轮中途变的 system 格带 `:turn`**：`one-run` 只在 system 是那一轮**第一条**时把它提到轮外；
  一条**续跑**的轮里字节变了，它落在轮中间、带 `:turn n`。这是有意的取舍：另一种做法是让视图在
  轮中间断开分组（一条轮画两个 `Turn N` 头），比这更坏。真记录里这几乎不发生（提示词一场会话一条），
  但 §五 那句「`system` 格不在任何轮的折叠里」只对**最前那条**字面成立。
- **`ui/lib/trajectory.ts` 从 `API_BASE` 常量改成 `apiBase()` 函数**：`API_BASE` 是导入期算的常量，
  Node 里没有 document 可解析，前端套件那条真端点用例走不通。`apiBase()` 正是
  `lib/threads.ts` 为「套件驱动真端点」准备的函数。
- **空 reminder 帧 → `injectionView` 返回 null**：一个框里什么都没有的块，画一张卡就是在声称
  一次没人能核对的注入。这条规矩随票 03 一起进来，此处记一笔。
- **文档改动超出票 07 列的文件**（`architecture.md` / `kernel.md` / `system-prompt.md` /
  `rules/testing.md`）：那几处写着旧标签、旧 payload 的句子不改就是假的。
- **测试里的 `turns-of` 是从账本读回旧形的适配器**（`trajectory_test.clj`）：它明说不是第二套折法，
  只为让三十条折法断言留在原来的措辞里；账本自己的形状由另外四条用例钉住。
