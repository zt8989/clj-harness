# spec: 一忙就把行上的按钮全亮出来（外加那颗转错了人的圈）

主人报的：**点「添加项目」，刷新按钮开始转，会话列表里每一行的归档按钮全部展示。**

两件事，一个来源：`busy`。侧栏在**任何一个写**（新建任务、加项目、归档、删除）进行期间把
`busy` 置真，而这个真值被两个地方当成了别的东西用。

## 一、`disabled:opacity-50` 压过了揭示用的 `opacity-0`

行上的动作（归档 / 取消归档 / 删除）与项目那行的「更多」都是**够着才出现**的：类里写着
`opacity-0 group-hover:opacity-100 group-focus-within:opacity-100`。而 shadcn 的 `Button`
底座带着 `disabled:opacity-50` —— **同一个工具类的同一个变体**，两个不归层叠管、归 `cn`
（tailwind-merge）管：它只留最后一个。于是这个揭示在**控件被禁用时整个失效**，而不是「压不住」：

| | 空闲 | busy（POST 挂住 5s） |
|---|---|---|
| 行上归档那颗 `opacity` | 0 | **0.5**（实测，2026-09-21，真 Chromium） |
| 项目「更多」那颗 | 0 | **0.5** |

侧栏**忙的时候把每一行都禁掉**，所以这不是角落：点「添加项目」之后，原生选目录框要等人回答多久，
`busy` 就真多久（`sidebar.tsx` 里那句注释正是这么写的），满列表的按钮就亮着多久。

**修**：揭示那一串收进 `lib/reveal.ts` 一处，并带上两件缺一不可的东西——
`disabled:opacity-0`（同变体、写在后面，`cn` 才留它）与 `group-hover:disabled:opacity-50`
（多一个类、更具体，所以**已经揭示出来的禁用控制仍旧发灰**，不是变成可用）。键盘那条路靠
`group-focus-within`，而它也正是揭示必须用 `opacity` 而不是 `visibility: hidden` 的原因：
看不见的元素不能被聚焦，Tab 进这一行就永远够不到那颗按钮。

## 二、转圈从来不在它自己那件事上转

刷新图标原本读 `busy`，**而 `refresh()` 是全组件唯一不置 `busy` 的函数**（它是读，没有第二个写要抢）：
所以那颗圈在「不是刷新」的时候转、在真刷新的时候不转——主人点的正是前者。

**修**：加 `refreshing`，在 `refresh()` 的 `try/finally` 里开关，图标只读它。

## 非目标

- **不改 `busy` 的粒度**：一个写进行中就禁掉全部四个控件，这是有意的（`sidebar.tsx` 里写着理由：
  等待原生对话框时，侧栏里任何第二个点击都是与第一个抢写的第二个写）。
- **刷新自己不置 `busy`**，也没顺手禁掉自己：两次并发读是后者覆盖前者，本次不动。
- 「忙」这件事对**新建 / 加项目 / 设置**三颗的可见反馈仍旧只有「变灰」——那是原有的设计，不动。

## 验收与实测

真 Chromium（1280×800，真后端 5173，用请求延迟造出 5s 的忙窗口；另在隔离家 5211/5212 复核）：

| 量什么 | 空闲 | 忙、鼠标不在行上 | 忙、hover 那一行 | 恢复 |
|---|---|---|---|---|
| 行上归档 `opacity` / `disabled` | 0 / false | **0 / true** | **0.5 / true** | 0 / false |
| 项目「更多」`opacity` | 0 | **0** | 0 | 0 |
| 刷新图标 `animate-spin` | 否 | **否** | 否 | 否 |
| 刷新图标（把 `GET /api/projects` 拖 3s 后点刷新） | 否 | — | **是**（用时 3s） | 否 |

修复前的同一张表在 ticket 01 里留着（归档 0 → **0.5** → 0，刷新图标在 busy 窗口里为**是**）。

截图在本 feature 自己的 `evidence/` 里：`bug-01-busy-reveals-archive.png`（修复前，满列表亮着）、
`bug-02-fixed-busy-window.png` 与 `bug-05-isolated-busy-reveal.png`（修复后的忙窗口）、
`bug-03-isolated-rail-busy.png`、`bug-04-isolated-reveal-after-fix.png`。

## 套件

`ui/test/suites/sidebar.tsx` 加 `a-disabled-row-action-stays-out-of-the-way`：把
`ThreadListItemAction` 以 `disabled` 渲染成字符串，读回它的 `class`，钉住 `disabled:opacity-0` 在、
**裸的 `disabled:opacity-50` 不在**（那正是 `cn` 会留下的那个）、`group-hover:disabled:opacity-50` 在。
渲染看不到布局，但「`cn` 之后剩哪些类」是渲染看得见的，而那正是这个 bug 的全部。
