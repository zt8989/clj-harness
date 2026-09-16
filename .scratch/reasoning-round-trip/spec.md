# spec: 思考模式的往返 —— 把 `reasoning_content` 送回给厂商

**一句话**：开了思考（`reasoning_effort`）的厂商要求**历史里每条 assistant 消息都把 `reasoning_content`
送回来**；这条链在「模型某一轮没产出推理」时断掉——我们把它**拼没了的那个键**还回去，就不会 400。

## 病因（真机取证，不是推测）

主人的 `kongming` provider（`https://model-info.forwe.store/v1`、`deepseek-v4.1-flash-expires-on-0910`、
默认档 `reasoning-effort "high"`）报：

```
HTTP 400: {"error":{"message":"The `reasoning_content` in the thinking mode must be passed back to the API." …}}
```

两次真请求定住了它（证据在 `evidence/`，用的是家里已配好的那个 provider）：

1. **厂商每一块 delta 都带 `reasoning_content` 键——包括空串**（`r1-tool-call-round.sse`：12 块 delta 里
   有 12 次这个键，值全是 `""`，`content` 12 次，`tool_calls` 10 块）。所以「模型这一轮没有推理」在我们
   这边**表现为一个空串，不是一个缺席的字段**。
2. **我们把它丢了**：`harness.kernel.llm/consume-sse` 组装返回消息时是
   `(pos? (.length think)) (assoc :reasoning_content …)`——推理文本为空就**不加这个键**。于是那一轮的
   assistant 消息进历史时没有键，下一轮请求（带 tools + thinking）就被厂商按上面那句话拒掉。
3. **同样的历史，补一个空串就过**：`r8-request-no-reasoning.json`（第二条 tool-call 消息没有那个键）
   → **400**；`r9-request-padded-empty.json`（同一份历史，只把那一条补成 `reasoning_content: ""`）
   → **200**，正常流式返回。**这条修法因此是已被厂商验过的，不是猜的。**

一句话：**厂商要求的是「键在」，而我们用「值为空 = 没有这个键」去表达「这轮没有推理」。**

## 决策

1. **修法：请求侧补齐。** 当这次请求处于思考模式（resolved provider 带 `:reasoning-effort`）时，
   历史里**每条 assistant 消息都必须带 `reasoning_content`**：有就原样带，没有就补一个空串
   （空串是那轮的真实情况——它确实没有推理内容，我们**不编**内容）。
2. **补在请求发出去之前、`message` 审计行写下之前。** `message` 行的契约是「LLM 真实看到/返回的
   provider 形状消息，逐字」；如果补在 `llm/stream!` 里，日志就会记一份没补的、与实际发出的不同。
   所以补齐这一步要么发生在拿 provider 组装这次 run 的消息列表那一步（`harness.edge.http`，
   provider 与消息都在手上），要么由一个 `llm` 的 helper 做、但调用点必须在写审计行之前。
3. **只对思考模式生效。** 没有 `:reasoning-effort` 的 provider 一个字都不改：那家不进入 thinking mode，
   也就没有这条要求，无端加一个键是另一种自作主张。
4. **不改 `consume-sse` 的「非空才带键」。** 让返回消息在推理为空时也带上空键，看起来更「忠实」，
   但那会改历史形状、动一条既有用例（`llm_test` 的「没有推理就没有这个键」），而且**修不了**客户端
   送回来的历史（下一轮的历史来自客户端，不是我们观察到的）。修在请求侧一处，覆盖面最大。
5. **同一类失败的另一处口子顺手堵上**：`harness.edge.ag_ui/provider-assistant` 是白名单重建，
   客户端**直接把 `reasoning_content` 当字段带上来**时它会被丢掉（与本次病因不同，但同样会 400）。
6. **厂商的这个怪癖要变成套件里的一条可复现用例。** `harness.fake` 增加「严格思考模式」：某一轮的
   tool_call 不带推理，且**任何处于思考模式、assistant 消息缺这个键的请求**都回 DeepSeek 那句话。
   于是这类 400 从此在套件里是可复现的，而不是只能等真机撞上。
7. **不从 jsonl 重建历史来修这件事（考虑过，否掉）。** 这条路是存在的：`harness.edge.replay/resume!`
   就是「按日志重建历史 + 追加新一轮 + 跑」，`records->messages` 是它的读侧，测试也断言重建出来的历史
   带 `reasoning_content`。但它**修不了这个 400**，因为**记录里是同一个洞**：`message` 行记的是**我们
   组装出来的** provider 消息，而我们的病就是组装时把空的 `reasoning_content` 丢了——你那份 jsonl 里
   那几轮正是 `reasoning=NONE`。照它重建，递给厂商的还是那份没有键的历史，同样 400；真修还是要补一个
   空串（就是决策 1）。另外它也不可能是「只从 jsonl」：**新一轮**（以及客户端上的编辑 / 重试 / 删除）
   根本还没进日志，只能「重建 + 追加」，于是两个来源并存，还要定去重与顺序规则。最后它撞两条铁律：
   铁律 1（日志是记录，run 中永不读自己的日志）与铁律 3（**客户端持有会话**，服务端每轮从请求里现收
   全部历史）。`resume!` 自己的 docstring 也写明它是作者侧动作、不是 run 路径、而且**不追加日志**。
   所以：**保持铁律，修在请求侧一处**。真要「让模型拿回自己那段推理原文」（比空串更好的那件事），
   它是一个独立特征，形状应该是「客户端仍决定有哪些消息，日志只用来把 provider 独有字段补回它们身上」，
   并且需要一条匹配规则（`message` 行不带 AG-UI 的 message id）——这一家厂商不需要（空串已被验证接受）。

8. **不动的东西**：AG-UI 帧一个不加（推理仍然只走 `REASONING_*`）、tool 调用形状不变、
   不新增 jsonl 行种类、不动 CORS。

## 非目标

- **不替厂商编推理内容**：补的是空串，不是上一轮的推理、不是摘要。
- 不做「关掉思考」那条路（主人已选：保住思考能力）。
- 不改历史里**已有的** `reasoning_content`（那是模型真的想过的东西，逐字留着）。
- 不追厂商为什么在这几轮不发推理文本（那是它家的事，我们按它自己的要求把键还回去就够）。

## 验收主线

在家里那台 `kongming` 上真跑一轮**两回合的工具对话**（第一轮模型调工具、第二轮继续），
全程不再出现 400；套件里严格 fake 的那条用例由红转绿，且把请求侧的补齐去掉会让它立刻变红。

## 交付顺序

| # | 票 | 依赖 | 一句话 |
|---|---|---|---|
| 01 | 脚本 provider 学会「严格思考模式」 | — | 把这类 400 变成套件里可复现的一条用例 |
| 02 | 请求里把缺的 `reasoning_content` 补成空串 | 01 | 真机上已被验证的那一条修法，补在审计行之前 |
| 03 | 客户端带上来的 `reasoning_content` 字段别丢 | — | `provider-assistant` 的白名单漏了这一个字段 |
| 04 | UI 套件补「第二回合」 | 01 | 客户端把推理送回来这条路要有套件钉住 |
| 05 | 文档与全量验收 | 01–04 | 把这条要求与那两次取证写进文档，端到端走一遍 |

01 与 03 互不阻塞（一个动测试替身，一个动入站白名单）；02 要 01 的红用例；04 要 01 的严格 fake 能在
e2e 里被选中；05 收口。

## 状态

- 票面立于 2026-09-16，`main` = `7f345bb`（本特征落地在它上面，另有一个会话的在办改动不属本特征）。
- 动工前基准：`clojure -M:test -m harness.test-runner` → **715 tests / 10561 assertions /
  2 failures**（两条常驻的 JDK 25 失败，见 `custom-providers/spec.md` 的状态一节）；
  `cd ui && npm test` → **14 passed**（UI 侧另有真 Chromium 走查）。
- 取证用的两次真请求各花了几十个 token，用的是家里那台 `kongming`；原始证据在 `evidence/`。

## 落地记录

五张票全部落地（分支 `reasoning-round-trip`，worktree `.worktrees/reasoning-round-trip`，
起点 `7f345bb`）。**未提交**——提交/合并按主人指示走。

**量的数**：

| 跑的是哪份 | 测试 | 断言 | 失败 |
|---|---|---|---|
| 动工前（`7f345bb`） | 715 | 10561 | 2（常驻的 JDK 25 那对） |
| 落地后 | **719** | **10585** | 2（同一条名单） |

前端：`cd ui && npm test` → **15 passed**（`EXPECTED_CASES` 14 → 15）；`npm run typecheck` 0 error。

### 修法比票面写的多了一处，是测试逼出来的

票面把修法写成「在请求侧补齐」（02 一张）。写下去才发现**请求侧补不了这一次**：
一轮 run 内部的历史是 `loop` 拿着原子长出来的，而那个 assistant 消息由 `consume-sse` 组装——
边在 run 开始前补一次，够不到它；补在 `stream!` 里够得到，但那样 `message` 审计行（契约是
「LLM 真正看到的，逐字」）就会记一份与发出去的不一样的东西。**测试直接把这一点指了出来**：第一条
端到端用例红着，报的正是真厂商那句话。

所以最终是**两半，各自成立**：

1. `llm/consume-sse` **按「厂商提到过这个字段」保留它**（空串也保留），而不是按「有没有文本」。
   这是**根因**那一半：真厂商每块 delta 都带这个键（空值表示这轮没有推理），旧写法把它丢了，
   下一轮就 400。同一条规则也让「从没提过这个字段」的厂商仍然不凭空多出一个键。
2. `llm/thinking-mode-history` 在**边组装这次 run 的消息列表时**把缺的补成空串（只补空串、不编内容、
   只对思考模式生效），由边在写审计行之前调用。这一半修的是**从客户端回来的历史**——那里缺字段
   不是我们的组装造成的。

两半都各有一条用例能让它变红（见下）。票面 02 的措辞因此偏窄：它描述的是第 2 半。

### 红过、也绿回来的三处（每条都真跑过）

| 把什么去掉 | 哪条用例变红 |
|---|---|
| 第 1 半（`consume-sse` 的「提到过就保留」） | `llm_test/omits-empty-fields`（2 条断言） |
| 第 2 半（边上的补齐） | `http_test/a-client-history-that-lost-the-field-is-repaired-on-the-way-out` |
| `provider-assistant` 的**两条**来源（折叠 + 字段） | `ui/test/suites/client.ts` 的第二回合用例 |

第三条有个值得记住的发现：**只去掉其中一条来源，用例不会红**——不是用例弱，是这条链上两条来源
互为备份：这个客户端既发 `reasoning` 角色消息，又把 `reasoning_content` 带在 assistant 消息上。
要证明用例有效，得两条一起去掉。

### 动手时才知道的几件事

1. **测试替身必须复刻厂商的「提到过但是空」**。`harness.fake` 的 turn 原来是 `(seq reasoning)` 才带键，
   于是它自己就把那个空值丢了——用它去验「厂商要求送回去」这件事，永远只能验到一半。现在
   `(contains? turn :reasoning)` 才算提到过，与真 wire 同一条规则。
2. **e2e rig 的 pin 要带上 `:reasoning-effort`**，否则严格档形同虚设：**拒与补都以上下文里的
   `:reasoning-effort` 为条件**（厂商那句话说的就是 "in the thinking mode"），而 pin 顶掉的是整个
   provider，config 里那个旋钮根本走不到。
3. **UI 用例第一版因为错误的理由通过了**：日志里既有「请求带过去的消息」也有「厂商返回的消息」，
   而脚本 provider 返回的那条自己就带着推理文本——于是断言 `reasoning_content === first` 被返回侧
   满足了，跟往返一点关系都没有。现在按**最后一个 `input` 行切片**，只看那一次请求带过去的消息。
4. **不加断言的 python 替换会静默不生效**：两次「去掉两半后仍然全绿」其实是替换没匹配上（文件已被
   上一次还原）。教训很便宜但很值：脚本化的改动要断言，别靠肉眼看输出。

### 真机验收（主人那台 `kongming`）

一轮**带工具调用的对话**（正是当初 400 的那个形状）在真厂商上跑完：

- 帧：`REASONING_START`×2、`TOOL_CALL_ARGS`×2、`TEXT_MESSAGE_*`×3、**`RUN_FINISHED`×1，
  没有任何 `RUN_ERROR`**；
- 会话 jsonl 里 8 条 `message`：两条 assistant 分别带厂商自己的推理文本，**没有一条缺这个字段**；
- 花钱：几十个 token（主人为此明确授权过一次）。

**没做的那半**：票面 05 还要求「再跑一轮**关掉思考**的对话，确认那条路上没有多出这个键」。
真机上要做这件事得改主人那份**正在用**的 `config.edn`（他的默认档就是 `reasoning-effort "high"`），
而用内联 provider 绕开它又会走全局 `HARNESS_API_KEY`（不是这家厂商的钥匙，会 401）——所以这一半
留给离线用例（`http_test/a-vendor-not-in-thinking-mode-is-left-alone` 与 `llm_test` 的单元断言），
真机不改他的配置。
