# 04 — 侧边栏的「子agent」面板

**What to build:** 侧边栏多一个**「子agent」条目**，点一下**展开**，看到这个家里有什么子agent、
跑过什么：

- **定义那一组**：内置的通用与探索，加上人自定义的。每行给它能一眼认出的东西——名字、一句描述、
  它的工具范围（全部减哪些 / 只留只读）。这一组是**只读**的：改在这儿不在，在设置面板（05）。
- **运行记录那一组**：正在跑的、跑过的子agent 调用，各归它的父会话。点开一条**进它自己的会话视图**：
  它自己的消息、它自己的轨迹。这一组是 03 那条子会话在界面上的样子。

点击展开这件事要**真的是一段界面行为**，不是渲染：展开、分组、点开，走查真浏览器一遍。
中文与英文两套文案都要在。

**Blocked by:** 03

**Status:** ready-for-agent

## 实现记

**落在哪：** 侧边栏 footer，Settings 那一行的上面（`components/subagent-panel.tsx`）。这一块是
**定住的**，不在中间那条滚动列表里——列表是「找会话」用的，这块是「按一下才看」的两份文件读数，
它不该是会话列表滚没了的原因。展开后自身 `max-h-72 overflow-y-auto`（第二个滚动区是有意的：
不设上限的话，委派攒多了会把项目列表顶出屏幕）。

**行不在这两个文件里：** 定义行/运行行都在 `components/subagent-list.tsx`，因为设置页画的是同一批
行。两份「这个子agent 能碰什么」就是两个关于同一段范围的说法。那个模块**不能**碰 `lib/i18n.ts`
（它在加载时写 `document`），否则 `ui/test/suites/subagents.tsx` 的渲染用例跑不起来。

**`data-slot`（名字，认领）：**

| 位置 | slot |
| --- | --- |
| 整块 / 触发 / 箭头 / 面板 | `sidebar-subagents` · `sidebar-subagents-trigger` · `sidebar-subagents-chevron` · `sidebar-subagents-panel` |
| 读取中 / 失败 / 文件里那句 problem | `sidebar-subagents-loading` · `sidebar-subagents-error` · `sidebar-subagents-problem` |
| 定义组 / 一行 / 名字 / 徽标 / 描述 / 范围 | `subagent-definitions` · `subagent-definition`(+`data-name` `data-builtin`) · `subagent-definition-name` · `subagent-definition-badge` · `subagent-definition-description` · `subagent-definition-range` |
| 「还没有你自己建的」 | `subagent-definitions-custom-empty` |
| 运行组 / 一行 / 触发 / 名字 / 状态 / 父会话 / 时刻 | `subagent-runs` · `subagent-run`(+`data-subagent` `data-running`) · `subagent-run-trigger` · `subagent-run-subagent` · `subagent-run-state` · `subagent-run-parent` · `subagent-run-at` |
| 空态 | `subagent-runs-empty` |

**「跑着」用哪一格：** `data-running`（属性，给样式和走查看）+ `subagent-run-state` 里那个词
（给人看）。两条从同一个布尔写出来，用例两边都钉（`a-delegation-still-in-flight-says-so-in-its-own-row-and-only-its-own`）。

**空态不是空白：** 两个内置永远在，所以定义组不会空；空的是「自建的」那一档，用一行字说，
并指出改的地方在设置里（`subagent-definitions-custom-empty`）。

## 验收

- [ ] 侧边栏上有「子agent」这个条目，点一下展开（再点收起），`data-slot` 齐、名字写进本票。
- [ ] 展开后两个内置子agent 都在，各自的描述与工具范围看得见；没有自定义子agent 时也画得出这一组
      （空态不是空白）。
- [ ] 跑一次委派，那条子agent 出现在运行记录里（归属它那条父会话），点开后是**它自己的**消息与轨迹，
      不是父会话的。
- [ ] 正在跑的子agent 在界面上与跑完的能分辨（用哪一格表达由实现定，用例把选的这一格钉住）。
- [ ] 中英两语文案齐（`locales/zh` 与 `locales/en` 同一批键），切换语言两边都不缺字。
- [ ] `node scripts/dev.mjs --scripted` 走查一遍：展开、看两个内置、看刚跑完那次、点开进它自己的会话，
      截图/记录留档。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单与基线一致（本票主要动 `ui/`，
      若为读子agent 清单加了只读端点，端点自己的用例要有）。
