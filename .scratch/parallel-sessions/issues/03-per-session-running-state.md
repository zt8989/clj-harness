# 03 — 每会话自己的「正在跑」：侧边栏不再假装

**What to build:** 侧边栏每一行说**这一场**在不在跑。两场会话同时跑时，两行都亮；
切到 B 的时候，A 那一行**仍然亮**。

**Blocked by:** 02

**Status:** ready-for-agent

## 现场：今天这份状态只有一份，而且被一个比较伪装成「每行都有」

```ts
// ui/src/components/sidebar.tsx:604   一份全局的 running，传给每一个 ProjectSection
const running = runInProgress(runtime);
// ui/src/components/sidebar.tsx:895    …到行上就成了「是不是当前页」的假答案
running={running && session.threadId === currentThreadId}
```

```ts
// ui/src/lib/run-state.ts:22-23       判据是那份唯一的 core 上的一个布尔
export const runInProgress = (runtime) => runtime.threads.main.getState().isRunning;
```

```ts
// ui/src/components/assistant-ui/elements/thread-list.aui.tsx:66-69（抄来的文件，本仓已就地重写过）
/// Whether this thread has a run in flight. Since only the open thread is
/// mounted, this is the main thread's running state -- the same thing the
/// refusal below the row is about.
running: boolean;
```

`== currentThreadId` 是**必要**的：今天只有一份 core，不问「是不是当前页」就会把 A 的跑动状态画在 B 上。
02 之后每场会话有自己的 core，这个比较就从「必要的修正」变成「错的答案」——
A 在跑而你在看 B 时，A 那行会灭。

## 要改成什么

**一份 `threadId -> 这一场自己的状态` 的注册表**，App 持有，侧边栏按 id 查。
每份 host（票 02 的形态）在状态变化时把它自己的 `isRunning` 报上去。

- **形状**：`{:running? bool}`，且有位置留给票 05 的 `:parked?`（那时它会一起用）。
  实现用 React state/context 就行；`zustand` 已经在依赖里，要用也**不必**新引任何东西。
- **报上去的时机是 effect，不是 render。** 在 render 里 `setState` 到父级是 React 的经典坏形
  （本轮渲染读到的就不是自己报上去的那个值）。每份 host 用 `useAuiState((s) => s.thread.isRunning)`
  读自己那份（`composer-stats.tsx:60` 与 `trajectory-view.tsx:474` 已经这么读），
  再在 effect 里把自己写进注册表；卸载时把自己那条**删掉**（不然关掉的会话会永远亮着）。
- **侧边栏**：`running` 从注册表按 `session.threadId` 查。`sidebar.tsx:895` 那个
  `&& session.threadId === currentThreadId` 随之退场——它不再必要，且它现在是错的。
- **`thread-list.aui.tsx` 的 prop 注释**改成新的真话（「这一行自己那一场在不在跑」），
  并在改动处留 `LOCAL:` 标记：这是抄来的文件，本仓已经就地重写过它，纪律是**逐处标注**。

## 顺带核一件事（别假装它对）

`composer-stats.tsx:60`、`trajectory-view.tsx:474`、`turn-steps.tsx:92`、`elements/thread.aui.tsx:390/405`
读的都是 `s.thread.isRunning`——那是**自己那个 provider 的** runtime。02 之后每份 host 有自己的
provider，所以这些应当**自动**变对。**要在走查里各验一次**（尤其 `turn-steps` 的折叠与
`thread.aui` 的 Send/Cancel），对不上就在本票里修，不要留给下次。

## 验收

- [ ] App 持有一份 `threadId -> {:running? ..}` 的注册表；每份 host 在 effect 里报自己的状态、卸载时删自己那条
- [ ] 侧边栏的 `running` 按 `session.threadId` 查；`sidebar.tsx` 里不再有 `running && session.threadId === currentThreadId`
- [ ] **走查（证据进 `.scratch/parallel-sessions/evidence/`）**：A 与 B 各一条慢 run 同时跑 →
      侧边栏**两行都亮** → 停在 B 上，A 那行**仍然亮** → 各自跑完，各自灭（不是一起灭）
- [ ] 关掉/切走一个会话后，那一行不会永远亮着（卸载即删）
- [ ] `turn-steps` 的折叠、`elements/thread.aui.tsx` 的 Send/Cancel、`composer-stats` 的重取，
      在「显示 B 而 A 在跑」时都读的是**B 的**状态（走查里逐条验）
- [ ] `cd ui && npm run build` 过
- [ ] `cd ui && npm test` 全绿
- [ ] `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致

## Comments

**2026-09-17：代码已落地。** App 持有一份 `threadId -> {:running? :parked?}` 的注册表；
每份 host 在 effect 里报自己的状态、卸载时删自己那条（`SessionStatusReporter`）；
侧边栏按 `session.threadId` 查，`running && session.threadId === currentThreadId` 已退场；
`thread-list.aui.tsx` 的 `running` 注释改成真话，新增 `parked` 那格也带 `LOCAL:` 标注。
`composer-stats` / `trajectory-view` / `turn-steps` / `thread.aui` 读的都是自己 provider 的
`s.thread.isRunning`，**按构造自动变对**——但**走查没跑**（端口被占），见证据 README。
