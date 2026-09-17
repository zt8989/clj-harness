# 04 — 收口：现状文档与全量

**What to build:** 把「内核自己的行现在是 `<project>` + `<env>`」写进现状文档，把复议记进历史，
跑全量。这一票不改行为。

**Blocked by:** 03

**Status:** ready-for-agent

## 要核的地方

| 地方 | 该说什么 |
|---|---|
| `docs/architecture/hooks.md` | 内建行的表：`<tools>` / `<provider>` 两行删掉，换成 `<env>`；`builtin:tools` / `builtin:provider` 的 id 不再存在。那张表下面「`<tools>` 报的是 wire 上那套」那段随之退场 |
| `docs/architecture/overview.md` | 「`prompt.md` 里那句工具枚举会过时，而 `<tools>` 块不会」——换成 `<project>` / `<env>`：**按事实现算的块不会过时**，理由不变 |
| `docs/architecture/layers.md` | 若它按行点名了内建的三条，跟着改（`harness.infra.shell` 现在多了一条解析链，也在这页的归属里） |
| `README.md` | 若有段落讲「开场时模型被告知什么」，跟着改；README 是入口页，机制细节归 `docs/architecture/` |
| `docs/architecture.md` | 快照点与「在办」段：本特征落地后这条从「在办」里拿掉（**如果它被写进去过**） |

## 复议记录（本仓的规矩，不是可选项）

- 在 `.scratch/system-prompt-blocks/spec.md` 末尾追加一条**带日期的复议**：它的三条内建行被本特征
  改成两条，`<tools>` / `<provider>` 为什么没必要（tools 在接口调用时自描述），以及**它的决策一条都没被推翻**
  ——被改的是行的集合，不是机制。原文**不改写**，只追加与指路。
- 在 `.scratch/session-context/spec.md` 补「已验证到什么程度」：每张票落在哪个提交、全量数字、
  以及**实现时撞出来而票面没写的事**（本仓 spec 的惯例，也是留给下一个人的唯一线索）。

## 验收

- [ ] `hooks.md` 的内建行表与 `harness.cap.system-prompt/install!` 的表**逐行对得上**（id、标签、说什么）
- [ ] `overview.md` 不再引用 `<tools>` 作为「不会过时」的例子
- [ ] `README.md` 若提到开场内容，与实际一致；未因此新增机制叙述
- [ ] `.scratch/system-prompt-blocks/spec.md` 末尾有本特征的复议段，且它的原文一字未被改写
- [ ] `.scratch/session-context/spec.md` 有落地记录；票面按惯例删除
- [ ] **离线全量 `clojure -M:test -m harness.test-runner` 退出码 0**，报数带上分支与提交
- [ ] `cd ui && npm test` 与 `cd ui && npm run build` 全绿（本特征不加帧、不动 UI，所以这是**回归**）
- [ ] 真机看一次：新会话里问模型「你现在跑在什么系统上、你的命令交给谁、有没有 rg」，
      它答得出来——**这块的全部价值就是这句话**。没有人跑过就不写「过了」
