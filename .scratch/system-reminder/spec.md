# spec：注入物的统一外形 —— 一个 `<system-reminder>`、一份 AGENTS.md、一轮里的系统提示词

**一句话**：把「每次调用前摆进历史的那些块」统一成**一条 `<system-reminder>` 包着的纯文本**——标签层去掉、
多个 AGENTS.md 合成一条、作业结束走同一条——并修两处「折」：轨迹的**一轮只装用户与模型**
（系统提示词挪出轮外），会话栏**折轮时把注入卡一起折进去**。

牛总 2026-09-30 的原话拆成五条：

1. 所有 context 注入用 `<system-reminder>` 包起来，**特别是 job_end**；
2. `<system-reminder>` 里面不要再次包括 xml；
3. 多个 AGENTS.md 合并成一个注入；
4. 按轮次折叠的时候要把上下文注入一起折叠；
5. 轨迹里第一轮把系统提示词包进去了（"不知道是事件顺序错误还是渲染错误"），**一轮就是用户+llm**。

已拍：范围 = 指令文件 / 技能目录 / 运行上下文条目 / 技能正文 / 作业结束通知（**不含** `instruction-update`）；
内层全改纯文本，来源/路径/命令/id 用**纯文本行**表达（像 Claude Code 的 `Contents of …`）。

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
| 轨迹里的 `system` 条目 | 一轮 `:items` 里的一个 `:kind "system"` | `harness.edge.trajectory` / `ui/src/components/trajectory-view.tsx` |

**`:source` 是承重墙**。`skill` / `job` 两种 `:source` 故意不在 entry 集合里——派生注入**每轮重算、不算会话的 entry**；
`opening` / `injection` 在集合里、靠 id 认。外形的改法一旦让 `returned-source` 认错，派生注入就会被当成 entry 去重、再发一遍，
轨迹也会把它画错位置。所以「去掉标签」这件事，必须同时把**分类依据**换掉，不能只换字符串。

### 轨迹：第一轮里的系统提示词

`harness.edge.trajectory/one-run` 在 `open-turn`（由第一条新 user 消息开轮）**之后**立刻
`append-last [(system-item …)]`。所以 `:turns[0].items[0]` 就是 system 条目，`:initial true`。

**这不是事件顺序错误，也不是渲染错误**——是折法自己的选择：`trajectory.clj` 的 docstring 写着
「THE SYSTEM MESSAGE IS SHOWN ONCE, and again whenever its bytes change」。要满足「一轮 = 用户 + llm」，
就得改这条折法，把系统提示词挪出轮外。

### 会话栏：折轮不折卡

`ui/src/components/turn-steps.tsx` 的注释把现状写死了：
「A CARD IS NOT A STEP … `thread.aui.tsx` draws a card part even in a folded step or head」。
所以一轮折上之后，**注入卡还挂在屏幕上**——除非它是那一轮的「结论」。牛总要的是它跟着轮一起折进去。

---

## 二、决定

### 决定 1：一个写手，住在 `harness.cap.reminder`

新命名空间 `harness.cap.reminder`，只做一件事：

```
(wrap ["第一行" "" "正文"]) =>
"<system-reminder>\n第一行\n\n正文\n</system-reminder>"
```

**为什么是新命名空间**：要用它的四家是 `cap.preamble`（指令/目录）、`cap.skills`（正文）、`cap.jobs`（通知）、
`edge.ag_ui`（上下文条目）。`preamble` 已经 require `skills`，`jobs` 谁都不 require；把 `wrap` 塞进
`preamble` 会让 `skills`/`jobs` → `preamble`（而 `preamble` → `skills`）成环。一个只依赖 `clojure.string`
的小叶子命名空间是唯一不造环的位置。（同 `cap.system-prompt` docstring 里那条 require 环的判据。）

### 决定 2：里面只有纯文本

reminder 里**不出现任何 XML 元素**，包括原来那些语义标签。实参是**行**的序列，写手不解析、不转义。

### 决定 3：机器可读的那半，从「标签」改成「首行标签行」

标签原来同时干两件事：给模型看的框 + 给代码读的锚。去掉标签之后，锚必须换个地方，选**首行的纯文本标签行**，
因为记录里只有消息字节，别的读者（会话表、轨迹）也只认字节。逐条规定：

| 注入物 | reminder 内首行（**锚**） | 之后 |
|---|---|---|
| 指令文件（**合并后一段**） | `Instructions from <绝对路径>` | 一条空行，然后是那个文件的正文；下一个文件同形，段间空行 |
| 技能目录 | `Available skills` | 目录正文（`cap.skills/catalog-text`） |
| 会话上下文条目 | `Session context` | 原来的 `- 描述: 值` 列表 |
| 技能正文 | `Skill <name>`（`<name>` 原样，不转义） | 正文 |
| 作业结束 | `Background job <id> ended: <status>` | `Command: <原样命令>`、`by: user`（人停的才有）、一句读法 |

- `returned-source` 改读这组首行：`Instructions from` / `Available skills` → `opening`；
  `Skill ` → `skill`；`Background job ` → `job`；其余 → `injection`（`Session context` 落这里，与今天一致）。
- `loaded-names` 改读 `Skill <name>` 首行。
- 两个读法都写成**一张表 + 一个函数**，与 `cap.reminder` 同住或紧挨着，别让两处各拼一遍前缀。

**另一案（没选）**：把 kind 显式挂在消息上（`:kind`/`:id` 前缀）端到端传。否掉的理由——kind 要穿过
kernel→edge 两层，而 `sessions/model-view` / `provider-part` 会剥掉未知字段，等于为了一个字符串多开一条
「不许剥」的例外；首行标签行是模型本来就要读的同一批字节，零新增管道。

### 决定 4：多个 AGENTS.md 合成一条注入

`harness.cap.preamble/messages` 从「每个文件一条」改成「**所有指令文件一条** + 技能目录一条」。
`gather` 的字面不变（还是 `[{:path :content} …]`，读盘失败的策略不变），改的只是折叠。

- 出生时写进会话的开场条目因此从 `N+1` 条变 `2` 条：`session-opening-0`（指令，内含 N 段）、
  `session-opening-1`（技能目录，若没有可用的技能则没有它）。
- `opening-entries` / `opening-entry?` / `isOpeningEntryId` 不用改（前缀与正则本来就与条数无关）。
- `preamble/report` 的「每块来源与字符数」改成「合并块的总字符数 + 逐文件的 `:path` 名」。

### 决定 5：轨迹的轮 = 用户 + llm，系统提示词出轮

`trajectory-answer` 的 payload 顶层多一个 `:system`，`{:turns […] :system […] :incomplete bool}`：

```
:system [{:turn 1 :text "<prompt.md + 本会话事实>" :initial true :tools [...呼出那张表...]}
         {:turn 4 :text "…字节变了…" :tools [...]}]     ; 每次字节变了多一条；末条是最新的
```

- `one-run` 不再把 `system-item` 塞进轮；轮里的 `:items` 只剩 `context` / `user` / `assistant` / `tool`。
  「显示一次、变了再显示」的性质保住：变了就在**当前轮号**下多一条 `:system`。
- `:turn` 是「它出现在哪一轮之前」的轮号（第一轮就是 1）。
- 前端 `trajectory-view.tsx` / `trajectory-timeline.tsx` 把系统提示词画成轮列表**上方**的一组行/卡，
  不再是轮内条目；点开仍然是「提示词 / 工具表」两个 tab（现成）。

### 决定 6：会话栏折轮时，注入卡跟着折

`ui/src/lib/turns.ts` + `ui/src/components/turn-steps.tsx`：一个**属于本轮的注入卡**（`injected-context`
的 card-only 消息）在轮折上时**不画**，只留摘要行；展开才画。

- 「属于本轮」= 它与本轮相邻（在 `turnBounds` 的 `first..last` 之间，或紧贴 `first` 之前的那张卡），
  且它不是**结论**。
- **压缩卡（`compacted-context`）不动**：它是「历史在这里被折过一次」的边界，是上一版刻意留在屏幕上的
  （`.scratch/compaction-frames`）。本特征只动注入卡。（若牛总要一起折，那是另一票。）

---

## 三、形状对照

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

- **不改注入的位置、顺序、次数**：`.scratch/context-frames` 决定 7（`system → 提问 → context → 技能正文`）
  与 `.scratch/session-opening`（开场只在出生写一次）原样保留。本特征只改**外形**与两处「折」。
- **不动 `<project>` / `<env>`**：它们在 **system 消息**（message[0]）里，是「本会话的事实」，不是 context 注入。
  （若牛总要把它们也包进 reminder，那是另一票，且要接受前缀缓存的影响。）
- **不动 `instruction-update`**（`role: "developer"`）：已拍不在范围。
- **不改记录格式**：注入仍然各是一条 `message` 行，payload 仍是消息本身，信封仍带 `:source`。
- **不改 `job_output` / `job_kill` / `bash` 的语义**，也不改通知的「一个作业只说一次」。
- **不动压缩卡**（见决定 6）。
- **不重做注入卡的外观**；只改它的标题来源（首行标签行）与折叠时机。

---

## 五、验收

- **外形**：一条指令文件的会话里，AGENTS.md（无论几份）只产生**一条**注入消息、一个 `<system-reminder>`；
  里面第一行是 `Instructions from <绝对路径>`，且**没有** `<instructions …>` 这类标签。
- **分类不变**：技能正文 / 作业通知的 `:source` 仍是 `skill` / `job`（不是 entry、每轮重算）；
  `opening` / `injection` 的 entry 语义与今天逐字相同。`skills-test` 的幂等用例、`http-test` 的
  `returned-source` 用例、`replay-test` / `trajectory-test` 的 `entry-row?` 用例钉住。
- **卡片**：会话栏里每个注入的折叠行显示的标题来自首行标签行（不再是 `system-reminder`），字节数不变。
- **轨迹**：payload 顶层有 `:system`，`turns[*].items` 里**没有** `kind == "system"`；系统提示词画在轮外；
  字节变了会在对应轮号下多一条。
- **会话栏折叠**：一轮折上之后，属于它的注入卡**不画**；展开又有；摘要行的步数不变。
- **机器门**：`clojure -M:test -m harness.test-runner`（定向 `preamble` / `skills` / `jobs` / `ag-ui` /
  `http` / `replay` / `trajectory` / `stats` / `loop` / `normalize`，再全量）；`ui && npm run typecheck &&
  npm test && npm run build`；`node scripts/dev.mjs --scripted` 起服务、**自己开浏览器走一趟**
  （发一句 → 折轮看卡是否一起折；出生块看是否一条 reminder；开一条后台作业等它结束看通知；切轨迹看系统提示词在轮外）。
- **旧记录**：老会话里的注入仍是老标签，读者必须**两种都认**（标签行优先，旧标签前缀兜底）——否则一升级，
  老会话的 `:source` 就认错。这条写进判据，不是"顺手"。

---

## 六、落地的票

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | `plain-text-reminder-writer` | — | `cap/reminder.clj`（写手 + 首行标签表）；`preamble` 指令合并成一条、目录一条、上下文条目包一层；`preamble-test` / `ag-ui-test` / `http-test` 的条数与字节断言 |
| 02 | `skill-body-and-job-notice` | 01 | `skills/skill-message` + `jobs/notice` 换形；`loaded-names` 与 `returned-source` 改读首行标签行（旧标签兜底）；`skills-test` / `jobs-test` / `http-test` 的分类断言 |
| 03 | `the-card-reads-the-label-line` | 01, 02 | `ui/lib/injections.ts` 的标题来源 + `context-card.tsx` 首行渲染 + i18n + `ui/test/suites/injections.ts` |
| 04 | `the-system-prompt-out-of-the-turn` | — | `edge/trajectory.clj` 的 `:system` 顶层与 `one-run` 改写；`trajectory-view.tsx` / `trajectory-timeline.tsx` 画在轮外；`trajectory-test` / 前端套件 |
| 05 | `the-fold-takes-the-injection-card` | — | `ui/lib/turns.ts` + `turn-steps.tsx` + `thread.aui.tsx`：注入卡随轮折，压缩卡不动；`ui/test/suites/turns.ts` |
| 06 | `docs-and-gates` | 01–05 | `CONTEXT.md`（注入词条）、`docs/architecture/skills-and-instructions.md` / `edge.md` / `client.md`；两套全量报数；真浏览器走查记录 |

04 与 05 互不依赖，也不必等 01–03——它们只碰轨迹与会话栏，先做哪条都不冲突。

---

## 七、与既有决定的关系

- `.scratch/context-frames` 决定 5/6/7：**位置与顺序一字不改**；改的只是每条的外形。它那句「卡片标题取首行标签名」
  由票 03 换成「首行标签行」。
- `.scratch/job-endings` 决定 7（「界面一个字都不用改」）早已被 `.scratch/context-frames` 取代；本特征再动一次
  通知的**字节**，不动它的**时机与次数**。
- `.scratch/compaction-frames`：压缩卡「折了也画」的规矩**保留**（决定 6）。
- `ADR 0004` / `docs/rules/panel-data.md`：与外形无关，不动。
- 上游对照：Claude Code 的 `<system-reminder>` 里同样是纯文本 + `Contents of <path> …` 的标签行；
  这是牛总要的形。
