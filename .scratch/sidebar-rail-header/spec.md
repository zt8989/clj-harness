# 聊天区 header 与侧栏折叠成图标 rail

对齐 https://www.assistant-ui.com/ 首页那个 demo 的 header：一条 3rem 的 header 行横贯整个
widget，侧栏格子里放折叠开关，聊天格子里放标题。我们目前 `app.tsx` 是 `flex h-dvh`：一个固定
`w-72` 侧栏 + 一个聊天列，**没有任何 header 行**，侧栏 header 自己挂着 New task / Add project /
Refresh，footer 挂着 Settings，聊天区连标题都没有。

## 参照：demo 的真实结构（浏览器实测，非猜）

用 playwright 打开官网并真点了「Collapse threads」按钮，读回来的 DOM：

widget 根是 **grid**：

```
bg-background grid h-full grid-rows-[3rem_minmax(0,1fr)] md:grid-cols-[15rem_minmax(0,1fr)]
```

四个格子依次是：侧栏 header / 聊天 header / 侧栏 body / main。

- **侧栏 header**（`hidden h-12 items-center gap-2 border-r border-b px-4 md:flex`，
  `bg-foreground/[0.025]`）：logo + 字标 `assistant-ui` + 右端
  `aria-label="Collapse threads" aria-expanded="true" aria-controls="aui-demo-sidebar"`
  的 PanelLeft 按钮（`ms-auto -me-1.5 grid size-7`）。
- **聊天 header**（`flex h-12 min-w-0 items-center gap-2 border-b px-4 md:px-5`）：最左两个
  互斥的 PanelLeft 按钮 —— 窄屏用的 `aria-label="Open threads"`（`md:hidden`），和折叠后才出现的
  `aria-label="Show threads"`（`hidden ... md:grid`）；紧跟着标题
  `min-w-0 truncate text-[13px] font-medium` → `New chat`；最右是
  `-me-1.5 ml-auto flex shrink-0 items-center gap-1.5` 的图标组（Demo options / Full screen）。
- **折叠的做法不是 display:none**：是把列定义从 `md:grid-cols-[15rem_minmax(0,1fr)]` 换成
  `md:grid-cols-[minmax(0,1fr)]`，两个侧栏格子保持 `hidden`（去掉 `md:flex`），聊天列顺势吃满整行。

## 本仓的四条决定（用户已定）

1. **折叠形态 = 图标窄栏 rail**（不是 demo 的完全隐藏，也不是浮层）：侧栏收成 ~3rem，只有图标，
   没有项目与会话列表。
2. **侧栏自己的动作不搬家**：New task / Add project / Refresh / Settings 在 rail 里全部保留，
   只显示图标（`title` + `sr-only` 文案一个都不能丢）。
3. **标题内容与侧栏行一致**：会话 id 的前缀；空会话显示 `New chat`。两侧共用一个标签函数，
   不许各截各的。
4. **折叠状态要记住**：写 localStorage，刷新后保持。

## 状态

- 2026-09-16：playwright 实测 demo 结构，拆出 4 张票，尚未开工。
