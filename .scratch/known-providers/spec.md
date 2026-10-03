# known-providers：设置列表只画自家的，加号里能挑内置厂商

**日期**：2026-10-03 · **状态**：已落（后端 + 设置页 + 浏览器走查）

## 主人要什么

> 清理设置列表，如果 API key 不存在，你就不要显示，比如 Ollama。然后增加供应商的时候，可以选择内置供应商
> 和自定义供应商。内置供应商的话，就直接取 model dev 里面的值。当然这一切的前提就是 model dev 提供 API 端点。

前提先查清楚了（`https://models.dev/api.json`，2026-10-03 实测）：**226 家里 185 家 `npm` 是
`@ai-sdk/openai-compatible`，且这 185 家全都带 `api` 端点**。也就是说「取 model dev 里面的值」这话成立，
但只能对**这一族**成立：其余 41 家（anthropic / google / azure / bedrock / openrouter 自己的 SDK …）
是**另一种线协议**，本 harness 只会说一种，把它们摆进选择器就是摆一个发不出去的请求。

## 三个决定

1. **列表画什么**：`drawnInSettings` = **有钥匙，或者这条是人自己写的**（`origin ≠ "builtin"`）。
   不是选择器那条 `hasKey`——两个问题、两份判据，写在同一个零 import 的 `lib/provider-key.ts` 里，
   由 UI 套件钉住。Ollama（内置、不要钥匙）从此不占一行；自己写进 `config.edn` 的条目一律画，哪怕还没钥匙。
   旧的「还有 N 家没有密钥——点开就能补上」整块删掉（连同两个 i18n 键，i18n 套件会报孤儿键）。
2. **没配置过的内置厂商去哪了**：**加号表单**。它不是消失了，而是出现在「该出现的地方」——原本它靠一个
   `<details>` 才看得见，那正是主人说的「清理」要清掉的东西。
3. **可选清单 = models.dev 的兼容厂商 ∪ 本 harness 自带表**。自带表必须并进来，否则会出现一个死结：
   `openrouter` 事实上兼容，但那份文档把它挂在自己的 SDK 下 → 不在 models.dev 的清单里 → 自带、没钥匙 →
   在新的列表规则下也不显示 → **彻底没有入口**。同 id 以 models.dev 为准（地址更新）。实测 187 家
   （185 + 自带 3 − deepseek 重复）+ 一个占位项 = 188 个选项。

## 落地

- `harness.cap.model-data`：新增 `provider-index`（只收 `@ai-sdk/openai-compatible` 且 `api` 非空的）
  与公开的 `known-providers`；缓存载荷多一张 `:providers` 表（同样是 `[id facts]` 对，理由同模型索引：
  `:key-fn keyword` 会把 id 变成关键字）。**老缓存（没有这张表）会自己触发一次刷新**，而不是等满 7 天——
  `kick-db-refresh!` 多了个 `missing?` 参数，因为「新鲜但答不了这个问题」和「过期」是两件事。
- `harness.cap.providers/known-providers`（私有）：两份来源合一、去重、按人读的名字排序；
  `registry-report` 多一个 `:known-providers`。搭在既有路由上而不是单开一条——画表单的页面就是读这条路由的页面。
- 设置页 Models：列表用 `drawnInSettings`；空列表时一句话；加号表单分「内置 / 自定义」两半，内置那半是
  一份 `<select>`（188 项，排名字序，原生输入跳转够用），选中即填 id / 显示名 / 地址 / 协议，
  **地址照样可改**（厂商公布的 URL 可能带占位符，如 Cloudflare 的账号 id）。
- 测试：`model_data_test` 3 个（只收能到的厂商、排序与 id、老缓存自愈）、`providers_test` 1 个
  （报告里两份来源合一）、`picker` 套件那格改写成两份判据并加了一行 user/keyless 的对照。

## 证据（浏览器走查，headless Chromium 打真页面）

一份全新的临时家（没有任何 provider）：

- 设置 → 模型：列的 provider **0 条**，旧的「没有密钥」那块 **0 个**，出现新的空列表提示；
- 添加提供方：内置 / 自定义两个按钮都在；厂商下拉 **188 个选项**，
  `302.AI · 302ai · 122 个模型` 这样一行一条；`zai` ✔、`openrouter` ✔（自带表兜底）、`anthropic` ✘（另一种协议）；
- 选中 `zai` → id `zai`、显示名 `Z.AI`、地址 `https://api.z.ai/api/paas/v4`、协议 `openai-completions`。

## 没做

- 厂商下拉没做搜索过滤（理由写在 `docs/architecture/client.md`：原生跳转够用，日后再换）。
- 没有按厂商预填思考档/模态等人情味的东西：那些本来就在解析时由 models.dev 回答。
