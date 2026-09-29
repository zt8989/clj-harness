# 01 — composer 读布局那一句判据，不再自己数消息

**What to build:** composer 的「这是新建，还是已经在会话里」改成读布局那一句（`isNewChatView` / `isEmpty`），
判据实现一次、两处读同一个函数。于是「历史还在读」这一瞬落进**会话中**那一态：
项目/分支条不画，composer 底下那 24px 也自己收掉。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 症状（主人 2026-09-29 报的）

点开一场还没在本页挂载过的旧会话（`POST /api/threads/<id>/rebuild` 在飞）时，composer 同时画出
**新建**与**会话中**两套家具：项目/分支条（`lisp-harness` / `main`）与任务横条（`8 已完成`）同框，
composer 底下多一条 24px 空隙。

## 根因（一句话）

`ComposerFrame` 的 `started` 数的是 `s.thread.messages.length > 0`，而布局数的是 `isNewChatView`。
加载中前者是 false、后者也是 false（"不是新会话"）——于是 chrome 当它是新建、布局当它是在会话里，
底部那条 `pb-4 md:pb-6` 也照着"新建"留着。

数表、四个判据各是谁、哪条 CSS 收底的：见 `../spec.md`。

## 怎么做

- `ui/src/lib/thread-view.ts`（新）：把 `isNewChatView` 从
  `ui/src/components/assistant-ui/elements/thread.aui.tsx` 搬过来（纯函数，吃 `AssistantState`），
  连它上面那段解释（为什么"启动占位"要当新会话）一起搬过去。
- `thread.aui.tsx`：删掉本地那个 `const isNewChatView`，改成从新模块 import——抄来的文件只多一行 import，
  LOCAL 标记照旧。`isHistoryLoadingView` 留在原处不动（只被这一处用）。
- `ui/src/components/composer-chrome.tsx`：`ComposerFrame` 里
  `const started = useAuiState((s) => s.thread.messages.length > 0);` 改成读同一个函数
  （`const started = useAuiState((s) => !isNewChatView(s));`）。`data-started`（806 行）与
  `{!started && <ComposerContextBar …/>}`（817 行）两处都不动。
- `ui/src/styles.css`：**一行不改**。`:has([data-started])` 靠的就是这枚属性，判据对了空隙自己就没了。

## 验收

- [ ] 加载中（`POST .../rebuild` 在飞）：`[data-slot="composer-context"]` **不在 DOM 里**；
      `.aui-thread-viewport-footer` 的计算 `padding-bottom` 是 `0px`；composer frame 底边 = 窗口底边；骨架屏仍在。
- [ ] 同一场加载完：`data-started` 仍在，任务横条与统计条照旧。
- [ ] 新建（居中）一场：`[data-slot="composer-context"]` 在，composer 仍在页面中间，`data-started` 缺席。
- [ ] 刷新落在会话中（window 门）：与改动前一样（贴底、`padding-bottom: 0`）。
- [ ] 有任务列表的会话在加载中：横条还在，且**不**与项目/分支条同框。
- [ ] `cd ui && npm run typecheck` 与 `npm run build` 绿；composer / todos 相关套件绿。
- [ ] 走查：`node scripts/dev.mjs --scripted`，两场会话 + 刷新 + 拖住 `rebuild`，把上面几条逐条量一遍。

## 不做

- 不改骨架屏、不改 `isHistoryLoadingView`、不动 dock / 居中那半句。
- 不新加 CSS 钩子；不为「加载中」加第三种视觉（文案、禁用态、spinner 都不加）。
- 不给任务横条加开关，不动它读的那一行。
- 不动项目/分支条的读写与长相。
