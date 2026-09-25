# 02: 侧栏折叠成图标 rail，四个动作一个都不丢

**What to build:** 侧栏格子顶部那颗 PanelLeft 按钮一点，侧栏收成 ~3rem 的**图标窄栏**：项目与会话
列表藏起来，New task / Add project / Refresh / Settings 四个动作**全部保留**，只是只剩图标（`title`
和 `sr-only` 文案一个都不能少）。再点一下原样展开回 15rem。

**收窄是改 grid 的列定义**（`[15rem_minmax(0,1fr)]` ↔ `[3rem_minmax(0,1fr)]`），不是给侧栏加
`display:none`。demo 就是这么做的，而这么做在本仓还有一层好处：聊天列顺势吃满，不需要任何
"折叠后重新算宽度"的逻辑。

**列表藏起来，但侧栏不许卸载。** `Sidebar` 仍然在组件树里：列表的刷新、busy、以及"新任务属于某个
项目"这套派生逻辑都长在它身上，卸载了 rail 里的 New task 就失了根。所以 rail 态是**不渲染列表
区域**，不是不挂载组件。

**rail 下拒绝文案必须有地方落。** 今天 New task 的两句拒绝（`NO_PROJECT_REFUSAL`、run-in-flight）
是显示在下方的错误行上的，那些行的宽度在 rail 里没有了。决定：rail 态下把拒绝短句渲染在图标下方
（或 tooltip 位），**复用原句一个字不改**，绝不因为窄就换个说法或干脆不显示——一句拒绝被吞掉，人就
以为按钮坏了。

**Blocked by:** 01: 聊天列有了 3rem header 行，标题跟着当前会话走（列定义与 header 格子由 01 定下）。

**Status:** ready-for-agent

- [ ] 侧栏 header 格子加 PanelLeft toggle：`data-slot="sidebar-collapse-toggle"`，
      `aria-expanded`（展开 true / rail false）、`aria-controls` 指向侧栏 body 的 id、
      `title` 与 `sr-only` 分别写"收起侧栏/展开侧栏"；展开态靠右，rail 态居中。
- [ ] 折叠 = 列定义切到 `3rem`：列宽真的变成 ~3rem；聊天列吃满剩余；`Thread` 不重排失败
      （消息列表宽度变化后仍然只有它滚）。
- [ ] rail 态：项目/会话列表不渲染；四个动作按图标竖排保留 —— 顶部 New task / Add project /
      Refresh，底部 Settings；每个都保留 `title` + `sr-only` + 既有的 `disabled`（busy 时全灰）。
- [ ] rail 态下四个动作的语义与展开态**完全一致**：New task 仍是 mint-bind-switch 三步，Add project
      仍是一次点击一次系统弹窗，Refresh 仍是重读列表，Settings 仍是那份只读报告。
- [ ] rail 态下的拒绝与失败：New task 的两句、Add project 的失败句，都还在屏幕上（图标下方一行
      `role="alert"`），文案与展开态逐字相同。
- [ ] 键盘可达：toggle 能 Tab 到、能回车触发；rail 里的图标按钮焦点环可见。
- [ ] 高度纪律不变：侧栏仍 `h-full`，中间滚动区仍 `min-h-0 flex-1`（展开态回到原来的三区结构）。
- [ ] 真实浏览器取证：展开态、rail 态、rail 态下 New task 被拒、rail 态下 Settings 面板，截图进
      `evidence/t02-*`。
- [ ] `npm run typecheck` 与 `npm test`（`ui/` 下）不退化。
- [ ] 结论写进 spec「状态」再删票。
