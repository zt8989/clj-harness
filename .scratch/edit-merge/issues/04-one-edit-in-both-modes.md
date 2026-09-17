# 04 — 两个模式共用一个 `edit`

**What to build:** `edit` 这个名字**在两种编辑模式下都存在**，脸随模式换：

- `:editing {:mode :hashline}`（默认）→ 参数是 `input`（票 01–03 的补丁语言）
- `:editing {:mode :str-replace}` → 参数是 `path` / `old_string` / `new_string`（今天的样子，一个字不改）

也就是说「会话按什么编辑」在工具表上从**换名字**变成**换一张脸**——正是 `read` / `write` 已经在用的
机制（`:describe`），也是 omp 的 `edit` 的形状（它的四种模式共用一个内部工具，schema 与例子随模式换）。
顺带：`undo_last_replace` 改名 **`undo_last_edit`**。

**Blocked by:** 03（寄存器与文件级动作）

**Status:** ready-for-agent

## 为什么「模式选脸」而不是「模式选名字」

omp 的模式选择是一个**配置**决定（模型专用变体 → 环境变量 → `edit.mode` → 默认 `hashline`），
四种模式**没有一种是调用参数**；换成 wire name 的那一种（`apply_patch`）在分派上仍到同一个内部工具。
本仓的模式同样来自配置（`harness.edn` 的 `:editing {:mode …}`，每次调用现读、按会话解析），
所以「同一个名字、两副脸」在本仓是可表达的，而且已经有一个先例：`read` 在两种模式下同名、
靠 `:describe` 换描述与参数（`.scratch/hashline-edit/spec.md` 记着它这么做是因为用户要求
「覆盖内置 read」，不做两个名字）。

**代价明说**：`edit` 这个名字从此在两种模式里都是**同一个工具的两副脸**，
所以任何按名字写死的清单（`families`、文档、UI 的表、测试里的向量）都要跟着换；
而 `unserved-message` 那套话术要改——`edit` 不再是「另一个模式的工具」。

## 要做的事

- **`:describe` 的第二个使用者**：今天只有 `read` 与 `write` 声明它，`edit` 成为第三个
  （或者：`read` / `write` / `edit` 三者的脸都由 `cap.editing` 供给，一处实现）。
- **`families` 表跟着改**：`edit` 不再属于任何**单一**模式——它两个模式都服务，只是脸不同。
  于是这张表里剩下的「模式减法」只对 `anchor_grep` / `undo_last_edit` 生效；
  注释与 `unserved-message` 的话术要跟上（今天它会说「本会话按锚点编辑，用 replace」——那时没有
  `replace` 了，应该说「用 `edit`，它的载荷是锚点补丁」）。
- **改名 `undo_last_replace` → `undo_last_edit`**：名字把**单位**说清楚，撤销的单位从「一次
  `replace`」变成「一次 `edit` 调用」。这是本仓自己那条规矩（`undo_last_change` → `undo_last_replace`
  就是这么来的）。跟着改：注册、描述、`TOOL_ICONS`、`subjectOf`、`CONTEXT.md` 的工具名清单、
  `docs/architecture` 里每一处、`README.md`、以及所有按旧名字写的用例。
- **两个模式的描述各自完整**：锚点脸要讲载荷（票 01–03 的动作表），str-replace 脸仍讲
  `old_string` 必须唯一；**不许**让一个模式的描述提另一个模式的参数（这正是 `:describe` 存在的理由）。
- **`read` 的锚点脸与本票无关**：它的换脸早就有了，一个字不动（回归）。

## 不要做的事

- 不引入环境变量或模型专用档选模式（spec 非目标）。
- 不把 str-replace 模式的实现合并进锚点那套（那是两套编辑实现，本特征只合并**入口**）。
- 不动 `write`（它两个模式同名同脸，是另一回事）。

## 验收

- [ ] 默认（锚点）会话调 `edit`：参数是 `input`，描述里有动作表；`:str-replace` 会话调 `edit`：
      参数是 `path` / `old_string` / `new_string`，描述是今天那段——**两条用例各自断言自己那一套参数
      的名字集合**（照 `hashline/read_test` 里断言参数集合的做法）
- [ ] 两个模式的描述**互不提及**对方：断言锚点脸的描述里没有 `old_string`，str-replace 脸里没有 `input`
- [ ] `edit` 在两种模式的工具表里都在：`specs` 的清单在两种模式下都含 `edit`（两处硬编码向量跟着改）
- [ ] `unserved-message` 的话术更新并有用例：对一个本会话不服务的名字（如 str-replace 会话里的
      `anchor_grep`），话里给出**存在的替代**（`edit` + 锚点补丁），不再提 `replace`
- [ ] 全仓没有 `undo_last_replace` 这个工具名：`grep -rn "undo_last_replace" src test ui docs CONTEXT.md README.md`
      只剩历史文档（`.scratch/`）里的
- [ ] UI：`TOOL_ICONS` / `subjectOf` 的两条 case 改名后 `cd ui && npm test` 过
- [ ] `read` 的两副脸一个字没变（它的既有用例不改而通过）
- [ ] `timeout 900 clojure -M:test -m harness.test-runner` 通过，失败**用例名**与基线一致
