# 01 — 砍掉 `<tools>` 与 `<provider>` 两行

**What to build:** 内核自己的行从三条变两条——`builtin:tools` 与 `builtin:provider` 退场，
`builtin:project` 留下。组装机制一个字不动：还是同一条表、同一条缝、同一种开关、同样每 run 现算。
随之要逐条改写那些拿这两行当样本的断言，并且把**停掉告诉的三件事**各自安顿好。

**Blocked by:** None — can start immediately

**Status:** ready-for-agent

## 为什么砍

**tools 在接口调用的时候就是自描述的。** wire 上的 `:tools` 数组每次都带着每个工具的名字与描述，
而 `tools-block` 自己也知道这件事（它的 docstring 写着 NO DESCRIPTIONS，因为它不复制描述），
所以它剩下的只是一份**名册**。`<provider>` 同理：那是「本会话服务在谁身上」，不是模型干活必需的常驻事实。

**但「能力一律不必说」不是这条得出的规矩**——自描述的是名册，不是技法。别把这条推得太远。

## 三件停掉的事实，各有去处

1. **本会话关掉了哪些工具** → 按需回答，**今天就已经如此**：调用一个被关掉的工具，缝的
   `disabled-message` 就是一句指名的话，还附带把它打开的确切写法。能被问出来的答案不必常驻。
2. **哪些工具来自外部程序** → **今天那半句是死代码**（MCP 一行都没落地，一个 `mcp__` 工具都没有）。
   它落地时由那一侧负责。
3. **本会话服务在哪个 vendor / model / 思考档** → **净损失，不改写它**。连同那句配方一起没了
   （「想知道由谁服务，问 `harness.cap.providers/active-provider`」是随 `<provider>` 块从 prompt.md 的
   secrets 一节搬过来的，块没了它就没有家）。**接受**：仍然可问、`/api/model` 仍然答，只是不再主动说。

## 要改写的既有断言（逐条列明）

| 用例 | 今天 | 改成 |
|---|---|---|
| `cap/system_prompt_test/the-kernel-registers-its-own-rows-like-any-other-hook` | 行集合含 `builtin:tools` / `builtin:provider` | `#{"builtin:project" "builtin:env"}`（`<env>` 由 03 加，本票先只减） |
| `…/a-builtin-row-is-switchable-exactly-like-a-declared-one` | 拿 `builtin:tools` 当样本 | 换 `builtin:project`（开关的性质不变，只是换个样本） |
| `…/the-kernel-rows-run-first-then-the-file-then-the-session` | 断言 `<tools>` < `<project>` < `<provider>` < 声明块 | 只剩 `<project>` < 声明块 |
| `…/the-tools-block-reports-the-set-that-is-actually-served` | — | **整条退场** |
| `…/a-tool-this-session-registers-appears-and-an-outside-one-is-named` | — | **整条退场** |
| `…/a-tool-this-session-switches-off-stays-visible-and-is-named-separately` | — | **整条退场**（「关闭不是隐藏」这条规矩本身由工具表的既有断言守着，不靠这个块） |
| `…/the-provider-block-reports-the-effective-selection`、`…/the-provider-block-follows-a-mid-session-change`、`…/a-thread-that-cannot-answer-produces-no-provider-block` | — | **三条退场** |
| `…/the-provider-block-never-carries-the-api-key` | 块里搜不到 key | **不许退场，搬**：对**整份组装出来的 system 文本**搜真 key 与 `:api-key`。块走了，这条纪律不能跟着走 |
| `…/the-text-is-a-function-of-the-facts-and-nothing-else` | 逐字节稳定性 | 保住，样本换成剩下的块 |
| `kernel/hooks/install_test/the-kernel-rows-arrive-and-leave-as-one-layer` | `["builtin:tools" "builtin:project" "builtin:provider"]` | `["builtin:project"]`（03 之后加 `<env>`） |
| `edge/http_test` 的三来源在场那组 | 含 `<tools>` / `<provider>` | 含 `<project>` / 声明块；顺序断言同改 |
| `edge/http_test` 的「关掉再打开」 | `session-disable! … "builtin:tools"` | 换 `"builtin:project"` |
| `docs/architecture/overview.md` 的铁律 2 | 说「内建三条」，并拿 `<tools>` 块当「按事实现算所以不会过时」的例子（那句里甚至还有「`prompt.md` 里那句工具枚举」——那个枚举上一个特征就撤了，遗留的半句） | 内建**两条**，例子换 `<project>` / `<env>`；顺手把那半句遗留清掉 |

## 验收

- [ ] `effective-hooks` 里只剩 `builtin:project`（`<env>` 由 03 加），`builtin:tools` / `builtin:provider` 不再存在
- [ ] 组装出来的 system 文本里没有 `<tools>` 与 `<provider>`，`prompt.md` 的冻结开头一字未动
- [ ] **组装机制零变化**：`SystemPrompt` 点、三个来源、`install!` 的装载/卸载、每 run 现算、
      原样进 prompt、退出 2 拒绝这次 run —— 既有断言除上表列明的之外一条不改写
- [ ] 上表每一条都改了，且**改的是断言不是行为**：没有为了让它好写而放宽任何一条主张
- [ ] 「整份 system 文本里搜不到 api-key」这条断言在场且真的能红（拿一个哨兵 key 验）
- [ ] `<project>` 块自身的行为一字不变（绑定 / 重新绑定 / 解绑 / strict 四组用例原样通过）
- [ ] 离线全量 `harness.test-runner` 全绿；`docs/architecture.md` 的「在办」段不动（本特征是落地改动）
