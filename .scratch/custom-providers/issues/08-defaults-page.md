# 08 — General 页：默认提供商 / 默认模型 / 默认 reasoning 强度

**What to build:** General 那一页（导航壳见 06）在「现状」报告下面多出**默认档**的三个控件，
它们写的是 `config.edn` 的 `:default`——也就是这台机器**下一次启动的会话**从哪儿开始：

```
Default provider          [ acme-gateway  ▾ ]   （含「unset」一项）
Default model             [ gpt-x         ▾ ]   （跟着上面那个厂商的模型表变）
Default reasoning effort  [ high          ▾ ]   （闭集：low / medium / high，含 unset）
```

- 读的一侧就是 04 的 `GET /api/providers`：厂商与它们的 model 表在那个答案的 `:providers` 里，
  默认档现状在它的 `:default` 里——**不必再叫 `/api/choices`**，一个面板一次读、少一个会漂的第二答案
  （`/api/choices` 是 composer 那个选择器的菜单，它答的是**当前生效**的那三个，不是默认档）。
- 写的一侧是一条新路由 `POST /api/defaults`，body 是三个旋钮键：
  **缺席 = 不动那一项**，**`null` = 把那个键从 `:default` 里清掉**（`:model` 清掉就是「用这家厂商自己的
  默认 model」，`:reasoning-effort` 清掉就是「不送这个字段」）。这个「缺席 vs null」的分别要写进
  docstring 与用例——JSON 里两者长得近，意思差一件事。
- **校验先于写入，而且校验的方式是「解析一遍」**：新的 `:default` 必须先能 `assemble` 出来
  （厂商在目录里、model 被那家声明过），否则当场 400 说清，`config.edn` 一个字节不变。
  这是 `POST /api/model` 那条老规矩的同一条（一个服务不了的变更不是一个变更），
  **复用 05 的写入机器**（读 → 改 → 整份校验 → 原子替换 + `config.edn.bak`），别写第二份。
- **写完立刻看得见**：成功之后面板重取一次，General 页的「现状」跟着更新——但**要如实**：
  - 若本会话自己的档（或本次请求）正盖着某一项，「现状」那一行的档标签会写 `this session` /
    `this run's request`，页面上补一句话说明**默认档改了、本会话仍听自己的**。
    一个人改了默认发现当前会话没变，最容易以为是没生效。
  - 若 `:default` 是**内联描述**形状（没有名字、直接描述一个 endpoint），三个控件表达不了它：
    把那段描述原样只读显示出来，并说清「保存会用命名形状换掉它」。这一条要看得见，不能装作没有。
- **不动 provider 表单**（07）：那边新建/改写只写 `:providers`，从不顺手改默认档。默认档有它自己的一页。

从用户视角：换了默认厂商，**下一个新会话**就从那儿开始——不用再手编 `config.edn`。

**Blocked by:** 04（`:default` 与厂商列表都在那份答案里）、05（写入机器：原子替换、`.bak`、
校验先于写入）、06（导航壳：这一页挂在它下面）

**Status:** ready-for-agent

## 验收

- [ ] 后端（`test/harness/edge/http_test.clj`，用既有的 `with-resolved-config` 那类装置写一份真
      `config.edn`）：
      - 三个旋钮各写一遍，`config.edn` 的 `:default` 是预期的那份，且 **`:providers` 那一节逐字段不变**；
      - `:default` 原本缺席时写出一条来；
      - `null` 清掉一个键（`:model` 清掉之后同一份目录能解析、落在厂商的默认 model 上）；
      - **拒绝一条服务不了的变更**：一个目录里没有的厂商、或那家没声明的 model → 400 + 原话，
        且 `config.edn` **逐字节不变**（含 mtime）；
      - `GET /api/settings` 的 `:tiers` 在写完之后把该旋钮归到 `config` 那一档；
      - **不留审计行**：调用前后 jsonl 一个字节不动。
- [ ] 前端：控件是**原生 `<select>`**（照 `composer-chrome.tsx` 里那个本地写法的先例，仓库没有
      Select 原语），三个都带一个 unset 项；选新厂商时模型下拉跟着换，**旧 model 不合法就清空而不是
      偷偷带过去**（服务端会拒，但界面不该先造一个必然被拒的请求）。`data-slot`：
      `settings-default-provider`、`settings-default-model`、`settings-default-reasoning`、
      `settings-default-save`、`settings-default-error`、`settings-default-inline`。文案英文。
- [ ] **真机端到端**（证明默认档真的生效，而不只是文件改了）：在面板里把默认厂商换成新建的那家
      → 在**一个没有会话档盖着的新会话**里发一句话 → `grep` 那条会话的 jsonl：
      `provider/init` 行里的选择是新厂商，`:source` 是 `"default"`。截图 + 那行日志进 `evidence/`。
- [ ] 一条**故意的失败**：把默认 model 指向一个那家没声明的 id → 400 那句原话出现在页面上，
      `cat config.edn` 证明没变。
- [ ] `:default` 是内联描述形状时：三个控件处显示只读描述 + 那句话（真机截图一张）。
- [ ] `cd ui && npm run typecheck` 0 error、`npm run build` 全绿、`npm test` 通过（用例数不变）；
      `clojure -M:test -m harness.test-runner` 失败名单逐条不变。
- [ ] `docs/architecture/providers.md` 的「三个旋钮，三档」与 `docs/architecture/edge.md` 的路由表
      跟上（新路由一行；写 `config.edn` → 审计栏「无」）。
