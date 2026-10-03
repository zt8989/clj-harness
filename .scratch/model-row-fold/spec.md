# model-row-fold：一行只留 id + 名称，其余全部收进一个折叠

**日期**：2026-10-03 · **状态**：已落，**等验收**（未合并）

## 主人要什么

> 在设置模型的时候，只需要设置 id，名称可选，然后把上下限的折叠上移到 input output + 上下限，
> 这些信息可以从 modeldev 中获取。

读法：模型行**留在折叠外面的只有 id 与可选名称**；原来散在外面的一排（模态勾选、输出说明、指令更新
三态）与原来那个「上限」小折叠**合成一个折叠**；折叠里的信息**来自 models.dev**，文件写了的以文件为准。

## 怎么落的

- **一个折叠**（`settings-provider-model-facts`，虚线边框），摘要行在收起时就**把答案带在脸上**：
  `text+image · text · 1M · 128K`（`input · output · context · max-out`；数字人化，1M / 128K / `—`）。
  摘要取的是「**解析会怎么答**」：文件说的用文件的，沉默的用 models.dev 的（`modelFactsOf`），
  未知写成 `未知` 而不是空白——两个位置和四个位置一眼就能分出来。
- **折叠内**：模态勾选（text / image）+ 输出说明 + 指令更新三态 + 两个计数输入。
  两个计数输入**带占位符**（models.dev 的数），勾选框旁一行小字说明「以上由 models.dev 按这个 id 回答，
  文件写了的以文件为准，留空就一直是沉默」。
- **报告多四个 suggested 键**（`model-row`）：`input-suggested` / `output-suggested` /
  `context-window-suggested` / `max-output-tokens-suggested`，与 `name-suggested` 同一条纪律——
  **只在文件沉默时带**，只在「展示」这条路上被读；保存时留空仍然什么也不写。模态建议**收窄到本 harness
  能搬运的**（`:video` 进不了复选行），与解析那条 `modalities-with` 一致。
- `form.limits` 那个键被折叠摘要把「上限」两个字顶掉了，i18n 套件的孤儿键检查抓到了——已从两份语言里删掉。

## 验证

- UI：typecheck ✔、230 个用例全绿（含 i18n 的孤儿键检查）、build ✔。
- 后端：`providers-test` 108 个用例全绿（`the-report-shows-the-file-as-written...` 扩成钉四个 suggested 键：
  `[image text]` 收窄自 `[:image :text :video]`、两个计数）。
- **真机走查没做**（这票先停在 worktree 等验收；要看的话：`node scripts/dev.mjs --scripted` → 设置 →
  模型 → 展开一个提供方，看每个模型行只剩 id + 名称，折叠收起时摘要行就是那四个数）。

## 交接

- 工作树：`.worktrees/model-row-fold`，分支同名（从 main 切出）。
- `ui/dist` 没有为主检出重建；要起服务走查就用工作树里的那份。
