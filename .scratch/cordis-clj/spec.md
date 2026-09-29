# spec: cordis-clj

Status: ready-for-agent

在本仓**并存**地实现一份数据驱动的插件/服务内核（Cordis 的 Clojure 版）：插件是 map、服务是
keyword 键的注册表、依赖由 `:inject` 声明、每个 effect 返回一个 disposer 并按 LIFO 撤销；
在这套内核之上，把支撑 Web 的基础模块各自封装成一个插件。

来源是一次对话里的方案（概念映射表 + `cordis.core` 的实现 + 模块清单）。本目录把它切成 16 张票，
并把当时没写清的几件事定下来——定的是**落点、范围、依赖纪律、测试门**，不是实现细节。

## 落点与四条决定

1. **落点（这一条的后半已被撤销，见下）**：代码 `src/cordis/` + `test/cordis/`，票在本目录，
   两棵树并存、互不 require。

   > **撤销记录（`.scratch/plugin-tree/`）**：这一条原先还写着「**`src/harness/` 一行不动**、cordis
   > 不接进 `edge.http/start!` 那个组合根」。那半**已被撤销**——`plugin-tree` 就是要用这套运行时把
   > harness 的四层重切成三类服务（薄核心 + 可插拔能力 + 一份组装清单），那是一次动 harness 的宽重构。
   > 本目录自己的票（01–16）照旧，只做运行时与 Web 基础模块；**「整仓全量套件在这个新树存在之后照旧绿」
   > 这条判据照旧成立**，但它不再意味着 harness 一行没动过。
2. **范围**：票 01–16 = 核心（01–07）+ 必选 Web（08–15）+ WebSocket（16）。
   可选模块（安全 / OpenAPI / 迁移 / Kaocha / 前端构建）**不在这批**。方案里那条
   Shadow-CLJS 与本仓「前端已整体换成 TypeScript」的现状冲突，真要做得单独开一次题。
3. **依赖**：这批票**零新增依赖**。Web 票只写能力、不写库名；选型由实现者按仓库纪律当场论证。
   为什么这么严：本仓今天只有一个二进制依赖（`sqlite-jdbc`），`deps.edn` 里为它写了一整段理由——
   「加一个依赖」在这个仓是一件事，不是一个附注。方案推荐的 Ring/Jetty/Reitit/HikariCP/Timbre
   都是**候选**，不是既定。
4. **测试门**：并进本仓那扇门——`clojure -M:test -m harness.test-runner`。
   新测试命名空间**必须与文件同一次提交**进 `harness.test-runner/test-namespaces` 那个字面量，
   漏了就是整轮静默不跑（`test/harness/test_runner.clj` 里有一整段注释写这件事）。
   每命名空间 300s、整轮 1800s 的时限对 cordis 命名空间同样算数。

## 语汇

这套词 `CONTEXT.md` 还没有（那份表描述的是 harness 这个内核，不是这棵树）。本目录行文一律照下表的左列。

| 词 | 是什么 | 别叫成 |
|---|---|---|
| **插件**（plugin） | 一个含 `:id` / `:inject` / `:apply` 的 **map**。不立类型、不写协议 | 模块、扩展、组件 |
| **服务**（service） | 以 keyword 为键注册进 ctx 的东西（`:config` / `:http/router`） | bean、单例、依赖 |
| **fiber** | 一个插件实例：它的 id、它声明的依赖、它的 disposer 栈、它的状态（PENDING / ACTIVE） | 实例、上下文、句柄 |
| **disposer** | 撤销一次 effect 的 0 参函数。压进 fiber 的栈，卸载时**逆序**跑 | 清理函数、回调、析构 |
| **可逆 effect** | `apply` 里做的任何会改世界的事，都必须交回一个 disposer（注册服务、挂 listener 都算） | 副作用、注册 |
| **ctx** | `:apply` 收到的那个 **map**，四把钥匙：`get-service` / `register-service!` / `effect` / `on` | 容器、注入器、环境 |
| **PENDING** | 「依赖还没齐，我不动」——`apply` 一行没跑，但注册表里看得见它 | 待加载、未就绪 |
| **waterfall** | around 中间件式的 dispatch：listener 收 `(…, next)`，不调 `next` 就短路 | 洋葱模型、管道 |
| **热重载** | 同一个 id 用新定义换掉旧 fiber：旧效果撤净、注册表不重置 | 重载、刷新、重启 |

`ctx` 是 map 而不是对象，这条不是风格：本仓的立场是「能用数据说清就别立类型」，fiber 也因此天生
可以在 REPL 里直接看、直接 `pr-str`。

## 与 harness 已有机制的关系

本仓已有一层同构的东西：`install! → teardown`（`docs/architecture/layers.md`「安装门的形状」）——
按层记账、同名后者覆盖前者、禁用不是删除、`teardown` 撤自己那一层并还原下面那层。
cordis 是它的**动态版**，多两样：一个「依赖」维度（`:inject` + 自动启停）与一条自动的「撤销栈」
（disposer LIFO）。两者今天**互不调用**：harness 那边的四条约束照旧，cordis 不替它做决定。

反面意见要写下来：`install!` 已经满足了「能力怎么装进核心」，为什么还要第二套？因为 `install!`
的四条约束是**给测试用的**（`teardown` 的真正消费者是测试），而 cordis 要的是**运行时的装配**
——依赖是数据、服务消失会级联、顺序由算出来的拓扑决定。这两件事各自成立，代价是仓里有两套装配词汇。

## 探索得到的三条事实（票面按它们写）

- `harness.layers-test/every-namespace-is-in-a-layer` 扫的是**整个 `src/`**，判据是「归入 harness 四层」。
  所以 `src/cordis/core.clj` 一落地就会让它红。**票 01 就是这件事**，而且处理方式是**扩判据**，
  不是放宽或删掉——见那张票。
- `harness.test-runner/test-namespaces` 是**字面量**。不在表里的命名空间，整轮不跑，而输出照旧说全绿。
- 本仓的测试不写死端口（`{:port 0}` 让 OS 分配），cordis 的 HTTP/WebSocket 票照同一规矩。

## 不做什么

- 不改 `src/harness/`（唯一会被碰到的 harness 文件是 `layers_test.clj` 与 `test_runner.clj`
  那两处清单/判据，理由写在票 01 与 commit message 里）。
- 不做可选模块（票 16 之后那些）。
- 不定任何库；不引任何新依赖。
- 不用这套东西替掉 harness 自己的分层或 `install!`。
