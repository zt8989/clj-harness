# 06 — 拆掉左侧栏那一块

**What to build:** 上一版把「有哪些子agent、跑过什么」做成了左侧导航栏里的一个块，点运行记录会
**换掉主栏显示的会话**。这一版入口在对话里（04），所以那一块整个拆掉——留着就是同一件事的第二个
说法，而且它的点击行为是这一版明确要废掉的那个。

**Blocked by:** 05（新的入口得先在，不然拆完就没路进子agent 的会话了）

**Status:** done

**落地情况（2026-09-22）：** 删掉 `ui/src/components/subagent-panel.tsx` 与侧栏的 import/挂载
（`busy` 留着，别的按钮在用）；`subagent-list.tsx` 只删 `RunRows`（确认过唯一读者是那块面板）与
它专用的三个键（`runsEmpty` / `running`），`DefinitionRows` 与范围那句话留着；
`locales/{en,zh}/shell.json` 删掉只被那块用过的 `titleHint` / `definitions` / `runs` /
`runsEmpty` / `running` / `changeInSettings`（逐键 grep 确认过；`title` / `loading` 设置页还在用）。
`GET /api/subagents` 与它的 `runs` 留着 ~~—— 前端不再读它这件事写在 `subagent-list.tsx` 的注释与
提交信息里~~（原来的 `subagents` 套件仍钉着这个端点，`runs` 那一半也钉着）。
**2026-09-24**：划掉的那半句被 `.scratch/right-pane-tasks` 票 03 翻掉——任务视图的「子代理」那一段
**重新读** `GET /api/subagents` 的 `runs`（按 `:parent` 收窄到本会话），读者是 `ui/src/lib/subagents-runs.ts`。
端点与 `runs` 照旧留着，落地的决定一个字没改；过期的是 `subagent-list.tsx` 里那句「前端不再读它」。

`EXPECTED_CASES` 102 -> 106，并在 `ui.test.ts` 的计数史里写清加减：**减 2** 条委派行渲染用例
（`RunRows` 没有屏了），**加 6** 条 `subagent-view` 套件用例（读源码，钉 04/05/06 的决定）。
`.scratch/subagents/evidence/README.md` 顶部加了注记：`t04-*` 四张截图已被取代、保留不删。

要点：

- 删 `ui/src/components/subagent-panel.tsx`，以及 `sidebar.tsx` 里挂它的那一处接线。
  **连带检查 `busy`**：那个 prop 是「本侧栏有请求在飞」，如果只有这一块在用，它也该跟着走。
- **`subagent-list.tsx` 不是整块删。** 它是**两个屏共用**的行（设置页也画同一批定义行），
  拆的只是「运行记录」那一组。先确认 `RunRows` 现在还有没有别的读者，没有才删；
  `DefinitionRows` 与「范围怎么读成一句话」那段（两个屏共用的唯一出处）**留**。
- **i18n 要一起收。** `locales/{en,zh}/` 里只被这一块用过的键要删掉，但**逐键确认**：
  `subagents.title` / `changeInSettings` 之类可能设置页还在用。两个语言的目录**对称**（本仓有
  `both-catalogs-say-the-same-things` 与 `every-catalog-entry-is-named-by-something` 两条用例守着，
  删一边不删另一边会红）。
- **`ui/test/ui.test.ts` 的 `EXPECTED_CASES` 是合同，不是记账。** 它现在钉在 50，就是为了让一个
  悄悄掉出去的套件被抓出来。这一票会拿掉若干条（原来那几条侧边栏面板的渲染用例），04/05 会加上
  新的——**必须**同步改这个数并写清加减了什么，别把它改成「当前的值」了事。
- **后端先别跟着删。** `GET /api/subagents` 会因此没有前端读者（`runs` 那一半尤其）。它是便宜的、
  也是「这个家委派过什么」唯一的答案，**留着**；但要在提交信息或注释里说清「前端不再读它」，
  免得下一个读者以为漏了接线。要真的删是另一个决定。
  **2026-09-24**：上面这条的前提（「会因此没有前端读者」）不再成立，理由见本文件开头那条注——
  第二条读者回来了。「别急着删端点」这个决定本身没有翻。
- **上一版的走查证据会过期。** `.scratch/subagents/evidence/` 里 `t04-*`（侧边栏面板）那几张截图
  描述的是一个不再存在的屏。**不要删**（那是上一版的记录），但在那里加一句话说明它们已被
  `.scratch/subagent-view/` 取代——一份读起来像现状的旧证据比没有证据更坏。
