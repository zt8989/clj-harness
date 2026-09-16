# 03 — provider 的显示名：`:display-name` 是给人看的第二个名字

**What to build:** 一个 provider 条目可以带一个**可选**的 `:display-name`。模型选择器与设置面板显示它，
没有就回落显示 id。**id 仍是对外的身份**：凭据名（02）、`provider/init` 与 `provider/changed` 行、
`:provider` 旋钮、`GET /api/choices` 每行的 `name`，全都还是 id——显示名只影响人看的那一层。

```clojure
{:providers {:acme-gateway {:display-name "Acme Gateway"   ; 可选
                            :protocol :openai-completions …}}}
```

从用户视角：选择器里那一行不再是 `acme-gateway` 这种机器名字，而是一个自己起的名字；
日志与凭据名不受影响，所以「给显示名换个字」不是一次配置变更。

**Blocked by:** 01（条目形状先落成 `config.edn` 的 `:providers`）

**Status:** ready-for-agent

## 验收

- [ ] `check-provider` 收下 `:display-name`（`provider-keys` 放宽这一处，且**只**这一处）；
      `:display-name` 进 `resolved-fields`（于是 `active-provider` / `wire` / 两条 audit 行都带上它），
      **并且必须同时进 `catalog-fields`**：那份名单是**字面列出来的**，不会自动跟上，
      于是「某一档里写 `:display-name`」会走 `selection` 的 `select-keys` **被静默丢掉**——
      正是这个仓库最不想要的那种失败（调用方写了、run 报成功、什么都没发生）。
      一条用例断言会话档里写 `:display-name` **当场指名失败**。
- [ ] `over` 的逐字段合并让「只改显示名」成为一条合法条目（用例：内置 `:ollama` 上补一行
      `{:display-name "本地 ollama"}`，其它字段仍是内置的）。
- [ ] `GET /api/choices` 每行带 `:display-name`（缺席就是缺席，不填 `null`、不回落成 id——
      回落是**渲染侧**的事，服务端说事实）；`GET /api/settings` 与 `GET /api/model` 也带上当前
      provider 的显示名（走既有的 `active-provider` + `wire`，所以模态集合的有序渲染等规矩自动跟上）。
- [ ] composer 的选择器（`ui/src/components/composer-chrome.tsx` 里那组 `<optgroup>`）显示显示名，
      没有就显示 id；**选中后发出去的仍是 id**（`:provider` 旋钮不变，用例 + 真机各证一半）。
- [ ] 一条没有显示名的 provider 显示出来仍是 id（用例：选择器那段格式化函数的两种输入）。
- [ ] 真机：设置面板/选择器里看到显示名的那一格截图进 `evidence/`（真机的缝见 07：
      `harness.e2e-server` + 假 `CLJ_HARNESS_HOME`）。
- [ ] `clojure -M:test -m harness.test-runner` 失败名单逐条不变、`cd ui && npm run typecheck` 0 error、
      `cd ui && npm test` 通过（用例数与基准一致）。
