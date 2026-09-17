# 01 — 原型与形状确认：挂着的、没在显示的 host，它的 run 还活着吗？

**What to build:** 一个**可以扔掉**的原型：让 App 同时挂住两份 `useAgUiRuntime`（一场会话一份 host），
只显示其中一份，另一份在后台跑一条 run。把四个问题的答案**连证据**写下来。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 为什么这张票存在

spec 里的形状决定（一个会话一份 runtime、切换不再经过 runtime）是从**上游代码**读出来的，
不是在这儿试出来的：`useAgUiRuntime` 每次调用自造一份 core 并存在 ref 里
（`node_modules/@assistant-ui/react-ag-ui/dist/useAgUiRuntime.js:22`），而 core 类没有从包里导出。
读代码能得到「结构上应该行」，得不到「所以它的 run 会继续流、消息不会丢、切回来还是完整那一轮」。
**这就是要试的东西。**

这张票**不交付**本特征：原型代码可以直接扔掉，交付的是**答案与证据**。

## 四个问题（每个都要有证据，不许写「应该可以」）

1. **后台那场会话的 run 还活着吗？** 一份 host 挂住、没在显示时：
   它 core 里的消息还在长吗（流式帧继续进来）？run 结束后那一轮是完整的吗（工具行、思考行都在）？
   `isRunning` 会不会一路是对的？
2. **换 `AssistantRuntimeProvider` 的 runtime 会不会把 `<Thread/>` 整棵重挂？**
   滚动位置、折叠状态、composer 里草稿的文本，切走再切回来还在不在？
   若不在，第二个 provider 常驻（把 `<Thread/>` 挪到「显示哪一场」的外面）能不能解决？
3. **每个 host 各自给 `threadList` 传自己那个 `threadId` 够不够？**
   退场的是 `onSwitchToThread` / `onSwitchToNewThread` 两个回调。
   装完之后 `runtime.threads.switchToThread` 还有没有别的调用方非要它？（今天有，见
   `ui/src/components/sidebar.tsx:324,367,372,426,429`——把每一处要改成什么都列出来。）
4. **N 份 host 的代价。** 不显示的那一份只挂 runtime、不渲染 `<Thread/>`：它还会不会订阅并渲染消息？
   一次 update 触发几次 render？两份、三份的量级写在证据里（不必精确，要有数）。

## 怎么做（照本仓的走查形式）

- 后端起法照 `.scratch/composer-status/evidence/strip-in-the-browser.md` 那份：
  **临时 `CLJ_HARNESS_HOME`**、`harness.e2e-server`（脚本厂商，一条会**慢**的：流式拉长到几秒，
  否则「后台还在跑」看不出来）、`cd ui && npm run dev`。
- 证据写进 `.scratch/parallel-sessions/evidence/two-hosts.md`：怎么起的、看到什么、
  必要时的 console 输出与截图文件名。
- 答案与结论写进**本票的 `## Comments`**。
- **若某个答案推翻了 spec 的形状决定**，在 `spec.md` 追加一段**复议**（带日期），
  说清推翻的是哪一条、新形状是什么——票面是当时的草稿，spec 记决策。

## 验收

- [ ] 四个问题各有一条答案，每条都能指着**证据**（走查记录里的哪一段、哪个文件、哪次 console 输出）
- [ ] 「后台 run 还活着」这条是**看着它跑完**的：切走、等它结束、切回来，那一轮完整
- [ ] `switchToThread` 的每一处调用方（`sidebar.tsx` 五处）在答案里各有一个处置：改成什么、或为什么还留着
- [ ] 证据文件在 `.scratch/parallel-sessions/evidence/two-hosts.md`，含起服务的**完整命令**（含临时家目录）
- [ ] 结论若与 spec 不符，`spec.md` 已有带日期的复议段；相符也写明「核过了，与 spec 一致」
- [ ] 原型代码**不必**留：本票不以「改动落地」为完成条件，但要写明哪些文件被改过、哪些已经还原

## Comments

**2026-09-17：这张票没有做，原型与四个问题的实测都空着。** 形状是**读上游代码**定下来的
（`useAgUiRuntime` 每次调用自造一份 core 存在 ref 里；`history` 适配器每个 core 只 `load()` 一次），
于是票 02 直接按那个形状落了地，而没有先跑一个能扔的原型。

代价写在 `.scratch/parallel-sessions/evidence/README.md` 的第三节：第 2 问（换 provider 会不会把
`<Thread/>` 重挂、滚动与草稿还在不在）**没有实测**，而落地形态（不显示的 host 不渲染 `<Thread/>`）
对它的回答是**推论**。真机走查时第一件要看的就是它。
