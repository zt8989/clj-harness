# 02 — 客户端：轨迹那一栏改从下行读

**What to build:** `trajectory-view.tsx` 不再发 `GET /api/threads/<stem>/trajectory`、也不再有
一条自己的长响应：挂载 = 订阅下行 socket 的第五族，`payload` 由帧拼出来。**头每帧都到**，
所以「run 落定要重开一次流」那一格 `isRunning` 从 effect 依赖里去掉。

**Blocked by:** 01

**Status:** ready-for-agent

## 在哪改

- `ui/src/lib/mux.ts`
  - `TrajectoryFrame`（`:threadId` / `:type "trajectory"` / `:snapshot?` / `:turns` /
    `:incomplete` / `:behind?` / `:error?`）、`TRAJECTORY_FRAME_TYPE`，`familyOf` 多一支
    `"trajectory"`（**必须显式命名**：落进 `else` 就会被当成 AG-UI 帧交给 `@agent/client`
    的 schema 校验，把这一轮打死——这是 `fact` 那一族留下的教训）；
  - `trajectorySubscriptions: Map<string, Set<…>>`（同一场会话可能有两栏在看：两个 host 各一栏）；
  - `subscribeTrajectory(threadId, onFrame)`：进表 → `ensure()` → `declareThread(threadId)`（**每次**
    都声明，这样重新订阅能拿回开场快照）；退订时若 `stillWanted` 还有别的声明，
    **重新声明这一条**（`trajectory: false`）而不是整条 `unsubscribe`——折子与推送都要停，
    但它不是这场会话最后一个声明者；
  - `wantedThreads` / `stillWanted` / `declaredSet` / `deliver` 四处各加一格，
    `declaredSet` 的每条多带 `trajectory: boolean`。
- `ui/src/lib/trajectory.ts`：`trajectoryFor`（fetch + `onProgress`）换成订阅式的入口——
  按 `:index` 合并（当前那一轮到了就地替换，这是**窗口帧同一条规矩**），`:snapshot` 帧**整份替换**，
  `:error` 帧读成「没有记录」那一态。返回 `unsubscribe`。
- `ui/src/components/trajectory-view.tsx`：effect 用新入口；`isRunning` 从依赖里去掉；
  `onDownlinkOpen` 那一处去掉（重新声明就是修补）；`useAuiState` 若因此不再需要就删掉 import
  （`tsc` 会替我们盯着）。
- `ui/test/suites/mux.ts`：加一例——只订阅轨迹的一场会话，`declaredSet()` 里那一条带
  `trajectory: true`；`familyOf("trajectory") === "trajectory"`。

## 验收

- [ ] 打开轨迹那一栏：**没有** `/trajectory` 的网络请求，帧从 socket 来（浏览器网络面板）
- [ ] 一轮 run 里新的一轮最终化：视图跟着长，**不重开任何流**
- [ ] run 落定：`:incomplete` 翻面（帧里带的），不用重开
- [ ] 关掉轨迹那一栏、窗口还开着：窗口那一半不断，服务端不再推轨迹
- [ ] `cd ui && npm run typecheck && npm test && npm run build` 全绿
- [ ] `node scripts/dev.mjs --scripted` 起服务，**自己开浏览器点开轨迹那一栏走一趟**
      （机器门挡不住「渲染看不到布局」那一格）
