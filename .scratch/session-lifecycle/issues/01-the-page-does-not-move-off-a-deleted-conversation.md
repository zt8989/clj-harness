# 01 — 删掉正在读的那条会话，页面要挪窝

**Status:** ready-for-agent
**Blocked by:** None

**症状（2026-09-30 走查时实测）**：设置 →「会话」页把**屏幕上正读着的那条会话**删掉之后，
页面仍然停在一条**已经不存在的会话**上：主栏还画着它原来的对话、输入框还能发（发出去只会吃
run edge 的「未知会话」拒绝）。原因是 `localStorage` 里记的还是它的 id，而 `GET /api/projects`
已经不再列它。

**侧栏对同一件事已经有规矩**：归档掉正在读的那条时页面会挪（`ui/src/components/sidebar.tsx`
的 `movesThePage`）。缺的是这一半：`SettingsPanel` 只拿到 `open / onOpenChange / threadId`，
**没有任何让页面挪窝的回调**，而推送也不会替它挪。

**要做的**：面板把**删成功的 id** 交回 `sidebar.tsx`，由侧栏按 archive 那条既有的规矩挪一步
（当前会话在其中 → 落到一条新的/空闲的会话上）。形状写在
`.scratch/session-lifecycle/spec.md` 的「走查发现、本票没做」那一段里。

**要守的既有规矩**：`docs/rules/panel-data.md`（推送之外的存量补一次）、
`ui/src/lib/session-status.ts`（运行中的会话拒绝删除的那条规则不要在这里重写一遍）。

**验收**：

- `ui` 用例钉住"面板把成功删除的 id 交了出来"（SSR 能断言的那部分）；
- `node scripts/dev.mjs --scripted` 起服务、开浏览器走一趟：删掉正在读的那条，
  页面落在别处、输入框不往死会话发。
