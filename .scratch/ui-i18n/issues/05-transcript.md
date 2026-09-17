# 05 — 正文与步骤行

**What to build:** 从用户视角：切成中文后，对话里每一步的读法都跟着变——展开一张卡看得到
`参数` / `结果` / `错误：`，折叠一轮之后留下的摘要是 `72 次工具调用 · 25 条消息`（中文）或
`72 tool calls · 25 messages`（英文），思考行的标签在两种语言里各有说法；而**模型写的东西**
（参数 JSON、工具结果、思考正文）一个字不动。

**Blocked by:** 01, 02 — 轮摘要要那一族量词。

**Status:** ready-for-agent

## 验收

- [ ] `message-parts`（约 15 条：`Running` / `Needs approval` / `Arguments` / `No result` /
      `Error:` / `Result:` / `[Unserializable value]` 等）与 `turn-steps` 自己的词进目录
      （`thread` namespace）。
- [ ] **今天那三处中文进目录，两种语言各有说法**：`subjectOf` 的 `删除` / `行` / `完成`
      （`replace` / `insert` / `todo_write` 三支）、思考行的 `思考`、轮摘要的
      `N 次工具调用 · M 条消息`。中文那一列照今天的说法，英文那一列是新写的。
      投影里那些**不是话的符号**（`…`、` · `、`→`、`/`）不进目录。
- [ ] `思考` 那条注释今天写着「它是整条对话里唯一一处中文标签」——这句话不再成立，就地改掉
      （改成「它是本行自己的词，进目录，两种语言各有说法」）。
- [ ] 工具名逐字不翻（`read` / `bash` / `todo_write`），参数与结果的**正文**不翻（spec 决策 4）。
- [ ] `lib/turns.ts` 的摘要函数收一个 `t`（运行时零 import 照旧），`turns` 套件那三条断言改成
      两种语言各一条；`EXPECTED_CASES` 一起改。
- [ ] 真机：中文下一次「想 → 读 → 写 → 答」的对话，看得见工具行、展开的参数与结果、思考行、
      折叠后的轮摘要。中英各一张截图进 `evidence/`。
- [ ] `cd ui && npm run typecheck && cd ui && npm test` 全绿。
