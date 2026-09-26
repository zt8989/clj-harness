# 02 — Composer 之上那条横条：折着的一行，点开是明细

Status: ready-for-agent
Blocked by: 01

## 做什么

Composer 的输入框**之上**多一条横条，画的就是票 01 那条口子答回来的列表。

**折着（默认）**：一行。左边一个清单图标（lucide 的 `ListTodoIcon` 与对话里 `todo_write` 那张卡同一个，
`ui/src/components/message-parts.tsx` 已经在用），中间是**只列非零项**的计数，
右边一颗随开合翻面的箭头（`^` / `v`）：

```
☰  3 已完成 · 1 进行中 · 1 待处理                                    ^
```

顺序照截图：**已完成 · 进行中 · 待处理**。某一档是 0 就**不出现**（不画「0 待处理」）。
一条都没有时**整条不画**——包括第一次答案还没回来的时候（`null` 与 `[]` 在这里是同一种「没得画」）。

**点开**：在它下面（输入框之上，同一条横条之内）长出明细，一行一条：

```
☰  3 已完成 · 1 进行中 · 1 待处理                                    v
   [x] 读一遍 ComposerFrame
   [~] 写横条
   [ ] 走查一遍
```

- 每行 = **状态标记 + 文本**，文本是模型写的原文，**不翻译**。
- 标记复用 `harness.cap.todos` 那三个字符（`[x]` 完成 / `[~]` 进行中 / `[ ]` 待处理），不再造第二套：
  屏幕上这三个字符与模型 `todo_read` 读回来的是同一份词汇表，于是「人看到的」与「模型回读的」对得上。
  **状态词**（已完成 / 进行中 / 待处理）只出现在那行计数里。
- 明细长了要**自己滚**（一个 max-height + `overflow-y-auto`），**不许**把输入框顶下去或顶出屏幕：
  这条横条坐在 composer 的框里，框的长高会挤压对话。
- 文本超长**换行或截断都行，但不许横向撑破**那条横条的宽度。

**位置与实现**：新组件 `ui/src/components/composer-todos.tsx`（`export const ComposerTodos`），
挂在 `ui/src/components/composer-chrome.tsx` 的 `ComposerFrame` 里、**`{children}`（输入框那一坨）之前**，
与今天那条「目录/分支」同一个位置——而那条只在会话没开始时画（`!started`），两者不相遇，所以不用排先后。
拿数据的那个函数放 `ui/src/lib/todos.ts`，形状照 `lib/stats.ts`：一个 `todosFor(threadId)`，
一个显式的返回类型，**不抛**，读不到就当作 `[]`（票 01 已经保证「没写过」是 `[]` 而不是 404）。

**交互**：`Collapsible` / `CollapsibleTrigger` / `CollapsibleContent` 已经在
`ui/src/components/ui/collapsible.tsx`（radix 包的那层），用它——键盘与 `aria-expanded` / `aria-controls`
就是它自带的，不要手搓一个 `div` 上的 `onClick`。开合状态是组件自己的 `useState(false)`，
**不持久化**（`right-pane-toggle` 那条「transient layout preferences」同一条理由：刷新回折着）。
打开时**不写**任何东西：这条横条只有一个动词——看。

**文案**走 `locales/<lng>/composer.json`（它就在 composer 里），中英各一份，跟界面语言走。
中文照截图：**已完成 / 进行中 / 待处理**。复数用 i18next 的 `_one` / `_other`——英文要是
`1 done · 1 in progress` 而不是 `1 dones`。

**data-slot**（测试与走查按它找东西，照本仓库的命名）：
`composer-todos`（整条）、`composer-todos-toggle`（那颗按钮 = 整行）、`composer-todos-summary`（那行计数）、
`composer-todos-list`（明细那个盒子）、`composer-todos-item`（明细里的一行）。

## 验收

- [ ] 给定 `[{已完成},{已完成},{已完成},{进行中},{待处理}]`，`composer-todos-summary` 的文字是
      「3 已完成 · 1 进行中 · 1 待处理」（英文目录里是 `3 done · 1 in progress · 1 pending`），
      顺序是 已完成 · 进行中 · 待处理。
- [ ] 某档为 0 的那一档**不出现在文字里**；只有待处理的列表 ⇒ 只写「N 待处理」。
- [ ] `todos` 是 `[]`（或第一次答案还没回来）⇒ **整个 `composer-todos` 不在 DOM 里**。
- [ ] 点一次 ⇒ `composer-todos-list` 出现、每行一个 `composer-todos-item`、标记是 `[x]` / `[~]` / `[ ]`、
      文本是原文；`composer-todos-toggle` 的 `aria-expanded` 从 `false` 变 `true`；再点一次 ⇒ 回到折着。
- [ ] 只按键盘也能开关（`Tab` 到那颗按钮 + `Enter` / `Space`），焦点样式看得见。
- [ ] 30 条以上的列表展开后**输入框仍在屏幕上**（明细在自己那个盒子里滚）。
- [ ] 它在输入框**之上**、在同一个 `composer-frame` 里：DOM 顺序上 `composer-todos` 在 `aui-composer-root` 之前。
- [ ] 切语言 ⇒ 那行计数跟着变；明细里的文本**不变**（那是模型写的内容）。
- [ ] 前端测试补一套 `ui/test/suites/composer-todos.tsx`（上面每一条至少一个断言）；
      `cd ui && npm test` 绿、`npm run typecheck` 0 error、`npm run build` 绿。
