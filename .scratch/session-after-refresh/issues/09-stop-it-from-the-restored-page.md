# 09 — 浏览器：刷新之后也能停

**What to build:** 刷新落进一条还在跑的会话，那里有一个能按的「停」，按了**服务端那条 run 真的停**
（票 07/08 的那条路），不是只把这一页的 fetch 掐断。停完这一页回到能正常发。

**Blocked by:** 04（那一场没完时 composer 的状态）、07、08（服务端真的停得下来）

**Status:** ready-for-agent

## 现场：今天的 Stop 是一条假动作

```tsx
// ui/src/components/assistant-ui/elements/thread.aui.tsx:406-416   composer 里的 Stop
<ComposerPrimitive.Cancel asChild>
  <ComposerActionIconButton ... aria-label="Stop generating" />
</ComposerPrimitive.Cancel>
```

它走 runtime → `HttpAgent.abortRun()` → `abortController.abort()`，
断的是**浏览器那条 fetch**；`@ag-ui/client` 把 `AbortError` 合成一个 `RUN_ERROR`
（`code: "abort"`），界面就画成一次失败。服务端**收到的是零**（本仓 2026-09-17 实测）。
而刷新之后连那条 fetch 都没有了，所以票 03 落地之后，一条**刷新回来的**还在跑的会话
在界面上完全没有能停的地方。

## 要改成什么

**一、那个「停」调票 07 的端点**，寻址用**当前显示的那一场**（票 03 那个 state 里的 id），
不是 `agent.threadId` 顺手拿的那个——两者在票 03/`parallel-sessions 02` 之后不一定是同一场。

**二、两种「没完」都要有路**：在跑的（票 07 的取消）与悬置的（票 06 的决定）。
悬置那一种今天的入口已经在（`approval-gate.tsx` 的卡片），本票只管在跑的。

**三、不许顺手踢出一条新 run。** 上游的 onCancel 路径里有一条会**自己起一条替代 run**
（`ui/node_modules/@assistant-ui/react-ag-ui/dist/runtime/AgUiThreadRuntimeCore.js:206-209`：
`onCancel started a replacement run; dropping the superseding send`）。
本票的停是「服务端那条停下」，不是「这条停下、再开一条」——验收里明写这一条，
因为两者的界面表现只差一点点，而记录差很多。

**四、停完之后这一页要回到正常。** composer 从「在跑」回到能发，那一轮按中止画
（`ui/src/components/message-parts.tsx:189` 已经有 `cancelled: {label: "Cancelled"}` 这个词，
工具行那条路已经会画，别新造一套），**并且**刷新回来读得回来的是完整的一份记录。

## 验收

- [ ] **走查**（证据进 `evidence/`）：起一条慢 run → 刷新 → 回到那一场 →
      有能按的「停」→ 按它 → **服务端那条 run 真的停**（进程日志里那一场的 run 到了 terminal，
      工具进程树没了，`GET /api/projects` 那行回到 false）
- [ ] 同一次走查：停完之后 composer 立刻能发，发出去的是**同一份对话**的下一轮
- [ ] 同一次走查：停之后刷新，回来读得到那份记录，最后那一轮是中止的样子（不是完整、也不是报错弹窗）
- [ ] 同一次走查：按停**没有**顺手起一条新 run（网络面板里那次取消之后没有第三条 `POST /`；
      服务端日志里那一场没有第二个 `run/start`）
- [ ] 另一场会话在跑的时候，本票的停**只停当前这一场**（走查里同时跑两场，各停一次）
- [ ] `cd ui && npm run build` 过；`cd ui && npm test` 全绿
- [ ] 后端本票未动：`timeout 900 clojure -M:test -m harness.test-runner` 失败**用例名**与基线一致
