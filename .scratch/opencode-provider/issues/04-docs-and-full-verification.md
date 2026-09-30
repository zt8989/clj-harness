# 04 — 收口：文档、示例与全量验收

**What to build:** 把这四票落地的东西收进文档，并把两道机器门各跑一遍。这是 contract 票：
它不新增行为，它让**已经落地的东西**在纸上与人脑里也对得上。

**Blocked by:** 01、02、03

**Status:** ready-for-agent

## 验收

- [ ] `docs/architecture/layers.md`：写下「扩展层（`ext/`）在四层法之外」这件事，
      以及**为什么不是第五层**（组合根要装载它、而「不许有边指向顶层」会被自己破掉）；
      同时说清 `harness.layers-test` 扫的是哪些根，因此这个新根不在守卫里——
      「守卫看不见什么，也要写下来」那一节补一句。
- [ ] `docs/architecture/providers.md`：目录条目的第四个来源（扩展）、`install!` 这道门、
      **头上线**这件事（解析结果上的 `:headers`、值从不渲染、探询不带）、以及
      「子 agent 带父会话的 thread-id」这条口径。
- [ ] `docs/architecture/providers.md` 里**明写没有 onrequest / onresponse**（并说清头是数据位、
      不是拦截），给出翻案路径：要动响应就换整套 `:protocol`。这一条是写给下一个想加拦截器的人的。
- [ ] `CONTEXT.md`：给这套东西定词——**扩展**（`ext`）、**安装门**（沿用已有的「安装」那条的写法）、
      **请求头**（`x-opencode-session` 是这一家的口径，不是核心的词）。**同义词是要避免的**，
      所以要想清楚：`plugin` / `extend` 这两个词**不采用**，在词条里说一句为什么。
- [ ] **配置形状从三节变四节，每一处说得出话的地方都改**，逐处点名：`config.edn.example` 的节清单、
      `README.md` 那一行（`:default` + `:providers` → 加 `:ext`）、`docs/architecture/providers.md`
      的「一份配置，三节」与它旁边「`:ui` 是有意开的一格」那一段（`:ext` 是第二格有意的，而且它是
      **唯一不是 map 的一节**）、`docs/architecture/home-and-storage.md` 的家目录与手编配置清单（若有）。
      再加一句：为什么它在 `config.edn` 而不是 `harness.edn`（`harness.edn` 是「与厂商/model/密钥无关的
      旋钮」，扩展名义上属于那里，主人定的是 `config.edn`——把这个取舍写下来，别让下一个人重新猜）。
- [ ] `config.edn.example` 里 `:ext` 那一节带一行注释：写短名（`["opencode"]` → `ext.opencode/install`）、
      **改完要重启**、名字不认识就起不来。
- [ ] `clojure -M:test -m harness.test-runner` 全量：失败名单与动工前**逐条**对照并写进 `spec.md`
      （判据是名字不是数量）。动工前量一次、收口时再量一次，两次都在 `spec.md` 里。
- [ ] `cd ui && npm run typecheck` 0 error、`npm test` 通过、`npm run build` 通过
      （本票与整个 feature 都不该动前端行为；扩展贡献的厂商经由现有的 `GET /api/choices` 进选择器，
      前端一行不用改——**如果发现要改，说明 02 的接线选错了地方**，回到那一票）。
- [ ] spec 的落地记录补齐：四票各自的失败名单、用例数、走查结论；`Status:` 从
      ready-for-agent 改成落地后的状态。
