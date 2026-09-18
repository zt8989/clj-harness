# 01 — 活着的 run 是服务端说得出的一个事实

**What to build:** 服务端能回答「这一场会话现在有没有一条 run 在跑」，而且这个事实**落在客户端本来就会读的那一行上**——
侧边栏加载时拿到的会话行。一条 run 在跑时那行说 true，terminal 一到就说 false，崩掉的 run 也不许永远亮着。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 现场：这个事实今天只有一个地方有，而它随响应流一起消失

```clojure
;; src/harness/edge/http.clj:680   一个请求内部最接近「活 run 记录」的东西
state (atom {:terminal nil :last nil})
```

它归那一次 `handle-run` 所有（`:672-718`），响应流一结束就没人引用了。
往外一点，`run/start`（`:528`）把 run 写进**进程日志**——按 thread-id grep 得出来，客户端读不到。
会话自己的 jsonl 里能看出「有 input 没有 terminal」，可那分不清两件事：

```clojure
;; src/harness/edge/replay.clj:122-141   open-run 只在数文件，不知道进程里发生了什么
input-at  (last (keep-indexed (fn [i r] (when (= "input" (:kind r)) i)) records))
closed-at (last (keep-indexed (fn [i r] (when (and (= "event" (:kind r))
                                                  (frames/terminal? (:payload r))) i)) records))
```

**「还在跑」与「进程死了」在文件里长得一模一样。** 而这两件事在本特征里要给出不同的答案
（票 02 的读、票 04 的输入框、票 08 的停）。

## 要改成什么

一份**进程级的活 run 登记表**：`threadId -> {:run-id .. }`（后续票会想往里加东西，比如票 07 的取消把手，
所以形状留余地，但这一票只要求 run-id）。

- **登记点在 `run/start` 旁边**（`http.clj:528` 那一段），不是 `run-agent!` 的开头：
  一条**没跑起来**的 run（`:510-521` 那条 `run-refused` 的路：没有 provider、模型被拒）
  绝不能留下登记，否则它会永远亮着。
- **注销点必须覆盖每一个结束**：terminal（`:330-344` 那条 `run/terminal` 就在同一个位置）、
  崩溃（`:663-669` 的 `run/crashed` 分支）、以及通道没给 terminal 就关掉的那条（`:652-662`）。
  **一次 run 恰好注销一次**。仓里的旧伤值得先看一眼：`update-in` 会把已经删掉的键**复活成 nil**
  （`.scratch/bash-lifetime` 记过这一条），所以这里的形状要用显式判存在再删，不要 update-in 兜底。
  漏掉的后果不是崩，是**一个会话永远显示在跑**，而那会让票 04 把它自己的输入框永久关掉。
- **暴露位置：`session-row`**（`http.clj:1035-1056`，今天是 `{:threadId :archived :lastActivity :bytes}`）。
  加一个 `:running`。选这里而不是 `/api/threads/<stem>/stats`：`stats` 是**折记录**的读法
  （`:1214` 的 docstring 就写着 folded from its RECORD），而这个事实**不在记录里**，硬塞进去会让那个端点说谎。
  侧边栏与票 03 的恢复走的是同一份 payload，一次加载两件事一起拿到。

## 顺带（只留一句，不要在这里开工）

`.scratch/parallel-sessions/issues/04-remaining-refusals.md` 要的判据是「**那条会话自己**在不在跑」。
本票这个字段必须够它直接用——它要的是同一件事，只是从服务端而不是从客户端凑。
**不要**在这一票里顺手改归档/删项目的守卫。

## 验收

- [ ] 一条 run 在跑时，`GET /api/projects` 里那一场的行带 `running: true`；terminal 一到，同一行是 `false`
- [ ] 一条**被拒没跑起来**的 run（没有 provider 那条路）在任何时刻都不出现在登记里
- [ ] 崩掉的 run 会注销（用 `run/crashed` 那条路验：`run-agent!` 体里抛），登记表不残留
- [ ] 后端用例覆盖上面三条（三条都属于「拿一条真 run、在中间读一次」的形状，
      静默 run 的写法照 `test/harness/edge/http_test.clj` 里既有的那套：`fire-run!` + 轮询 `await-log`，
      **不要**用 `post-run`——它会 block 到 terminal）
- [ ] `timeout 900 clojure -M:test -m harness.test-runner`：新用例名出现在输出里，失败**用例名**与基线一致
- [ ] `docs/architecture/home-and-storage.md` 里「读一份断掉的记录」那一节加一个第五问
      （「它在本进程里活着吗，问谁」），并在改动处留一句指向登记表的注释
