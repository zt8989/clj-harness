# spec: `write` 的答案不带内容 —— 写不是读

**这是对已落地的 `hashline-edit` 的一次复议。** 锚点那套一个字不动——铸造、归属、已展示、陈旧判定、
`read` 的 `a3f9│content` 行、`replace` / `insert` 的载荷、`anchor_grep` 的命中行。动的是 `write`
**成功之后**交回去的那一段：今天它把文件开头 20 行的带锚点行交回来（`:auto-read`，默认 `true`），
本特征把它拿掉，只剩一行「写了多少、写到哪里」加一句「锚点已释放，去 `read`」。

复议（2026-09-17，牛总）：**`write` 不返回内容。**

**参考**：omp（`can1357/oh-my-pi`）的 `write`，2026-09-17 读的原文：
`docs/tools/write.md` 的 Outputs 一节、`packages/coding-agent/src/tools/write.ts:452-470`、
`docs/tools/edit.md:142`。

**一句话**：写完之后，模型手里不该多出一份**它没有读过的内容**。

## 依据：omp 为什么不这么做，以及它是对的

omp 在 hashline 显示模式下，`write` 的答案只比普通回答多**一行**：`[<path>#TAG]`（整文件的版本戳，
由规范化内容算出，可在会话的快照库里解析），接着还是那句 `Successfully wrote N bytes to path`。
内容一个字不返回。更要紧的是它**怎么记**这次快照——`write.ts:452-470` 的注释原话：

> but with EMPTY seen-line provenance: a write displays no numbered lines, so anchored edits against
> this tag must first see the anchor content (the patcher rejects them with an inline reveal).
> **Authoring content is not knowing its line numbers.**

它把刚写过的文件记进快照库（所以 tag 能解析、陈旧判定有依据），但**seen 行区间是空的**：
紧接着的锚点编辑会被拒（`edit.md:142`：anchor「outside the recorded seen-line ranges」），
那次拒绝顺手把内容亮出来。`read` / `grep` 是唯一能让人「看见」行的动作。

本仓今天的形状为什么是现在这样：`hashline-edit` 那张票的 `:auto-read` 是给「刚写完就改开头」
这条路径省一次往返的，而且它记成「已展示」的行**确实是展示过的**——它不是撒谎，
它只是把「可编辑」的授予限定在头上 20 行。三处代价：

1. **一个被读成「整份都能编辑」的有界授予。** 第 21 行以后照样要 `read`，页脚的 `offset=`
   说了这件事，但那是答案末尾的一句注释，不是一笔交易的边界。模型对「我刚写完，我知道里面有什么」
   的自信，与它真正能编辑的范围不一致。
2. **每次 write 都付一段 token**，包括**写完之后根本不打算改**的那些调用（写日志、写配置、
   一次成型的文件）——那是常见情形，不是罕见情形。
3. **它是唯一一处让 write 的答案变成「读」的地方。** `write` 的 docstring、`perform!` 的次序、
   锁的顺序，全是围着它组织的：`immutable-data/03` 那半张死锁票的现场（`write.clj` 先拿 path 锁、
   再经 auto-read 进 `serve/read!` 拿 session 锁）就是它。

**「返回完整内容」更不行**：刚写出去的东西原样带锚点再吐一遍，是把同一份 token 付第二次，
而且对几千行的文件是无界返回。omp 也不这么做。

**本仓没有 omp 的那一行 tag 可照抄**：它的地址是行号 + 整文件 tag，所以一行 tag 就够模型指任何一行；
本仓的地址是**逐行锚点**、陈旧判定停在行上，「存在」与「展示过」是两件事
（`store.clj:56`），`read` 的输出里也没有文件头。所以照抄那一行的收益是零，
能照的只是它的**立场**：write 的答案只报事实。

## 撤销

- ~~`harness.edn` 的 `:editing {:auto-read true}`~~
- ~~`write` 答案里那一段头 20 行的带锚点行（`auto-read-lines` = 20）~~
- ~~`:editing` 的 `:auto-read` 这个键本身~~

## 停掉了什么（写清楚，别让它悄悄消失）

1. **「写完就能改开头」这一次往返。** 没了：写完想改，现在要一次 `read`。接受，理由与 omp 同源——
   作者写的是**内容**，不是行号；把「我刚写的」当成「我看过的」是一个便宜的假设，而它的失败方式
   （编辑一个自己以为知道、其实没看过的行）正是锚点这套东西存在的理由。
2. **`:auto-read` 这个键，连键一起删。** 一个开关的另一半值就是新行为，留着它就是在没有第二种行为
   的情况下留了第二种答案。本仓对「没人读的键」的立场本来就是**指名失败**
   （`editing.clj` 的 `check-known-keys!`：`KEYS NOBODY READS ARE A FAILURE IN EITHER FILE`），
   所以写 `:auto-read` 的 `harness.edn` 会得到一句说到键名与文件的话，改法就是删掉那一行。
   **代价与事实**：这台机器上**没有** `~/.clj-harness/harness.edn`（实测，文件不存在），
   所以今天没有谁的配置被这条失败打到；唯一会写这个键的地方是 `harness.edn.example`。
3. **`:editing` 逐键合成的例证要换一个键。** `editing.clj` 的 docstring、`README.md`、`CONTEXT.md`
   都拿「项目级只写 `{:auto-read false}`」当那个例子，`docs/architecture/skills-and-instructions.md`
   还借了它一次。机制一个字不动——逐键合成仍然付得起（`{:anchor-grep false}` 之类同价）——
   只是例子换成真键。**这一条是净损失为零的编辑**，但漏掉就会留下指向一个已不存在的键的说明书。

## 决策

1. **锚点模式下 `write` 的答案是两行**：`wrote N chars to PATH`、加上「该文件的锚点已释放，
   读一遍拿现在的锚点」。这就是今天 `:auto-read false` 那一支，成为唯一的答案（文案已经写好，不新造）。
2. **删掉 `:auto-read` 的整套**：`defaults` 的键、`vocab` 的校验条目、`auto-read-lines`、
   `auto-read-note`，以及 `perform!` 的 `config` 参数（它只为这一个开关存在）。
   `str-replace` 模式下 `write` 本来就没这些，一个字不改。
3. **工具描述跟着改**（`write-anchor-description`）：今天那句「The answer shows the top of the file
   it just wrote with the anchors that name those lines now, so you can edit what you wrote without
   reading it back」删掉，换成「锚点已释放，要锚点就 `read`」。描述只讲今天真会发生的事。
4. **`write` 之后只拿 path 锁。** `forget-file!` / `clear-undo!` 走的是 DB 事务、不碰 session 锁，
   auto-read 一走，`perform!` 的 lock 区里就没有第二把锁了。**这是死锁面的净减少**，
   记在跨特征对照里。
5. **UI 一行不改。** 答案文本是通用渲染，没有专门的 write 组件。

## 非目标

- **不搬 omp 的 `[path#TAG]`**（依据那节）：本仓没有文件级版本戳，交回一行 tag 模型拿它做不了任何事。
  真要往那个方向走，得先有「文件 tag」这个概念，那是另一个特征。
- **不动 `edit` / `replace` / `insert` 交回改动行的行为。** 那是**读**的结果（改动后的行真的展示给了模型），
  而且它服务的是「同一处连着改两手」——与本特征撤掉的东西不是一件事。
- **不动 `read`、不动锚点本身、不动 `:editing` 的逐键合成机制、不加新参数。**
- **不做「write 之后可选地读回来」的第二个开关**：那正是被撤掉的东西，换个名字回来不算复议。
- **不动 `.scratch/immutable-data/` 的任何文件**：它的 spec 与票面此刻正在别处被改（工作树里有未提交改动），
  这一侧的记录写在本文件的跨特征对照里，落地那天由那一侧跟着改。

## 验收主线

一条真会话（或直调 `run!` 的测试），四件事看得见：

1. 锚点模式一次 `write` → 答案里**没有 `│`、没有内容行**；紧接着用**旧锚点** `replace` → 指名「先 read」。
2. `harness.edn` 里写 `:auto-read` → **指名失败**，话说清这个键不认识、去哪个文件删；
   `grep -n auto-read harness.edn.example` 无输出。
3. `write` 的**描述**不再承诺交回锚点（锚点模式的描述里有「released」、没有「shows the top」）。
4. 全量：`timeout 900 clojure -M:test -m harness.test-runner` 的失败用例名与基线一致；
   `cd ui && npm test` 条数与基线一致（本特征不碰 `ui/`）。

## 跨特征对照

- **`.scratch/hashline-edit/`（已落地）**：本特征是它的复议。落地那天要往**它的** spec 加一段
  **加注的复议**（旧话不动、被推翻的那几行划删除线），至少这三处：`:34`（示例块里的 `:auto-read true`）、
  `:449-455`（「`:auto-read` 才是这一票的重点」那一段）、`:478-480`（测试清单里 auto-read 的几条）。
- **`.scratch/immutable-data/`（已立票未落地）**：`03-session-lock-outer` 的现场表里，
  `write.clj` 那一行就是 `auto-read-note` → `serve/read!`；本特征落地后 `write` 只剩 path 锁，
  **那半张票的现场消失**（`undo` 半边照旧，它才是那张票剩下的全部内容）。那张票验收里
  「auto-read 的三条语义都不许动」同时作废。本特征**不动**它的文件（见非目标）。
- **`.scratch/edit-merge/`（立票未开工）**：它把「`read` / `write` 的 `:describe` 换脸」当 `edit` 的先例，
  本特征只换 write 描述的文字、不动机制，先例仍成立；它非目标里那句「不动 `write` 的定位」照旧。
- **`docs/architecture/kernel.md` 的模式表**与 `client.md` 的工具表：`write` 仍在「两种模式共用」那一列，
  一个字不改（`ui/` 也不改）。

## 交付顺序

一张票，完整可交。没有前置：它删的是落地代码，不是加新机制；也没有哪张在办的票压在它后面
（`edit-merge` 不动 write，`immutable-data` 只是被它缩小）。

| # | 票 | Blocked by | 交付什么 |
|---|---|---|---|
| 01 | `write` 的答案不带内容：`auto-read` 退场 | — | `write.clj` 删 auto-read 三件套、答案只剩释放通知、`perform!` 少一个参数；`editing.clj` 删键与校验；工具描述换句；四条用例退场、两条改写；`harness.edn.example` 与三处文档例子换键；两套全量 |

## 状态

**立票，未开工**（2026-09-17）。基线（`main` @ `8cd325f`，立票当天实测）：

- 后端：`timeout 900 clojure -M:test -m harness.test-runner`
  → `Ran 855 tests containing 11252 assertions. 0 failures, 0 errors.`（`EXIT=0`）。
- 前端：`cd ui && npm test` → `Test Files 1 passed (1)` / `Tests 31 passed (31)`（退出码 0）；
  `EXPECTED_CASES` 钉在 31（`ui/test/ui.test.ts:62`）。本特征不碰 `ui/`。

**这台机器上的读法（立票当天撞到，记下来免得下一个人误判）**：同一条后端命令在**并发**下（当时工作树里
另有两轮别人的套件在跑）跑出过 `1 failures` 加一句
`ISOLATION FAILURE: …/.clj-harness/harness.db changed during this run: [12349440 …] -> [13545472 …]`；
紧接着重跑是同一组数字、`0 failures, 0 errors.`、`EXIT=0`，守卫没有再响。

那不是树里的问题，而且本仓已经把它钉过了：`layer-layout` 的 spec（`:322-332`）用硬证据说明——
这台机器上**有活的 app** 在用**真** `~/.clj-harness`（证据是另一个工作区 `win-ai-harness` 的会话 jsonl
被写，而离线套件造不出那种 thread id），而隔离检查比的正是开发者的真库，所以它的写能正好压在别人的
测试窗口上（`trajectory-injection-once/spec.md:72` 也记过同一条）。**那一次的失败用例名没留下来**
（输出被 `tail` 截掉了），所以本文件的基线只认第二遍那两行；落地那天若又撞见守卫，
照 `layer-layout` 那条办：先看它打印的 before/after 再判断，别急着当成自己写红的。
