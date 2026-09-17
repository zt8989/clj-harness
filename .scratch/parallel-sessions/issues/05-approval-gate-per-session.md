# 05 — 审批门跟着会话走：一场悬置不许关上别场的输入框

**What to build:** 一场会话停下来等人决定时，**只有那一场**的 composer 关上；别的会话照常能发消息。
那一行侧边栏说「等你决定」，切过去看得见那张门。

**Blocked by:** 02、03

**Status:** ready-for-agent

## 现场：门是整页的

```ts
// ui/src/app.tsx:126     一个 App 级的布尔
const [gateOpen, setGateOpen] = useState(false);
// ui/src/app.tsx:186     它直接决定「能不能发」——对唯一的那个 composer 是对的
const runtime = useAgUiRuntime({ agent, isSendDisabled: gateOpen, .. });
// ui/src/app.tsx:206     谁喂它：那个唯一的审批门
<ApprovalBatchProvider onHoldChange={setGateOpen}>
```

`:59-73` 的注释解释了为什么要这样：门的动作会被 runtime 拒绝且**静默**（打进去的字被清空、
哪儿都没到），所以「关门」是唯一不吞掉别人输入的处理。

一份 runtime 的时候这是对的。02 之后每场会话有自己的 provider 与自己的门，而
`isSendDisabled` 还是 App 级的一个布尔 ⇒ **A 在等人决定，B 的输入框也关了**——
一个会让人以为「哪儿都发不出去」的假状态。

**另有一件今天就没有答案的事：悬置不是「在跑」。** `isRunning` 在悬置时是 `false`
（那一轮 run 已经以 interrupt 结束），但 `getPendingInterrupts()` 不是 `null`。
所以今天侧边栏那行**看不出**一场会话正在等人，而它其实是「没完」。

## 要改成什么

**一、门住进它那一份 host。** `gateOpen` 从 App 挪进 host（或按 `threadId` 存进票 03 的注册表），
`isSendDisabled` 用的是**那一场自己的**门。`ApprovalBatchProvider` 随之每场一份——
它的位置本来就是「provider 之内、thread 之上」，而它读的正是**它上面那个 provider** 的待决中断
（`app.tsx:59-65`），所以按 host 拆开是**按构造**正确的，不需要新机制。

**二、注册表多一格 `:parked?`**（票 03 留好了位置）。判据是那份 host 自己的
`getPendingInterrupts() !== null`（读法照 `approval-gate.tsx` 现在用的 `useAgUiInterrupts`；
它今天没有 `isRunning` 的读取，本票给它加上这一格）。侧边栏那一行在悬置时说
`Waiting on you`（英文，UI 文案一律英文）。

**三、悬置与「在跑」同等对待归档与删项目。** 票 04 那张表加一行：
**归档一场悬置中的会话、或删掉它所属的项目 → 继续拒绝**，理由是同一个（那一场没完，
而 resume 还要往那份日志里追加）。句子照 04 的写法。

## 验收

- [ ] `isSendDisabled` 是**每场会话自己**的门，不再是 App 级的一个布尔；
      `ApprovalBatchProvider` 每份 host 一份
- [ ] 注册表里有 `:parked?`，侧边栏那一行在悬置时显示 `Waiting on you`（在跑与悬置是**两种不同的说法**）
- [ ] 归档 / 删项目对悬置中的会话也拒绝（04 的句子与落点，判据换成 `:parked?`）
- [ ] **走查（证据进 `.scratch/parallel-sessions/evidence/`）**：
      ① A 的工具触发审批 → A 那行说 `Waiting on you`，A 的 composer 关着
      ② 切到 B：B 的 composer **能用**且能发出去（今天会被一起关上，这就是本票修的那一格）
      ③ 回到 A：那张门在，答它，A 继续跑完
      ④ 归档悬置中的 A → 拒；答完（A 跑完）再归档 → 放行
- [ ] A 悬置期间 B 发的那条 run 自己的日志完整（两场互不影响）
- [ ] `cd ui && npm run build` 过
- [ ] `cd ui && npm test` 全绿（`ui/test/suites/approval.ts` 是 agent 层的，断言不许为了好看而放宽）
- [ ] `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致

## Comments

**2026-09-17：代码已落地。** `gateOpen` 从 App 挪进 `SessionHost`（`isSendDisabled` 用的是
**那一场自己的**门），`ApprovalBatchProvider` 每份 host 一个；注册表里有 `:parked?`
（`useAgUiInterrupts().some(isParkedInterrupt)`），那一行在悬置时说 `Waiting on you`；
归档 / 删项目对悬置中的会话同样拒绝（`blocked` = running || parked）。**走查没跑**，
见证据 README（尤其「A 悬置时 B 的 composer 能用」这一格）。
