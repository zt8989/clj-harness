# 0004 —— 记录不背工具表：`model/start` 只留签名，锚点比签名不比文本

- **日期**：2026-09-24
- **状态**：**已采纳**
- **边界**：不触碰 `docs/adr/0003-the-feed-is-a-window.md`（以及它修正的 0002）。本条**不改记录格式**：
  行种类（`event` / `message`）、一行一个 JSON、一个写者按发生顺序 append——全部照旧。它只改
  `model/start` 一行 **payload 里有哪些键**，并且读侧**同时认新旧两种拼写**。

## 背景

1. **一次 run 里那张工具表被一字不差地写了几百遍。** `harness.kernel.event/model-start` 把发起请求时
   那张完整工具表写进**每一条** `model/start` 行。thread `bbcd4ae4-…`（129.7 MB / 362,359 行）里，
   672 条 `model/start` 的 payload 合计 **50.2 MB**，占整份日志四成。这张表是 runtime 配置
   （`harness.kernel.tools/specs` 按会话解析出来的），不是记录该背的对话事实。
2. **同一张表变没变，不需要整张表来回答。** 判据是**名字集合**：改一条工具的描述不改变它，加一个或删一个
   工具才改变它（2026-09-24 主人拍定）。名字集合压成一个 SHA-256 就够了，而且内容 hash 会和名字 hash
   给出两个可能打架的答案——所以只留名字那一份。
3. **压力表的锚点判据踩了同一格。** 锚点（拿厂商上次报的 `prompt_tokens` 当基准）只在「信封没变」时成立，
   而它当时的判据里有「锚那次的 system 文本 == 现在这条 system 文本」。system 是**每轮现装**的
   （`harness.cap.system-prompt/assemble`），文本比文本把「谁进了这次组装」这件事比成了一个字符串。
   同一时间还暴露了另一个 bug：压缩那次失败调用的 `model/start`（没有 run id、没有工具表）成了记录里最新
   的模型调用，把锚点从 `usage` 打回 `estimated`（`.scratch/model-surface-and-meter` 票 02）。

## 决策

1. **`model/start` 不再写整张工具表。** 只写 `:tools-names-hash`（工具**名字**集合排序后的 SHA-256）、
   `:tools-count`、`:tools-bytes`（`harness.edge.context/size-of` 的字符数，给上下文圈的 tools 篮子）。
   表为空时不写这三个键——与旧的「没有表就不写 `:tools`」同义。**删掉 `:tools` 键。**
2. **签名在解析工具表的那一处算一次、传进来。** kernel 提供机制（`harness.kernel.loop` 的 `model-call!`
   把签名交给 `event/model-start`，缺省 `harness.kernel.tools/default-signature`）；字节数那半是能力，
   由 edge 实现（`harness.edge.context/tool-signature`）并从 `run-chan` 传进去——`event` 层不自己解析表，
   两个解析会是两张碰巧一致的表。
3. **hook 的身份集合写在 system 那条 `message` 行的信封上**（`:hooks-names-hash`），不写在 `model/start`：
   写 `model/start` 时还不知道系统消息这一步组装了哪些 hook。`harness.kernel.hooks.dispatch/fire` 在
   content 点返回 `:hooks`（matched 声明的 id，按书写序），`cap.system-prompt/assemble*` 把它压成一个 hash。
4. **压力表的锚点比签名，不比 system 文本**：工具表的名字集合 + 路由 + hook 的身份集合三者一致，锚点才采用。
   改一条描述、改 `prompt.md` 都不作废；加删一个工具或一条 hook 才作废。（送达方式分档——`:in-place` 下
   hook 变了不作废——由 `.scratch/instruction-updates` 决定；能力位读不到时按 `:replace` 办。）
5. **模型面只有一份。** 「模型看的」（能直接交给 provider 的数组）是**记录的纯投影**，实现只有
   `harness.edge.replay/model-message` / `model-view` 一处；压缩和压力表都消费它，谁也不许另写一份。
   2026-09-24 那次压缩失败，就是把**客户端面**（`entries`，卡还在里面）当模型面交了出去，请求里带了 25 个
   `{"type":"data"}` 卡，厂商以 HTTP 422 拒收。
6. **整张表写在 system 那条 `message` 行的信封上（`role=system` 的那条记录，键 `:tools`）。** 不是写进
   message 的**正文**——正文里放 `<tools>` 块会让模型读到第二遍、白付 token，主人明确否掉了。信封是
   `log!` 放「属于**行**而不属于它承载的东西」的字段的地方（`:source`、`:hash` 就在那儿），
   `harness.edge.replay/payload` 把信封挡在消息之外——所以整张表（名字 + 描述 + parameters）**记录里回读
   得到、模型读不到**。这是 2026-09-24 主人对「tools 应该写进 role=system」的落地：wire 上的 `:tools`
   自描述，但 **wire 不留**，而这条行留。

   AND THE ROW IS THE RUN'S FIRST `message` ROW: `harness.edge.http` writes the prompt BEFORE the
   action's own entries, so the record's `message` rows come out in the array's own order —
   `role=system` first, then what the person said, then what the run returned. That is what lets
   `harness.edge.trajectory/run-segments` open every run at its prompt.

## 后果

- 记录体积：一次 98-tool 调用的 `model/start` payload 从 ~75 KB 降到 **< 300 B**；thread `bbcd4ae4-…` 上
  `model/start` 总计从 50.2 MB 降到 < 1 MB（`harness.kernel.event-test` 钉住前者）。
- **工具表本身仍然回读得到**（决定 6）：整张表在 system 那条 `message` 行的信封 `:tools` 上，不在正文里。
  「工具表」那一栏按名字 hash 给 `:toolsNamesHash` / `:toolsCount` 分组，回答的是「哪几次调用共用一张
  信封」——名字集合，不是内容。
- 旧记录照读：只有 `:tools` 的记录，读侧在本进程内现算名字 hash 与条数（`pressure/tools-names-hash-of`、
  `trajectory/one-call`），两种拼写给同一个形状。
- **改一条描述不再作废锚点**——这是一条为正确性付的性能账，判据从「文本相等」改成「名字集合相等」。
