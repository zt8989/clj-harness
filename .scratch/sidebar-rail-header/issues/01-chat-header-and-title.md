# 01: 聊天列有了 3rem header 行，标题跟着当前会话走

**What to build:** 页面顶部出现一条横贯的 header 行（和官网 demo 一样高 3rem）：侧栏那一格留给
02 的折叠开关，聊天这一格显示标题——**空会话是 `New chat`，进了会话是那个会话 id 的前缀**，切会话、
新建会话标题都跟着变。整页仍然只有消息列表在滚动，页面本身不滚动。

现在 `app.tsx` 是 `flex h-dvh` 里一个 `w-72` 侧栏 + 一个 `min-h-0 flex-1` 的聊天列，`Thread` 直接
吃满整列，聊天区连一个标题都没有。本票把骨架换成 demo 的 grid：

```
grid h-full grid-rows-[3rem_minmax(0,1fr)] grid-cols-[15rem_minmax(0,1fr)]
```

`Thread` 落到第二行的格子里（`min-h-0` 那条纪律一个字都不能松——少了它 flex/grid 子项不肯收缩，
整页会滚，`Thread` 的 `h-full` 也就读不到高度）。

**标题的来源只有一个**，并且要和侧栏行**共用同一个标签函数**（抽成纯函数，放 `lib/`）：会话 id 的
前缀。侧栏行今天靠 CSS `truncate` 截断整串 id，header 改成用同一个函数，两边从此不可能说不一样的话。

**空会话的判定沿用 `Thread` 已有的那两条规则**（`isNewChatView` / `isHistoryLoadingView`）：历史还在
装载时会话 id 是已知的，标题就显示 id，不许闪一下 `New chat` 再变回去；只有真的没有消息才显示
`New chat`。

**Blocked by:** None (can start immediately).

**Status:** ready-for-agent

- [ ] `app.tsx` 换成 grid：两行（`3rem` / `minmax(0,1fr)`）两列（侧栏 / `minmax(0,1fr)`）；侧栏与
      聊天列各自进格子，`min-h-0` 到位。
- [ ] 聊天列新增 header 行：`h-12`、`border-b`、`px-4 md:px-5`、`flex min-w-0 items-center gap-2`，
      加 `data-slot="chat-header"`；标题元素 `data-slot="chat-header-title"`，
      `min-w-0 truncate text-[13px] font-medium`（与 demo 同一串类）。
- [ ] 会话标签抽成一个纯函数（会话 id → 前缀），header 与侧栏行**都用它**，不再各截各的。
- [ ] 空会话显示 `New chat`；有会话显示 id 前缀；**切会话、新建会话、恢复会话**三种路径标题都立即
      跟着变（threadId 的owner 仍是 `app.tsx` 的 state，标题从它派生，不要另起一份状态）。
- [ ] 长 id 单行截断（`truncate`），不换行、不把 header 撑高、不把 header 挤走。
- [ ] 高度纪律的手验：消息很多时只有消息列表滚；composer 仍停在底部；header 不随列表滚动。
- [ ] 纯函数进 vitest 套件（空会话文案、前缀长度、含 `-` 的 uuid、极端短 id）；新增 suite 记得
      同步 bump `test/ui.test.ts` 的 `EXPECTED_CASES`。
- [ ] 真实浏览器取证：展开态标题、空会话 `New chat`、切会话后标题变化，截图进 `evidence/t01-*`。
      （vitest driver 没有 DOM，本仓既有纪律：画出来什么样在真浏览器里量。）
- [ ] `npm run typecheck` 与 `npm test`（`ui/` 下）不退化；改动未触及 Clojure 侧时不要求跑
      `clojure -M:test`，但碰了就跑。
- [ ] 结论写进 spec「状态」再删票。
