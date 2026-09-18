# 02 — 还在被写的记录：读得回来，且不被结掉

**What to build:** 一条正在被写的记录读得回来——回来的是已记下的帧，并且**自己声明**它不完整、在等什么；
同时那条会**收尾**的写路径对**本进程里还活着的** run 拒绝动手。真断掉（进程死了）的记录仍然照今天那样被收尾。

**Blocked by:** 01（活不活着这件事只有它说得出来）

**Status:** ready-for-agent

## 现场一：读的那条路会把活的 run 结掉

```clojure
;; src/harness/edge/http.clj:1322-1331   rebuild 之前先收尾，而收尾只问文件
(let [located (try {:ok (replay/locate (home/projects-dir) stem)} ...)
      _       (when (nil? (:error located))
                (close-off-open-run! stem (:ok located)))     ; ← 这里
```

`close-off-open-run!`（`:1295-1311`）→ `replay/closing-frames`（`replay.clj:143-176`）→ `replay/open-run`。
`open-run` 判「open」的唯一依据是文件里 input 比 terminal 多（`:122-141`），**没有存活判断**。
所以在一条**正在跑**的 run 上调用它，会给它追加：

- 每个没答的调用一条 `TOOL_CALL_RESULT`（`replay.clj:163-170`），
- 一个 `RUN_ERROR`（`:174`），
- 外加一行 `session/closed-off` audit（`http.clj:1302`）。

然后那条 run 继续跑，最后写出自己真正的 `RUN_FINISHED`。
**一条 run 两个 terminal，而且中间还夹着帧。** 这不是「修了一半」，这是往一份
「每一行都必须是真的」的追加记录里写了一句假话——而这两个 terminal 恰好证明了有人不知道那条 run 还活着。

今天就能踩到：刷新（新 runtime 的 `isRunning` 为假，`app.tsx:154` 那道守卫拦不住）→ 点那一场 → rebuild。

## 现场二：数据本来就在，缺的只是一条不说谎的读法

`log!` 是逐帧 `spit :append`（`http.clj:150-156`），每帧写完就关流——
**一条正在跑的 run，它的帧已经在文件里了**。所以这件事不需要新写任何东西，只需要一条读法：

```clojure
;; src/harness/edge/replay.clj:193   拒绝就长在读法里，读法与拒绝是同一个调用
(ensure-complete! records)
(into (vec seed) (frames/apply-frames frames))
```

`:91-106` 的拒绝**不是要删的东西**：它防的是「悄悄折叠半条 run，读成一段更短的对话」。
本票给的是相反的东西——**折叠之前先声明**。

## 要改成什么

**一、一条只会读、永远不写的读法。** 建议新开一个 GET verb（`thread-verbs` 那个闭集，`http.clj:1104-1113`）：
`rebuild` 的语义是「把对话交给我，让我重新拥有它」（`replay.clj:210-215`），
而这条是「把已经记下的给我看看」——两个意图，且**轮询一条 POST 是个陷阱**（它可能动手写文件）。
落在新增 verb 上时，那句 `ONE OF THE FOUR IS A GET` 的 docstring 要跟着改写（现在是两个 GET）。
命名自取，但它必须读起来像「至今为止的对话」而不是像「一个资源」。

**二、答案里带状态，三个而不是两个**（spec 的决定二）：

| 状态 | 怎么判 | 读法返回 |
|---|---|---|
| 在跑 | 票 01 说这一场活着 | 已记下的消息 + `partial? true`、在跑 |
| 悬置 | 记录以 terminal 结尾，且那是 interrupt | 消息 + 悬置（票 06 负责让卡片真的回得来） |
| 截断 | 没有 terminal，且票 01 说它不在 | 今天的行为：`close-off-open-run!` 照旧收尾、照旧拒绝 |

**三、收尾那一步先问票 01。** `close-off-open-run!` 在动文件之前先问登记表：活着 ⇒ 不动。
已死的记录（进程真没了）照旧修，一个字不改。
`open-run` 与 `ensure-complete!` 那对（`replay.clj:114` 写着「拒绝与修复必须不许漂移」）
继续共用同一条走法——**不要**为这条读法另写一份「怎么算 open」的判定。

## 验收

- [ ] run 跑到一半读那一场：拿回已记下的消息（含只有半句的 assistant 消息、进行中的工具调用），
      `partial?` 为 true 且指向「在跑」
- [ ] 同一次读之后断言**文件**：那条 run 的 terminal 计数仍是 0，且没有 `session/closed-off` 行
- [ ] 那条 run 自己结束后再读：`partial?` false，消息与今天的 `rebuild` 给的一致（同一份记录，两种读法不许分叉）
- [ ] 进程真死了留下的截断记录：仍然被收尾（`close-off-open-run!` 的既有行为有既有的用例，不许改动它们的断言）
- [ ] 一条 run 从头到尾只留一个 terminal：在一次活的读之后再等它跑完，对文件断言 terminal 计数为 1
- [ ] 读法**不写文件**：读十次，文件的字节数与 mtime 不变（这是「轮询」能不能用的判据）
- [ ] 时序断言按仓规写：**不要**去抢 writer（等它自己收尾：终帧在了且最后一行是 `message`；
      可以照 `.scratch` 里记的那条既有教训，别用「body 结束就量文件」的写法）
- [ ] `timeout 900 clojure -M:test -m harness.test-runner`：失败**用例名**与基线一致
