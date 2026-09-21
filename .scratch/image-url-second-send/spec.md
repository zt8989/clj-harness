# spec: 会话里有过一张图，第二句也能发

**现场（2026-09-21，主人自己的会话，title「你是谁」）**：第一次发图正常，第二次 `RUN_ERROR`——

```
unsupported content part type "image_url"; this harness carries "text" and "image"
```

## 为什么偏偏是"第二次"

四个环节各自都对，串起来才错：

1. **第一次**会话活着，手里那份对话是**客户端送来的 AG-UI 拼法**（`{:type "image" :source …}`），
   `ag/provider-messages` 翻一次成厂商的 `image_url`。这一步从来没出过问题。
2. 会话被搁下（三十秒没人问，sweeper 收摊）或进程重启 ⇒ 下一次动作从**记录**里出生
   （`sessions/build` → `replay/sofar` → `replay/entries`）。
3. 而记录里的 `message` 行**本来就是厂商拼法**——`.scratch/jsonl-two-kinds` 票 02 拍定的：
   payload 是「厂商读到的那条消息」，不是客户端的原文。同一次折叠又把条目的 `:id`（信封上的身份）
   **盖回**它折出来的消息上。
4. `ag/provider-shaped?` 当时是按**枚举**判的：`(not-any? #(contains? m %) ag-ui-only)`，而 `:id`
   在那个集合里——于是「厂商拼法 + 信封 id」被读成「这条还是 AG-UI 的、还得翻」，
   已经翻好的 `image_url` 被**翻第二次**，第二次按名字拒绝它（`provider-part` 的表里只有 `text`/`image`）。

**失败是不可恢复的**：这场会话此后每一次出生都读同一行 ⇒ 再也发不出下一句，不只是这一轮。

同一个错还有第二张脸：`http.clj` 把客户端那条消息交给 `provider-messages` 做展示时，
一条从记录读回的消息走的是同一条路，会在同一个地方炸。

## 判据（Phase 1 的循环）

| 循环 | 是什么 | 跑法 |
|---|---|---|
| `dev/scratch_image_e2e.clj` | **一个 test var**，真边界（真 socket + SSE），秒级 | `clojure -M:dev -m scratch-image-e2e` |
| `dev/scratch_image_reborn.clj [log]` | 拿**这场会话自己的 jsonl** 走 `sessions/model-view` → `ag/inbound`，三段递进：整场对话 / 只剩带图那条 / 带 `:id` 与摘掉 `:id` 的最小对照 | `clojure -M:dev -m scratch-image-reborn` |

回归用例（真边界，判据是「放下去、再从记录里生出来、这一轮答 `RUN_FINISHED`」）：
`http_test/a-picture-in-the-record-does-not-stop-the-next-run`；
单元级（部件所在的接缝，无服务器无日志）：`ag_ui_test/a-record-s-own-row-passes-through-the-fold-unchanged`。

## 修法：一张表，一行入口

1. **信封字段在折叠的入口摘掉**。`ag-ui/absorbed` 的第一步就是 `(map strip-ag-ui-only messages)`。
   一条消息是一个事实，而这个折叠**在两种拼法下都会跑**——所以"手里这份是哪种拼法"不许取决于信封。
2. **`provider-parts` 一张表**：键是**输入侧**的部件类型，值说它出来是什么（`:out`）与怎么带过去
   （`:carry`）。**`:out` 等于键就表示这个部件已经是厂商的**——于是"翻译表"与"能不能 carry 的判据"
   是同一张表，**一张和翻译不一致的 tell 不再写得出来**。它同时收 `image`（AG-UI 的拼法）与
   `image_url`（厂商自己的，也是记录里那条回来的路），因为两条路都要走这里。表外的类型**按名字拒绝**
   （`:document` 那条老用例照旧红得有理）。
3. **`ag/strip-identity` 退休**。它的活就是入口那一行，而入口是**两条路共用的**：
   `replay/history`（重建）与活着的会话（`sessions/model-view` → `ag/inbound`）。
   老写法把它挂在 `history` 一个调用点上，活着的会话不认识它——这正是这个 bug 的另一半。

## 不变量（用例钉住的）

- `ag/provider-messages` **幂等**：`(fold (fold xs)) = (fold xs)`——记录是它自己写的，读回来必须原样。
- 记录里的一条 `message` 行喂回折叠 ⇒ **一个字节不变**；AG-UI 的部件照旧被翻译（幂等不等于什么都放过）。
- 厂商数组里**没有**信封字段（`:id` / `metadata` / …）。
- 表外的部件类型**按名字拒绝**，句子里带着那个名字。

## 落地时套件里的红（不是这一改动带的）

`clojure -M:test -m harness.test-runner` 在同一提交上 A/B 过一次
（`.worktrees/image-url-baseline` = 干净的 `852fc88`，与改动树各跑一遍 `http-test` + `claims-test`）：

| 用例 | 基线（无此改动） | 改动树 |
|---|---|---|
| `the-record-holds-exactly-two-kinds-of-row`（3 条断言） | 红 | 红 |
| `rebuild-closes-a-mid-run-log-and-refuses-a-corrupt-one` | 红 | 红 |
| `a-second-jvm-owns-a-conversation-until-it-goes-away`（3 红 + 1 错） | 红 | 红 |

失败集**逐条相同**（基线 107 用例 / 7 红 1 错；改动树 108 用例——多的那条就是本页的回归用例——
仍是 7 红 1 错）。两条的机制都查清了，**都不是这一改动的事**：

- **`two-kinds` 是"读得早"。** 它读原始字节时只等"有一条 `message` 行"，而 prompt 那条行在 run 的
  第一帧**之前**就落了——这是这条用例自己断言并且过着的（`prompt` 排在 `RUN_STARTED` 前面）。
  于是 `until` 可以在 run 还没开始时就返回，接下来那三条要的终帧 `RUN_FINISHED` 常常还没写下去
  （邻居 `wait-for-recorded` 的注释说的就是这条滞后）。单跑这条用例是绿的、整命名空间跑才红。
  改法是一行：等的判据换成**终帧那一行**，而不是"有任何 `message` 行"。
- **`claims-test` 杀错了进程。** `start-child!` 起的是 `["clojure" … "-M" script]`，`Process` 句柄
  指的是**启动器**；而子进程写回的那个 pid 是 `(.pid (ProcessHandle/current))`，即 **JVM 自己**的
  （`child-form` 的注释把这条区别写得很清楚）。用例 `(.destroyForcibly killed)` 杀的是启动器，
  紧接着问的却是那个 JVM 死没死——本机上启动器只是壳，JVM 活着：跑完三次，机器上留着三个这样的
  孤儿（pid 45724 / 45820 / 49756，各自 `clojure.main … child2.form.clj`；已清理）。它们也是这条
  用例"下一个进程接手"那一步失败的原因。要杀的是那个 pid 自己（或整棵进程树），不是启动器。
  这两条红都在**别的线**上：没有伴这一次改动落，是因为它们要各自一个决定，不是顺手能改的。

## 相关

- `.scratch/jsonl-two-kinds/spec.md`（票 02 读侧那条判据被这一页推翻，原写法留在那里作对照）
- `.scratch/composer-image/spec.md`（图片部件怎么进来的：客户端 `image` → 厂商 `image_url`）
- `docs/architecture/edge.md`（「两种方言，出口一种」那段）
