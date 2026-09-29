# 08 — `ctx.agents`：活体 agent 与 `agent/*` 词汇

**What to build:** 今天「一轮 run 的活体侧」是循环内部的局部状态，外面只能看记录。dsh 把它拆成一个接口
加一个**活体注册表**加一套 `agent/*` 事件词汇（inbox / step / status / request / validation /
continuation / turn-stopping），好处是：观察与拦截**不用 import 循环**。这一票只立**词汇与注册表**，
不动循环——这是循环能在下一票被换掉的前提。

**Blocked by:** 04 — `ctx.sessions`

**Status:** ready-for-agent

## 验收

- [ ] `ctx.agents` 答得出「现在活着的 agent 有哪些」，每个带得出它的会话与状态
- [ ] `agent/*` 事件逐个有定义（时机 / payload / 是否瀑布 / 能不能拦截），写成本仓的词汇表
- [ ] 观察者是**订阅**进来的：一个观察者都没有时，行为与改动前逐条相同
- [ ] **inbox 的投影能从记录里折出来**——没有活体 agent 时（进程刚重启）也答得出「还有什么没做」
- [ ] 子代理今天那套（`cap.subagents`）在这套词汇里说得清，但**一个字不改**
- [ ] 「活体状态不是事实」这条守着：唯一真源仍是会话记录，活体只是当下那一份
