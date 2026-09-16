# 10 — 文档与全量验收

**What to build:** 全仓文档跟上这一个特征，并**端到端走一遍**证明它成立。文档不是收尾的礼貌：
`config.edn` 的形状、`providers.edn` 退休、凭据名的派生规则、四条新路由与「写盘先校验」这几件事，
都是别人（和以后的模型）会照着自己那份旧文档做错的地方。

**Blocked by:** 01–09（01 已经带着一部分文档改完；本票负责剩下的、以及核一遍）

**Status:** ready-for-agent

## 验收

- [ ] `docs/architecture/providers.md`：目录的形状（`config.edn` 的 `:default` + `:providers`、
  合并顺序、内置表仍是地板）、api-key 一节（**派生规则与查找顺序**）、只读的生效配置那一节，
  再加写入侧：四条路由（provider 的读/写/删 + 默认档那条）、**校验先于写入**、原子替换与
  `config.edn.bak`、`GET /api/providers` 的形状与三种 `origin`。
  「三个旋钮，三档」那一节要写清：默认档现在**可以从界面上改**，而界面改它走的是哪条路由。
- [ ] `docs/architecture/client.md`：设置面板那一节改成「四页，其中 General 的默认档与 Models 的
  厂商表单会写，另外两页仍是只读报告」，并说清表单长什么样、文案为什么是英文；
  `ui/src/lib/providers.ts` 进客户端模块那份清单。
- [ ] `docs/architecture/home-and-storage.md`：家目录树（`config.edn` 一份装两样、`providers.edn` 不再被读、
  `.env` 里可能有派生名的密钥行、`config.edn.bak` 是改写前那份）、手编配置那份清单、
  「不搬进库」那条。
- [ ] `docs/architecture/edge.md`：路由表加四行（`GET /api/providers`、`POST /api/providers`、
  `POST /api/providers/<id>/remove`、`POST /api/defaults`，加 09 的探询那条），审计栏一律「无」，
  理由一句（写 `config.edn` 不搬日志、不读日志）。
- [ ] `docs/architecture.md` 的「在办」那一节：本特征落地后跟上（照那份索引自己的规矩）。
- [ ] `README.md`：家目录树与配置那两段收成一份 `config.edn`；`providers.edn.example` 的拷贝那一步删掉；
  「加一家厂商」这件事从「编辑 providers.edn」换成「设置面板里点两下」。
- [ ] `config.edn.example`：最终形状——`:default` 的三旋钮 + `:providers` 一节带注释
  （01 把 `providers.edn.example` 的内容折进来了），并且**头一段就写明「这份文件由设置面板维护，
  注释会被重排掉」**。
- [ ] `CONTEXT.md` 的术语表补两个词（本特征引入的、今天没在表里的）：
      **提供方**（provider 是厂商：一个 endpoint、一个协议，model 挂在它下面；*别叫成* 供应商/vendor）、
      **凭据名**（由 provider id 派生的 `<ID>_API_KEY`；*别叫成* 密钥名/凭据 ID）。
      两个词都得说清「id 才是对外的身份，显示名只是给人看的」，并补一句**默认档**（那个三旋钮的
      `config.edn` 一层）今天可以改在哪一页。
- [ ] 全仓扫一遍，剩下的名字都得是对的：
      `rg -n "providers\.edn" docs README.md src test ui --glob '!node_modules'`（命中只剩「退休了」的句子）
      与 `rg -n "display-name|凭据名|_API_KEY" docs src | head -40`（新词与代码一致）。
- [ ] **全量验收主线**（spec.md 里那条，逐条走完，命令与截图都留下）：
      真机上 设置 → Models → Add provider → `acme-gateway`（endpoint / 协议 / 密钥 / 一个 model）
      → Create → 列表里出现 → composer 选择器里立刻有它 → **选中它跑一轮拿到回答**
      → 设置 → General → 把默认档换成它 → **新会话**里跑一轮，`provider/init` 行是它、`:source` 是 `default`
      → 编辑一条 → 删一条；家目录的 `config.edn` 与 `.env` 改动逐行看得见，`config.edn.bak` 是改写前那份，
      **jsonl 里除审计行外一个字节不动**。
- [ ] 后端全量：`clojure -M:test -m harness.test-runner`，失败名单与动工前**逐条相同**——
      动工前 626 条 / 9960 断言，失败数不稳（2～6），所以对的是**名字**：`cap/project_test.clj` 那条
      JDK 25 常驻失败 + `edge/http_test.clj` 里两条竞态，三条都在 `spec.md` 的状态一节列着；
      量之前先确认没有别的会话在同一个仓库上跑套件。名单若有变化，**逐条查是不是本特征带来的**，
      不是就说清为什么，别把它记成「本来就有的」。
- [ ] 前端全量：`cd ui && npm run typecheck`（0 error）、`npm run build`（全绿）、`npm test`
      （通过，用例数与基准一致）。
- [ ] **`spec.md` 的落地记录**：实数（两个套件的数与失败名单）、真机量到的东西、以及
      **落地时发现的与此处写的不一样的地方**（这个仓库每份 spec 都记这一段——`flat-step-rows` 那份记了四处）。
- [ ] 票面收尾：按 `docs/agents/issue-tracker.md` 的规矩处理已做的票面
      （`project-sidebar` 那次做完了就删，`flat-step-rows` 那次先「票面入库」留了一份——
      本票不新增规矩，但要在落地记录里写明这次是哪种），`spec.md` 留着。
