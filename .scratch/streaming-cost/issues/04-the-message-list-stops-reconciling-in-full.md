# 04 — 整屏消息不再整体参与 reconcile

**做什么**

让一次提交只让**真正变了的那几条消息**重新 reconcile。今天每次 store 更新（流式期间每个 delta
一次、合流之后每帧一次）都会让整个消息列表重走一遍 React 的 reconcile：一场 400 条消息的会话里，
**没变的那 399 条也要重新走一次**，代价按会话长度摊销（实测斜率约 0.06ms/条消息：1 条消息的会话
一次提交约 10ms，400 条约 34ms —— 也就是 29fps 的那个数）。

**为什么是这张票**（CPU profile 的结论，见 `spec.md`「量了（一次提交的代价）」）：页面自己花的时间
里**没有一处「我们的热点」** —— 唯一叫得出名字的是 `getBoundingClientRect`（2.4%，就是思考行自己的
测量，票 01 已经把它减半），其余是 React 的 `beginWork`/`placeChild` 加上库里按会话长度摊销的每更新
工作，散在一条很长的尾巴里。**减少提交次数**那条杠杆票 02 已经做了；剩下这条就是「别让整屏都参与」。

**做法（做的时候定，两个方向不互斥）**

1. **列表级 windowing**：只画视口附近的消息（`content-visibility: auto` 今天已经在省**布局**，
   但 React 该建的树一个不少）。注意这条路的坑：`thread.aui.tsx` 的折叠（`fold === "step"`）、
   回合之间的间距（`isTurnEnd`/`isStepAfter` 看的是**前后两条消息**）、以及「看更早的」那套滚动
   锚定（`lib/window-scroll.ts` 是按**文本**认锚点的）都假设整列都在 DOM 里 —— 一处一处说清。
2. **逐条 memo**：消息组件按「这条消息自己变了没有」短路。前提是先弄清 assistant-ui 的
   `import`/store 每次提交是不是**重建了所有 message 对象**：如果是，任何 shallow 比较都救不了，
   得先决定这件事归谁管（上游？还是我们少 import 一次）。

**Blocked by:** None

**Status:** needs-triage —— 方向 1 与 2 的取舍要一次实地看（先量：一次提交里 400 条消息各自花多少，
以及 assistant-ui 每次提交是否重建 message 对象）。

- [ ] 先量：一场 400 条消息的会话里，一次 store 更新让**多少条**消息组件真的 re-render、各自多少 ms
      （React 的 `Profiler` 或 `beginWork` 侧的计数），以及 `runtime.thread.import` 之后
      **message 对象的身份**是否全变（变 ⇒ 方向 2 不成立，先解决它）。
- [ ] 选定的那条路做完后，同一条夹具上「一次提交的墙钟」与「re-render 的消息条数」都要降下来，
      数字进 `evidence/`。
- [ ] 行为一条不改：折叠的回合（`fold === "step"` 藏起来的步骤）、相邻两条消息之间的 24px 间距、
      「看更早的」的滚动锚定（`withHeldScroll` 的三趟纠正）、`content-visibility` 的原有收益 —— 走查
      `thinking-row-tail` 与 `.scratch/sessions-live-on-the-server` 那两条都要绿。
- [ ] 键盘与无障碍不许退化：消息列表不要变成「只在滚动时才存在」的东西（跳转/查找/朗读都要还找得到
      已经渲染过的内容）。
