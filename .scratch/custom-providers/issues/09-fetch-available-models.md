# 09 — 表单里的「Fetch available models」：问厂商要一份 model 列表

**What to build:** 表单的模型目录那一段上一个 **Fetch available models**：服务端拿表单**此刻**填着的
endpoint 与协议（以及**此刻**填着的密钥，没填就用这家已存的派生密钥）去问厂商
（`GET <base-url>/models`，`Authorization: Bearer <key>`，取 `data[].id`），回来的 id 列成可勾的行，
勾了进模型目录。厂商拒绝时（401 / 404 / 超时 / 不是 JSON）**它的原话回传到表单里**。

厂商那份列表**没有模态信息**，所以勾进来的 model **默认只声明 `:text`**（声明得最少就是最诚实的默认），
行仍然是可改的。

从用户视角：不用去别处抄 model id 了——这一条也是这个仓库自己表里那句自夸的兑现
（内置表的 `:as-of` 注释写着「每一个 id 都是当天从厂商自己的列表上读来的」）。

**Blocked by:** 07（这个动作长在模型页的表单上）

**Status:** ready-for-agent

## 验收

- [ ] 路由是 `POST /api/providers/models`，**必须是精确串匹配那一段**：`/api/<collection>/<stem>/<verb>`
      那个形状要求两段，`/api/providers/models` 只有一段，掉进兜底就是 `handle-run`——
      也就是「body 根本不存在的 500」那种老毛病（`edge.md` 里记着这个坑，别重踩）。
- [ ] body：`base-url`（或一个已存的 `id`）、`protocol`、可选的 `api-key`。
      **密钥缺席时按 02 的规则查**（`id` 的派生名 → 全局兜底），所以编辑一条已存在的厂商时
      不必重新输一遍。这条查找是同一个函数，不是这里的第二份实现。
- [ ] **测试缝**：一个 `alter-var-root` 的 var（照 `*directory-chooser*` 的先例），真实的实现是默认值；
      测试里换成 stub，**不出网**。用例三条：stub 给出的列表进响应；stub 抛 401 时**厂商那句原话**
      是响应里的 `error`；请求里没有密钥的本地 endpoint 也允许试（不因为没有密钥就拒）。
- [ ] **超时**有自己的句子（不能挂住一个请求线程），并且与厂商拒绝的句子分得开。
- [ ] 回答里**没有任何密钥值**（成功与失败两条路各断言一遍），
      回答也**不写任何东西**：jsonl 一个字节不动、`config.edn` 不动、`.env` 不动（用例）。
- [ ] 表单侧：按钮 → 转圈 → 勾选列表 → 选中的行进模型目录（模态默认 `:text`，默认 model 仍是
      目录里的第一行 / 用户指那行）。失败时那句话显示在**同一处** `settings-provider-error` 里，
      与提交失败的显示是同一种（一个人不该学两种错误位置）。
- [ ] 真机截图一张（`.scratch/custom-providers/evidence/t07-*.png`）：勾选列表出现在模型目录上方。
      真机这一趟可以对着一个本地假 endpoint（`harness.fake` 那类），不必出网。
- [ ] `cd ui && npm run typecheck` 0 error、`npm test` 通过（套件数不变）；
      `clojure -M:test -m harness.test-runner` 失败名单逐条不变；
      `docs/architecture/edge.md` 的路由表加这一行（只读语义 → 审计栏写「无」，理由：不写任何状态）。
