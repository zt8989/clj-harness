# 04 — 请求侧：一次模型调用的边界，与它照发出的那张表

**What to build:** 每一次模型调用在记录里留下起点与终点，起点带上**发出去的那张工具表**；
右侧面板长出 `工具` 页签，显示选中那一轮**照发出**的表。

从用户视角：能回答「这一轮模型手里有哪些工具、它们的描述到底是怎么写的」——那是排查
「模型为什么不按我说的调工具」时唯一有用的东西；而且它就是当天真的发出去的那一份。

**Blocked by:** 03（右侧面板与轮的选择在那一票里）

**Status:** ready-for-agent

## 验收

- [ ] `harness.event` 加两个构造器：`:model/start`（`{:model … :base-url … :reasoning-effort … :tools […]}`）
      与 `:model/end`。`:model/end` 在本票里**载荷是空的**——它此刻的全部含义就是「这次调用到这里结束」，
      载荷在 05 里长出来。一条只有 `:ts` 的审计行不是半成品。
      顶部那句「11 种事件」跟着改（13）。
- [ ] **请求的三个参数一并记下**（`model/start`）：`:model`（会话中途换过就是换过之后那个）、
      `:base-url`、以及**有才记**的 `:reasoning-effort`。
      今天这三个要折 `provider/init` + `provider/changed` 才知道当时是谁，而它们是**这次调用的身份**——
      记在标记上，一行自足，读侧不必按序折一遍才敢说「这次发给谁」。
      记录**照原样**（字段名照 provider 的，不重命名）；`base-url` 不是秘密：`provider/init` 里本来就是它。
- [ ] `harness.ag_ui` 的 `convert` **明说这两种没有帧**（跟 `:tool/pre-execute` 那一组放一起，
      同一段注释的立场）：**AG-UI 帧一个都不加**。别让它掉进一个没有分支的 `case`，更不要为了「让 fold 完整」
      给它发明一帧——协议不动是这个仓库的硬约束。
- [ ] `harness.http` 的 `lifecycle-record` 加两条映射：`"model/start"` / `"model/end"`
      （就是那三条工具审计行的同一个形状）。除事件流之外**不加别的写入点**：
      日志只有边写（`harness.http/log!`），内核不发事件就没有这行。
- [ ] `harness.llm/stream!` 里 `(tools/specs thread-id)` **只解析一次**，同一个值**既进请求体、又进
      `model/start`**。这让「照发出的样子」是**构造正确**的，而不是「再解析一次、碰巧相等」——
      docstring 要写出这句话，因为下一次有人把 `tools/specs` 挪走时，这句话就是他会毁掉的东西。
- [ ] 时间戳**不新记字段**：行本来就有 `:ts`（决策 7）。本次调用属于哪一轮、是第几次调用，
      也**不记**——那是数序，记一份就是同一件事实的第二份（记录清单下那一段）。
- [ ] **假 provider 也发这两条**（`harness.fake` 的 `:fake` 分支）。替身漏发就不是替身：
      否则离线测试根本折不到 `model/*`，本票与 05、06 会退化成只能起真机才能验。
- [ ] 读侧：轮里长一个 `calls` 数组，每次模型调用一条：

      {"index": 1,
       "items": [ …, {"kind": "assistant", "call": 0, …} …],
       "calls": [{"index": 0, "model": "deepseek-…", "tools": [ …照发出的… ]}]}

      调用序号**由秩序定**（一个 run 里第 n 条 `model/start` 就是第 n 次调用），不往记录里塞计数器。
- [ ] 右侧面板长出 `工具` 页签：显示**该轮第一次**模型调用发出的那一份；轮内换过就再列一份，
      标题写清是第几次调用——与 02 给 system 条定的规矩一模一样（**变了就必须看得见**）。
      表按原样的 JSON 展示（可折叠、可复制、不重排不加注释）。
- [ ] 表**每次调用都记一份正文，不按摘要去重**：`message` 行已经是逐字节的立场，
      去重会把「照发出的样子」变成「照摘要解析回来的样子」，代价是日志变大——
      这与「每个 run 记一整份 system prompt」是同一种代价，接受它。
- [ ] 老日志（没有 `model/*` 行）照旧能读：`calls` 为空、`工具` 页签如实说「这一轮的记录里没有表」，
      **不猜、不用今天的表冒充当时的**（这是本特性唯一会撒谎的地方，别撒谎）。
- [ ] 现有把工具生命周期事件从观测里滤掉的地方（`loop_test.clj` 的 `seen`）跟着处理：
      两条新事件要么也滤掉、要么断言之，别让既有断言的形状被悄悄改掉。
- [ ] 测试：`model/start` 里的表与 `harness.tools/specs` 的返回值**逐字节相等**（一条断言，就是本票的脊柱）；
      帧侧断言**一个字节都没变**（`harness.wire` / 既有帧用例全过）；假 provider 驱动的一轮能折出 `calls`。
- [ ] 真机证据补一张：`工具` 页签里的表与当期会话的工具表对得上（`.scratch/trajectory/evidence/`）。
- [ ] `clojure -M:test -m harness.test-runner` 与 `cd ui && npm test` 全绿
      （基线以落地当次为准，报数带上分支与提交）。

## 复议（2026-09-16）：两条审计行已由 `.scratch/composer-status/` 01 落地

**加注，不改写上面的话。** `:model/start` / `:model/end` 两个事件、`lifecycle-record` 的两条映射、
`harness.ag_ui/convert` 里「这两种没有帧」那段注释、以及假 provider 的那一节，都已经由
`.scratch/composer-status/` 的票 01 落地，**行名与载荷字段与上面要求的一字不差**
（`:model/start` 带 `{:model … :base-url … :reasoning-effort …}`，`:model/end` 带 `{:usage … :finish-reason … :model …}`）。

**本条票只剩两件事**：① 往那条已经存在的 `model/start` 上**加 `:tools`**（照发出的那张表，
与 `(tools/specs thread-id)` 是**同一次 resolve** 出来的那个值）；② 右侧面板的 `工具` 页签。
验收里凡是要「加事件 / 加映射 / 改 convert」的条目，落地时会看到它们**已经在了**——那是这一节说的，
不是本票漏了。

时间戳与序号那两条纪律（不新记字段、不记调用序号）已经照着做了：行的 `:ts` 就是时间，
序号由次序定——`composer-status` 的读侧就是这么折的。

## 复议（2026-09-17，记录一半落地，UI 一半仍开着）

**世界变了两次，票面按变化分段读**：

1. **`model/start` / `model/end` 两条审计行、以及 `:model` / `:base-url` / `:reasoning-effort` 三个参数，
   已经由 `composer-status` 落地**（`harness.kernel.event` 的两个构造器、`harness.kernel.loop/model-call!`
   的钳制、`harness.edge.http/lifecycle-record` 的两条映射、`harness.edge.ag_ui/convert` 的「没有帧」）。
   它连「按秩序配对、不写计数器」的理由都写进了 docstring，与本票一致。**这一半不用再做了。**
2. **工具表那一半本日补上**：`ev/model-start` 多收一个 `specs`，由 `harness.kernel.loop/model-call!`
   **resolve 一次**并同时交给 provider（进请求体）与事件（进记录）——「照发出的样子」因此是构造正确，
   而不是再 resolve 一次碰巧相等。`harness.kernel.llm` 不再自己 reach 工具表（那个 require 也删了），
   `:tools` 随 provider map 走。记录照原样：表就在 `model/start` 的 payload 里。
3. **读侧那一半也落地**：轮里长出 `:calls [{:index :model :tools}]`（没有表的那次调用**没有** `:tools` 键；
   记录早于这两行的轮**没有** `:calls`——三种答案各不相同，都不许拿今天的表冒充当时的）。

**仍然开着的**：右侧面板的 `工具` 页签（与 03 的视图一起做）、以及「轮内换过表就再列一份」那条显示规矩。

## 复议（2026-09-17，UI 一半落地）

**这一票到此结束**（记录在上一段复议里已落地）：右侧面板长出 `工具` 页签，显示**该轮第一次**模型调用
发出的那一份；轮内换过表就**再列一份**并标出是第几次调用——与 system 条同一条规矩（变了必须看得见）。

**最后一条空态是两句话，不是一句**：记录早于 `model/*` 两行的，说「这份记录说不了当时桌上有哪些工具」；
有那两行但每次调用都没带表的，说「这一轮没有调用带过工具表」。两种都不是「没有工具」。

## 复议（2026-09-17 晚）

`工具` 页签随 03 的口径改动删掉了，**表本身没有消失**：它是某一次调用的请求，所以挂在**那次调用的
`assistant` 条目**上（`data-slot="trajectory-call-tools"`），点那一行才展开。两句话照旧：
记录早于 `model/*` 两行的说「这份记录说不了当时桌上有哪些工具」，有那两行但这次调用没带表的说
「这次调用没有发工具表」——都不是「没有工具」。

## 复议（2026-09-17 夜）

**表搬到了 `system` 条**（见 03 最新的复议）：提示词里那段 `<tools>` 就是它，工具集变了提示词也变，
所以两样一起出现在同一条上。`assistant` 条只留 `tools: N` 这个计数，不再重复整份 JSON。
折叠列表的展开内容照票面的意思给全：**名字、描述、整份定义 JSON**。
