# a-model-row-cannot-be-saved-back

**日期**：2026-10-06 · **状态**：已落，**等验收**（未合并）

## 主人要什么

打开设置 → Models → 编辑一个数据库认识的厂商（比如 `:workbuddy` 的 `cn:deepseek-v4.1-flash`），
**什么都不改**，按保存，被退回：

```
model "cn:deepseek-v4.1-flash" of provider :workbuddy carries [:name-suggested],
which it does not understand; it knows [:context-window :input :instruction-updates
:max-output-tokens :name :output]
```

## 为什么

报告的行和写下的条目**本来就是两个形状，这一票之前是同一个形状**：

- **报告**（`registry-report` 的 `model-row`）给的是**文件写的样子** + 数据库的建议**骑在旁边**
  （`name-suggested` / `input-suggested` / `output-suggested` / `context-window-suggested` /
  `max-output-tokens-suggested`，`.scratch/model-row-fold` 加的）。建议是**给人看的**。
- **条目**（`config.edn` 的 model）只认 `model-keys`：id 加那六个键，**多一个键就是指名失败**。

`draftOf` 把报告的行原样收进草稿（`{ ...m }`），`save` 又把草稿原样发出去
（`models: draft.models`）。于是五个建议键跟着回到写入端，`check-model` 的
`unknown-keys!` 把整次写入退回——**人什么都没改，所以「改对了再保存」这条路根本不存在**。

## 怎么落的

- 新模块 `ui/src/lib/model-entry.ts`：`entryOf(row)` 把**报告行**投影成**条目**。
  **逐字段按名字挑，不展开**——展开会把建议一起带走，而按名字挑意味着：明天报告里多一个
  建议键，它到这个函数为止，不会顺手流进写入端。可选键**没有值时不存在**（不是 `null`：
  JSON 的 null 到了服务端是一个值）。
- `ProviderPayload.models` 的内联类型换成 `ModelEntry`（`lib/providers.ts`），写不进去的东西
  就在类型上写不进去。
- `save` 改走 `models: draft.models.map(entryOf)`；`Draft.models` 仍是 `ModelRow[]`
  （草稿是**报告行的草稿**，不是发送出去的条目）。
- 五个用例 `ui/test/suites/model-entry.ts`：五个建议键不过去、文件写了的原样过去、
  新建的空行还是空行、数组是复制不是同一个引用、同一行投影两次结果相等。

## 验证

- UI：typecheck ✔；build ✔；`pnpm test` 237 个用例全绿（`EXPECTED_CASES` 232 → 237，52s）。
- 后端：没动 `src/`，`providers-test` 那一串不用重跑（那串钉的是**服务端句子**，本来就对）。
- **真机走查没做**（这票先停在 worktree 等验收）。要看的话：`node scripts/dev.mjs --scripted`
  → 设置 → Models → 编辑一个厂商 → 什么都不改 → Save。

## 交接

- 工作树：`.worktrees/model-row-suggested-leak`，分支同名（从 main 切出）。
- 新树的 `ui/node_modules` 用 `pnpm install --prefer-offline` 装（主检出已换 pnpm，见 `5ba343a`），
21s 装完、`lucide-react` 完整，typecheck 与 build 都直接过——不再需要手工拷 `.d.ts`。
- 两票的提交：`201cb2d`（本票）、`0039bd7`（跟着 main 换 pnpm）。**都没合并**。
