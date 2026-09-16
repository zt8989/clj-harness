# 02 — 凭据名由 provider id 派生：`<ID>_API_KEY` 优先，全局兜底

**What to build:** 参考界面那句「ID … 用于派生凭据名」变成事实：给一家厂商配的密钥按
**id 派生的名字**存在家目录 `.env` 里，解析这家厂商时先找它，找不到退回全局 `HARNESS_API_KEY`。

派生规则一处定义：id 全大写、非 `[A-Za-z0-9]` 换成 `_`、缀 `_API_KEY`——
`acme-gateway` → `ACME_GATEWAY_API_KEY`。每一档都照旧是「`.env` 里的值优先于真实环境变量」，
这个顺序不变，只是**先按名字找，再按兜底找**：

```
ACME_GATEWAY_API_KEY   ← 这个 provider 的
HARNESS_API_KEY        ← 全局兜底（内联描述只有这一档）
```

从用户视角：两台厂商各一把钥匙，互不覆盖；老用户的 `HARNESS_API_KEY` 照旧管用（内置三个厂商、
内联描述都不用改一个字）。设置面板的 key 一节从「有没有、从哪来」变成**按本会话的 provider 报**，
并且**把找的那个名字说出来**——一个人要改哪个变量，答案就是这个名字。

**Blocked by:** None — can start immediately（与 01 互不阻塞）

**Status:** ready-for-agent

## 验收

- [ ] 派生函数一处定义、**公开**（写入侧与读取侧必须用同一份，照 `harness.infra.home/sanitize` 的
      「写的人与读的人必须同意」那条）：`acme-gateway` → `ACME_GATEWAY_API_KEY`；
      它的 docstring 写明**不单射**——`a-b` 与 `a_b`、`a.b` 派生出同一个名字，并用一条用例把这个
      事实钉住（而不是留一句没人验的话）。
- [ ] **优先序有用例**：临时家里 `.env` 同时写两行（派生的与全局），`resolve-provider` 拿到的
      `:api-key` 是**派生的**那把；只写全局那把时拿到全局；两把都没有时是 `nil`；
      真实环境变量里设了派生的名字而 `.env` 里没有 → 用环境变量那把（`.env` 优先只在自己有时生效）。
- [ ] **内联描述只有全局那一档**：`config.edn` 里描述一个 provider（没有名字）时，解析用的仍是
      `HARNESS_API_KEY`，且不因为「没有名字」就抛错。
- [ ] `api-key-source` 改成**按 provider** 回答，并带上名字：
      `{:present? true :source :env-file :name "ACME_GATEWAY_API_KEY"}`。
      `GET /api/settings` 的 `key` 一节带上这个名字（用例；`http_test`）。
- [ ] **密钥值在任何深度都不出现**：`api-key-source` 仍是「不把值读出来」的那种写法（不是包一层
      `api-key` 再删），`GET /api/settings` 与 `GET /api/providers` 的返回里都没有它；
      既有的「整棵树找不到 `:api-key`」断言继续过，且新增一条：返回里也找不到那把**值**本身。
- [ ] 设置面板那一行跟上：`ui/src/components/settings-panel.tsx` 的 key 一节把派生的名字显示出来
      （英文），真机看一眼那一行（真机的缝见 07：`harness.e2e-server` + 假 `CLJ_HARNESS_HOME`；
      本票只保证它显示对）。
- [ ] `cd ui && npm run typecheck` 0 error。
- [ ] `.env.example` 那句**说反了的话**改掉：它写着「真实环境变量总是赢过这个文件」，
      而 `api-key` 与 `api-key-source` 都是 `.env` 赢。顺手把派生的名字写进注释（一个例子里两行）。
- [ ] `clojure -M:test -m harness.test-runner`：失败名单与本票动工前逐条相同。
