# 07 — 服务端：一条 run 停得下来（取消到得了循环，terminal 诚实）

**What to build:** 按会话寻址的一条取消：一条还在跑的 run 收到它之后**真的到达 terminal**，
而那个 terminal 说的是实话。这是票 09「刷新之后也能停」的服务端那一半。

**Blocked by:** 01（取消得先找得到那条 run）

**Status:** ready-for-agent

## 现场：取消这条路今天根本不存在

```clojure
;; src/harness/edge/http.clj:1104-1113   会话下的动词是个闭集
#{"rebuild" "archive" "stats" "trajectory"}
```

没有 cancel，也没有任何等价物。而界面上那个 Stop **只断浏览器那条 fetch**：
`@ag-ui/client` 把 `AbortError` 合成一个 `RUN_ERROR`（`code: "abort"`，message "Request aborted"），
**没有东西到达服务端**（本仓 2026-09-17 实测，记在 `http.clj:310-316` 与 `:689-698` 的注释里）。
所以今天「停」是一条假动作：界面看起来停了，服务端继续跑、记录继续长。

## 要改成什么

**一、动词进那个闭集。** 建议 `cancel`（`thread-verbs`，`http.clj:1104-1113`），
并按仓规把那段 docstring 里「一个 GET」的句子一起改对（票 02 也在动这个集合，
两张票落地顺序不定，谁后落谁扫那一段）。

**二、判据是票 01 的登记表，而拒绝要具名**：这个 thread-id 没有活着的 run ⇒ 具名拒绝
（不静默成功、不 500）；已经有 terminal 之后再取消 ⇒ 同样具名拒绝（这是同一条边界的两半）。

**三、terminal 不许说谎——这一条是本票的核心决定，理由写在票面。**
帧的词汇表只有两个 terminal：「A run is over when one of these arrives. Nothing may follow it.」
（`frames.clj:17-20`，`#{"RUN_FINISHED" "RUN_ERROR"}`）。一条被人停掉的 run **没有跑完**，
所以 **terminal 是 `RUN_ERROR`，reason 说清是人停的**。
这和本仓既有的先例是同一个判断：`closing-frames` 给截断记录补 terminal 时写着
「AND THE TERMINAL IS RUN_ERROR, NEVER RUN_FINISHED. A run that never reached a terminal frame did not finish」
（`replay.clj:156-160`）。也和协议侧既有的形状一致：浏览器自己 abort 时合成的就是 `RUN_ERROR`。
「人按的停不是失败」不等于「这条 run 完成了」——两件事，记录里记的那件是后者。
reason 会顺着 `http.clj:342-344` 那行 `run/terminal` 落进进程日志（`:reason`），所以事后读得出来是这个原因。

**四、取消到得了循环，这一点要说清它的边界。** 循环在 `alts!!` 上等工具结果（`loop.clj:172-180`），
所以在**步骤之间**观测取消是这一票能给的；**一次已经发出去的工具调用**怎么停，是票 08 的活。
票面写明这条边界，免得验收时拿一个卡在 bash 上的 run 来验本票。

**五、取消是一个显式请求，不是从「谁挂断了」推出来的。** spec 的「不改的」里写着
`handle-run` 继续不管客户端走没走；这条不许被本票顺手改掉。

## 验收

- [ ] 后端用例：一条 run 停在**慢的一步**上（慢的模型调用，用既有的 seam 造：
      照 `test/harness/edge/http_test.clj` 里静默 run 那套重定义 `loop/run-chan` / `ag/outbound`），
      对它 cancel ⇒ run 到达 terminal
- [ ] 断言那个 terminal 是 `RUN_ERROR` 且 reason 指向「人停的」；进程日志里同一 run 的
      `run/terminal` 那行 `:reason` 与之一致
- [ ] 记录里那条 run **只有一个** terminal，且 terminal 之后没有帧
      （`frames/terminal?` 的 docstring 就是判据：「Nothing may follow it」）
- [ ] cancel 之后同一 thread-id 能正常发下一条并跑完（票 05 那道门必须放开）
- [ ] 对一个没有 run 在跑的 thread-id cancel ⇒ 具名拒绝；对一条**已经终了**的 run cancel ⇒ 具名拒绝
- [ ] 取消之后 `GET /api/projects` 那一行回到 `running: false`（票 01 的注销走同一条路，不是第二条）
- [ ] `timeout 900 clojure -M:test -m harness.test-runner`：新用例名出现，失败**用例名**与基线一致
