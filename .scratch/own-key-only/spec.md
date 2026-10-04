# own-key-only：彻底去掉全局 HARNESS_API_KEY

**日期**：2026-10-03 · **状态**：已落，**等验收**（未合并）

## 主人指出的两件事

> 首先，永远不去 harness APIK 这个，没有通用 APIK，而是每个平台都有自的 APIK，所以你列表里面所说的
> 已有 APIK 是错误的。还有，你看一下 Localhost 的 8081，为什么 Olama 明明没有 APIK，还是在那存着？
> 是因为读了 harness APIK 的原因吗？

**都问对了**。8081 那份的 `GET /api/providers` 现场（我拉下来看的）：

```
ollama       origin=builtin   key={"present?": true, "source": "env-file", "name": "HARNESS_API_KEY"}
deepseek     origin=builtin   key={"present?": true, ..., "name": "HARNESS_API_KEY"}
openrouter   origin=builtin   key={"present?": true, ..., "name": "HARNESS_API_KEY"}
kongming/qwen/workbuddy       key={... "name": "KONGNING/QWEN/WORKBUDDY_API_KEY"}
```

`ollama` 之所以占着设置列表一行，是因为钥匙查找的**全局兜底**（`credential-names` =
`[本 provider 的名字, "HARNESS_API_KEY"]`）让一个导出在 `.env` 里的通用变量替**每一条**目录项
回答「有钥匙」。上一票新加的「环境里已有密钥，选了就能用」也因此误亮了一整列。

主人定案（我问了范围）：**完全清理，每个供应商都要有 ID，即使是自定义的。**

## 三条规则

1. **没有全局钥匙。** `credential-names` 不再含 `HARNESS_API_KEY`；一把通用变量对任何 provider
   都不算数。内联描述（`:default` 直接描述 endpoint）**没有 id，因此没有钥匙**——要鉴权的 endpoint
   请写进 `:providers` 并给它一个 id。run、设置列表、可选清单从此读同一件事。
2. **名单有两半，顺序是判据**：`[本 harness 由 id 派生的名字] ++ [厂商自己文档里的变量名]`。
   `:zai` 既认 `ZAI_API_KEY` 也认 `ZHIPU_API_KEY`——已经导出过平台自己那把钥匙的人，不必把同一个秘密
   再写一遍。派生名在前：它是本 harness 会写、文档会念的那一行，而第二半依赖一份缓存文档
   （`harness.cap.model-data/provider-env-names`，永不阻塞、不知道就答 `[]`），所以缓存丢了只会少一种
   拼法，不会让哪个 run 少钥匙。
3. **可选清单的 `key-ready` 问的就是 run 那条查找**（同一个 `credential-names`），所以
   「环境里已有密钥，选了就能用」这句话是真的能跑，不是猜的。之前那一版查的是 models.dev 的变量名，
   而 run 只读派生名——那样这句就是谎话（`ZHIPU_API_KEY` 摆着，`zai` 却读 `ZAI_API_KEY`）。

## 落地

- `harness.cap.model-data/provider-env-names`（新，公开）：按 id 取厂商自己文档里的变量名（`env`），
  多名的全带（`CLOUDFLARE_ACCOUNT_ID,CLOUDFLARE_API_KEY` 那种），永不阻塞。
- `harness.cap.providers/credential-names`：两半合一；`api-key` / `api-key-source` 照旧只经过它。
  `api-key-source` 对内联描述答 `{:present? false :source nil :name nil}`（没有 id 就没有可念的行）。
- `known-providers` 的 `names-of` 直接改成调 `credential-names`——一份判据，两处读。
- 文档：README（`.env` 一行 + 首次使用）、`config.edn.example`、`docs/architecture/{providers,client,
  home-and-storage}.md`、`harness.cap.web/search.clj` 的注释。

## 测试

- `providers-test`：`the-key-comes-from…then-the-global-one` → `a-key-comes-from-the-providers-own-name-and-nowhere-else`
  （全局变量对谁都无效）；`an-inline-provider-has-only-the-global-key` → `…has-no-key-because-it-has-no-id`；
  `api-key-source-names-the-line-either-way` → `…the-providers-own-line`（内联答 nil）。
  **新增** `a-vendor-that-already-has-its-own-variable-exported-needs-no-new-line`（平台自己的名字能用、
  派生名优先、全局不算数）；可选清单那格加了「一把全局钥匙不点亮任何一行」的断言。
- 子进程探针（真环境测名字优先）：全局变量改成 `ALPHA_API_KEY`，并把「文件里的全局钥匙不覆盖环境里的
  自己名字」改成「文件里的全局钥匙根本不是谁的钥匙」。
- `system-prompt-test`：密钥不再写在全局名下——测试自己写一份 config.edn，用一个**有 id 的** provider
  （`SP_KEY_API_KEY`），所以「密钥真的在解析器读的地方」这句仍然成立（并多断言一次解析器确实读到它）。
- `http_test` / `model_data_test` 跟着改；`model_data_test` 新增 `provider-env-names` 一格。

数字：后端**全量 1474 个用例 / 14846 断言，0 红**（main 基线是 1472 / 1 红，那一处红是我自己的
后台作业注入了 system-reminder 的 flaky）。UI typecheck + 230 用例全绿。

## 途中被数据抓住的两件事（都留证）

1. **密钥查找必须是纯读。** 第一版让 `credential-names` 调 `provider-env-names`，而它会顺手
   `kick-db-refresh!`——于是**测试的子进程探针在 `slurp` 上等住了**（一个 JVM 里挂着没下完的
   `future` 就不退出），`providers-test` 撞 300s 上限，**整轮 40 处红**。现在 `provider-env-names`
   不起任何下载（缓存没有就答 `[]`），`api-key` 也**先判 nil 再查**（内联描述没有 id，就不该问
   任何东西）。名字是干净的：**派生的那一半永远够用**，外部那半只是便利。
2. **`system-prompt-test` 改写的 `config.edn` 忘了还原。** 它为了给密钥一个有名有姓的 provider
   自己写了一份 `{:default {:provider :sp-key …}}`，而那个家是**整轮共享**的——后面每个命名空间都
   继承了它，于是 223 次「no provider」拒绝。现在先读原内容、`finally` 里**原样写回**（原来那份不存在
   才删）。这是本 repo 反复吃过的那种教训：`providers-test` 的 `with-home` 早就这么做。

## 交接

- 工作树：`.worktrees/own-key-only`，分支同名（从 `main` = `83a862e` 切出）。**没有合并。**
- 走查：`cd .worktrees/own-key-only && node scripts/dev.mjs --scripted` → 设置 → 模型：
  内置三家（deepseek / ollama / openrouter）**都不在列表里**（没有各自的钥匙）；加号表单里
  只有真正配了对应变量的厂商才印「环境里已有密钥，选了就能用」。
