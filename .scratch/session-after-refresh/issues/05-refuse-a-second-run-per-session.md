# 05 — 服务端拒绝同一会话的第二条 run

**What to build:** 一条 run 还在跑时，同一 thread-id 的第二条 `POST /` 被**具名拒绝**，而不是悄悄开始第二条；
不同 thread-id 的两条仍然同时跑通（那是 `.scratch/parallel-sessions/` 的全部基础，不许碰）。

**Blocked by:** 01（「还在跑」这件事只有它说得出来）

**Status:** ready-for-agent

## 现场：run 的准入上一无所有

```clojure
;; src/harness/edge/http.clj:672-680   handle-run 从头到尾没有任何门
(defn- handle-run [req]
  (let [input (json/read-str (slurp (:body req) :encoding "UTF-8") :key-fn keyword)
        ...
        state (atom {:terminal nil :last nil})]
```

`http.clj` 里唯一的锁是 jsonl 追加那把（`:155`），它管的是**行不许交错**，不管**谁有资格开跑**。
所以同一会话的第二条 run 会正常启动，然后：

- 文件里出现**两条** `input`（`:453` 那行 `log!` 每个 run 都写）；
- 两条 run 的帧交错落进同一份记录；
- 而 reading 那边的算术是按「一条 run」写的：
  `ensure-complete!` 数 inputs 与 terminals（`replay.clj:92-106`），
  `open-run` 取**最后**一条 input 与**最后**一个 terminal（`:122-141`），
  `first-input` 取**第一条** input 当种子与 context（`:201-208`）。
  两条 run 之后，`rebuild` 交出来的对话会丢掉第二条 run 的 context，`closing-frames` 会去答第二个 run 的调用。
  **不是崩，是一份读起来像真的、但不是真的记录。**

今天只有 UI 的 `isRunning` 遮着这件事。票 03 之后这个遮挡没了（刷新后的客户端什么都不知道），
而 localStorage（spec 的决定三）保证了**新标签页会落在同一场**——所以这一票不是防御性编程，是必需的底。

## 要改成什么

**一、准入检查放在 run 起跑之前**，判据是票 01 的登记表：这一场已经有活着的 run ⇒ 拒绝。
一个**具名的**拒绝（一个说得清的状态码 + 一个人能读的原因），不是排队——排队是本仓没有的机制，
硬在这里发明一个，就得同时发明「排队的人走了怎么办」。

**二、拒绝必须放过的东西**（把这些写进用例，它们是同一条边界的两半）：

- **不同 thread-id 同时跑**：今天真并发（`handle-run` 一次请求一条线程、run 体是自己的 go 块），
  `.scratch/parallel-sessions/` 整个特征是建立在它之上的。**不许多一道全局锁**。
- **前一条终了之后同一 thread-id 能再发**：包括**崩掉**的那种终了（票 01 的注销路径），
  否则一次崩溃把一个会话锁死。

**三、这条用例只有一份。** `.scratch/parallel-sessions/issues/06-docs-and-verification.md` 想要一条
「两个 thread-id 各一条真 run 同时跑、两条都完成」的后端用例——**写在这里**，
连同上面那条负例一起（拒绝与放行互为边界，拆开写就丢了「同一台服务器上两件事同时为真」这个断言）。
在那张票面里留一句指向本票，别写第二份。

## 验收

- [ ] 后端用例：一条 run 在跑时，同一 thread-id 的第二条 `POST /` 被**具名拒绝**（断言状态码与原因文本）
- [ ] 同一条用例的负半边：两个不同 thread-id 的两条 run **同时**发出去（**不要** `await` 完一条再发另一条），
      两条都到 terminal、各自的 jsonl 用 `rebuild` 读回来都对
- [ ] 被拒的那条**没写任何东西**：文件里那条 run 的 `input` 只有一条（对文件断言，不看响应）
- [ ] 前一条 run 终了后，同一 thread-id 能正常发下一条并跑完
- [ ] 一条**崩掉**的 run（`run/crashed`）之后，同一 thread-id 仍然能发（登记表注销到位）
- [ ] 时序写法按仓规：`post-run` 会 block 到 terminal，所以并发那条用 `fire-run!`
      + 轮询/等各自终了的方式写；子进程若介入，答案**从文件读**，不从 stdout 读
- [ ] `timeout 900 clojure -M:test -m harness.test-runner`：新用例名出现在输出里，失败**用例名**与基线一致
