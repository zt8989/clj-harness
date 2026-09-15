# 12 — 翻默认：hashline 成为默认实现，prompt 与文档跟上，既有断言重写

**What to build:** 把默认编辑模式从 `str-replace` 翻到 `hashline`。在此之前每一票都是 opt-in 落地的，
既有 189 tests / 930 assertions 一条没动；这一票是一次**有意的、可回退的**默认值翻转，所以要一次说清它
动了什么、以及怎么退回去。

**退回去只有一步。** 在 `harness.edn` 里写 `:editing {:mode :str-replace}`，`edit` 回到工具表，
锚点工具全部离场——这一票之后两种模式都仍是**一等公民**，都仍有完整用例。翻默认不是删掉旧的。

**prompt.md 改成模式中立的说法。** 系统提示是**冻结**的（`harness.memory/prompt` 读一次就固定，
provider 的 prefill 缓存键在这个稳定前缀上），所以不按模式拼两套文本——那会让每次切换模式都冷启一次
缓存，而且 prompt 是**代码资产**（要进 git 历史、要人 review），不是一个运行时派生的字符串。做法是：

- 第 3–4 行的工具清单与 `edit` 的说明改成模式中立的说法，并**点出自省入口**
  （`(harness.memory/editing-mode harness.memory/*thread-id*)`），让模型自己问出本会话在哪个模式；
- 锚点的具体语法**不进 prompt**，留在工具描述里——工具描述本来就是按 thread 送达的、可以随模式变，
  prompt 不行；
- 项目绑定那两段里 `read/write/edit` 的措辞跟着改（`edit` 不再是常在的名字）。

**术语进 CONTEXT.md。** 锚点、已展示行、漂移、批、编辑模式这几个词是本特性的行话，第一次出现就在
CONTEXT.md 里定义，后面所有 issue 与代码注释统一用它们；`edit` / `replace` / `undo_last_replace`
不叫「工具」以外的别名。

**README 与示例配置跟上。** 示例的 `harness.edn` 里给出 `:editing` 全量默认值与注释；README 里讲
编辑工具的那几处（工具清单、项目绑定段落、`~/.clj-harness` 下的文件清单）补上模式开关，并写清锚点
落在 `harness.db` 的哪三张表上（**不是**一个 `hashline/` 目录——2026-09-15 起锚点存储是共用的 sqlite，
见本特征 spec 的落盘持久化一节与 `project-sidebar` 01）。

**逐条改写既有断言（本仓允许后出特性推翻前 spec，只需记明）：**

- `tools_test/specs-expose-every-base-tool`：写死的六个名字要拆成「默认模式下应有的名字」与
  「str-replace 模式下应有的名字」两条，两条都要有
- `tools_test/edit-requires-an-exact-unique-match`：改为**在 str-replace 模式下**跑——`edit` 的
  行为一个字没变，变的是它不再默认在场，所以这条断言要显式声明它测的是哪一种模式
- `tools_test` 里 `write-then-read-roundtrips`、`a-bound-session-roots-relative-paths-at-its-project`
  两处用 `read` / `edit` 断言内容的地方：改为断言**锚点行背后的内容**（去掉 `锚点│` 前缀后逐行相等），
  这样它在两种模式下都成立；模式专属的形状差异归 04/05 自己的用例
- `session_tools_test` 里那条用 `edit` 做的幂等性检查（`enabling a name that was never disabled`）：
  换成一个**两种模式下都在场**的名字（`bash`）
- **新增**一条元断言：默认模式下 `edit` 不在 specs、hashline 模式下在；两种模式下 specs 各自**完整**
  且没有重复名——翻默认这件事本身要有用例钉住，否则将来一次不小心的手改就会静默改变默认行为

**Blocked by:** 03, 04, 05, 06, 07, 08, 09, 10, 11

**Status:** ready-for-agent

- [ ] 默认模式（两个 `harness.edn` 都不存在）下：`edit` 不在工具表里，`read` 带锚点出行，
      `replace` / `insert` / `anchor_grep` / `undo_last_replace` 在场
- [ ] `:editing {:mode :str-replace}` 下工具表与今天逐字节相同，`read` 不带锚点，`edit` 行为不变
- [ ] 两种模式各自跑一遍「read → 改 → 撤销」的完整路径，都能工作
- [ ] prompt.md 不含任何只在一种模式下成立的措辞；自省入口写进去了
- [ ] **prompt 仍然只读一次**（冻结行为不变），有断言
- [ ] CONTEXT.md 收录本特性的行话，代码注释与 issue 用词与之一致
- [ ] `harness.edn.example` 给出 `:editing` 全量默认值并带注释；README 的相关段落更新（锚点落在
      `harness.db` 的三张表上，而不是某个目录）
- [ ] 上面列出的既有断言逐条改写完毕，**没有一条因为「反正默认变了」而被删掉**
- [ ] 新增元断言：默认模式与 str-replace 模式的 specs 各自完整、无重复、差异恰好是那五个名字
- [ ] 离线全量 `harness.test-runner` 全绿，且**连跑两次结果一致**（锚点表与落盘状态不引入顺序依赖）
- [ ] 端到端三段（spec.md 的验收主线）：改完接着改不重读、漂移后被拒并当场拿到新锚点后重试成功、
      撤销回退且重启进程后同一批锚点仍可用
- [ ] `NOTICE` 里的 MIT 署名与来源 URL 就位
