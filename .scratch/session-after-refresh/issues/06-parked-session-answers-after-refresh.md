# 06 — parked 的那一场，刷新回来还能答

**What to build:** 一场停在审批上等人决定的会话，刷新回来**卡片还在**，做决定之后那次调用真的拿到结果。
这是「没完」的第二种，而它今天比「在跑」更糟：刷新之后那次悬置的调用**永远没有人能答**。

**Blocked by:** 03（你得先能回到那一场）

**Status:** ready-for-agent

## 现场：三处加起来，卡片消失、而且门还开着

**一、服务端把悬置丢了。** park 以 `RUN_FINISHED` 带 `outcome.interrupts` 结束：

```clojure
;; src/harness/edge/ag_ui.clj:144-150
;; unchanged; the client turns outcome.interrupts into pendingInterrupts.
...(cond-> {:type "RUN_FINISHED" ...}
      (:interrupts ev) (assoc :outcome {:type "interrupt"
                                        :interrupts (mapv interrupt-frame (:interrupts ev))}))
```

而 rebuild 只折三类帧——文本、思考、工具：

```clojure
;; src/harness/kernel/frames.clj:32-62   apply-frames 的 cond 里没有 RUN_FINISHED
(= t "TEXT_MESSAGE_START") ...
(= t "TOOL_CALL_START") ...
```

`records->messages`（`replay.clj:182-194`）= 种子消息 + `apply-frames`，所以 `outcome` 到此为止。

**二、客户端需要的是一个具体的形状。** 上游的 `getPendingInterrupts()` 要求**最后一条 assistant**
是 `requires-action`/`interrupt`，而且它的 metadata 里存着 interrupts：

```js
// ui/node_modules/@assistant-ui/react-ag-ui/dist/runtime/AgUiThreadRuntimeCore.js:241-247
const assistant = this.findRequiresActionAssistant("interrupt");
if (!assistant) return null;
const stored = assistant.metadata.custom[AG_UI_METADATA_NAMESPACE]?.interrupts;   // "agui"
if (!stored?.length) return null;
```

（namespace 的值是 `"agui"`：`.../adapter/run-aggregator.js:7`。
直播那条路上是**上游的 aggregator 自己**把 interrupts 写进这个 metadata 的——`:820`。
所以 rebuild 只要产出同样的形状，两条路就一致。）

**三、而我们的转换把每一条消息都盖成「完成」。**

```ts
// ui/src/app.tsx:102-109   每一条都盖上 complete/unknown，包括那条悬置的助手消息
fromThreadMessageLike(message, message.id ?? crypto.randomUUID(), {
  type: "complete", reason: "unknown",
}),
```

于是 `findRequiresActionAssistant("interrupt")` 找不到东西 ⇒ 没有卡片；
而且 `assertNoPendingInterrupts()` 因此**放行** ⇒ 用户以为自己可以正常发下一条，
那次悬置的调用连服务端那条 parked 记录一起，成为孤儿。

顺带一件要改的假话：

```
// ui/src/app.tsx:95-97
/// `fromAgUiMessages` rebuilds text, reasoning and tool calls -- and reads back
/// `metadata.custom.agui.interrupts` when the log carried them
```

「日志带着的时候读得回来」今天不成立——没有人把 interrupts 放进 rebuild 的输出里。

## 要改成什么

**一、服务端：rebuild 把悬置折回它属于的那条消息。** 记录里那条帧本来就在（`input` + frames），
所以这是**投影**的活，不是新状态：`apply-frames`/`records->messages` 要认得
带 `outcome.interrupts` 的 `RUN_FINISHED`，并把它落到该落的那条 assistant 消息上。
落哪一条由客户端那条规则反过来定（它找的是**最后一条** assistant），所以本票的判据是：
**rebuild 的输出必须满足 `findRequiresActionAssistant("interrupt")` + `metadata.custom.agui.interrupts` 那个形状**，
而不是「字段里出现了 interrupts 就算」。

**二、客户端：别把每一条都盖成 `complete`。** 这一条不是本票的附属——它今天是「卡片回不来」的**第二半**，
两半缺一都还是坏。改的时候注意 `toThreadMessages` 是**共用**的（挂载恢复、切会话都用它），
所以改的是它对每条消息的判定，不是加一个特例分支。

**三、不该由本票做的**：不给悬置新加一套界面。卡片、门、`decide` 那条路都已经在
`ui/src/components/approval-gate.tsx` 里，本票只让**刷新回来的那一份状态**重新满足它们的入口条件。

**四、本票还要接上票 04 剩下的那一半**（2026-09-21 补记）。票 04 落地的是**「在跑」**那一半
（服务端窗口说 `running` ⇒ composer 不发 + 一句话；见 spec 的落地一节），**「悬置」那一半正等着本票**：
`lib/session-status.ts` 的 `statusOf` 今天**故意**不从服务端取 `parked`——卡片回不来的时候，为它关上的门
是一扇**出不去**的门。所以本票让卡片回得来之后，要顺手把那一格也接上服务端那个词，并把
`ui/test/suites/running.tsx` 里钉住这个决定的那条用例改成**新的事实**（它现在断言的是
「服务端的 `parked` 不算在跑」，那正是要改掉的那一条）。

## 验收

- [ ] 后端用例：`rebuild` 一条以 `RUN_FINISHED(outcome.interrupts)` 结束的日志 ⇒ 返回的消息里那条助手消息
      带着悬置（形状按上游那条规则断言）。**今天这条用例是红的**，先让它红再让它绿
- [ ] 后端用例：一条**正常跑完**（没有 interrupt）的日志，rebuild 出来的消息里**没有** `requires-action`
      （别把「最后一条 assistant 一律标成悬置」当成实现）
- [ ] 客户端：`toThreadMessages` 不再无条件 `{type:"complete"}`；悬置那条按真实状态进去，
      因此 `getPendingInterrupts()` 找得到、`assertNoPendingInterrupts()` 该拦就拦
- [ ] **走查**（证据进 `evidence/`）：跑到审批那一步停下 → 刷新 → **卡片还在** →
      作出决定 → 那次调用拿到结果、run 按 resume 那条路继续（服务端那行 `approval/decided` 对得上）
- [ ] **走查**：悬置期间 composer 关着（与票 04 同一次走查，两票共用）
- [ ] `app.tsx:95-97` 那句注释改掉——要么改成真的，要么删掉；**不许留一句今天不成立的话**
- [ ] `cd ui && npm run build` 过；`cd ui && npm test` 全绿
- [ ] `timeout 900 clojure -M:test -m harness.test-runner`：新用例名出现，失败**用例名**与基线一致
