# 02 — 一会话一 runtime：切换不再经过 runtime，run 不再被孤儿

**What to build:** 每个会话一份自己的 runtime（一份 host 组件），「现在看哪一场」变成 App 的 state。
同时给 A 与 B 各发一条消息，两条 run 同时在飞；A 的日志里只有 A 的话；切回 A 看见的是**还在长的
那一份**，不是从 jsonl 重建出来的空壳。

**Blocked by:**
- 01（形状确认）
- `.scratch/immutable-data/issues/02-turn-plan-per-turn.md`（**跨特征**：两个会话同时在跑正是
  `kernel.tools/turn-plan` 那个全局单槽会互相清计划的场景，那一票不落地，本特征一放开就等于
  把锚点批的丢更新一起放出去）

**Status:** ready-for-agent

## 今天的形状（要拆的就是它）

```ts
// ui/src/app.tsx:119-124   一个 agent，一份 runtime（:184-199 的 useAgUiRuntime 只调一次）
const agent = useMemo(() => { const created = new HttpAgent({ url: AGENT_URL });
                              created.threadId = threadId; return created; }, []);
```

```ts
// ui/src/app.tsx:152-162   切换 = 改这一个 agent 的 threadId + 拒掉 run 中的切换
const onSwitchToThread = useCallback(async (id) => {
  if (runtimeRef.current && runInProgress(runtimeRef.current)) throw new Error(RUN_IN_PROGRESS_REFUSAL);
  adoptThread(id);
  const rebuilt = await rebuildThread(id);
  return { messages: toThreadMessages(rebuilt.messages) };
}, [..]);
```

而**上游**会在问宿主之前把那一份 core 清空（`useAgUiRuntime.js:90-99`：
`core.applyExternalMessages([])` → `await onSwitchToThread(threadId)` → 回来再灌）。
run 中途走这条路 ⇒ 正在流的帧落进一个已改名的库 ⇒ 服务端写 A 的日志、界面说这是 B。

## 要改成什么

**一、App 拆出 `SessionHost`。** 每场会话一份，各自 `useMemo` 造自己的 `HttpAgent`、各自调
`useAgUiRuntime`，`threadList: { threadId: <本场 id> }`——**只传 id，不传两个切换回调**
（那两个回调的效果是在当前 core 上清库/灌库，正是本票要拆掉的东西）。

**二、「显示哪一场」是 App 的 state。** 今天 `threadId`（`app.tsx:115`）已经承担了一部分这个角色；
本票让它成为**唯一**的「谁在屏幕上」，并给它一个真正的动作（显示某一场 / 新开一场）。
`adoptThread` 那种「把 id 写到共享 agent 上」的动词随 agent 一起退场。

**三、`runtime.threads.switchToThread(...)` 的调用方全部改道。**
`ui/src/components/sidebar.tsx:324`（`openThread`）、`:367`/`:372`（归档后换一场）、
`:426`/`:429`（删项目后换一场或新开）——每一处都改成「让 App 显示这一场」。
01 的答案里已经列过这五处的处置，照它做。

**四、第一次打开一场会话才 `rebuildThread`。**
判据是**那份 host 在不在**，不是「日志存不存在」：host 已经活着，conversation 就在它的 core 里
（可能还在流），拿 rebuild 的结果盖上去就是又一次孤儿。第一次打开（还没有 host）才重建。

**五、`Sidebar` 不再接一份 runtime。**
今天它是 `<Sidebar runtime={runtime} currentThreadId={threadId} />`（`app.tsx:214`），
**坐在 provider 之内**——因为只有一份 runtime，而调用切换要靠它。
本票之后它管的是**全部**会话，所以它要挪到 host 之外，`runtime` 这个 prop 退场；
它的数据源是「显示哪一场」的 state、`GET /api/projects` 那份清单、以及票 03 的注册表。
（它今天那五处 `runtime.threads.switchToThread(..)` 正是靠这个 prop，见第三条。）

**六、删掉那两句拒绝与它们在这一层的守卫。**
`RUN_IN_PROGRESS_REFUSAL` 与 `RUN_IN_PROGRESS_NEW_THREAD_REFUSAL` 在本票里**不再拦切换与新建**
（剩下的拒绝在票 04：归档/删一场正在跑的会话）。`lib/run-state.ts` 这个模块的处置在票 06 收口，
本票先把本层用不到的那部分摘掉。

## 验收

- [ ] `app.tsx` 里 `useAgUiRuntime` 的调用点从「一处」变成「每场会话一处」（组件形态）；
      `adoptThread` 不再存在，`threadList` 适配器里没有切换回调
- [ ] `sidebar.tsx` 那五处 `threads.switchToThread` 全部改道；**第一场**会话第一次打开仍然走
      `rebuildThread`，已经活着的那一场**不走**
- [ ] `Sidebar` 挪到 provider 之外，`runtime` 这个 prop 退场（它管的是全部会话）
- [ ] **走查（证据进 `.scratch/parallel-sessions/evidence/`）**：A 发一条慢的 → 切到 B 发一条 →
      两条 run **同时在飞**（两边都在长）→ 切回 A：那一轮完整（工具行、思考行都在），且不是空壳
- [ ] **日志对得上**：`threads/<A 的 stem>.jsonl` 里只有 A 的话，`B 的` 里只有 B 的；两边都到
      terminal（`run/terminal`），没有一次串号
- [ ] A 跑完之后**不再**需要刷新或重建才能看到完整那一轮：切走再切回来，内容一模一样
- [ ] `cd ui && npm run build` 过（`tsc --noEmit` 是这次 React 重构的机器门）
- [ ] `cd ui && npm test` 全绿（agent 层套件不许因此改动断言；本票不动服务端）
- [ ] `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致

## Comments

**2026-09-17：代码已落地，机器门全过，真机走查没跑。** 形状是 `app.tsx` 的 `SessionHost`
（一场会话一份 runtime、一份 agent、一份 core），`shown` 是 App 的 state，`threadList` 适配器
**只传 threadId**；重建改走运行时自己的 `history` 适配器（每个 core 只 `load()` 一次，
空实现 `append`/`update`，因为日志归服务端）；侧边栏挪出 provider、`runtime` prop 退场，
那五处 `switchToThread` 分别改成 `onShow` / `onShowFresh`。

证据、跑过的命令、以及**哪一格还空着**：`.scratch/parallel-sessions/evidence/README.md`。

**跨特征的前置也落了地**：`.scratch/immutable-data/issues/02-turn-plan-per-turn.md`
（`turn-plan` 按 thread-id 分家 + 登记早于 spawn），它的四条新用例与既有批用例一起绿。
