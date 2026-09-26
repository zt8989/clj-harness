# spec: 折叠的第二种形态 —— 宽屏的图标 rail

折叠之后**不再只有一种样子**：窗口够宽时它是一条 48px 宽、只画图标的**轨道（rail）**，
列还在、动词都还在原处；窗口窄时仍然是今天那样——抽屉消失、左上角留一颗浮标。
要求是主人提的：*「手机端收起才是一个浮动按钮，电脑或者平板收起是，侧边栏变小，只显示图标，
包括折叠、设置、新建等」*，顶格那格当场定成「只显示 logo，hover 之后展示折叠」。

## 三种形态（断点 `lg`，与 `sidebar-fold` 的同一条）

| 窗口 | 展开 | 折叠 |
|---|---|---|
| 宽（≥ `lg` = 64rem） | 288px 整列（今天的样子） | **48px rail**：只有 logo 与图标按钮，无浮标 |
| 窄（< `lg`） | 288px **浮在对话上** + 背板（今天的样子） | `display: none`，**左上角浮标**（今天的样子） |

**这条断点仍然只有一个读者**：CSS 的 `lg:` 类。`isWideWindow()` 那三个「CSS 答不了」的问题
（初始折没折、picked 要不要关、Escape 要不要关）一个字没变——rail 不新增任何 state。

## 定下来的六件事

**一、rail 宽 48px，格子高 48px。** 和顶栏、品牌行同一个数（demo 的 `3rem`，见
`.scratch/brand-header` 的二版一节）：rail 的顶格因此是个 48×48 的方块，它那条 `border-b`
与对话列顶栏那条仍然落在同一个 y 上，整页还是一条横线。图标按钮仍是 32px（与全列一致），
在格子里居中——不是把按钮撑成 48px：那样 hover 的底色会顶到 rail 两边。

**二、rail 里只留控件，会话与项目列表不显示。** 自上而下：logo、新建任务、加项目、刷新、设置。
列表那一段用 `hidden` 而不是不渲染，理由与 `sidebar-fold` 那条「折 ≠ 卸载」是同一条：
`sidebar.tsx` 是 `GET /api/projects` 唯一的读者，而且列表自己带着滚动位置与项目的展开状态。
*不做* hover 把整列 peeking 展开——那要引入一条 hover 的延时/宽度状态机，且与「点开才展开」打架。

**三、顶格平时只有 logo，悬停（或键盘聚焦）才露出那颗「展开」。** 48px 放不下 16px 的记号
加一个 32px 的按钮；堆成两格会让 rail 的顶格与顶栏的 48px 对不上。所以那一格是**一个悬停目标**：
默认记号居中，hover 时记号淡出、展开那颗淡入（`opacity` + `group-hover/brand`），
键盘走到它时靠 `focus-visible` 显形——不能让一个只有鼠标能看见的控制成为唯一出口。

**四、rail 顶格那颗是「展开」，不是「收起」。** `aria-expanded` 报的是**所指区域此刻的状态**，
不是按钮的意图：rail 里那块区域是收着的，所以它说 `false`，图标是 `PanelLeftIcon`——
与窄窗浮标是同一个动词、同一个组件、两种 shape（`shape="corner"` / `shape="rail"`），
`aria-controls` 三处都指同一个 `SIDEBAR_ID`。「收起」那颗（`PanelLeftCloseIcon`）只在展开的
品牌行里，说 `true`。

**五、让位只在窄窗。** 顶栏的 `ps-12` 是给**浮标**让位的；宽屏折叠时没有浮标、rail 是实宽的一列，
对话列本来就排在它右边，所以那条清空只该在窄窗生效：`ps-12 lg:ps-3`。

**六、rail 里的失败要说得出话。** 「加项目」在一台没有目录对话框的机器上会退到那个手输路径的
逃生口（错误句 + 输入框）。48px 里这两样都放不下，于是那条路**先把列展开再给表单**——
否则按钮点下去什么都不发生，而「看起来像做了事」是本仓最不能接受的一种失败。

## 非目标

- 悬停整列 peek、拖拽调宽、记住折叠状态（`sidebar-toggle.tsx` 里那条「transient layout
  preference」的判决不变）。
- rail 里显示会话行（首字母/状态点）或项目图标——主人选了「只留控件图标」。
- 手机端的抽屉形态一个字不改。

## 走查（`node scripts/dev.mjs --scripted`，隔离家、OS 分配端口）

宽窗（1280×800）：折起来 ⇒ `aside` 宽 **48**、`display: flex`（不是 none）、记号在、产品名不在、
新建/加项目/刷新/设置四颗都在且**都只有图标**、会话列表 `display: none`、**没有**浮标；
顶格 hover ⇒ 展开那颗出现（`opacity` 从 0 到 1）且 `aria-expanded="false"`，点它 ⇒ 回到 288。
窄窗（390×844）：折起来 ⇒ `aside` `display: none`、浮标在 (8,8)、rail 一个字都看不见。
三态都要截图。

## 走查实测（2026-09-21，真 Chromium，`node scripts/dev.mjs --scripted --ui-port 5211`）

宽窗 1280×800，展开 ⇒ 折叠（点品牌行末位那颗）：

| | 展开 | rail（折叠） | rail 顶格 hover |
|---|---|---|---|
| `aside` | 288 × 800，`flex`，`static` | **48 × 800**，`flex`，`static` | 48 |
| 品牌格 | 287×48，记号 x=10、产品名 x=34、收起那颗 x=245 | **48×48**，记号 x=16 居中，**产品名不在 DOM 里**（`null`） | 记号淡出、展开那颗 `opacity` **0 → 1**（x=8，32×32） |
| 新建 / 加项目 / 刷新 | 199 / 32 / 32，一行 | 各 **32×32、x=8**（48px 一格，竖着排） | 同左 |
| 设置 | 271×32，带字 | **32×32、x=8**，带 `sr-only`（`textContent` 仍是 `Settings`） | 同左 |
| 会话/项目列表 | 287 × 655 | **`display: none`**，元素**还在**（`hasList: true`） | 同左 |
| 对话列顶栏 x / 宽 | 288 / 992 | **48 / 1232**（rail 占自己的实宽，顶栏**没有** `ps-12`） | 同左 |
| 浮标 | 不在 | **`display: none`**（`lg:hidden`） | 同左 |

rail 顶格那颗点一下 ⇒ `aside` 回到 288（`flex`）。键盘：在 rail 状态下按一次 **Tab**，焦点就是
rail 那颗「展开」，`opacity` 量到 **1**（`focus-visible` 生效，不需要鼠标）。

窄窗 390×844（Escape 收抽屉之后）：`aside` **`display: none`**（rail 在窄窗一个字都不出现，
里面那颗 `0×0`），**浮标在 (8,8)、32×32、`opacity: 1`**；顶栏 `padding-inline-start: 48px`、
标题与页签的文字都在 x=48（清空还在）。抽屉展开时顶栏是 12px（没让位）。
**任一窗口同时只有一颗「展开」在屏幕上**：窄窗是浮标（`inSidebar: false`），宽窗是 rail 里那颗
（`inSidebar: true`），两颗都指 `app-sidebar`、都说 `aria-expanded="false"`、名字都是 `Open sidebar`。

截图：`evidence/01-wide-expanded.png`、`02-wide-rail.png`、`03-rail-top-cell-hover.png`、
`04-narrow-drawer.png`、`05-narrow-floating-button.png`。

## 修一版：设置那颗浮上来了（主人当场指出）

第一版的 rail 里，设置那颗紧贴在刷新下面（y≈217），没有待在列的底部。原因是我把列表那一格改成了
`hidden`：它是这一列的**生长项**（`flex-1`），而 `display: none` 的子项既不占空间也不生长——
于是剩下的空间没人要，`footer` 就跟着 header 上来了。

**修**：`footer` 上加 `mt-auto`。这不是补丁而是这条布局的正确说法——「这一列的脚在列底」，
与上面那格是显示还是隐藏无关。**展开时它完全惰性**（列表把每一分空闲都吃掉了，没有剩余给 auto margin），
所以两个分支都写它、不必只写在 rail 那支。

量到（1280×800，真后端）：rail 里 `footer` y=751..800（高 49）、设置那颗 y=760..792，
与展开时**同一组数**；刷新那颗仍在 y=152..184，列表 `0×0`。截图 `evidence/07-rail-settings-at-bottom.png`。

## 落地时与计划的出入

- **顶格那颗按 `shape` 复用同一个组件**（原计划含糊地说「rail 自己画一颗」）：三处出口一个动词，
  两处是同一个 `SidebarOpenButton` 的两个 `shape`，第三处（收起）仍在品牌行。这样 `aria-controls`
  与名字只有一份来源。
- **逃生口先展开**是加进来的一条（spec 六），原计划只想「rail 里不画表单」——那样按钮会看起来没反应。
- **`AppBrand compact` 是 prop 不是第二个组件**：记号只有一张画法。
- 列表用 `cn(...)` 加 `hidden` 而不是外面套一层条件渲染：DOM 必须留着（滚到哪儿、哪个项目展开着）。

## 套件钉什么、钉不住什么

`ui/test/suites/sidebar.tsx`（改不了渲染，这个跑不起 `sidebar.tsx`——它够到 `lib/i18n.ts` 的
`document`）：`AppBrand compact` 渲染出**只有记号**（`brand-name` 不在），完整形态两样都在；
`sidebar.tsx` 的源码文本里，rail 那一支用 `compact`、折叠时列表那段带 `hidden`、
三处折叠控制仍在同一个文件里各出现一次。

**钉不住的**：rail 究竟多宽、hover 真的显形没有、宽屏折叠时浮标真的不画、48px 的格子对不对齐——
那些只有浏览器量得出来，是走查的表。

## 票

| # | 什么 | blocked by |
|---|---|---|
| 01 | rail 的骨架：`aside` 三态、顶格、三个 48 格、列表隐藏、设置那颗 | — |
| 02 | 展开这颗的两种 shape、浮标只在窄窗、顶栏让位只在窄窗、逃生口先展开 | 01 |
| 03 | 走查、套件与收口 | 01, 02 |
