# 03 — 刷新回到刚才那一场

**What to build:** 刷新之后你还在刚才那一场：同一份对话、同一个项目高亮、侧边栏那一行仍然是选中态。
如果那一场的 run 还在跑，最后那一轮显示成**没完**（已产出的内容在，而且在继续长），
而不是悄悄当成一轮完整的话。

**Blocked by:** 02（「没完」这件事要有读法才画得出来）

**Status:** ready-for-agent

## 现场：刷新就是一枚新的随机 id

```ts
// ui/src/app.tsx:115   每次挂载都新铸一枚，没有任何东西把它记下来
const [threadId, setThreadId] = useState<string>(() => crypto.randomUUID());
```

```ts
// ui/src/app.tsx:144-150   唯一一条改变它的路，由侧边栏的点击驱动
const adoptThread = useCallback((id: string) => { agent.threadId = id; setThreadId(id); }, [agent]);
```

`ui/src` 里没有 `localStorage`、没有 `sessionStorage`、没有 `location.hash`/`history.replace`（grep 零输出）——
没有路由，id 无处可去。挂载时也**没有**任何 rebuild：今天的 id 是新的，没有可重建的东西。
所以「刷新后进入新会话」是今天唯一的路径，不是某个分支出的错。

## 要改成什么

**一、把「显示哪一场」记下来，用 localStorage。** 一个键，存 session id。
刷新、关掉重开、新标签页都回到那一场（spec 的决定三）。存下来的 id 指向一场**已经不存在的会话**
（被删、被归档、日志被手工挪走）时：**落回一个新会话，不报错、不弹东西**——
那不是一个需要人决定的状况。加项目 / 新建任务那两条路照今天走（它们本来就自己铸 id）。

**二、挂载时恢复一次。** 有存下来的 id 且它在会话清单里 ⇒ `adoptThread` + 走读法把它读回来；
读回来是 `partial?` 的（票 02）⇒ 那一轮画成没完。清单本身来自客户端已经在读的那份 payload
（`GET /api/projects` 里的会话行，`http.clj:1035-1056`），所以「id 属于哪个项目」也在同一份里，
**不要**为此新加一个请求。

**三、没完的那一场要跟着长。** `partial?` 为真时按间隔再读一次（spec 的决定一：读记录 + 轮询），
读到 terminal 就停。轮询的判据用票 02 那个「读法不写文件」的性质，别去开第二条通路。

**四、形状必须按 `.scratch/parallel-sessions/issues/02` 要的写。**
「现在看哪一场」归 App 的 state，**不走 runtime**；那张票会把
`onSwitchToThread`/`onSwitchToNewThread` 退场、把 rebuild 留给「第一次打开」。
本票落的就是那个 state 的持久化与挂载恢复，写成别的形状会让那张票先来拆本票。

## 现成的词汇表，别新发明

```ts
// ui/src/lib/turns.ts:48 附近   「没完」今天已经有词
/// The status of its LAST message is what says so: `running` is still arriving,
/// `requires-action` is parked on a human, and both `complete` and `incomplete` are over
export function turnIsSettled(...)
```

最后那一轮按 `incomplete` 走既有渲染，**不要**给「半条」新加一套样式。

## 验收

- [ ] **走查**（证据进 `.scratch/session-after-refresh/evidence/`）：跑一轮正常的，刷新 → 同一条会话、
      同一份对话、侧边栏那一行仍选中、项目仍是那个
- [ ] **走查**：刷新时那条 run **还在跑** → 回到同一场 → 最后那一轮显示为**没完**（不是完整），
      已产出的文字/工具行在，并且它在继续长（轮询到位）
- [ ] **走查**：关掉标签页再打开 → 仍然回到那一场（localStorage，不是 sessionStorage）
- [ ] **走查**：存下来的 id 指向一场已经不存在的会话 → 落到一个新会话，控制台无错、界面无报错提示
- [ ] 用 `clojure -M:test` 起一条慢 run 期间做上面那两条刷新走查（慢 run 的造法照走查文档：
      脚本厂商 + 一个会等的工具）
- [ ] `cd ui && npm run build` 过
- [ ] `cd ui && npm test` 全绿
- [ ] 后端本票未动：`timeout 900 clojure -M:test -m harness.test-runner` 失败**用例名**与基线一致
      （这一步是「没碰后端」的证据，不是形式）
