# 08 — 服务端：正在跑的那次工具调用也停得下来

**What to build:** 一条卡在**已经在跑的命令**上的 run，取消之后那次调用真的停——
进程树没了，run 到达 terminal，记录里不留一个没有结果的 open call。

**Blocked by:** 07（取消这条路、以及那个 terminal 的定论）

**Status:** ready-for-agent

## 现场：一次在飞的调用今天没有任何「地址」

```clojure
;; src/harness/kernel/loop.clj:153-159   每次调用一个裸的 async/thread，没有把手
(async/thread
  (let [{:keys [content error parked]} (tools/run! call thread-id emit)]
    (async/>!! ch {:id id :content content :error error :parked parked})))
```

循环在 `alts!!` 上等这些 channel（`:172-180`），**没有任何中断的路**：
一个已经发出去的调用，除了等它自己回来，谁也动不了它。
而杀掉进程树的能力**已经有了**，只是在别的家里、而且不对外：

```clojure
;; src/harness/infra/shell.clj:259
(defn- kill-tree!
```

它的两个调用点都是 shell 自己的时限那条路（`:324`、`:476`），也就是「一条命令超时」杀掉整棵树。
而 `.scratch/bash-lifetime` 落地时写明了它为什么必须杀树（只杀手里那个 shell 会留下孤儿 JVM，
实测见过两个 `harness.test-runner` 挂在 PPID 1 上 22 小时）。所以本票要的是**把那个已有的把手接到取消上**，
不是再造一套 kill。job 那边今天没有时限（`cap/jobs.clj`），本票不替它发明一条，但要写清它今天的样子。

## 要改成什么

**一、规格：一次调用要能被放弃，它的结果要被丢掉而不是等。** 这是形状的那一半——
`alts!!` 那个 drain 要知道还有几次结果没回来，且**不许**因为放弃而在记录里留下空洞。

**二、记录里不许留 open call。** 理由不是整洁，是形状：一条 `toolCalls` 有调用没有对应结果的
assistant 消息是**厂商会拒**的形状——`closing-frames` 里已经为同一件事写过这条理由
（`replay.clj:149-155`：给它补一条结果，句子是真话「这次调用被切断了、没有结果被记下」）。
被取消的调用照同一句话处理，**不要**发明第二套措辞。

**三、不可打断的那一类要诚实。** 一个厂商 HTTP 调用没有进程可以杀；
对它的正确行为是**放弃等待**、让 run 的 terminal 按票 07 到达，
而不是让取消跟着它一起等。票面要求：这类调用下 terminal 仍然按时到达（用例里造一个）。

**四、不许把 kill 的权力做成全局开关。** 杀的是**这一次 run 的**那次调用的进程树。
一个「停掉所有 bash」的机制会让并行的另一场会话陪葬——而不同 thread-id 真并发是本仓的既定事实
（票 05 的负半边用例就在守它）。

## 验收

- [ ] 后端用例：一条 run 卡在**真在跑的慢命令**上（不是慢模型），cancel ⇒
      那条命令的**进程树**不存在了（按 `bash-lifetime` 那套断言：查进程是否存在、PPID 是谁），
      且 run 到达 terminal
- [ ] 同一用例断言记录：那次调用有一条结果（「被切断、没有结果被记下」那句话），
      全记录里**没有** open call（对文件断言：`TOOL_CALL_START` 与 `TOOL_CALL_RESULT` 的集合相等）
- [ ] 一条被取消的 run 的**下一个** run 能正常跑，且没有残留进程（同一台机器上再跑一条命令看得到）
- [ ] 不可打断的调用（厂商 HTTP 那条路，用 scripted vendor 造一个慢响应）：cancel 之后
      run 的 terminal **不等它**就到；那条调用在记录里同样是「被切断」而不是缺结果
- [ ] 另外一场会话的一条真命令不受影响（同一次用例里并行发一条，取消 A 之后 B 照常跑完）
- [ ] 时序与进程断言按仓规：子进程/命令的答案**从文件读**，不从 stdout 读
- [ ] `timeout 900 clojure -M:test -m harness.test-runner`：新用例名出现，失败**用例名**与基线一致
