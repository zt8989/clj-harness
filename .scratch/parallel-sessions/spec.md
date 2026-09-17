# spec: 多会话并行（一个会话一份 runtime）

2026-09-16 立。**要的是：两个会话可以同时在跑，而你看得见其中一个。**

今天的现场是一句拒绝：

> A run is in progress; a new session waits until it settles.

## 先把现场钉死（对着代码核过）

**服务端早就支持了，一个字都不用改。** http-kit 一个请求一条线程；`handle-run`
（`src/harness/edge/http.clj:651-697`）没有任何门，每次 run 有自己的 converter、emitter 与 state atom
（`:659`），run 体是自己的 go 块（`:454`，注释 `:660-662` 明说它不阻塞 `:on-open`）。
`http.clj` 里唯一的锁是 jsonl append 那把（`:94-101`），它不管 run 的准入。
**两个不同 thread-id 的 `POST /` 今天就是真并发在跑的。**

**拒绝是浏览器自己发的。** 字符串住在 `ui/src/lib/run-state.ts:16-20`，判据是
`runtime.threads.main.getState().isRunning`——**一个 app、一份 runtime、一条 main thread、一个消息库**。
而那一条 main thread 的「正在跑」是上游 `AgUiThreadRuntimeCore` 上的一个布尔
（`ui/node_modules/@assistant-ui/react-ag-ui/dist/useAgUiRuntime.js:47`）。

**为什么非拒绝不可**（`run-state.ts:10-13` 的 docstring 说的「孤儿」是真事）：
上游 `useAgUiRuntime` 的 `onSwitchToThread` 在**问宿主之前**就把那个库清空
（`useAgUiRuntime.js:90-99`：先 `core.applyExternalMessages([])`，再 `await onSwitchToThread(threadId)`，
回来才灌新消息）。run 中途走这条路，正在流的帧就落进了一个已经改名的库；而
`agent.threadId` 又被 `adoptThread`（`ui/src/app.tsx:144-150`）重新指过——于是**服务端往 A 的日志里写，
界面说这是 B**。守卫拦的正是这件事。

## 形状：一个会话一份 runtime，切换不再经过 runtime

**决定一：每个活着的会话一个 host 组件，各自调一次 `useAgUiRuntime`。**
为什么不是「一个模块级注册表，里面 N 份 runtime 对象」：`useAgUiRuntime` 每次调用自己造一个 core
并存在 ref 里（`useAgUiRuntime.js:22`），而 `AgUiThreadRuntimeCore` 与运行时实现类**没有从包里导出**
（`node_modules/@assistant-ui/react-ag-ui/dist/index.d.ts` 只导出 hook、类型与两个转换函数），
自己造 runtime 就得深引 `dist/runtime/AgUiThreadRuntimeCore.js`——一条随时会被上游挪走的路径。
按组件挂载是唯一只用公开 API 的做法；而且 core 归 hook 的 ref 所有**而不是归 DOM 子树**，
所以「这个会话没在显示」不等于「它的 run 死了」——这正是本特征要的性质。

**决定二：切换会话不再走 runtime。**
`threadList` 适配器的 `onSwitchToThread` / `onSwitchToNewThread` 要**退场**：它们的效果是
在**当前显示的那份 core** 上 `applyExternalMessages`，而那正是孤儿本身。
「现在看哪一场」变成 App 的 state（今天 `threadId` 已经是 App 的 state，`:115`，
`docs/architecture/client.md:37` 记着这件事）；runtime 那条路留着只会把它偷偷改回去。

**决定三：第一次打开一个会话仍然 rebuild，已经活着的会话不再 rebuild。**
`rebuildThread`（`lib/threads.ts`）是「从 jsonl 重建一份对话」，它的用途是**你还没有这份 conversation 时**
把它造出来。一个 host 已经活着的会话，conversation 就在那份 core 里（可能还在流），
拿 rebuild 的结果盖上去就是又一次孤儿。判据是**那份 host 在不在**，不是「有没有日志」。

**不改的：** 服务端；`GET /api/projects` 那份侧边栏清单（它本来就是另一份数据，
`client.md:62-64`）；AG-UI 协议；`<Thread/>` 与工具行/思考行的渲染。

## 仍然要拒绝的（不是全都放开）

- **归档一条正在跑的会话、或删掉它所属的项目**：run 还在写文件、jsonl 还在追加，收起来的日志会跟着被搬走。
  这两条**继续拒绝**，但判据从「当前页在跑」换成**那条会话自己在跑**，句子落在那**一行**上。
- **同一个会话再来一轮**：由那一场的 composer 自己说话（跑着就是 Cancel 而不是 Send，
  `elements/thread.aui.tsx:390/405` 读 `s.thread.isRunning`）。一个会话两份 runtime 不是本特征要的东西。
- **跨会话的一切不再拒绝。** 那两句拒绝（切换、新建）随之退场。

## 前置：一条跨特征的门

**`.scratch/immutable-data/issues/02-turn-plan-per-turn.md` 必须先落地。**
那一票修的是 `kernel.tools/turn-plan` 是**一个全局单槽**（`src/harness/kernel/tools.clj:546`）：
本特征一放开，两个会话同时跑就是常态，而 A 的 `register-turn!` 会清掉 B 的批计划
⇒ 锚点批（一条消息里的多次编辑合成一次写入）静默降级成并发独立编辑 ⇒ **丢更新**。
把那句「不要为了看起来有并行边而把票并起来」反过来读也一样：**一个特征是并行的时候，
它的前置必须是真的**。

## 怎么验证这件事（没有 React 测试的仓库，就别假装有）

`ui/test/` 是 **agent 层**的套件：真 `HttpAgent` + 真后端 + 脚本厂商，**不渲染 React**
（`ui/test/e2e.ts`、`ui/test/ui.test.ts`；devDependencies 里没有 testing-library，也没有 jsdom）。
所以：

| 层次 | 手段 |
|---|---|
| 类型与编译 | `cd ui && npm run build`（`tsc --noEmit` + `vite build`）——React 重构的机器门 |
| 协议与后端 | `cd ui && npm test`（agent 层：两个 thread-id 各一条真 run 同时跑） |
| 界面行为 | **真浏览器走查**，证据写成 `.scratch/parallel-sessions/evidence/*.md`（照 `composer-status` 那一份的形式：临时 `CLJ_HARNESS_HOME`、`harness.e2e-server`、vite dev） |
| 后端 | `timeout 900 clojure -M:test -m harness.test-runner`，失败**用例名**与基线一致 |

**不假装界面行为有自动化测试。** 谁要是想给 React 补一套 testing-library，那是另一个特征，
不是本特征偷偷加的依赖。

## 票

六张。01 是原型，它的答案**可以改写 02-06**（这在本仓是允许的：票面是当时的草稿，spec 记决策，
被推翻的写成复议段）。

- **01** 原型与形状确认：一个挂着的、没在显示的 host，它的 run 还活着吗？（无阻塞）
- **02** 一会话一 runtime，切换不再经过 runtime（阻塞：01 + immutable-data 02）
- **03** 每会话自己的「正在跑」（阻塞：02）
- **04** 剩下仍然要拒绝的三件事（阻塞：02）
- **05** 审批门跟着会话走（阻塞：02）
- **06** 文档与两套全量验证（阻塞：02、03、04、05）
