# 06 — 收口：文档不许再描述旧行为，两套全量 + 一条并发用例

**What to build:** 一个**唯一的收口票**：把描述旧行为的文档与注释改掉、给「两个会话同时跑」
在后端那一层补上今天没人写过的用例，并跑完两套全量与走查证据。

**Blocked by:** 02、03、04、05

**Status:** ready-for-agent

## 一、文档与注释：每一处都在说旧的事

| 地方 | 今天说的 | 改成 |
|---|---|---|
| `docs/architecture/client.md:73` | 「**run 进行中拒绝切换与新建**，拒绝的话显示在所点的行上」 | 新行为：切换与新建不再被 run 拦；仍然拒绝的是归档/删掉一场**没完**（在跑或悬置）的会话，句子落在那一行 |
| `docs/architecture/client.md`（`runtime` 那一节） | 一个 agent、一份 runtime 的叙述 | 「一场会话一份 runtime（一份 host）」，并说明**切换不再经过 runtime**、以及第一次打开才 rebuild |
| `ui/src/lib/run-state.ts` | 整个模块的理由是「两句拒绝 + 探针」（`:1-13`） | 只剩按会话的探针与票 03/05 那几格状态；两句旧拒绝已删（票 04）。**文件名与模块理由要一起改**，别留下一个名字在说旧事 |
| `ui/src/app.tsx:12-46` 的头注释 | 「runtime 不再拥有 threadId…… run 进行中被拒」那一整段 | 新形状（host、显示的 state、切换不经 runtime）；**删掉关于拒绝的段落** |
| `elements/thread-list.aui.tsx:66-69` | 「只有打开的 thread 被挂载」 | 票 03 已改；本票扫尾确认，且 `LOCAL:` 标记在 |

**判据是机读的**：`grep -rn "waits until it settles\|switching is refused\|只有打开的 thread 被挂载" ui/src docs/` 无输出。

## 二、补一条今天没人写过的用例（后端那一半）

`ui/test/` 是 agent 层的（真 `HttpAgent` + 真后端 + 脚本厂商，不渲染 React）。**两个 thread-id 各一条
真 run 同时跑、两条都完成**这件事，服务端早就支持，但**没有任何用例**——而本特征一放开，
它就是常态。在新的一支 suite（或既有的一支）里加：

- 两个不同的 thread-id，各自 `agent.runAgent(..)` **同时**发出去（不要 `await` 一条再发另一条）
- 断言：两条都跑到 terminal（没有 `run/error`）、各自的 `agent.messages` 里只有自己的话、
  两份 jsonl 各自完整（用 `rebuild` 读回来对）
- **`ui/test/ui.test.ts:40` 那个钉住的用例总数要跟着 +N**：那个文件就是靠它把「新增用例悄悄没跑」
  变成红的，忘了改它就等于新用例不存在

## 三、两套全量 + 证据

- 后端：`timeout 900 clojure -M:test -m harness.test-runner`，失败**用例名**与基线一致
- 前端：`cd ui && npm run build`、`cd ui && npm test`
- `.scratch/parallel-sessions/evidence/` 里的走查文件齐（01 的 `two-hosts.md`，02/03/04/05 各自的记录），
  每份都含**起服务的完整命令**（临时 `CLJ_HARNESS_HOME`）

## 验收

- [ ] 上表每一行都改了；`grep -rn "waits until it settles\|switching is refused" ui/src docs/` 无输出
- [ ] `docs/architecture/client.md` 里不再有任何一句描述「run 进行中拒绝切换」的行为
- [ ] `lib/run-state.ts` 的名字与 docstring 与它现在真正做的事一致（不再自称是「两句拒绝」的模块）
- [ ] agent 层多一条**并发**用例：两个 thread-id 同时跑、两条都完成、两份日志各自完整；
      `ui/test/ui.test.ts` 的用例总数同步 +N
- [ ] `cd ui && npm test` 全绿；`cd ui && npm run build` 过
- [ ] `timeout 900 clojure -M:test -m harness.test-runner` 的失败**用例名**与基线一致
- [ ] 证据文件齐，且**每一份都能照着里面的命令重跑**（家目录临时，端口不写死）

## Comments

**2026-09-17：文档、并发用例与两套全量都做了；两套全量的数字与基线一致。**

- `docs/architecture/client.md` 的装配、状态的归属、审批门、轨迹、套件清单都改了；
  机读判据 `grep -rn "waits until it settles\|switching is refused\|只有打开的 thread 被挂载" ui/src docs/`
  **无输出**。
- `lib/run-state.ts` → `lib/session-status.ts`（名字与 docstring 一起改）。
- agent 层多一条**并发**用例（`ui/test/suites/concurrent.ts`），`EXPECTED_CASES` 31 → 32，
  `npm test` 32/32 绿。
- 后端全量：`Ran 855 tests containing 11252 assertions. 0 failures, 0 errors.`，与基线一致。
- **走查文件只有一份** `.scratch/parallel-sessions/evidence/README.md`，而它如实写着
  **真机走查一条都没跑成**（8080 / 5173 被这台机器上这个仓库的另一个实例占着，
  验证命令与下一次怎么跑都写在里面）。所以「证据文件齐」这一格**仍然是空的**。
