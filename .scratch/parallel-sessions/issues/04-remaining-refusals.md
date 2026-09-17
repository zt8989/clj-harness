# 04 — 剩下仍然要拒绝的：归档/删掉一场还在跑的会话

**What to build:** 切换与新建放开了，但**收起来一场正在跑的会话**仍然要拒绝——
判据从「当前页在不在跑」换成**那条会话自己在不在跑**，句子落在那一行上。
归档别的会话、以及删掉一个没有会话在跑的项目，都放行。

**Blocked by:** 02

**Status:** ready-for-agent

## 现场：两处守卫的判据是「当前页」，那正是要换掉的东西

```ts
// ui/src/components/sidebar.tsx:351-353   归档：只在你归档的正是当前页时才拦
(movesThePage = archived && threadId === currentThreadId)
  .. RUN_IN_PROGRESS_REFUSAL ..
// ui/src/components/sidebar.tsx:393-400   删项目：被删的项目里有当前会话时才拦
```

今天这个判据**是对的**，因为「跑着的」只能是当前页（一份 runtime 一份 core）。
02 之后不对了：后台的 A 在跑，你去归档 A——`threadId` 是 B，`movesThePage` 为假，**放行**。
放行的后果不是崩：A 的 run 还在写文件、jsonl 还在追加，而日志文件被搬去了归档处；
收起来的记录与还在长的那一场从此分成两个地方。

## 要改成什么

**一、判据换成「那条会话自己在跑」**，用的是票 03 那份注册表（`threadId -> {:running? ..}`）：

| 动作 | 今天 | 改后 |
|---|---|---|
| 归档**正在跑**的那一场 | 拦（只在它是当前页时） | **拦**（不管它是不是当前页） |
| 归档别的会话 | 放行 | 放行 |
| 删掉一个项目，**有会话在跑** | 拦（只在有当前会话时） | **拦**，句子点名是**哪一场** |
| 删掉一个项目，没有会话在跑 | 放行 | 放行 |
| 切换 / 新建 | 拦 | **放行**（02 已做） |

**二、句子按会话重写。** 今天两句（`RUN_IN_PROGRESS_REFUSAL`、`RUN_IN_PROGRESS_NEW_THREAD_REFUSAL`，
`lib/run-state.ts:16-20`）说的是「一次 run 在做，所以整页停摆」——那个前提没了。
新句子要点名**这一场**（英文，照 `## UI copy is English` 那条纪律）：

- 归档：`That session is still running; archive it once it settles.`
- 删项目：`<session> is still running; remove the project once it settles.`（点名到会话，
  因为被拦下的是项目那颗按钮，而原因是里面某一场）

旧的两句在这个票里**删掉**：它们在本仓已经没有守卫了。票 06 会扫一遍确认没人再用。

**三、落点仍然是那一行**（归档）与**项目那一行**（删项目）。今天的行为就是这样，不要改：
`thread-list.aui.tsx:71-74` 那段注释说得很清楚——**顶部的消息要读者自己去对行，而人会对错**。

## 验收

- [ ] 归档守卫的判据是「被归档的那一场在不在跑」，不是「当前页在不在跑」；
      删项目的守卫同理，且句子**点名是哪一场**
- [ ] `RUN_IN_PROGRESS_REFUSAL` 与 `RUN_IN_PROGRESS_NEW_THREAD_REFUSAL` 已删除，
      两句新句子住在 `lib/run-state.ts`（或它的替代处，票 06 收口）
- [ ] **走查四条（证据进 `.scratch/parallel-sessions/evidence/`）**：
      ① A 在跑、当前页是 A，归档 A → 拒，句子在 A 那一行
      ② A 在跑、当前页是 B，归档 **A** → **拒**（今天会放行，这就是本票修的那一格），句子在 A 那一行
      ③ A 在跑、当前页是 B，归档 **C**（没有在跑的）→ 放行
      ④ A 在跑，删掉 A 所属的项目 → 拒，句子点名 A；A 跑完再删 → 放行
- [ ] 归档/删项目**成功**的那些路径仍然会切走当前页（02 的显示动词），没有留下「指向已消失会话」的显示态
- [ ] `cd ui && npm run build` 过
- [ ] `cd ui && npm test` 全绿
- [ ] `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致

## Comments

**2026-09-17：代码已落地。** 归档的判据换成「被归档的那一场在不在跑」（`blocked(statuses[id])`，
与当前页无关）；删项目的判据换成「项目里**任何**一场没完」，句子点名是哪一场
（`removeProjectRefusal(sessionId, status)`）。`RUN_IN_PROGRESS_REFUSAL` 与
`RUN_IN_PROGRESS_NEW_THREAD_REFUSAL` 已删，新句子住在 `lib/run-state.ts` 改名后的
`lib/session-status.ts`。**四条走查没跑**，见证据 README。
