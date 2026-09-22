# 05 — 项目记忆进 system 消息（第一个「读回来」）

**What to build:** 让 agentmemory 的记忆**回到模型眼前**一次。`:system-prompt` 是引擎里**唯一**
把 stdout 当内容的点，所以注入挂在这里：给桥加一个 `context` 动词（POST `/agentmemory/context`
`{sessionId, project, budget}`，把 `context` 原文打到 stdout），再在
`~/.clj-harness/hooks.edn` 里加一条 `:system-prompt` 声明。

**Status:** ready-for-agent

## 为什么是这里

- `/agentmemory/context` 就是这个用途：`pre-compact.mjs` 已经这么干了（读 payload 取
  `session_id`/`cwd` → POST `{sessionId, project, budget}` → `process.stdout.write(result.context)`），
  `context` 动词照它抄。
- 时机：`:system-prompt` 在每次组装 system 消息时触发（`harness.cap.system-prompt/assemble`，
  由 `src/harness/edge/http.clj:855` 调用）＝**每 run 一次**。这正是「这个项目/会话近来的记忆」的
  节奏；「这一句话的召回」是票 06，不在这里混。

## 决策

- **危险要说在最前面：`:system-prompt` 是门禁，`:on-error :block`。** 它非零退出或超时**会让这次
  run 起不来**。所以那个脚本必须「任何失败都 exit 0 且不打印任何东西」，并且自己带
  `AbortSignal.timeout(2000)`——**超时要在脚本里被吃掉**，不能留给引擎的 `:timeout` 去判。
  一条记忆服务把整个 harness 拖住，是这套接线里唯一能造成真损失的写法。
- **空输出 = 什么都不说**，这一点引擎已经保证（`:system-prompt` 收集的是 trim 后非空的 stdout），
  所以「服务没起来」与「没有相关记忆」在模型那边是同一种观察：**没有块**。这是对的。
- **桥的失败路径沿用现有纪律**：返回 1（永不 2）是给「桥自己坏了」用的；对
  `context` 动词，服务不可达属于**正常的空**——打印空、exit 0。两种失败要说清楚哪一种是哪种。
- **`:timeout` 给 4000**：脚本自己有 2000 的请求上限，剩下的余量给 node 启动。
- 桥里那个动词的**命名要诚实**：不要复用 `pre-compact`（行为一样但名字会骗人，下一个人会以为
  压缩在这条路上）。

## 验收

- [ ] 真跑一次（`clojure -M:run` 或走查脚本），**把模型实际收到的 system 消息打出来**：里面有
      那个记忆块，且块的内容是这次会话/项目的（不是别的项目的、不是空字符串）
- [ ] **服务停掉再跑一次**：run 照常起来、没有块、审计行里那条 `SystemPrompt` 的声明是什么
      `outcome`（拍下来）——这一条是这票的门槛，没有它不许合
- [ ] 服务**慢**（故意 sleep 3s）再跑一次：run 照常起来，因为脚本自己在 2s 处放弃
- [ ] 块与块之间的形状沿用既有约定（原样进 prompt，块之间一个空行，引擎不包装）
- [ ] 全量绿
